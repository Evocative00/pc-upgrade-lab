package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.catalog.identity.*;
import com.pcupgradelab.catalog.price.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit one-product registration, model binding and price observation in one preservation-checked transaction. */
@Service
public class RetailCatalogApplyService {
    public static final String REVIEW_SCOPE = "Approved ASUS domestic single-board sale 2026-10-10: exact TUF GAMING B860-PLUS WIFI, Intek distributor and reviewed package; reuse existing model without changing or activating installed-model references.";
    private final CatalogProductRepository products;
    private final CatalogProductSourceRepository sources;
    private final CatalogEntryService entries;
    private final CatalogIdentityProductRepository canonical;
    private final CatalogModelRepository models;
    private final CatalogIdentityService identities;
    private final CatalogReferencePriceRepository references;
    private final CatalogPriceImportService prices;
    private final CatalogPriceMappingRepository mappings;
    private final CatalogPriceObservationRepository observations;
    private final CatalogPriceDatabaseTimeZone timeZone;
    private final AllCatalogEvidenceTimestampReader evidenceTimes;
    private final RetailCatalogPreservationAudit audit;
    private final EntityManager entityManager;

    public RetailCatalogApplyService(CatalogProductRepository products, CatalogProductSourceRepository sources,
            CatalogEntryService entries, CatalogIdentityProductRepository canonical, CatalogModelRepository models,
            CatalogIdentityService identities, CatalogReferencePriceRepository references, CatalogPriceImportService prices,
            CatalogPriceMappingRepository mappings, CatalogPriceObservationRepository observations,
            CatalogPriceDatabaseTimeZone timeZone, AllCatalogEvidenceTimestampReader evidenceTimes,
            RetailCatalogPreservationAudit audit, EntityManager entityManager) {
        this.products=products; this.sources=sources; this.entries=entries; this.canonical=canonical; this.models=models;
        this.identities=identities; this.references=references; this.prices=prices; this.mappings=mappings;
        this.observations=observations; this.timeZone=timeZone; this.evidenceTimes=evidenceTimes; this.audit=audit; this.entityManager=entityManager;
    }
    public record Result(boolean dryRun, int checkedExistingProducts, int createdProducts, int createdSources,
                         int createdMotherboardSpecifications, int createdReferencePlaceholders, int productBindings,
                         int createdPriceMappings, int createdPriceObservations, int unchangedProducts, int modelWrites,
                         String productId, Map<String,RetailCatalogPreservationAudit.TableDigest> before,
                         Map<String,RetailCatalogPreservationAudit.TableDigest> after) { }
    @Transactional(readOnly=true)
    public Result preview(RetailCatalogApplyLoader.Plan plan) { return process(plan,false); }
    @Transactional
    public Result apply(RetailCatalogApplyLoader.Plan plan) { return process(plan,true); }

