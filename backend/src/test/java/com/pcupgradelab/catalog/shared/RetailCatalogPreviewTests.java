package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogVerificationStatus;
import com.pcupgradelab.catalog.identity.CatalogIdentityKind;
import com.pcupgradelab.catalog.identity.CatalogRole;
import com.pcupgradelab.catalog.price.CatalogPriceImportBatch;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real four-candidate public inputs, copied into a bounded temporary repository; no Spring or DB. */
class RetailCatalogPreviewTests {
    private static final String BASE_IDENTITIES = "data/catalog-shared/all-catalog-identities-2026-10-10.json";
    private static final String READY_CANONICAL = "f5d69333-0ab1-382d-bc72-9434811daccf";
    private static final List<String> PUBLIC_FILES = List.of(
            RetailCatalogPreviewLoader.FILE, BASE_IDENTITIES, SharedCatalogSnapshot.ACTIVE_PRICES_FILE,
            "data/catalog-review/pilot-import-preview-2026-10-10.json",
            "data/catalog-review/pilot-approved-prices-2026-10-10.json",
            "data/catalog-review/pilot-apply-approval-2026-10-10.json",
            "data/catalog-review/pilot-selection-2026-10-10.json",
            "data/catalog-review/catalog-selection-2026-10-09.json",
            "data/catalog-review/pilot-platform-evidence-2026-10-10.json",
            "data/catalog-review/pilot-ram-gpu-evidence-2026-10-10.json",
            "data/catalog-review/pilot-storage-evidence-2026-10-10.json",
            "data/catalog-review/pilot-existing-prices-2026-10-10.json",
            "data/catalog-review/pilot-existing-prices-2026-10-10.json.report.json",
            "data/catalog-review/pilot-existing-price-assessment-2026-10-10.json",
            "data/catalog-review/pilot-model-price-review-2026-10-10.json",
            "data/catalog-review/pilot-storage-price-review-2026-10-10.json");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RetailCatalogPreviewLoader loader = new RetailCatalogPreviewLoader();
    private final Path repositoryRoot = Path.of("..").toAbsolutePath().normalize();
    private final Map<String, String> originalHashes = new LinkedHashMap<>();
    @TempDir Path directory;

    @BeforeEach void copyOnlyReviewedPublicInputsWithoutSymlinks() throws Exception {
        Path realRoot = repositoryRoot.toRealPath();
        Path temporaryRoot = directory.toRealPath();
        for (String relative : PUBLIC_FILES) {
            assertThat(relative).startsWith("data/catalog-");
            Path source = realRoot.resolve(relative).normalize();
            Path target = temporaryRoot.resolve(relative).normalize();
            assertThat(source.startsWith(realRoot)).isTrue();
            assertThat(source.toRealPath().startsWith(realRoot)).isTrue();
            assertThat(target.startsWith(temporaryRoot)).isTrue();
            for (Path current = source; !current.equals(realRoot); current = current.getParent())
                assertThat(Files.isSymbolicLink(current)).as("public input must not use symlinks: %s", relative).isFalse();
            assertThat(Files.isRegularFile(source)).isTrue();
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
            originalHashes.put(relative, hash(source));
        }
    }

    @Test void actualFourCandidatesProduceOneUnactivatedPurchaseProductAndThreeHeldReferences() throws Exception {
        var plan = loader.load(directory);
        assertThat(plan.review().implementationApproved()).isTrue();
        assertThat(plan.review().databaseApplyApproved()).isFalse();
        assertThat(plan.review().pricePublicationApproved()).isFalse();
        assertThat(plan.review().candidates()).hasSize(4);
        assertThat(plan.review().candidates().stream().filter(c -> c.status() == RetailCatalogPreviewLoader.Status.HELD))
                .hasSize(3).allSatisfy(c -> assertThat(c.proposedSaleProduct()).isNull());
        assertThat(plan.review().candidates().stream().filter(c -> c.status() == RetailCatalogPreviewLoader.Status.READY_FOR_APPROVAL))
                .hasSize(1).allSatisfy(c -> assertThat(c.modelName()).isEqualTo("TUF GAMING B860-PLUS WIFI"));
        assertThat(plan.registrations()).hasSize(1);
        var ready = plan.review().candidates().getLast().proposedSaleProduct();
        var registration = plan.registrations().getFirst();
        assertThat(registration.product()).isEqualTo(ready.product());
        assertThat(registration.specification()).isEqualTo(ready.specification());
        assertThat(registration.sources()).containsExactly(ready.source());
        assertThat(ready.product().partNumber()).isNull();
        var identity = ready.identity().identity();
        assertThat(identity.canonicalId()).isEqualTo(READY_CANONICAL);
        assertThat(identity.identityKind()).isEqualTo(CatalogIdentityKind.PHYSICAL_VARIANT);
        assertThat(identity.role()).isEqualTo(CatalogRole.PURCHASE_CANDIDATE);
        assertThat(identity.active()).isFalse();
        assertThat(identity.verificationStatus()).isEqualTo(CatalogVerificationStatus.UNVERIFIED);
        assertThat(ready.price().price().amountKrw()).isEqualTo(275350L);
        assertThat(ready.price().price().observedAt()).isAfterOrEqualTo(ready.price().offer().verifiedAt());
        assertUnchangedPublicFiles();
    }

