package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.CatalogVerificationStatus;
import com.pcupgradelab.catalog.identity.CanonicalCatalogIds;
import com.pcupgradelab.catalog.identity.CatalogIdentityKind;
import com.pcupgradelab.catalog.identity.CatalogRole;
import com.pcupgradelab.catalog.price.CatalogPriceImportBatch;
import com.pcupgradelab.catalog.price.ReviewedMotherboardSaleConfiguration;
import com.pcupgradelab.pc.PartType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Synthetic append-only retail review; never opens Spring, a database or external seller pages. */
class SharedReviewedExtensionTests {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();
    private static final String IDENTITIES = "data/catalog-shared/all-catalog-identities-2026-10-10.json";
    private static final String EXTERNAL_ID = "asus:fixture:TUF GAMING B860-PLUS WIFI:retail:KR:intek:single";
    private static final String RETAIL_URL = "https://prod.danawa.com/info/?pcode=74255378";
    private static final String MANUFACTURER_URL = "https://www.asus.com/kr/motherboards-components/motherboards/tuf-gaming/tuf-gaming-b860-plus-wifi/techspec/";
    private static final Instant VERIFIED = Instant.parse("2026-01-01T01:00:00Z");
    private static final Instant OBSERVED = Instant.parse("2026-01-01T02:00:00Z");

    @Test
    void separateBoardReviewPreservesEveryOriginalEntryAndDoesNotReplaceDefaultInputs(@TempDir Path root) throws Exception {
        var fixture = fixture(root);
        var before = SharedCatalogSnapshot.loadFull(root);
        byte[] identityBytes = Files.readAllBytes(root.resolve(IDENTITIES));
        byte[] priceBytes = Files.readAllBytes(root.resolve(SharedCatalogSnapshot.ACTIVE_PRICES_FILE));
        var reviewed = load(fixture, fixture.identities(), fixture.prices());
        assertThat(reviewed.products()).hasSize(before.products().size() + 1);
        before.products().forEach((id, entry) -> assertThat(reviewed.products().get(id)).as(id).isEqualTo(entry));
        assertThat(reviewed.products().values().stream().filter(e -> e.price() != null).count())
                .isEqualTo(before.products().values().stream().filter(e -> e.price() != null).count() + 1);
        var addition = reviewed.products().get(fixture.canonicalId());
        assertThat(addition.identity().partNumber()).isNull();
        assertThat(addition.identity().identityKind()).isEqualTo(CatalogIdentityKind.PHYSICAL_VARIANT);
        assertThat(addition.identity().role()).isEqualTo(CatalogRole.PURCHASE_CANDIDATE);
        assertThat(addition.identity().active()).isFalse();
        assertThat(addition.price().amountKrw()).isEqualTo(275350);
        assertThat(addition.price().observedAt()).isEqualTo(OBSERVED);
        assertThat(reviewed.version()).isNotEqualTo(before.version());
        assertThat(reviewed.priceVersion()).isNotEqualTo(before.priceVersion());
        assertThat(SharedCatalogSnapshot.loadFull(root).products()).isEqualTo(before.products());
        assertThat(Files.readAllBytes(root.resolve(IDENTITIES))).isEqualTo(identityBytes);
        assertThat(Files.readAllBytes(root.resolve(SharedCatalogSnapshot.ACTIVE_PRICES_FILE))).isEqualTo(priceBytes);
    }

