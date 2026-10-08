package com.pcupgradelab.catalog.price;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.pc.PartType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 파일 전체의 제품 식별·판매 단위·충돌을 검증한 뒤 한 트랜잭션으로 적용한다. */
@Service
public class CatalogPriceImportService {
    private final CatalogProductSourceRepository sources;
    private final RamSpecRepository ram;
    private final CatalogPriceMappingRepository mappings;
    private final CatalogPriceObservationRepository observations;
    private final CatalogPriceDatabaseTimeZone databaseTimeZone;

    public CatalogPriceImportService(CatalogProductSourceRepository sources, RamSpecRepository ram,
                                     CatalogPriceMappingRepository mappings,
                                     CatalogPriceObservationRepository observations,
                                     CatalogPriceDatabaseTimeZone databaseTimeZone) {
        this.sources = sources;
        this.ram = ram;
        this.mappings = mappings;
        this.observations = observations;
        this.databaseTimeZone = databaseTimeZone;
    }

    public record Result(boolean dryRun, int checked, int createdMappings,
                         int createdObservations, int unchangedObservations) { }
    private record MappingKey(String provider, String externalId) { }
    private record ObservationKey(MappingKey mapping, Instant observedAt) { }
    private record Plan(CatalogProduct product, CatalogPriceImportBatch.Offer offer,
                        CatalogPriceImportBatch.Price price, CatalogPriceMapping mapping, boolean unchanged) { }

    /** 기본 실행 경로는 미리보기다. DB 쓰기는 별도 apply 메서드로만 허용한다. */
    @Transactional(readOnly = true)
    public Result preview(CatalogPriceImportBatch batch) {
        return databaseTimeZone.read(() -> process(batch, true));
    }

    @Transactional
    public Result apply(CatalogPriceImportBatch batch) {
        return databaseTimeZone.write(() -> process(batch, false));
    }

    private Result process(CatalogPriceImportBatch batch, boolean dryRun) {
        if (batch == null) throw new IllegalArgumentException("Price import batch is required");
        Map<MappingKey, Plan> offersInFile = new HashMap<>();
        Map<ObservationKey, CatalogPriceImportBatch.Price> pricesInFile = new HashMap<>();
        List<Plan> plans = new ArrayList<>();
        int unchanged = 0;

        // 전체 검증을 마칠 때까지 save하지 않는다. 뒤쪽 행의 오류도 앞쪽 행을 남기지 않는다.
        for (var item : batch.items()) {
            var identity = item.product();
            var source = sources.findBySourceNameAndExternalId(identity.sourceName(), identity.externalId())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown catalog source identity: "
                            + identity.sourceName() + "/" + identity.externalId()));
            var product = source.getProduct();
            verifyIdentity(product, identity);
            verifyUnit(product, item.offer());
            var offer = item.offer();
            var key = new MappingKey(offer.sourceName(), offer.externalId());
            var earlier = offersInFile.get(key);
            if (earlier != null && (!earlier.product().getId().equals(product.getId())
                    || !earlier.offer().equals(offer))) {
                throw collision(key);
            }
            var existingMapping = earlier == null
                    ? mappings.findBySourceNameAndExternalId(key.provider(), key.externalId()).orElse(null)
                    : earlier.mapping();
            if (existingMapping != null && (!existingMapping.getProduct().getId().equals(product.getId())
                    || !existingMapping.toOffer().equals(offer))) {
                throw collision(key);
            }

            var observationKey = new ObservationKey(key, item.price().observedAt());
            var duplicate = pricesInFile.putIfAbsent(observationKey, item.price());
            if (duplicate != null) {
                if (!duplicate.equals(item.price())) {
                    throw new IllegalArgumentException("Conflicting prices at the same provider and timestamp");
                }
                unchanged++;
                continue;
            }
            var existingPrice = existingMapping == null ? null : observations
                    .findByMapping_IdAndObservedAt(existingMapping.getId(), item.price().observedAt()).orElse(null);
            if (existingPrice != null && !existingPrice.matches(item.price())) {
                throw new IllegalArgumentException("An existing observation has different price or evidence at this timestamp");
            }
            boolean noChange = existingPrice != null;
            var plan = new Plan(product, offer, item.price(), existingMapping, noChange);
            offersInFile.putIfAbsent(key, plan);
            plans.add(plan);
            if (noChange) unchanged++;
        }

        int createdMappings = (int) offersInFile.values().stream().filter(plan -> plan.mapping() == null).count();
        int createdObservations = (int) plans.stream().filter(plan -> !plan.unchanged()).count();
        if (!dryRun) {
            Map<MappingKey, CatalogPriceMapping> appliedMappings = new HashMap<>();
            for (var plan : plans) {
                if (plan.unchanged()) continue;
                var key = new MappingKey(plan.offer().sourceName(), plan.offer().externalId());
                var mapping = appliedMappings.get(key);
                if (mapping == null) {
                    mapping = plan.mapping() != null ? plan.mapping()
                            : mappings.saveAndFlush(new CatalogPriceMapping(plan.product(), plan.offer()));
                    appliedMappings.put(key, mapping);
                }
                // UNIQUE/FK 제약이 동시 실행과 우회 입력을 막는다. 실패하면 배치 전체가 롤백된다.
                observations.saveAndFlush(new CatalogPriceObservation(mapping, plan.price()));
            }
        }
        return new Result(dryRun, batch.items().size(), createdMappings, createdObservations, unchanged);
    }

    private void verifyIdentity(CatalogProduct product, CatalogPriceImportBatch.Product identity) {
        if (!product.getManufacturer().equals(identity.manufacturer())
                || !product.getModelName().equals(identity.modelName())
                || (identity.partNumber() != null && !Objects.equals(product.getPartNumber(), identity.partNumber()))) {
            throw new IllegalArgumentException("Reviewed manufacturer/model/partNumber does not match the catalog product");
        }
    }

    private void verifyUnit(CatalogProduct product, CatalogPriceImportBatch.Offer offer) {
        if (product.getType() == PartType.RAM) {
            var specification = ram.findById(product.getId()).orElseThrow(() ->
                    new IllegalArgumentException("RAM sale unit cannot be verified without RAM specification"))
                    .toSpecification();
            if (offer.saleUnit() != CatalogPriceImportBatch.SaleUnit.RAM_KIT
                    || specification.moduleCount() == null
                    || !specification.moduleCount().equals(offer.moduleCount())) {
                throw new IllegalArgumentException("RAM price must cover the catalog kit's exact moduleCount");
            }
        } else if (offer.saleUnit() != CatalogPriceImportBatch.SaleUnit.PRODUCT) {
            throw new IllegalArgumentException("Non-RAM price must cover one PRODUCT");
        }
    }

    private IllegalArgumentException collision(MappingKey key) {
        return new IllegalArgumentException("Provider identity is already mapped to another product or reviewed sale configuration: "
                + key.provider() + "/" + key.externalId());
    }
}
