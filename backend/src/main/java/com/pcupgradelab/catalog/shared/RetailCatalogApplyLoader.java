package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.catalog.identity.*;
import com.pcupgradelab.catalog.pilot.CatalogPilotImportLoader;
import com.pcupgradelab.catalog.price.*;
import com.pcupgradelab.pc.PartType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Separate, exact approval for one sale product. The four-candidate preview remains unapproved for DB writes. */
public final class RetailCatalogApplyLoader {
    public static final String APPROVAL_FILE = "data/catalog-review/retail-asus-apply-approval-2026-10-10.json";
    public static final String SOURCE_ID = "asus:TUF GAMING B860-PLUS WIFI:retail:intek:2026-10-10";
    public static final String PRODUCT_CANONICAL = "f5d69333-0ab1-382d-bc72-9434811daccf";
    public static final String MODEL_CANONICAL = "3650ed66-d0bf-3906-9a1c-a77544ce34cf";
    public static final String ORIGINAL_CANONICAL = "688c711d-70d2-39f5-9642-d1c2a87d4bb7";
    private static final Set<String> CANDIDATES = Set.of(ORIGINAL_CANONICAL,
            "4328778c-84af-324b-bc60-e51891fff949", "f0a98fa1-b311-3001-ad31-cd5ad368ae44",
            "109952f9-01de-3293-9034-ab93de7f98d0");
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(tools.jackson.databind.cfg.EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).build();

    public record Scope(int newProducts, int newProductSources, int motherboardSpecifications,
                        int referencePricePlaceholders, int productBindings, int priceMappings,
                        int priceObservations, int modelWrites, boolean autoActivation) { }
    public record Approval(int schemaVersion, String decision, String previewFile, String previewSha256,
                           SharedCatalogSnapshot.SourceIdentity sourceIdentity, String productCanonicalId,
                           String modelCanonicalId, Scope scope) { }
    public static final class Plan {
        private final RetailCatalogPreviewLoader.SaleProduct sale;
        private final AllCatalogManifestGenerator.Plan baseline;
        private final CatalogPilotImportLoader.Plan pilot;
        private final String previewSha256;
        private Plan(RetailCatalogPreviewLoader.SaleProduct sale, AllCatalogManifestGenerator.Plan baseline,
                     CatalogPilotImportLoader.Plan pilot, String previewSha256) {
            this.sale = sale; this.baseline = baseline; this.pilot = pilot; this.previewSha256 = previewSha256;
        }
        public RetailCatalogPreviewLoader.SaleProduct sale() { return sale; }
        public AllCatalogManifestGenerator.Plan baseline() { return baseline; }
        public CatalogPilotImportLoader.Plan pilot() { return pilot; }
        public String previewSha256() { return previewSha256; }
        public CatalogEntryCreateRequest registration() {
            return new CatalogEntryCreateRequest(sale.product(), sale.specification(), List.of(sale.source()));
        }
        public CatalogPriceImportBatch priceBatch() { return new CatalogPriceImportBatch(1, List.of(sale.price())); }
    }

