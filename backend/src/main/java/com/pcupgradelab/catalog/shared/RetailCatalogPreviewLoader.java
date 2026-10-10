package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogEntryCreateRequest;
import com.pcupgradelab.catalog.CatalogProductCreateRequest;
import com.pcupgradelab.catalog.CatalogSourceInput;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.CatalogSpecification;
import com.pcupgradelab.catalog.identity.CanonicalCatalogIds;
import com.pcupgradelab.catalog.identity.CatalogIdentityKind;
import com.pcupgradelab.catalog.identity.CatalogRole;
import com.pcupgradelab.catalog.pilot.CatalogPilotImportLoader;
import com.pcupgradelab.catalog.price.CatalogPriceImportBatch;
import com.pcupgradelab.catalog.price.ReviewedMotherboardSaleConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Four-product registration/price preview. No Spring, JDBC, private settings or remote calls. */
public final class RetailCatalogPreviewLoader {
    public static final String FILE = "data/catalog-review/retail-four-preview-2026-10-10.json";
    private static final Set<String> SCOPE = Set.of("4328778c-84af-324b-bc60-e51891fff949",
            "f0a98fa1-b311-3001-ad31-cd5ad368ae44", "109952f9-01de-3293-9034-ab93de7f98d0",
            "688c711d-70d2-39f5-9642-d1c2a87d4bb7");
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(tools.jackson.databind.cfg.EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).build();

    public enum Status { READY_FOR_APPROVAL, HELD }
    public record Observation(String sourceName, String externalId, CatalogPriceImportBatch.Price price) { }
    public record SaleProduct(String modelCanonicalId, CatalogProductCreateRequest product,
                              CatalogSpecification.Motherboard specification, CatalogSourceInput source,
                              SharedCatalogSnapshot.ProductIdentity identity, CatalogPriceImportBatch.Item price) { }
    public record Candidate(String existingCanonicalId, String modelName, Status status, String reason,
                            Observation observation, SaleProduct proposedSaleProduct) { }
    public record Review(int schemaVersion, boolean implementationApproved, boolean databaseApplyApproved,
                         boolean pricePublicationApproved, List<Candidate> candidates) { }
    public record Plan(Review review, SharedCatalogSnapshot.IdentityManifest identities,
                       CatalogPriceImportBatch prices, List<CatalogEntryCreateRequest> registrations) { }

    public Plan load(Path repositoryRoot) {
        try { return read(repositoryRoot); }
        catch (IOException | RuntimeException ex) {
            throw new IllegalArgumentException("Invalid four-product retail preview: " + ex.getMessage(), ex);
        }
    }

