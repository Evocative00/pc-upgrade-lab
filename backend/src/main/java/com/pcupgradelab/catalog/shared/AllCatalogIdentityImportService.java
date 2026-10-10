package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.catalog.identity.*;
import com.pcupgradelab.catalog.price.CatalogPriceDatabaseTimeZone;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adds canonical identifiers to existing legacy products only; never imports prices, models or specifications. */
@Service
public class AllCatalogIdentityImportService {
    public static final String REVIEW_SCOPE = "Full 307-product central-price identity review 2026-10-10. Exact approved BuildCores source and seed identity; canonical sharing only. Sale identity, role, verification, activation and installed-PC links are not promoted.";
    private final CatalogProductRepository products;
    private final CatalogProductSourceRepository sources;
    private final CatalogEntryService entries;
    private final CatalogIdentityProductRepository canonical;
    private final CatalogIdentityService identity;
    private final CatalogPriceDatabaseTimeZone timeZone;
    private final AllCatalogEvidenceTimestampReader evidenceTimes;
    private final EntityManager entityManager;

    public AllCatalogIdentityImportService(CatalogProductRepository products,CatalogProductSourceRepository sources,
            CatalogEntryService entries,CatalogIdentityProductRepository canonical,CatalogIdentityService identity,
            CatalogPriceDatabaseTimeZone timeZone,AllCatalogEvidenceTimestampReader evidenceTimes,EntityManager entityManager) {
        this.products=products; this.sources=sources; this.entries=entries; this.canonical=canonical;
        this.identity=identity; this.timeZone=timeZone; this.evidenceTimes=evidenceTimes; this.entityManager=entityManager;
    }
    public record Result(boolean dryRun,int checkedProducts,int newCanonicalBindings,int unchangedCanonicalBindings,
                         int preservedPilotBindings,int priceWrites,int productCreates,int modelCreates) { }
    @Transactional(readOnly=true)
    public Result preview(AllCatalogManifestGenerator.Plan plan) { return process(plan,false); }
    @Transactional
    public Result apply(AllCatalogManifestGenerator.Plan plan) { return process(plan,true); }