    public Plan load(Path repositoryRoot) {
        try {
            require(repositoryRoot != null, "Repository root is required");
            var approval = decode(read(repositoryRoot.resolve(APPROVAL_FILE)), Approval.class);
            require(approval.schemaVersion() == 1 && "APPROVED".equals(approval.decision())
                    && RetailCatalogPreviewLoader.FILE.equals(approval.previewFile())
                    && approval.previewSha256() != null && approval.previewSha256().matches("[a-f0-9]{64}")
                    && new SharedCatalogSnapshot.SourceIdentity(CatalogSourceName.MANUFACTURER, SOURCE_ID).equals(approval.sourceIdentity())
                    && PRODUCT_CANONICAL.equals(approval.productCanonicalId()) && MODEL_CANONICAL.equals(approval.modelCanonicalId())
                    && new Scope(1,1,1,1,1,1,1,0,false).equals(approval.scope()), "Exact one-product approval is required");
            byte[] bytes = read(repositoryRoot.resolve(RetailCatalogPreviewLoader.FILE));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            require(hash.equals(approval.previewSha256()), "Approved raw preview SHA-256 differs");
            var review = decode(bytes, RetailCatalogPreviewLoader.Review.class);
            require(review.schemaVersion() == 1 && review.implementationApproved()
                    && !review.databaseApplyApproved() && !review.pricePublicationApproved()
                    && review.candidates() != null && review.candidates().size() == 4
                    && review.candidates().stream().allMatch(Objects::nonNull), "Original four-product preview flags or scope differ");
            var baseline = new AllCatalogManifestGenerator().load(repositoryRoot);
            var pilot = new CatalogPilotImportLoader().load(repositoryRoot);
            var seen = new HashSet<String>();
            RetailCatalogPreviewLoader.SaleProduct selected = null;
            for (var candidate : review.candidates()) {
                require(CANDIDATES.contains(candidate.existingCanonicalId()) && seen.add(candidate.existingCanonicalId())
                        && candidate.status() != null && candidate.reason() != null && !candidate.reason().isBlank(), "Candidate decision or scope differs");
                var original = baseline.products().stream().filter(p -> p.identity().canonicalId().equals(candidate.existingCanonicalId()))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("Original model identity is missing"));
                require(original.identity().identityKind() == CatalogIdentityKind.MODEL_REFERENCE
                        && original.identity().role() == CatalogRole.INSTALLED_PC_REFERENCE
                        && original.identity().modelName().equals(candidate.modelName()), "Original model identity differs");
                var observation = candidate.observation();
                require(observation != null && observation.price() != null
                        && SharedPriceSourcePolicy.matches(observation.sourceName(), observation.price().evidenceUrl(), observation.externalId()),
                        "Reviewed observation provider differs");
                if (!ORIGINAL_CANONICAL.equals(candidate.existingCanonicalId())) {
                    require(candidate.status() == RetailCatalogPreviewLoader.Status.HELD && candidate.proposedSaleProduct() == null,
                            "Approval cannot apply held CPU or other motherboard candidates");
                    continue;
                }
                require(candidate.status() == RetailCatalogPreviewLoader.Status.READY_FOR_APPROVAL, "Approved ASUS candidate must be ready");
                selected = candidate.proposedSaleProduct();
                validateSale(selected, candidate, original, pilot);
            }
            require(selected != null, "Approved ASUS sale product is missing");
            return new Plan(selected, baseline, pilot, hash);
        } catch (IOException | NoSuchAlgorithmException | RuntimeException ex) {
            throw new IllegalArgumentException("Invalid approved retail application: " + ex.getMessage(), ex);
        }
    }

    private static void validateSale(RetailCatalogPreviewLoader.SaleProduct sale, RetailCatalogPreviewLoader.Candidate candidate,
                                     AllCatalogManifestGenerator.ProductPlan original, CatalogPilotImportLoader.Plan pilot) {
        require(sale != null && sale.product() != null && sale.specification() != null && sale.source() != null
                && sale.identity() != null && sale.identity().identity() != null && sale.price() != null, "Complete ASUS registration is required");
        var part = pilot.parts().stream().filter(p -> p.proposedCanonicalId().equals(ORIGINAL_CANONICAL)).findFirst().orElseThrow();
        var product = sale.product(); var identity = sale.identity().identity(); var source = sale.source();
        require(MODEL_CANONICAL.equals(sale.modelCanonicalId()) && sale.modelCanonicalId().equals(part.modelCanonicalId())
                && sale.specification().equals(part.specification()) && sale.specification().equals(original.specification()), "Existing model or specification differs");
        require(product.type() == PartType.MOTHERBOARD && "ASUS".equals(product.manufacturer())
                && candidate.modelName().equals(product.modelName()) && product.partNumber() == null
                && identity.type() == product.type() && identity.manufacturer().equals(product.manufacturer())
                && identity.modelName().equals(product.modelName()) && identity.partNumber() == null
                && PRODUCT_CANONICAL.equals(identity.canonicalId()) && identity.identityKind() == CatalogIdentityKind.PHYSICAL_VARIANT
                && identity.role() == CatalogRole.PURCHASE_CANDIDATE && !identity.active()
                && identity.verificationStatus() == CatalogVerificationStatus.UNVERIFIED
                && "PRODUCT".equals(identity.saleUnit()) && identity.moduleCount() == null,
                "Only the exact inactive unverified ASUS sale identity is approved");
        require(source.sourceName() == CatalogSourceName.MANUFACTURER && SOURCE_ID.equals(source.externalId())
                && new SharedCatalogSnapshot.SourceIdentity(source.sourceName(), source.externalId()).equals(sale.identity().sourceIdentity())
                && CanonicalCatalogIds.productForReviewedSource(source.sourceName(), source.externalId()).equals(identity.canonicalId()),
                "Reviewed sale source and canonical identity differ");
        var configuration = sale.identity().reviewedSaleConfiguration(); var item = sale.price(); var offer = item.offer();
        require(configuration != null && configuration.equals(item.product().reviewedSaleConfiguration())
                && source.rawPayload() != null && configuration.equals(MAPPER.convertValue(source.rawPayload().get("reviewedSaleConfiguration"), ReviewedMotherboardSaleConfiguration.class))
                && configuration.partType() == product.type() && configuration.identityKind() == identity.identityKind()
                && configuration.role() == identity.role() && configuration.manufacturerModelName().equals(product.modelName())
                && configuration.socketCode().equals(sale.specification().socketCode())
                && configuration.memoryType().equals(sale.specification().memoryType())
                && configuration.formFactor().equals(sale.specification().formFactor())
                && configuration.evidenceUrls().contains(source.sourceUrl()) && configuration.evidenceUrls().contains(offer.sourceUrl())
                && !source.retrievedAt().isBefore(configuration.verifiedAt()), "Sale configuration, evidence or specification differs");
        require(item.product().sourceName() == source.sourceName() && item.product().externalId().equals(source.externalId())
                && item.product().manufacturer().equals(product.manufacturer()) && item.product().modelName().equals(product.modelName())
                && item.product().partNumber() == null && offer.saleSku().equals(configuration.saleSku())
                && offer.saleUnit() == CatalogPriceImportBatch.SaleUnit.PRODUCT && offer.moduleCount() == null
                && SharedPriceSourcePolicy.matches(offer.sourceName(), offer.sourceUrl(), offer.externalId())
                && offer.sourceName().equals(candidate.observation().sourceName()) && offer.externalId().equals(candidate.observation().externalId())
                && item.price().evidenceUrl().equals(offer.sourceUrl()) && item.price().equals(candidate.observation().price())
                && !offer.verifiedAt().isBefore(configuration.verifiedAt()) && !item.price().observedAt().isBefore(offer.verifiedAt()),
                "Exact reviewed offer, provider, observation or time differs");
    }
    private static byte[] read(Path path) throws IOException {
        require(Files.isRegularFile(path) && Files.size(path) <= 5 * 1024 * 1024, "Approval or preview file is missing or too large");
        return Files.readAllBytes(path);
    }
    private static <T> T decode(byte[] bytes, Class<T> type) {
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (value.startsWith("\uFEFF")) value = value.substring(1);
        return MAPPER.treeToValue(MAPPER.readTree(value), type);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