    @Test
    void changedOrRemovedOriginalIdentityCannotBeReclassifiedAsARetailProduct(@TempDir Path root) throws Exception {
        var fixture = fixture(root);
        int referenceIndex = referenceIndex(fixture.identities());
        for (var mutation : List.of(new Mutation("canonicalId", UUID.randomUUID().toString()),
                new Mutation("type", "CPU"), new Mutation("manufacturer", "Different maker"),
                new Mutation("modelName", "TUF GAMING B860M-PLUS WIFI"), new Mutation("partNumber", "INVENTED-PN"),
                new Mutation("identityKind", "PHYSICAL_VARIANT"), new Mutation("role", "PURCHASE_CANDIDATE"),
                new Mutation("verificationStatus", "VERIFIED"), new Mutation("active", true),
                new Mutation("saleUnit", "RAM_KIT"), new Mutation("moduleCount", 2))) {
            ObjectNode changed = fixture.identities().deepCopy();
            ((ObjectNode) changed.path("products").get(referenceIndex).path("identity"))
                    .set(mutation.field(), MAPPER.valueToTree(mutation.value()));
            rejected(fixture, changed, fixture.prices());
        }
        ObjectNode removed = fixture.identities().deepCopy();
        ((ArrayNode) removed.path("products")).remove(referenceIndex);
        rejected(fixture, removed, fixture.prices());
        ObjectNode changedSource = fixture.identities().deepCopy();
        ((ObjectNode) changedSource.path("products").get(referenceIndex).path("sourceIdentity"))
                .put("externalId", "asus:rewritten:model");
        rejected(fixture, changedSource, fixture.prices());
        ObjectNode fabricatedReview = fixture.identities().deepCopy();
        ((ObjectNode) fabricatedReview.path("products").get(referenceIndex))
                .set("reviewedSaleConfiguration", MAPPER.valueToTree(configuration()));
        rejected(fixture, fabricatedReview, fixture.prices());
    }

    @Test
    void everyPreviouslyApprovedPriceItemMustRemainCompleteAndUnchanged(@TempDir Path root) throws Exception {
        var fixture = fixture(root);
        for (var mutation : List.of(new Mutation("amountKrw", 1L), new Mutation("observedAt", VERIFIED.toString()),
                new Mutation("evidenceUrl", "https://prod.danawa.com/info/?pcode=999"))) {
            ObjectNode changed = fixture.prices().deepCopy();
            ((ObjectNode) changed.path("items").get(0).path("price"))
                    .set(mutation.field(), MAPPER.valueToTree(mutation.value()));
            rejected(fixture, fixture.identities(), changed);
        }
        ObjectNode removed = fixture.prices().deepCopy();
        ((ArrayNode) removed.path("items")).remove(0);
        rejected(fixture, fixture.identities(), removed);
        ObjectNode changedMapping = fixture.prices().deepCopy();
        ((ObjectNode) changedMapping.path("items").get(0).path("offer")).put("saleSku", "different complete package");
        rejected(fixture, fixture.identities(), changedMapping);
    }

    @Test
    void noNewPriceCanBeAttachedToAnExistingModelReferenceOrUnpricedLegacyProduct(@TempDir Path root) throws Exception {
        var fixture = fixture(root);
        var baseManifest = MAPPER.readValue(Files.readString(root.resolve(IDENTITIES)), SharedCatalogSnapshot.IdentityManifest.class);
        var reference = baseManifest.products().stream().filter(p -> p.identity().modelName().equals(configuration().manufacturerModelName())
                && p.identity().identityKind() == CatalogIdentityKind.MODEL_REFERENCE).findFirst().orElseThrow();
        var falselyPricedReference = new CatalogPriceImportBatch.Product(reference.sourceIdentity().sourceName(),
                reference.sourceIdentity().externalId(), reference.identity().manufacturer(), reference.identity().modelName(),
                null, configuration());
        ObjectNode pricedReference = fixture.prices().deepCopy();
        ((ArrayNode) pricedReference.path("items")).add(MAPPER.valueToTree(quote(falselyPricedReference)));
        rejected(fixture, fixture.identities(), pricedReference);

        var current = MAPPER.readValue(Files.readString(root.resolve(SharedCatalogSnapshot.ACTIVE_PRICES_FILE)), CatalogPriceImportBatch.class);
        Set<String> pricedSources = current.items().stream().map(p -> p.product().externalId()).collect(java.util.stream.Collectors.toSet());
        var unpriced = baseManifest.products().stream().filter(p -> p.sourceIdentity().sourceName() == CatalogSourceName.BUILDCORES
                && p.identity().type() != PartType.RAM && !pricedSources.contains(p.sourceIdentity().externalId()))
                .findFirst().orElseThrow();
        var oldIdentity = unpriced.identity();
        var legacy = new CatalogPriceImportBatch.Product(CatalogSourceName.BUILDCORES, unpriced.sourceIdentity().externalId(),
                oldIdentity.manufacturer(), oldIdentity.modelName(), oldIdentity.partNumber());
        var legacyOffer = new CatalogPriceImportBatch.Offer("DANAWA", "111", "https://prod.danawa.com/info/?pcode=111",
                oldIdentity.partNumber() == null ? oldIdentity.modelName() : oldIdentity.partNumber(),
                CatalogPriceImportBatch.SaleUnit.PRODUCT, null, VERIFIED);
        ObjectNode pricedLegacy = fixture.prices().deepCopy();
        ((ArrayNode) pricedLegacy.path("items")).add(MAPPER.valueToTree(new CatalogPriceImportBatch.Item(legacy, legacyOffer,
                new CatalogPriceImportBatch.Price(100000L, OBSERVED, legacyOffer.sourceUrl(), "NEW", true))));
        rejected(fixture, fixture.identities(), pricedLegacy);
    }

