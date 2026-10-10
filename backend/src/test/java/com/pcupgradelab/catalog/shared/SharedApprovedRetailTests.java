package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogVerificationStatus;
import com.pcupgradelab.catalog.identity.CatalogIdentityKind;
import com.pcupgradelab.catalog.identity.CatalogRole;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exact approved runtime extension; old snapshots and source files stay immutable. No database/cloud. */
class SharedApprovedRetailTests {
    private static final String BASE = "data/catalog-shared/all-catalog-identities-2026-10-10.json";
    private static final String ADDED = "f5d69333-0ab1-382d-bc72-9434811daccf";
    private static final List<String> FILES = List.of(BASE, SharedCatalogSnapshot.ACTIVE_PRICES_FILE,
            SharedCatalogSnapshot.APPROVED_RETAIL_FILE, RetailCatalogPreviewLoader.FILE);
    private final Path repository = Path.of("..").toAbsolutePath().normalize();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Map<String, String> hashes = new LinkedHashMap<>();
    @TempDir Path temporary;

    @BeforeEach void copyOnlyFourPublicInputsIntoBoundedTemporaryRepository() throws Exception {
        Path realRepository = repository.toRealPath();
        Path realTemporary = temporary.toRealPath();
        for (String relative : FILES) {
            Path source = realRepository.resolve(relative).normalize();
            Path target = realTemporary.resolve(relative).normalize();
            assertThat(source.toRealPath().startsWith(realRepository)).isTrue();
            assertThat(target.startsWith(realTemporary)).isTrue();
            for (Path current = source; !current.equals(realRepository); current = current.getParent())
                assertThat(Files.isSymbolicLink(current)).isFalse();
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
            hashes.put(relative, hash(source));
        }
    }

    @Test void approvedRuntimeAndBundledRuntimePreserve307And80AndAddOnlyTheUnactivatedAsusProduct() throws Exception {
        var baseline = SharedCatalogSnapshot.loadFull(temporary);
        var active = SharedCatalogSnapshot.loadActive(temporary);
        var bundled = SharedCatalogSnapshot.loadActive();
        assertThat(baseline.products()).hasSize(307);
        assertThat(baseline.products().values().stream().filter(p -> p.price() != null)).hasSize(80);
        assertThat(active.products()).hasSize(308).containsAllEntriesOf(baseline.products());
        assertThat(active.products().values().stream().filter(p -> p.price() != null)).hasSize(81);
        assertThat(bundled.products()).isEqualTo(active.products());
        assertThat(bundled.version()).isEqualTo(active.version());
        assertThat(bundled.priceVersion()).isEqualTo(active.priceVersion());
        var added = active.products().get(ADDED);
        assertThat(added.identity().modelName()).isEqualTo("TUF GAMING B860-PLUS WIFI");
        assertThat(added.identity().partNumber()).isNull();
        assertThat(added.identity().identityKind()).isEqualTo(CatalogIdentityKind.PHYSICAL_VARIANT);
        assertThat(added.identity().role()).isEqualTo(CatalogRole.PURCHASE_CANDIDATE);
        assertThat(added.identity().active()).isFalse();
        assertThat(added.identity().verificationStatus()).isEqualTo(CatalogVerificationStatus.UNVERIFIED);
        assertThat(added.price().amountKrw()).isEqualTo(275350L);
        assertThat(active.products().get("688c711d-70d2-39f5-9642-d1c2a87d4bb7").price()).isNull();
        assertThat(SharedCatalogSnapshot.loadFull().products()).hasSize(307);
        assertThat(SharedCatalogSnapshot.loadFull().products().values().stream().filter(p -> p.price() != null)).hasSize(74);
        assertThat(SharedCatalogSnapshot.loadPilot().products()).hasSize(14);
        assertThat(SharedCatalogSnapshot.loadPilot().products().values().stream().filter(p -> p.price() != null)).hasSize(8);
        for (var entry : hashes.entrySet()) {
            assertThat(hash(temporary.resolve(entry.getKey()))).isEqualTo(entry.getValue());
            assertThat(hash(repository.resolve(entry.getKey()))).isEqualTo(entry.getValue());
        }
    }

    @Test void missingApprovalOrActiveBasePricesFailClosedWithoutOldPriceFallback() throws Exception {
        for (String file : List.of(SharedCatalogSnapshot.APPROVED_RETAIL_FILE, SharedCatalogSnapshot.ACTIVE_PRICES_FILE,
                RetailCatalogPreviewLoader.FILE)) {
            Files.delete(temporary.resolve(file));
            assertThatThrownBy(() -> SharedCatalogSnapshot.loadActive(temporary))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no baseline fallback");
            Files.copy(repository.resolve(file), temporary.resolve(file));
        }
    }

