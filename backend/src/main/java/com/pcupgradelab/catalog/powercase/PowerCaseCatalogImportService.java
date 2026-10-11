package com.pcupgradelab.catalog.powercase;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.catalog.identity.*;
import com.pcupgradelab.catalog.price.*;
import com.pcupgradelab.catalog.shared.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 별도 승인된 정확한 7종만 추가한다. 기존 상품·가격·활성 상태는 변경하거나 보수하지 않는다. */
@Service
public class PowerCaseCatalogImportService {
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
    private final PowerCaseCatalogPreservationAudit audit;
    private final EntityManager entityManager;

    public PowerCaseCatalogImportService(CatalogProductRepository products, CatalogProductSourceRepository sources,
            CatalogEntryService entries, CatalogIdentityProductRepository canonical, CatalogModelRepository models,
            CatalogIdentityService identities, CatalogReferencePriceRepository references, CatalogPriceImportService prices,
            CatalogPriceMappingRepository mappings, CatalogPriceObservationRepository observations,
            CatalogPriceDatabaseTimeZone timeZone, AllCatalogEvidenceTimestampReader evidenceTimes,
            PowerCaseCatalogPreservationAudit audit, EntityManager entityManager) {
        this.products=products; this.sources=sources; this.entries=entries; this.canonical=canonical; this.models=models;
        this.identities=identities; this.references=references; this.prices=prices; this.mappings=mappings;
        this.observations=observations; this.timeZone=timeZone; this.evidenceTimes=evidenceTimes; this.audit=audit; this.entityManager=entityManager;
    }
    public record Result(boolean dryRun, int checkedExistingProducts, int plannedProducts, int createdProducts,
            int createdSources, int productBindings, int createdPriceMappings, int createdPriceObservations,
            int unchangedProducts, int modelWrites, int activations, String previewSha256, List<String> productIds,
            Map<String,PowerCaseCatalogPreservationAudit.TableDigest> before,
            Map<String,PowerCaseCatalogPreservationAudit.TableDigest> after) { }

    @Transactional(readOnly=true)
    public Result preview(PowerCaseCatalogImportLoader.Plan plan) { return process(plan,false); }
    @Transactional
    public Result apply(PowerCaseCatalogImportLoader.Plan plan) { return process(plan,true); }

