package com.pcupgradelab.pricetrend;

import com.pcupgradelab.catalog.CatalogProduct;
import com.pcupgradelab.catalog.CatalogProductSourceRepository;
import com.pcupgradelab.catalog.RamSpecRepository;
import com.pcupgradelab.catalog.price.CatalogPriceDatabaseTimeZone;
import com.pcupgradelab.catalog.price.CatalogPriceImportBatch;
import com.pcupgradelab.pc.PartType;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검토된 관측 파일을 price_history_observation에만 추가한다.
 * 기존 상품·출처·제원·가격 테이블은 조회만 하며, 상품을 만들거나 활성화하지 않는다.
 * 같은 관측(판매처·상품·관측 시각)이 기존 관측이나 이력에 이미 있으면 다시 넣지 않는다.
 */
@Service
public class PriceHistoryImportService {
    private static final int MAX_REPORTED_SKIPS = 50;
    private final CatalogProductSourceRepository sources;
    private final RamSpecRepository ram;
    private final ExistingPriceObservationQuery existing;
    private final PriceHistoryObservationRepository history;
    private final CatalogPriceDatabaseTimeZone databaseTimeZone;

    public PriceHistoryImportService(CatalogProductSourceRepository sources, RamSpecRepository ram,
                                     ExistingPriceObservationQuery existing, PriceHistoryObservationRepository history,
                                     CatalogPriceDatabaseTimeZone databaseTimeZone) {
        this.sources = sources;
        this.ram = ram;
        this.existing = existing;
        this.history = history;
        this.databaseTimeZone = databaseTimeZone;
    }

    /** 가져올 파일 하나. file은 기록용 저장소 기준 상대 경로다. */
    public record NamedBatch(String file, CatalogPriceImportBatch batch) {
        public NamedBatch {
            if (file == null || file.isBlank() || batch == null) throw new IllegalArgumentException("File and batch are required");
        }
    }

    public record Result(boolean dryRun, int checked, int created, int alreadyInCatalog, int alreadyInHistory,
                         int duplicateInFiles, int skippedUnknownProduct, int skippedMismatch, List<String> skipped) { }

    private record Plan(String productId, CatalogPriceImportBatch.Offer offer,
                        CatalogPriceImportBatch.Price price, String file) { }
    private record OfferId(String sourceName, String externalId) { }

    @Transactional(readOnly = true)
    public Result preview(List<NamedBatch> batches) {
        return databaseTimeZone.read(() -> process(batches, true, null));
    }

    @Transactional
    public Result apply(List<NamedBatch> batches, Instant importedAt) {
        if (importedAt == null) throw new IllegalArgumentException("Import time is required");
        return databaseTimeZone.write(() -> process(batches, false, importedAt));
    }

    private Result process(List<NamedBatch> batches, boolean dryRun, Instant importedAt) {
        if (batches == null || batches.isEmpty()) throw new IllegalArgumentException("At least one price file is required");
        var seen = new HashMap<PricePoint.ObservationKey, Long>();
        var offerOwners = new HashMap<OfferId, String>();
        var plans = new ArrayList<Plan>();
        var skipped = new ArrayList<String>();
        int checked = 0, inCatalog = 0, inHistory = 0, duplicates = 0, unknown = 0, mismatch = 0;

        // 전체 검증을 마친 뒤에만 저장한다. 충돌은 예외로 전체 실행을 거절한다.
        for (var named : batches) {
            for (var item : named.batch().items()) {
                checked++;
                var identity = item.product();
                var offer = item.offer();
                var price = item.price();
                var source = sources.findBySourceNameAndExternalId(identity.sourceName(), identity.externalId());
                if (source.isEmpty()) {
                    unknown++;
                    report(skipped, named.file(), "UNKNOWN_PRODUCT", identity.sourceName() + "/" + identity.externalId());
                    continue;
                }
                var product = source.get().getProduct();
                String problem = identityProblem(product, identity, offer);
                var mappedTo = existing.findMappedProductIds(offer.sourceName(), offer.externalId());
                if (problem == null && !mappedTo.isEmpty() && !mappedTo.contains(product.getId())) {
                    problem = "OFFER_MAPPED_TO_OTHER_PRODUCT";
                }
                if (problem != null) {
                    mismatch++;
                    report(skipped, named.file(), problem, offer.sourceName() + "/" + offer.externalId());
                    continue;
                }
                var offerId = new OfferId(offer.sourceName(), offer.externalId());
                var owner = offerOwners.putIfAbsent(offerId, product.getId());
                if (owner != null && !owner.equals(product.getId())) {
                    throw new IllegalArgumentException("The same provider offer is linked to different products: "
                            + offer.sourceName() + "/" + offer.externalId());
                }

                var key = new PricePoint.ObservationKey(offer.sourceName(), offer.externalId(), price.observedAt());
                var earlier = seen.putIfAbsent(key, price.amountKrw());
                if (earlier != null) {
                    if (!earlier.equals(price.amountKrw())) {
                        throw new IllegalArgumentException("Conflicting prices for the same offer and timestamp: " + key);
                    }
                    duplicates++;
                    continue;
                }
                if (existing.existsObservation(offer.sourceName(), offer.externalId(), price.observedAt())) {
                    inCatalog++;
                    continue;
                }
                var stored = history.findBySourceNameAndExternalIdAndObservedAt(
                        offer.sourceName(), offer.externalId(), price.observedAt());
                if (stored.isPresent()) {
                    if (stored.get().getAmountKrw() != price.amountKrw()
                            || !stored.get().getProductId().equals(product.getId())) {
                        throw new IllegalArgumentException("An imported history row differs at the same offer and timestamp: " + key);
                    }
                    inHistory++;
                    continue;
                }
                plans.add(new Plan(product.getId(), offer, price, named.file()));
            }
        }
        if (!dryRun) {
            for (var plan : plans) {
                // UNIQUE 제약이 동시 실행을 막는다. 실패하면 이번 실행 전체가 롤백된다.
                history.saveAndFlush(new PriceHistoryObservation(plan.productId(), plan.offer(), plan.price(),
                        plan.file(), importedAt));
            }
        }
        return new Result(dryRun, checked, plans.size(), inCatalog, inHistory, duplicates, unknown, mismatch,
                List.copyOf(skipped));
    }

    /** 기존 가격 가져오기와 같은 기준으로 제품 식별과 판매 단위를 확인한다. */
    private String identityProblem(CatalogProduct product, CatalogPriceImportBatch.Product identity,
                                   CatalogPriceImportBatch.Offer offer) {
        if (!product.getManufacturer().equals(identity.manufacturer())
                || !product.getModelName().equals(identity.modelName())
                || (identity.partNumber() != null && !Objects.equals(product.getPartNumber(), identity.partNumber()))) {
            return "PRODUCT_IDENTITY_MISMATCH";
        }
        if (product.getType() == PartType.RAM) {
            var spec = ram.findById(product.getId()).map(row -> row.toSpecification()).orElse(null);
            if (spec == null || offer.saleUnit() != CatalogPriceImportBatch.SaleUnit.RAM_KIT
                    || spec.moduleCount() == null || !spec.moduleCount().equals(offer.moduleCount())) {
                return "RAM_KIT_MISMATCH";
            }
        } else if (offer.saleUnit() != CatalogPriceImportBatch.SaleUnit.PRODUCT) {
            return "SALE_UNIT_MISMATCH";
        }
        return null;
    }

    private static void report(List<String> skipped, String file, String reason, String key) {
        if (skipped.size() < MAX_REPORTED_SKIPS) skipped.add(reason + " " + key + " (" + file + ")");
    }
}