    private Plan read(Path root) throws IOException {
        var review = decode(text(root.resolve(FILE)), Review.class);
        require(review.schemaVersion() == 1 && review.implementationApproved()
                && !review.databaseApplyApproved() && !review.pricePublicationApproved(),
                "This scope authorizes implementation and preview only");
        require(review.candidates() != null && review.candidates().size() == 4
                && review.candidates().stream().allMatch(Objects::nonNull), "Exactly four candidates are required");
        var base = decode(text(root.resolve("data/catalog-shared/all-catalog-identities-2026-10-10.json")),
                SharedCatalogSnapshot.IdentityManifest.class);
        var active = decode(text(root.resolve(SharedCatalogSnapshot.ACTIVE_PRICES_FILE)), CatalogPriceImportBatch.class);
        // Load the original approved model and specification plan without opening a database.
        var pilot = new CatalogPilotImportLoader().load(root);
        var identities = new ArrayList<>(base.products());
        var prices = new ArrayList<>(active.items());
        var registrations = new ArrayList<CatalogEntryCreateRequest>();
        var seen = new HashSet<String>();
        for (var candidate : review.candidates()) {
            require(SCOPE.contains(candidate.existingCanonicalId()) && seen.add(candidate.existingCanonicalId()),
                    "Candidate is outside the approved four-product scope or repeated");
            var existing = base.products().stream().filter(p -> p.identity().canonicalId().equals(candidate.existingCanonicalId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Existing installed model is missing"));
            require(existing.identity().identityKind() == CatalogIdentityKind.MODEL_REFERENCE
                    && existing.identity().role() == CatalogRole.INSTALLED_PC_REFERENCE
                    && existing.identity().modelName().equals(candidate.modelName()), "Existing model identity differs");
            require(candidate.status() != null && candidate.reason() != null && !candidate.reason().isBlank(),
                    "Candidate requires a decision and reason");
            var observation = candidate.observation();
            require(observation != null && observation.price() != null
                    && SharedPriceSourcePolicy.matches(observation.sourceName(), observation.price().evidenceUrl(),
                        observation.externalId()), "Candidate observation uses an invalid provider product ID");
            if (candidate.status() == Status.HELD) {
                require(candidate.proposedSaleProduct() == null, "Held candidates cannot produce registration or price rows");
                continue;
            }
            var sale = candidate.proposedSaleProduct();
            require(sale != null && sale.product() != null && sale.specification() != null
                    && sale.source() != null && sale.identity() != null && sale.price() != null,
                    "Ready candidate requires a complete proposed sale product");
            var original = pilot.parts().stream().filter(p -> p.proposedCanonicalId().equals(candidate.existingCanonicalId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Approved model specification is missing"));
            require(sale.modelCanonicalId().equals(original.modelCanonicalId())
                    && sale.specification().equals(original.specification()), "Sale product changed the approved model or specification");
            var product = sale.product(); var identity = sale.identity().identity(); var source = sale.source();
            require(identity != null && sale.identity().sourceIdentity() != null
                    && product.type() == existing.identity().type()
                    && product.manufacturer().equals(existing.identity().manufacturer())
                    && product.modelName().equals(candidate.modelName()) && product.partNumber() == null
                    && identity.type() == product.type() && identity.manufacturer().equals(product.manufacturer())
                    && identity.modelName().equals(product.modelName()) && identity.partNumber() == null,
                    "Sale identity must preserve the exact manufacturer model without inventing a PN");
            require(source.sourceName() == CatalogSourceName.MANUFACTURER
                    && source.sourceName() == sale.identity().sourceIdentity().sourceName()
                    && source.externalId().equals(sale.identity().sourceIdentity().externalId())
                    && CanonicalCatalogIds.productForReviewedSource(source.sourceName(), source.externalId())
                        .equals(identity.canonicalId()), "Proposed source and canonical ID differ");
            var configuration = sale.identity().reviewedSaleConfiguration();
            require(configuration != null && configuration.equals(sale.price().product().reviewedSaleConfiguration())
                    && configuration.equals(MAPPER.convertValue(source.rawPayload().get("reviewedSaleConfiguration"),
                        ReviewedMotherboardSaleConfiguration.class))
                    && configuration.socketCode().equals(sale.specification().socketCode())
                    && configuration.memoryType().equals(sale.specification().memoryType())
                    && configuration.formFactor().equals(sale.specification().formFactor())
                    && configuration.evidenceUrls().contains(source.sourceUrl())
                    && configuration.evidenceUrls().contains(observation.price().evidenceUrl())
                    && !source.retrievedAt().isBefore(configuration.verifiedAt()),
                    "Sale configuration must equal the recorded evidence and stored specification");
            require(sale.price().price().equals(observation.price()), "Ready price must equal the current reviewed observation");
            // SharedCatalogSnapshot performs the remaining source/offer/identity/time checks before export.
            registrations.add(new CatalogEntryCreateRequest(product, sale.specification(), List.of(source)));
            identities.add(sale.identity()); prices.add(sale.price());
        }
        return new Plan(review, new SharedCatalogSnapshot.IdentityManifest(1, List.copyOf(identities)),
                new CatalogPriceImportBatch(1, List.copyOf(prices)), List.copyOf(registrations));
    }

    private static String text(Path path) throws IOException {
        require(Files.size(path) <= 5 * 1024 * 1024, "Review file is too large");
        String value = Files.readString(path, StandardCharsets.UTF_8);
        return (value.startsWith("\uFEFF") ? value.substring(1) : value).replace("\r\n", "\n");
    }
    private static <T> T decode(String value, Class<T> type) {
        // Duplicate-key validation applies to tree parsing, before record binding.
        return MAPPER.treeToValue(MAPPER.readTree(value), type);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