    @Test void onlyTheExplicitOneProductApprovalWithNoActivationAndUnchangedReviewHashIsAccepted() throws Exception {
        rejects(value -> approval(value).put("decision", "approve all four candidates"));
        rejects(value -> approval(value).put("newProducts", 4));
        rejects(value -> approval(value).put("newPriceObservations", 4));
        rejects(value -> approval(value).put("autoActivation", true));
        rejects(value -> approval(value).remove("autoActivation"));
        rejects(value -> approval(value).put("reviewSha256", "0".repeat(64)));
        rejects(value -> approval(value).put("reviewFile", "backend/src/main/resources/application-local.properties"));
        rejects(value -> value.put("schemaVersion", 2));
        rejects(value -> value.put("unreviewedApply", true));
    }

    @Test void changesToExistingCatalogFieldsPricesOrObservationTimesAreRejected() throws Exception {
        rejects(value -> node(value.path("identities").path("products").get(0), "identity").put("active", true));
        rejects(value -> node(value.path("identities").path("products").get(0), "identity").put("modelName", "different existing model"));
        rejects(value -> node(value.path("prices").path("items").get(0), "price").put("amountKrw", 1L));
        rejects(value -> node(value.path("prices").path("items").get(0), "price").put("observedAt", "2026-10-01T00:00:00Z"));
    }

    @Test void changedAsusConfigurationSourceQuoteOrPromotionCannotPiggybackOnTheApproval() throws Exception {
        rejects(value -> node(addition(value), "identity").put("active", true));
        rejects(value -> node(addition(value), "identity").put("canonicalId", "f5d69333-0ab1-382d-bc72-9434811dacce"));
        rejects(value -> node(addition(value), "sourceIdentity").put("externalId", "asus:wrong-retail"));
        rejects(value -> node(addition(value), "reviewedSaleConfiguration").put("wifiIncluded", false));
        rejects(value -> node(addition(value), "reviewedSaleConfiguration").put("domesticDistributor", "different distributor"));
        rejects(value -> node(newQuote(value), "price").put("amountKrw", 275351L));
        rejects(value -> node(newQuote(value), "offer").put("externalId", "74255379"));
        rejects(value -> node(newQuote(value), "offer").put("saleSku", "coupon price"));
    }

    @Test void reviewTamperingEvenWhenIdentitiesAndPricesStayUnchangedInvalidatesTheApproval() throws Exception {
        Path review = temporary.resolve(RetailCatalogPreviewLoader.FILE);
        Files.writeString(review, Files.readString(review) + "\n ", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> SharedCatalogSnapshot.loadActive(temporary))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unchanged review hash");
    }

    @Test void approvedRetailCliHasExplicitDistinctOutputAndRejectsApplyOrArbitraryPriceOverrides() {
        assertThat(SharedCatalogExportApplication.RETAIL_CATALOG_FILE).isEqualTo("catalog-retail-approved.json")
                .isNotIn("catalog.json", "catalog-v2.json");
        assertThat(SharedCatalogExportApplication.RETAIL_CONTRACTS_FILE).isEqualTo("contract-fixtures-retail-approved.json")
                .isNotIn("contract-fixtures.json", "contract-fixtures-v2.json");
        for (String[] args : List.of(new String[]{temporary.toString(), "--retail-approved", "--prices", "unused.json"},
                new String[]{temporary.toString(), "--retail-approved", "--apply"},
                new String[]{temporary.toString(), "--retail-approved", "--retail-approved"}))
            assertThatThrownBy(() -> SharedCatalogExportApplication.main(args)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(temporary.resolve("workers"))).isFalse();
    }

    private void rejects(Consumer<ObjectNode> mutation) throws Exception {
        var value = (ObjectNode) mapper.readTree(Files.readString(repository.resolve(SharedCatalogSnapshot.APPROVED_RETAIL_FILE)));
        mutation.accept(value);
        Files.writeString(temporary.resolve(SharedCatalogSnapshot.APPROVED_RETAIL_FILE), mapper.writeValueAsString(value), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> SharedCatalogSnapshot.loadActive(temporary)).isInstanceOf(IllegalArgumentException.class);
    }
    private static ObjectNode approval(ObjectNode value) { return node(value, "approval"); }
    private static ObjectNode addition(ObjectNode value) {
        for (var product : value.path("identities").path("products"))
            if (ADDED.equals(product.path("identity").path("canonicalId").stringValue())) return (ObjectNode) product;
        throw new IllegalArgumentException("Approved ASUS identity is missing in test input");
    }
    private static ObjectNode newQuote(ObjectNode value) {
        for (var quote : value.path("prices").path("items"))
            if (!quote.path("product").path("reviewedSaleConfiguration").isMissingNode()
                    && !quote.path("product").path("reviewedSaleConfiguration").isNull()) return (ObjectNode) quote;
        throw new IllegalArgumentException("Approved ASUS quote is missing in test input");
    }
    private static ObjectNode node(tools.jackson.databind.JsonNode parent, String key) { return (ObjectNode) parent.path(key); }
    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