    @Test
    void appendedProductMustRemainPnLessUnverifiedInactiveAndExactlyMatchReviewedRole(@TempDir Path root) throws Exception {
        var fixture = fixture(root);
        for (var mutation : List.of(new Mutation("type", "CPU"), new Mutation("identityKind", "MODEL_REFERENCE"),
                new Mutation("role", "INSTALLED_PC_REFERENCE"), new Mutation("role", "BOTH"),
                new Mutation("partNumber", "FABRICATED-PN"), new Mutation("active", true),
                new Mutation("verificationStatus", "VERIFIED"), new Mutation("canonicalId", UUID.randomUUID().toString()))) {
            ObjectNode changed = fixture.identities().deepCopy();
            newIdentity(changed).set(mutation.field(), MAPPER.valueToTree(mutation.value()));
            rejected(fixture, changed, fixture.prices());
        }
        ObjectNode missingConfiguration = fixture.identities().deepCopy();
        newProduct(missingConfiguration).remove("reviewedSaleConfiguration");
        rejected(fixture, missingConfiguration, fixture.prices());
        ObjectNode duplicate = fixture.identities().deepCopy();
        ((ArrayNode) duplicate.path("products")).add(newProduct(duplicate).deepCopy());
        rejected(fixture, duplicate, fixture.prices());
    }

    @Test
    void quoteCannotSubstituteDistributorWifiRevisionOrPartialSaleSku(@TempDir Path root) throws Exception {
        var fixture = fixture(root);
        for (var mutation : List.of(new Mutation("domesticDistributor", "Other distributor"), new Mutation("wifiIncluded", false),
                new Mutation("revisionScope", "Other physical revision"), new Mutation("reviewId", "other-review"))) {
            ObjectNode changed = fixture.prices().deepCopy();
            var configurationNode = (ObjectNode) newQuote(changed).path("product").path("reviewedSaleConfiguration");
            configurationNode.set(mutation.field(), MAPPER.valueToTree(mutation.value()));
            var changedConfiguration = MAPPER.treeToValue(configurationNode, ReviewedMotherboardSaleConfiguration.class);
            ((ObjectNode) newQuote(changed).path("offer")).put("saleSku", changedConfiguration.saleSku());
            rejected(fixture, fixture.identities(), changed);
        }
        ObjectNode partial = fixture.prices().deepCopy();
        ((ObjectNode) newQuote(partial).path("offer")).put("saleSku", configuration().manufacturerModelName());
        rejected(fixture, fixture.identities(), partial);
    }