    private Result process(PowerCaseCatalogImportLoader.Plan plan, boolean apply) {
        if (plan == null || (apply && !plan.databaseApplyApproved()))
            throw new IllegalArgumentException("Exact separate database approval is required before PSU/CASE apply");
        audit.requireV15();
        var before = audit.capture();
        var existing = new LinkedHashMap<String,CatalogProduct>();
        for (var registration : plan.preview().registrations()) {
            var primary = registration.entry().sources().getFirst();
            var source = sources.findBySourceNameAndExternalId(primary.sourceName(),primary.externalId()).orElse(null);
            var owner = canonical.findByCanonicalId(registration.canonicalId()).orElse(null);
            if (source == null && owner == null) continue;
            if (source == null || owner == null || !source.getProduct().getId().equals(owner.getId()))
                throw new IllegalArgumentException("Partial or conflicting PSU/CASE source or canonical binding");
            existing.put(registration.key(),owner);
        }
        if ((!existing.isEmpty() && existing.size() != 7) || products.count() != 308L + existing.size())
            throw new IllegalArgumentException("Expected complete approved 308-product baseline or identical 315-product replay; partial imports require review");
        // Legacy seed TIMESTAMP evidence intentionally reads the original session's wall clock.
        // Only newly reviewed model evidence and price rows enter their own UTC scopes.
        verifyBaseline(plan.baseline(),apply);
        if (!existing.isEmpty()) {
            timeZone.read(() -> {
                for (var registration : plan.preview().registrations()) {
                    var product = existing.get(registration.key());
                    if (apply) entityManager.refresh(product,LockModeType.PESSIMISTIC_WRITE);
                    verifyCompleted(plan,registration,product);
                }
                return null;
            });
            var after = audit.capture(); require(before.equals(after),"Completed replay changed database rows");
            return result(plan,!apply,0,7,existing.values().stream().map(CatalogProduct::getId).toList(),before,after);
        }
        timeZone.read(() -> {
            for (var price : plan.preview().prices())
                require(mappings.findBySourceNameAndExternalId(price.offer().sourceName(),price.offer().externalId()).isEmpty(),
                        "Reviewed PSU/CASE offer is already mapped; explicit conflict review is required");
            return null;
        });
        if (!apply) {
            var after = audit.capture(); require(before.equals(after),"Read-only PSU/CASE preview changed database rows");
            return result(plan,true,0,0,List.of(),before,after);
        }
        var inserted = timeZone.write(() -> {
            var ids = new LinkedHashMap<String,String>();
            for (var registration : plan.preview().registrations()) {
                var entry = entries.create(registration.entry());
                var primary = registration.entry().sources().getFirst();
                var evidence = sources.findBySourceNameAndExternalId(primary.sourceName(),primary.externalId()).orElseThrow();
                identities.bindProduct(new CatalogIdentityRequests.ProductBinding(entry.product().id(),registration.canonicalId(),null,
                        registration.identityKind(),registration.role(),evidence.getId(),reviewScope(plan)));
                ids.put(registration.key(),entry.product().id());
            }
            // Dedicated reviewed seven-product path: exact model manufacturer evidence may have no published part number.
            // It deliberately does not weaken the general price importer or fabricate a SKU.
            for (var price : plan.preview().prices()) {
                var product = products.findById(ids.get(price.key())).orElseThrow();
                var mapping = mappings.saveAndFlush(new CatalogPriceMapping(product,price.offer()));
                observations.saveAndFlush(new CatalogPriceObservation(mapping,price.price()));
            }
            entityManager.flush();
            for (var registration : plan.preview().registrations())
                verifyCompleted(plan,registration,products.findById(ids.get(registration.key())).orElseThrow());
            return List.copyOf(ids.values());
        });
        var after = audit.capture();
        var expected = new HashMap<>(plan.preview().report().proposedRegistrationRows());
        expected.putAll(plan.preview().report().proposedPriceRows());
        audit.assertOnlyInsertions(before,after,inserted,expected);
        return result(plan,false,7,0,inserted,before,after);
    }
    private Result result(PowerCaseCatalogImportLoader.Plan plan,boolean dryRun,int created,int unchanged,List<String> ids,
            Map<String,PowerCaseCatalogPreservationAudit.TableDigest> before,Map<String,PowerCaseCatalogPreservationAudit.TableDigest> after) {
        return new Result(dryRun,308,unchanged == 0 ? 7 : 0,created,
                created == 0 ? 0 : plan.preview().report().proposedRegistrationRows().get("catalog_product_source"),created,
                created == 0 ? 0 : plan.preview().prices().size(),created == 0 ? 0 : plan.preview().prices().size(),
                unchanged,0,0,plan.previewSha256(),ids,before,after);
    }
    public static String reviewScope(PowerCaseCatalogImportLoader.Plan plan) {
        return "Reviewed exact four PSU and three CASE variants 2026-10-11; model links and activation excluded; normalized source SHA-256=" + plan.previewSha256();
    }
    private void verifyBaseline(RetailCatalogApplyLoader.Plan plan, boolean lock) {
        for (var expected : plan.baseline().products()) {
            var source = sources.findBySourceNameAndExternalId(expected.sourceIdentity().sourceName(),expected.sourceIdentity().externalId())
                    .orElseThrow(() -> new IllegalArgumentException("Existing approved catalog source is missing"));
            var product = source.getProduct();
            if (lock) entityManager.refresh(product,LockModeType.PESSIMISTIC_WRITE);
            assertIdentity(product,expected.identity());
            require(expected.specification().equals(entries.findById(product.getId()).orElseThrow().specification()),"Existing approved specification differs");
            var evidence = sources.findBySourceNameAndExternalId(expected.evidence().sourceName(),expected.evidence().externalId()).orElseThrow();
            assertSource(evidence,product.getId(),expected.evidence(),evidenceTimes.read(evidence,expected.bindLegacy()));
            if (expected.bindLegacy()) {
                require(product.getModelId() == null && Objects.equals(product.getIdentityEvidenceSourceId(),evidence.getId())
                        && AllCatalogIdentityImportService.REVIEW_SCOPE.equals(product.getIdentityReviewScope()),"Existing legacy canonical review differs");
            } else {
                var pilot = plan.pilot().parts().stream().filter(p -> p.proposedCanonicalId().equals(expected.identity().canonicalId())).findFirst().orElseThrow();
                var model = models.findByCanonicalId(pilot.modelCanonicalId()).orElseThrow();
                require(model.getId().equals(product.getModelId()) && Objects.equals(product.getIdentityEvidenceSourceId(),evidence.getId())
                        && pilot.bindingReviewScope().equals(product.getIdentityReviewScope()),"Existing pilot model binding differs");
            }
        }
        timeZone.read(() -> { for (var model : plan.pilot().models()) identities.verifyModel(model); return null; });
        var oldPrices = prices.preview(plan.baseline().prices());
        require(oldPrices.checked() == 74 && oldPrices.createdMappings() == 0 && oldPrices.createdObservations() == 0
                && oldPrices.unchangedObservations() == 74,"Existing 74 local price observations are incomplete or differ");
        var sale = plan.sale();
        var saleSource = sources.findBySourceNameAndExternalId(sale.source().sourceName(),sale.source().externalId())
                .orElseThrow(() -> new IllegalArgumentException("Previously approved ASUS retail product is missing"));
        var product = saleSource.getProduct(); if (lock) entityManager.refresh(product,LockModeType.PESSIMISTIC_WRITE);
        assertIdentity(product,sale.identity().identity());
        var model = models.findByCanonicalId(sale.modelCanonicalId()).orElseThrow();
        require(model.getId().equals(product.getModelId()) && Objects.equals(product.getIdentityEvidenceSourceId(),saleSource.getId())
                && RetailCatalogApplyService.REVIEW_SCOPE.equals(product.getIdentityReviewScope())
                && sale.specification().equals(entries.findById(product.getId()).orElseThrow().specification())
                && sources.findAllByProduct_IdOrderByIdAsc(product.getId()).size() == 1,"Existing approved ASUS registration differs");
        assertSource(saleSource,product.getId(),sale.source(),evidenceTimes.read(saleSource,false));
        assertPlaceholder(product.getId());
        var retailPrice = prices.preview(plan.priceBatch());
        require(retailPrice.createdMappings() == 0 && retailPrice.createdObservations() == 0 && retailPrice.unchangedObservations() == 1
                && priceRows("CatalogPriceMapping",product.getId()) == 1 && priceRows("CatalogPriceObservation",product.getId()) == 1,
                "Previously approved ASUS price is incomplete or differs");
    }
    private void verifyCompleted(PowerCaseCatalogImportLoader.Plan plan,PowerCaseCatalogPreviewLoader.Registration registration,CatalogProduct product) {
        var input = registration.entry().product();
        require(product.getType() == input.type() && product.getManufacturer().equals(input.manufacturer())
                && product.getModelName().equals(input.modelName()) && Objects.equals(product.getPartNumber(),input.partNumber())
                && registration.canonicalId().equals(product.getCanonicalId()) && product.getModelId() == null
                && product.getIdentityKind() == registration.identityKind() && product.getRole() == registration.role()
                && product.getVerificationStatus() == registration.verificationStatus() && product.isActive() == registration.active()
                && reviewScope(plan).equals(product.getIdentityReviewScope())
                && registration.entry().specification().equals(entries.findById(product.getId()).orElseThrow().specification()),
                "Partial or differing completed PSU/CASE registration");
        var actual = new ArrayList<>(sources.findAllByProduct_IdOrderByIdAsc(product.getId()));
        require(actual.size() == registration.entry().sources().size(),"Completed PSU/CASE source inventory differs");
        for (int i=0; i<registration.entry().sources().size(); i++) {
            var expected = registration.entry().sources().get(i);
            var matching = actual.stream().filter(s -> sourceMatches(s,product.getId(),expected,evidenceTimes.read(s,false))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Completed PSU/CASE source evidence differs"));
            if (i == 0) require(Objects.equals(product.getIdentityEvidenceSourceId(),matching.getId()),"Completed PSU/CASE primary evidence binding differs");
            actual.remove(matching);
        }
        assertPlaceholder(product.getId());
        var price = plan.preview().prices().stream().filter(p -> p.key().equals(registration.key())).findFirst().orElse(null);
        long expectedRows = price == null ? 0 : 1;
        require(priceRows("CatalogPriceMapping",product.getId()) == expectedRows && priceRows("CatalogPriceObservation",product.getId()) == expectedRows,
                "Partial or excessive PSU/CASE price rows require explicit review");
        if (price != null) {
            var mapping = mappings.findBySourceNameAndExternalId(price.offer().sourceName(),price.offer().externalId()).orElseThrow();
            require(mapping.getProduct().getId().equals(product.getId()) && mapping.toOffer().equals(price.offer()),"Completed PSU/CASE exact offer differs");
            var observation = observations.findByMapping_IdAndObservedAt(mapping.getId(),price.price().observedAt()).orElseThrow();
            require(observation.getProductId().equals(product.getId()) && observation.matches(price.price()),"Completed PSU/CASE observation differs");
        }
    }
    private long priceRows(String entity,String id) {
        return entityManager.createQuery("select count(p) from " + entity + " p where p.product.id=:id",Long.class).setParameter("id",id).getSingleResult();
    }
    private void assertPlaceholder(String id) {
        var r = references.findById(id).orElseThrow(() -> new IllegalArgumentException("Reference placeholder is missing"));
        require(r.getStatus() == CatalogPriceStatus.UNCONFIRMED && r.getAmountKrw() == null && r.getMethod() == null
                && r.getPeriodStart() == null && r.getPeriodEnd() == null && r.getObservedDayCount() == null && r.getSampleCount() == null
                && r.getPriceBasis() == null && r.getEvidenceRef() == null && r.getCalculatedAt() == null && r.getConfirmedAt() == null,
                "Reference placeholder differs");
    }
    private static void assertIdentity(CatalogProduct p,SharedPriceDtos.Identity e) {
        require(p.getType() == e.type() && p.getManufacturer().equals(e.manufacturer()) && p.getModelName().equals(e.modelName())
                && Objects.equals(p.getPartNumber(),e.partNumber()) && e.canonicalId().equals(p.getCanonicalId())
                && p.getIdentityKind() == e.identityKind() && p.getRole() == e.role() && p.getVerificationStatus() == e.verificationStatus()
                && p.isActive() == e.active(),"Existing exact identity, canonical ID or classification differs");
    }
    private static void assertSource(CatalogProductSource s,String owner,CatalogSourceInput e,Instant time) {
        require(sourceMatches(s,owner,e,time),"Approved source evidence differs");
    }
    private static boolean sourceMatches(CatalogProductSource s,String owner,CatalogSourceInput e,Instant time) {
        return s.getProduct().getId().equals(owner) && s.getSourceName() == e.sourceName() && Objects.equals(s.getExternalId(),e.externalId())
                && Objects.equals(s.getSourceRevision(),e.sourceRevision()) && s.getSourceUrl().equals(e.sourceUrl())
                && time.equals(e.retrievedAt()) && sameJson(s.getRawPayload(),e.rawPayload());
    }
    private static boolean sameJson(Object left,Object right) {
        if (left instanceof Number l && right instanceof Number r) return new BigDecimal(l.toString()).compareTo(new BigDecimal(r.toString())) == 0;
        if (left instanceof Map<?,?> l && right instanceof Map<?,?> r)
            return l.keySet().equals(r.keySet()) && l.keySet().stream().allMatch(k -> sameJson(l.get(k),r.get(k)));
        if (left instanceof List<?> l && right instanceof List<?> r)
            return l.size() == r.size() && java.util.stream.IntStream.range(0,l.size()).allMatch(i -> sameJson(l.get(i),r.get(i)));
        return Objects.equals(left,right);
    }
    private static void require(boolean condition,String message) { if (!condition) throw new IllegalArgumentException(message); }
}