    @Test void appendOnlyPreviewPreservesAll307IdentitiesAndEveryFieldOf80ApprovedPrices() throws Exception {
        var plan = loader.load(directory);
        var baselineIdentities = mapper.readValue(Files.readString(directory.resolve(BASE_IDENTITIES)),
                SharedCatalogSnapshot.IdentityManifest.class);
        var baselinePrices = mapper.readValue(Files.readString(directory.resolve(SharedCatalogSnapshot.ACTIVE_PRICES_FILE)),
                CatalogPriceImportBatch.class);
        assertThat(baselineIdentities.products()).hasSize(307);
        assertThat(baselinePrices.items()).hasSize(80);
        assertThat(plan.identities().products()).hasSize(308);
        assertThat(plan.identities().products().subList(0, 307)).containsExactlyElementsOf(baselineIdentities.products());
        assertThat(plan.prices().items()).hasSize(81);
        assertThat(plan.prices().items().subList(0, 80)).containsExactlyElementsOf(baselinePrices.items());
        var baseline = SharedCatalogSnapshot.loadFull(directory);
        var preview = extension(plan);
        assertThat(preview.products()).hasSize(308).containsAllEntriesOf(baseline.products());
        assertThat(preview.products().values().stream().filter(e -> e.price() != null)).hasSize(81);
        assertThat(preview.products().get(READY_CANONICAL).price().amountKrw()).isEqualTo(275350L);
        for (var candidate : plan.review().candidates()) {
            assertThat(preview.products().get(candidate.existingCanonicalId()))
                    .isEqualTo(baseline.products().get(candidate.existingCanonicalId()));
            assertThat(preview.products().get(candidate.existingCanonicalId()).price()).isNull();
        }
        assertThat(preview.version()).isNotEqualTo(baseline.version());
        assertThat(preview.priceVersion()).isNotEqualTo(baseline.priceVersion());
        assertUnchangedPublicFiles();
    }

    @Test void heldCpuAndMsiCandidatesCannotCreateAnyRegistrationOrPriceRow() throws Exception {
        for (int index = 0; index < 3; index++) {
            final int held = index;
            rejects(value -> candidate(value, held).set("proposedSaleProduct", sale(value).deepCopy()),
                    "Held candidates cannot produce registration or price rows");
            rejects(value -> candidate(value, held).put("status", "READY_FOR_APPROVAL"),
                    "Ready candidate requires a complete proposed sale product");
        }
    }

    @Test void changedFactoryPnModelRelationOrApprovedSpecificationIsRejected() throws Exception {
        rejects(value -> node(sale(value), "product").put("partNumber", "SHOP-74255378"), "inventing a PN");
        rejects(value -> sale(value).put("modelCanonicalId", "3650ed66-d0bf-3906-9a1c-a77544ce34ce"),
                "changed the approved model or specification");
        rejects(value -> node(sale(value), "specification").put("memoryType", "DDR4"),
                "changed the approved model or specification");
        rejects(value -> node(sale(value), "specification").put("formFactor", "MICRO_ATX"),
                "changed the approved model or specification");
        rejects(value -> node(sale(value), "product").put("manufacturer", "MSI"), "exact manufacturer model");
        rejects(value -> node(sale(value), "product").put("modelName", "TUF GAMING B860-PLUS"), "exact manufacturer model");
    }