    @Test
    void quoteRequiresMatchedProviderIdSameEvidenceUrlAndObservationAfterVerification(@TempDir Path root) throws Exception {
        var fixture = fixture(root);
        for (var mutation : List.of(new Mutation("externalId", "999"), new Mutation("sourceName", "OTHER"),
                new Mutation("sourceName", "ICODA"), new Mutation("sourceUrl", RETAIL_URL + "&coupon=1"),
                new Mutation("sourceUrl", RETAIL_URL + "#price"),
                new Mutation("sourceUrl", "https://prod.danawa.com:443/info/?pcode=74255378"),
                new Mutation("verifiedAt", VERIFIED.minusSeconds(1).toString()))) {
            ObjectNode changed = fixture.prices().deepCopy();
            ((ObjectNode) newQuote(changed).path("offer")).set(mutation.field(), MAPPER.valueToTree(mutation.value()));
            rejected(fixture, fixture.identities(), changed);
        }
        ObjectNode changedEvidence = fixture.prices().deepCopy();
        ((ObjectNode) newQuote(changedEvidence).path("price")).put("evidenceUrl", "https://prod.danawa.com/info/?pcode=999");
        rejected(fixture, fixture.identities(), changedEvidence);
        ObjectNode early = fixture.prices().deepCopy();
        ((ObjectNode) newQuote(early).path("price")).put("observedAt", VERIFIED.minusSeconds(1).toString());
        rejected(fixture, fixture.identities(), early);
        ObjectNode noRetailProofIdentities = fixture.identities().deepCopy();
        ObjectNode noRetailProofPrices = fixture.prices().deepCopy();
        ((ObjectNode) newProduct(noRetailProofIdentities).path("reviewedSaleConfiguration"))
                .set("evidenceUrls", MAPPER.valueToTree(List.of(MANUFACTURER_URL)));
        ((ObjectNode) newQuote(noRetailProofPrices).path("product").path("reviewedSaleConfiguration"))
                .set("evidenceUrls", MAPPER.valueToTree(List.of(MANUFACTURER_URL)));
        rejected(fixture, noRetailProofIdentities, noRetailProofPrices);
    }

    @Test
    void bothRoleIsAllowedOnlyWhenIdentityAndBothReviewedConfigurationsAgree(@TempDir Path root) throws Exception {
        var fixture = fixture(root);
        ObjectNode identities = fixture.identities().deepCopy();
        ObjectNode prices = fixture.prices().deepCopy();
        newIdentity(identities).put("role", "BOTH");
        ((ObjectNode) newProduct(identities).path("reviewedSaleConfiguration")).put("role", "BOTH");
        ((ObjectNode) newQuote(prices).path("product").path("reviewedSaleConfiguration")).put("role", "BOTH");
        assertThat(load(fixture, identities, prices).products().get(fixture.canonicalId()).identity().role())
                .isEqualTo(CatalogRole.BOTH);
    }

    @Test
    void malformedOrMissingManifestRowsCannotBeTreatedAsAnApprovedExtension(@TempDir Path root) throws Exception {
        var fixture = fixture(root);
        for (String field : List.of("sourceIdentity", "identity")) {
            ObjectNode missing = fixture.identities().deepCopy();
            newProduct(missing).remove(field);
            assertThatThrownBy(() -> load(fixture, missing, fixture.prices())).isInstanceOf(IllegalArgumentException.class);
        }
        ObjectNode nullRow = fixture.identities().deepCopy();
        ((ArrayNode) nullRow.path("products")).addNull();
        assertThatThrownBy(() -> load(fixture, nullRow, fixture.prices())).isInstanceOf(IllegalArgumentException.class);
        ObjectNode nullProducts = fixture.identities().deepCopy();
        nullProducts.putNull("products");
        assertThatThrownBy(() -> load(fixture, nullProducts, fixture.prices())).isInstanceOf(IllegalArgumentException.class);
        ObjectNode unknown = fixture.identities().deepCopy();
        newIdentity(unknown).put("unreviewedAlias", "Some board");
        rejected(fixture, unknown, fixture.prices());
    }

    private record Fixture(Path root, Path identityFile, Path priceFile, ObjectNode identities, ObjectNode prices,
                           String canonicalId) { }
    private record Mutation(String field, Object value) { }