    private Result process(RetailCatalogApplyLoader.Plan plan, boolean apply) {
        if (plan == null) throw new IllegalArgumentException("Approved retail plan is required");
        var before = audit.capture();
        var sale = plan.sale();
        var existingSource = sources.findBySourceNameAndExternalId(sale.source().sourceName(), sale.source().externalId()).orElse(null);
        long expectedCount = existingSource == null ? 307 : 308;
        if (products.count() != expectedCount) throw new IllegalArgumentException("Expected complete approved 307-product catalog or identical completed 308-product replay");
        verifyBaseline(plan,apply);
        var model = models.findByCanonicalId(sale.modelCanonicalId()).orElseThrow(() -> new IllegalArgumentException("Approved existing model is missing"));
        var reviewedModel = plan.pilot().models().stream().filter(m -> m.canonicalId().equals(sale.modelCanonicalId())).findFirst().orElseThrow();
        timeZone.read(() -> identities.verifyModel(reviewedModel));
        var owner = canonical.findByCanonicalId(sale.identity().identity().canonicalId()).orElse(null);
        if (existingSource != null) {
            var product = existingSource.getProduct();
            if (apply) entityManager.refresh(product,LockModeType.PESSIMISTIC_WRITE);
            if (owner == null || !owner.getId().equals(product.getId())) throw new IllegalArgumentException("Partial or conflicting retail canonical binding");
            verifyCompleted(plan,existingSource,model.getId());
            var after = audit.capture();
            if (!before.equals(after)) throw new IllegalStateException("Read-only replay changed database rows");
            return result(!apply,0,1,product.getId(),before,after);
        }
        if (owner != null) throw new IllegalArgumentException("Retail canonical ID belongs to another product");
        timeZone.read(() -> {
            if (mappings.findBySourceNameAndExternalId(sale.price().offer().sourceName(),sale.price().offer().externalId()).isPresent())
                throw new IllegalArgumentException("Reviewed retail offer is already mapped; explicit conflict review is required");
            return null;
        });
        if (!apply) {
            var after = audit.capture();
            if (!before.equals(after)) throw new IllegalStateException("Read-only retail preview changed database rows");
            return result(true,1,0,null,before,after);
        }
        String productId = timeZone.write(() -> {
            var entry = entries.create(plan.registration());
            var evidence = sources.findBySourceNameAndExternalId(sale.source().sourceName(),sale.source().externalId()).orElseThrow();
            identities.bindProduct(new CatalogIdentityRequests.ProductBinding(entry.product().id(),sale.identity().identity().canonicalId(),model.getId(),
                    sale.identity().identity().identityKind(),sale.identity().identity().role(),evidence.getId(),REVIEW_SCOPE));
            var priceResult = prices.apply(plan.priceBatch());
            if (priceResult.createdMappings() != 1 || priceResult.createdObservations() != 1 || priceResult.unchangedObservations() != 0)
                throw new IllegalStateException("Retail price insertion differs from approved scope");
            entityManager.flush();
            return entry.product().id();
        });
        var after = audit.capture();
        audit.assertOnlyApprovedInsertions(before,after,productId);
        return result(false,1,0,productId,before,after);
    }
    private Result result(boolean dryRun,int creates,int unchanged,String id,
                          Map<String,RetailCatalogPreservationAudit.TableDigest> before,Map<String,RetailCatalogPreservationAudit.TableDigest> after) {
        return new Result(dryRun,307,creates,creates,creates,creates,creates,creates,creates,unchanged,0,id,before,after);
    }
    private void verifyBaseline(RetailCatalogApplyLoader.Plan plan,boolean lock) {
        for (var expected : plan.baseline().products()) {
            var source = sources.findBySourceNameAndExternalId(expected.sourceIdentity().sourceName(),expected.sourceIdentity().externalId())
                    .orElseThrow(() -> new IllegalArgumentException("Existing approved catalog source is missing"));
            var product = source.getProduct();
            if (lock) entityManager.refresh(product,LockModeType.PESSIMISTIC_WRITE);
            assertIdentity(product,expected.identity());
            if (!expected.specification().equals(entries.findById(product.getId()).orElseThrow().specification()))
                throw new IllegalArgumentException("Existing approved specification differs");
            var evidence = sources.findBySourceNameAndExternalId(expected.evidence().sourceName(),expected.evidence().externalId()).orElseThrow();
            assertSource(evidence,product.getId(),expected.evidence(),evidenceTimes.read(evidence,expected.bindLegacy()));
            if (expected.bindLegacy()) {
                if (product.getModelId() != null || !Objects.equals(product.getIdentityEvidenceSourceId(),evidence.getId())
                        || !AllCatalogIdentityImportService.REVIEW_SCOPE.equals(product.getIdentityReviewScope()))
                    throw new IllegalArgumentException("Existing legacy canonical review differs");
            } else {
                var pilotPart = plan.pilot().parts().stream().filter(p -> p.proposedCanonicalId().equals(expected.identity().canonicalId())).findFirst().orElseThrow();
                var model = models.findByCanonicalId(pilotPart.modelCanonicalId()).orElseThrow();
                if (!model.getId().equals(product.getModelId()) || !Objects.equals(product.getIdentityEvidenceSourceId(),evidence.getId())
                        || !pilotPart.bindingReviewScope().equals(product.getIdentityReviewScope()))
                    throw new IllegalArgumentException("Existing pilot model binding differs");
            }
        }
        var baselinePrices = prices.preview(plan.baseline().prices());
        if (baselinePrices.checked() != 74 || baselinePrices.createdMappings() != 0 || baselinePrices.createdObservations() != 0
                || baselinePrices.unchangedObservations() != 74) throw new IllegalArgumentException("Existing 74 local price observations are incomplete or differ");
    }
    private void verifyCompleted(RetailCatalogApplyLoader.Plan plan,CatalogProductSource source,String modelId) {
        var product = source.getProduct(); var sale = plan.sale();
        assertIdentity(product,sale.identity().identity());
        if (!modelId.equals(product.getModelId()) || !Objects.equals(product.getIdentityEvidenceSourceId(),source.getId())
                || !REVIEW_SCOPE.equals(product.getIdentityReviewScope())
                || !sale.specification().equals(entries.findById(product.getId()).orElseThrow().specification())
                || sources.findAllByProduct_IdOrderByIdAsc(product.getId()).size() != 1)
            throw new IllegalArgumentException("Partial or differing completed retail registration");
        assertSource(source,product.getId(),sale.source(),evidenceTimes.read(source,false));
        var reference = references.findById(product.getId()).orElseThrow(() -> new IllegalArgumentException("Retail reference placeholder is missing"));
        if (reference.getStatus() != CatalogPriceStatus.UNCONFIRMED || reference.getAmountKrw() != null || reference.getMethod() != null
                || reference.getPeriodStart() != null || reference.getPeriodEnd() != null || reference.getObservedDayCount() != null
                || reference.getSampleCount() != null || reference.getPriceBasis() != null || reference.getEvidenceRef() != null
                || reference.getCalculatedAt() != null || reference.getConfirmedAt() != null)
            throw new IllegalArgumentException("Retail reference placeholder differs");
        timeZone.read(() -> {
            var checked = prices.preview(plan.priceBatch());
            if (checked.createdMappings() != 0 || checked.createdObservations() != 0 || checked.unchangedObservations() != 1)
                throw new IllegalArgumentException("Partial retail price application is not repaired automatically");
            long mappingCount = entityManager.createQuery("select count(m) from CatalogPriceMapping m where m.product.id=:id",Long.class)
                    .setParameter("id",product.getId()).getSingleResult();
            long observationCount = entityManager.createQuery("select count(o) from CatalogPriceObservation o where o.product.id=:id",Long.class)
                    .setParameter("id",product.getId()).getSingleResult();
            if (mappingCount != 1 || observationCount != 1) throw new IllegalArgumentException("Completed retail price rows exceed approved scope");
            return null;
        });
    }
    private static void assertIdentity(CatalogProduct product,SharedPriceDtos.Identity expected) {
        if (product.getType() != expected.type() || !product.getManufacturer().equals(expected.manufacturer())
                || !product.getModelName().equals(expected.modelName()) || !Objects.equals(product.getPartNumber(),expected.partNumber())
                || !expected.canonicalId().equals(product.getCanonicalId()) || product.getIdentityKind() != expected.identityKind()
                || product.getRole() != expected.role() || product.getVerificationStatus() != expected.verificationStatus()
                || product.isActive() != expected.active()) throw new IllegalArgumentException("Existing exact product identity, canonical ID or classification differs");
    }
    private static void assertSource(CatalogProductSource source,String owner,CatalogSourceInput expected,Instant retrievedAt) {
        if (!source.getProduct().getId().equals(owner) || source.getSourceName() != expected.sourceName()
                || !Objects.equals(source.getExternalId(),expected.externalId()) || !Objects.equals(source.getSourceRevision(),expected.sourceRevision())
                || !source.getSourceUrl().equals(expected.sourceUrl()) || !retrievedAt.equals(expected.retrievedAt())
                || !sameJson(source.getRawPayload(),expected.rawPayload())) throw new IllegalArgumentException("Approved catalog source evidence differs");
    }
    private static boolean sameJson(Object left,Object right) {
        if (left instanceof Number l && right instanceof Number r) return new BigDecimal(l.toString()).compareTo(new BigDecimal(r.toString())) == 0;
        if (left instanceof Map<?,?> l && right instanceof Map<?,?> r)
            return l.keySet().equals(r.keySet()) && l.keySet().stream().allMatch(k -> sameJson(l.get(k),r.get(k)));
        if (left instanceof List<?> l && right instanceof List<?> r)
            return l.size() == r.size() && java.util.stream.IntStream.range(0,l.size()).allMatch(i -> sameJson(l.get(i),r.get(i)));
        return Objects.equals(left,right);
    }
}