    @Test void changedSourceIdCanonicalIdOrEvidenceOwnerIsRejected() throws Exception {
        rejects(value -> node(sale(value), "source").put("externalId", "asus:other-retail-product"),
                "Proposed source and canonical ID differ");
        rejects(value -> node(node(sale(value), "identity"), "identity").put("canonicalId", "f5d69333-0ab1-382d-bc72-9434811dacce"),
                "Proposed source and canonical ID differ");
        rejects(value -> node(sale(value), "source").put("sourceName", "MANUAL"),
                "Proposed source and canonical ID differ");
        rejects(value -> node(sale(value), "source").put("sourceUrl", "https://example.com/another-model"),
                "Sale configuration must equal");
        rejects(value -> node(sale(value), "source").put("retrievedAt", "2026-10-10T11:57:37.399Z"),
                "Sale configuration must equal");
    }

    @Test void configurationMustMatchItsSourceRawPayloadAndPriceProductExactly() throws Exception {
        rejects(value -> configuration(value).put("domesticDistributor", "다른 유통사"), "Sale configuration must equal");
        rejects(value -> node(node(sale(value), "source"), "rawPayload").remove("reviewedSaleConfiguration"),
                "Sale configuration must equal");
        rejects(value -> node(node(node(sale(value), "price"), "product"), "reviewedSaleConfiguration").put("wifiIncluded", false),
                "Sale configuration must equal");
        rejects(value -> configuration(value).put("revisionScope", "All PCB revisions are asserted equivalent"),
                "Sale configuration must equal");
        rejects(value -> configuration(value).put("unitCount", 2), null);
        rejects(value -> configuration(value).put("packageKind", "DOMESTIC_BOARD_CPU_BUNDLE"), null);
        rejects(value -> configuration(value).putNull("wifiIncluded"), null);
        rejects(value -> configuration(value).put("partType", "CPU"), null);
        rejects(value -> configuration(value).put("identityKind", "MODEL_REFERENCE"), null);
        rejects(value -> configuration(value).put("role", "INSTALLED_PC_REFERENCE"), null);
    }

    @Test void observedAmountProviderAndOfferCannotDriftBetweenCandidateAndReadyPrice() throws Exception {
        rejects(value -> node(node(sale(value), "price"), "price").put("amountKrw", 275351L),
                "Ready price must equal");
        rejects(value -> node(node(candidate(value, 3), "observation"), "price").put("evidenceUrl", "https://prod.danawa.com/info/?pcode=74255379"),
                "invalid provider product ID");
        rejects(value -> node(candidate(value, 0), "observation").put("externalId", "1183681"),
                "invalid provider product ID");
        rejects(value -> node(node(candidate(value, 1), "observation"), "price").put("evidenceUrl", "https://usr.icoda.co.kr/item/view/1739432?coupon=1"),
                "invalid provider product ID");
        rejects(value -> node(node(sale(value), "price"), "offer").put("externalId", "74255379"), null);
        rejects(value -> node(node(sale(value), "price"), "offer").put("saleSku", "TUF GAMING B860-PLUS WIFI"), null);
        rejects(value -> node(node(sale(value), "price"), "offer").put("verifiedAt", "2026-10-10T11:57:37.759Z"), null);
    }

    @Test void registrationCannotActivateVerifyOrChangeTheSeparatePurchaseRole() throws Exception {
        rejects(value -> node(node(sale(value), "identity"), "identity").put("active", true), null);
        rejects(value -> node(node(sale(value), "identity"), "identity").put("verificationStatus", "CORE_VERIFIED"), null);
        rejects(value -> node(node(sale(value), "identity"), "identity").put("role", "INSTALLED_PC_REFERENCE"), null);
        rejects(value -> node(node(sale(value), "identity"), "identity").put("identityKind", "MODEL_REFERENCE"), null);
        rejects(value -> node(node(sale(value), "identity"), "identity").put("saleUnit", "RAM_KIT"), null);
    }

    @Test void reviewedExtensionRejectsChangesToAnyExistingIdentityOrApprovedPrice() throws Exception {
        var plan = loader.load(directory);
        Path identities = writeOutput("extension-identities.json", plan.identities());
        Path prices = writeOutput("extension-prices.json", plan.prices());
        var identityTree = (ObjectNode) mapper.readTree(Files.readString(identities));
        ((ObjectNode) identityTree.path("products").get(0).path("identity")).put("active", true);
        Files.writeString(identities, mapper.writeValueAsString(identityTree), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> SharedCatalogSnapshot.loadReviewedExtension(directory, identities, prices))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("changed or removed an existing identity");
        writeOutput("extension-identities.json", plan.identities());
        var priceTree = (ObjectNode) mapper.readTree(Files.readString(prices));
        ((ObjectNode) priceTree.path("items").get(0).path("price")).put("amountKrw", 1L);
        Files.writeString(prices, mapper.writeValueAsString(priceTree), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> SharedCatalogSnapshot.loadReviewedExtension(directory, identities, prices))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("changed or removed an approved price");
    }