    private Fixture fixture(Path root) throws Exception {
        Files.createDirectories(root.resolve("data/catalog-shared"));
        Files.copy(REPOSITORY.resolve(IDENTITIES), root.resolve(IDENTITIES));
        Files.copy(REPOSITORY.resolve(SharedCatalogSnapshot.ACTIVE_PRICES_FILE), root.resolve(SharedCatalogSnapshot.ACTIVE_PRICES_FILE));
        ObjectNode identities = (ObjectNode) MAPPER.readTree(Files.readString(root.resolve(IDENTITIES)));
        ObjectNode prices = (ObjectNode) MAPPER.readTree(Files.readString(root.resolve(SharedCatalogSnapshot.ACTIVE_PRICES_FILE)));
        String canonical = CanonicalCatalogIds.productForReviewedSource(CatalogSourceName.MANUFACTURER, EXTERNAL_ID);
        var identity = new SharedPriceDtos.Identity(canonical, PartType.MOTHERBOARD, "ASUS", configuration().manufacturerModelName(),
                null, CatalogIdentityKind.PHYSICAL_VARIANT, CatalogRole.PURCHASE_CANDIDATE,
                CatalogVerificationStatus.UNVERIFIED, false, "PRODUCT", null);
        ((ArrayNode) identities.path("products")).add(MAPPER.valueToTree(new SharedCatalogSnapshot.ProductIdentity(
                new SharedCatalogSnapshot.SourceIdentity(CatalogSourceName.MANUFACTURER, EXTERNAL_ID), identity, configuration())));
        var product = new CatalogPriceImportBatch.Product(CatalogSourceName.MANUFACTURER, EXTERNAL_ID,
                "ASUS", configuration().manufacturerModelName(), null, configuration());
        ((ArrayNode) prices.path("items")).add(MAPPER.valueToTree(quote(product)));
        return new Fixture(root, root.resolve("extension-identities.json"), root.resolve("extension-prices.json"), identities, prices, canonical);
    }

    private static ReviewedMotherboardSaleConfiguration configuration() {
        return new ReviewedMotherboardSaleConfiguration("synthetic-board-review", PartType.MOTHERBOARD,
                CatalogIdentityKind.PHYSICAL_VARIANT, CatalogRole.PURCHASE_CANDIDATE, "TUF GAMING B860-PLUS WIFI",
                "인텍앤컴퍼니", ReviewedMotherboardSaleConfiguration.SINGLE_BOARD, 1, "LGA1851", "DDR5", "ATX", true,
                "Reviewed retail model; no other physical revision inferred", List.of(MANUFACTURER_URL, RETAIL_URL), VERIFIED);
    }

    private CatalogPriceImportBatch.Item quote(CatalogPriceImportBatch.Product product) {
        return new CatalogPriceImportBatch.Item(product, new CatalogPriceImportBatch.Offer("DANAWA", "74255378", RETAIL_URL,
                configuration().saleSku(), CatalogPriceImportBatch.SaleUnit.PRODUCT, null, VERIFIED),
                new CatalogPriceImportBatch.Price(275350L, OBSERVED, RETAIL_URL, "NEW", true));
    }

    private SharedCatalogSnapshot load(Fixture fixture, ObjectNode identities, ObjectNode prices) throws Exception {
        Files.writeString(fixture.identityFile(), MAPPER.writeValueAsString(identities));
        Files.writeString(fixture.priceFile(), MAPPER.writeValueAsString(prices));
        return SharedCatalogSnapshot.loadReviewedExtension(fixture.root(), fixture.identityFile(), fixture.priceFile());
    }

    private void rejected(Fixture fixture, ObjectNode identities, ObjectNode prices) {
        assertThatThrownBy(() -> load(fixture, identities, prices)).isInstanceOf(RuntimeException.class);
    }

    private static ObjectNode newProduct(ObjectNode identities) {
        var products = identities.path("products");
        return (ObjectNode) products.get(products.size() - 1);
    }

    private static ObjectNode newIdentity(ObjectNode identities) { return (ObjectNode) newProduct(identities).path("identity"); }

    private static ObjectNode newQuote(ObjectNode prices) {
        var items = prices.path("items");
        return (ObjectNode) items.get(items.size() - 1);
    }

    private static int referenceIndex(ObjectNode identities) {
        var products = identities.path("products");
        for (int i = 0; i < products.size(); i++)
            if (products.get(i).path("identity").path("modelName").stringValue().equals(configuration().manufacturerModelName())
                    && products.get(i).path("identity").path("identityKind").stringValue().equals("MODEL_REFERENCE")) return i;
        throw new IllegalArgumentException("Expected existing ASUS installed model reference");
    }
}