    private Result process(AllCatalogManifestGenerator.Plan plan,boolean apply) {
        if (plan == null || plan.products().size() != 307 || plan.products().stream().filter(p -> p.bindLegacy()).count() != 293
                || plan.products().stream().map(p -> p.sourceIdentity()).distinct().count() != 307
                || plan.products().stream().map(p -> p.identity().canonicalId()).distinct().count() != 307)
            throw new IllegalArgumentException("Only the reviewed 307-product/293-binding plan is supported");
        if (products.count() != 307) throw new IllegalArgumentException("Expected existing 307-product catalog; no products are created or deleted");
        var pending = new ArrayList<CatalogIdentityRequests.ProductBinding>();
        int unchanged = 0;
        for (var expected : plan.products()) {
            var source = sources.findBySourceNameAndExternalId(expected.sourceIdentity().sourceName(),expected.sourceIdentity().externalId())
                    .orElseThrow(() -> new IllegalArgumentException("Reviewed existing catalog source is missing"));
            var product = source.getProduct();
            if (apply) entityManager.refresh(product,LockModeType.PESSIMISTIC_WRITE);
            var typed = expected.identity();
            if (product.getType() != typed.type() || !product.getManufacturer().equals(typed.manufacturer())
                    || !product.getModelName().equals(typed.modelName()) || !Objects.equals(product.getPartNumber(),typed.partNumber())
                    || product.getIdentityKind() != typed.identityKind() || product.getRole() != typed.role()
                    || product.getVerificationStatus() != typed.verificationStatus() || product.isActive() != typed.active()
                    || !entries.findById(product.getId()).orElseThrow().specification().equals(expected.specification()))
                throw new IllegalArgumentException("Existing catalog identity, classification or specification differs; no implicit overwrite");
            var evidence = sources.findBySourceNameAndExternalId(expected.evidence().sourceName(),expected.evidence().externalId())
                    .orElseThrow(() -> new IllegalArgumentException("Reviewed catalog evidence is missing"));
            var retrievedAt = evidenceTimes.read(evidence,expected.bindLegacy());
            if (!sourceMatches(evidence,product.getId(),expected.evidence(),retrievedAt))
                throw new IllegalArgumentException("Reviewed catalog evidence differs for " + typed.type() + " " + typed.modelName()
                        + " (productId=" + product.getId() + ", canonicalId=" + typed.canonicalId() + "): "
                        + evidenceDifferences(evidence,product.getId(),expected.evidence(),retrievedAt));
            var owner = canonical.findByCanonicalId(typed.canonicalId());
            if (owner.isPresent() && !owner.orElseThrow().getId().equals(product.getId()))
                throw new IllegalArgumentException("Canonical ID belongs to another product");
            if (!expected.bindLegacy()) {
                if (!typed.canonicalId().equals(product.getCanonicalId()))
                    throw new IllegalArgumentException("Existing approved pilot canonical binding is missing or differs");
                continue;
            }
            if (product.getModelId() != null || typed.identityKind() != CatalogIdentityKind.LEGACY_UNCLASSIFIED
                    || typed.role() != CatalogRole.UNASSIGNED)
                throw new IllegalArgumentException("Legacy canonical sharing must not infer model or role");
            var binding = new CatalogIdentityRequests.ProductBinding(product.getId(),typed.canonicalId(),null,
                    CatalogIdentityKind.LEGACY_UNCLASSIFIED,CatalogRole.UNASSIGNED,evidence.getId(),REVIEW_SCOPE);
            if (product.getCanonicalId() == null) pending.add(binding);
            else {
                if (!typed.canonicalId().equals(product.getCanonicalId())
                        || !Objects.equals(product.getIdentityEvidenceSourceId(),evidence.getId())
                        || !REVIEW_SCOPE.equals(product.getIdentityReviewScope()))
                    throw new IllegalArgumentException("Existing legacy canonical review differs; explicit re-review is required");
                unchanged++;
            }
        }
        // Every product is preflighted before the first mutation. Any concurrent conflict rolls back this transaction.
        if (apply) {
            // Only new canonical-review timestamps are written in UTC; original seed evidence is never reinterpreted or updated.
            timeZone.write(() -> {
                for (var binding : pending) identity.bindProduct(binding);
                entityManager.flush();
                return null;
            });
        }
        return new Result(!apply,307,pending.size(),unchanged,14,0,0,0);
    }
    private static boolean sourceMatches(CatalogProductSource actual,String owner,CatalogSourceInput expected,Instant retrievedAt) {
        return actual.getProduct().getId().equals(owner) && actual.getSourceName() == expected.sourceName()
                && Objects.equals(actual.getExternalId(),expected.externalId()) && Objects.equals(actual.getSourceRevision(),expected.sourceRevision())
                && actual.getSourceUrl().equals(expected.sourceUrl()) && retrievedAt.equals(expected.retrievedAt())
                && sameJson(actual.getRawPayload(),expected.rawPayload());
    }
    /** Only comparison results and public source times are exposed; never source URLs or raw JSON. */
    private static String evidenceDifferences(CatalogProductSource actual,String owner,CatalogSourceInput expected,Instant retrievedAt) {
        var checks = new LinkedHashMap<String,Boolean>();
        checks.put("owner",actual.getProduct().getId().equals(owner));
        checks.put("sourceName",actual.getSourceName() == expected.sourceName());
        checks.put("externalId",Objects.equals(actual.getExternalId(),expected.externalId()));
        checks.put("sourceRevision",Objects.equals(actual.getSourceRevision(),expected.sourceRevision()));
        checks.put("sourceUrl",actual.getSourceUrl().equals(expected.sourceUrl()));
        checks.put("retrievedAt",retrievedAt.equals(expected.retrievedAt()));
        checks.put("rawPayload",sameJson(actual.getRawPayload(),expected.rawPayload()));
        String diagnostic = checks.entrySet().stream().map(check -> check.getKey() + "=" + (check.getValue() ? "MATCH" : "DIFFERS"))
                .collect(java.util.stream.Collectors.joining(", "));
        if (!checks.get("retrievedAt")) diagnostic += "; retrievedAtActual=" + retrievedAt
                + "; retrievedAtExpected=" + expected.retrievedAt();
        return diagnostic;
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