    @Test void previewRejectsExpandedScopeApplyApprovalAndMalformedJson() throws Exception {
        rejects(value -> value.put("databaseApplyApproved", true), "preview only");
        rejects(value -> value.put("pricePublicationApproved", true), "preview only");
        rejects(value -> value.put("implementationApproved", false), "preview only");
        rejects(value -> candidate(value, 0).put("existingCanonicalId", "10039536-d228-3f0c-a740-ea75a2df6b05"),
                "outside the approved four-product scope");
        rejects(value -> candidate(value, 1).put("existingCanonicalId", candidate(value, 0).path("existingCanonicalId").stringValue()),
                "outside the approved four-product scope");
        rejects(value -> ((ArrayNode) value.path("candidates")).remove(0), "Exactly four candidates");
        rejects(value -> value.put("apply", true), null);
        rejects(value -> value.put("databaseApplyApproved", "false"), null);
        String original = Files.readString(repositoryRoot.resolve(RetailCatalogPreviewLoader.FILE));
        Files.writeString(directory.resolve(RetailCatalogPreviewLoader.FILE), original + " {}", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> loader.load(directory)).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(directory.resolve(RetailCatalogPreviewLoader.FILE), original.replaceFirst("\\\"schemaVersion\\\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1"), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> loader.load(directory)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void applyAndUnknownCliArgumentsAreRejectedBeforeReadingOrWritingAnything() {
        for (String[] args : List.of(new String[]{"--apply"}, new String[]{"--apply=true"},
                new String[]{"--source-root", directory.toString(), "--apply"},
                new String[]{"--source-root", directory.toString(), "--publish"},
                new String[]{"--source-root"}, new String[]{"--unknown", directory.toString()})) {
            assertThatThrownBy(() -> RetailCatalogPreviewApplication.main(args))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("apply is unavailable");
        }
        assertThat(Files.exists(directory.resolve("backend/build/catalog-retail-preview"))).isFalse();
        assertThat(Files.exists(directory.resolve("workers"))).isFalse();
    }

    private void rejects(Consumer<ObjectNode> mutation, String message) throws Exception {
        var value = (ObjectNode) mapper.readTree(Files.readString(repositoryRoot.resolve(RetailCatalogPreviewLoader.FILE)));
        mutation.accept(value);
        Files.writeString(directory.resolve(RetailCatalogPreviewLoader.FILE), mapper.writeValueAsString(value), StandardCharsets.UTF_8);
        var assertion = assertThatThrownBy(() -> extension(loader.load(directory))).isInstanceOf(IllegalArgumentException.class);
        if (message != null) assertion.hasMessageContaining(message);
    }

    private SharedCatalogSnapshot extension(RetailCatalogPreviewLoader.Plan plan) throws Exception {
        return SharedCatalogSnapshot.loadReviewedExtension(directory,
                writeOutput("extension-identities.json", plan.identities()), writeOutput("extension-prices.json", plan.prices()));
    }

    private Path writeOutput(String file, Object content) throws Exception {
        Path target = directory.resolve("backend/build/catalog-retail-preview").resolve(file).normalize();
        assertThat(target.startsWith(directory.toAbsolutePath().normalize())).isTrue();
        Files.createDirectories(target.getParent());
        Files.writeString(target, mapper.writeValueAsString(content), StandardCharsets.UTF_8);
        return target;
    }

    private void assertUnchangedPublicFiles() throws Exception {
        for (var entry : originalHashes.entrySet()) {
            assertThat(hash(directory.resolve(entry.getKey()))).as("temporary input: %s", entry.getKey()).isEqualTo(entry.getValue());
            assertThat(hash(repositoryRoot.resolve(entry.getKey()))).as("original input: %s", entry.getKey()).isEqualTo(entry.getValue());
        }
    }

    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static ObjectNode candidate(ObjectNode value, int index) { return (ObjectNode) value.path("candidates").get(index); }
    private static ObjectNode sale(ObjectNode value) { return node(candidate(value, 3), "proposedSaleProduct"); }
    private static ObjectNode configuration(ObjectNode value) { return node(node(sale(value), "identity"), "reviewedSaleConfiguration"); }
    private static ObjectNode node(ObjectNode parent, String key) { return (ObjectNode) parent.path(key); }
}
