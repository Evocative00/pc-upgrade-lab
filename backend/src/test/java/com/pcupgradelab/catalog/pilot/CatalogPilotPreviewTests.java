package com.pcupgradelab.catalog.pilot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Boundary checks use only reviewed public files and domain values, never a Spring context or database. */
class CatalogPilotPreviewTests {
    private final CatalogPilotPreviewLoader loader = new CatalogPilotPreviewLoader();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Path repositoryRoot = Path.of("..").toAbsolutePath().normalize();

    @Test
    void validatesTypedDtosCanonicalRelationsAndSixExistingPricesWithoutLocalIds() {
        var result = loader.load(repositoryRoot);
        assertThat(result.parts()).isEqualTo(14);
        assertThat(result.reusedProducts()).isEqualTo(7);
        assertThat(result.newProducts()).isEqualTo(7);
        assertThat(result.models()).isEqualTo(13);
        assertThat(result.modelSources()).isEqualTo(14);
        assertThat(result.bindings()).isEqualTo(14);
        assertThat(result.sourceHashes()).isEqualTo(10);
        assertThat(result.storageProfiles()).isEqualTo(4);
        assertThat(result.storageSlots()).isEqualTo(19);
        assertThat(result.priceCandidates()).isEqualTo(6);
        assertThat(result.heldModelQuotes()).isEqualTo(5);
        assertThat(result.storageSellerQuotes()).isEqualTo(2);
    }

    @Test
    void rejectsSourceTamperingAndPrivateOrEscapingPaths(@TempDir Path directory) throws Exception {
        ObjectNode tree = tree();
        for (var source : tree.path("sourceHashes")) {
            Path target = directory.resolve(source.path("file").stringValue());
            Files.createDirectories(target.getParent());
            Files.copy(repositoryRoot.resolve(source.path("file").stringValue()), target);
        }
        Path source = directory.resolve(tree.path("sourceHashes").get(0).path("file").stringValue());
        Files.writeString(source, Files.readString(source) + "\n ");
        assertThatThrownBy(() -> loader.read(mapper.writeValueAsBytes(tree), directory))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hash mismatch");
        rejects(value -> ((ObjectNode) value.path("sourceHashes").get(0)).put("file", "backend/src/main/resources/application-local.properties"));
        rejects(value -> ((ObjectNode) value.path("sourceHashes").get(0)).put("file", "data/catalog-review/../../outside.json"));
    }

    @Test
    void rejectsWrongCanonicalIdsInventedLocalIdsAndVerificationPromotion() throws Exception {
        rejects(value -> product(value, 0).put("proposedCanonicalId", UUID.randomUUID().toString()));
        rejects(value -> product(value, 0).put("localProductId", UUID.randomUUID().toString()));
        rejects(value -> product(value, 0).put("evidenceSourceId", 1));
        rejects(value -> product(value, 5).put("identityKind", "PHYSICAL_VARIANT"));
        rejects(value -> product(value, 5).put("role", "BOTH"));
        rejects(value -> ((ObjectNode) value.path("models").get(0).path("registration")).put("verificationStatus", "VERIFIED"));
        rejects(value -> ((ObjectNode) value.path("executionPolicy")).put("doNotPromoteVerificationOrActivation", false));
    }

    @Test
    void rejectsInvalidStorageAndUnreviewedSpecsOrPriceUnits() throws Exception {
        rejects(value -> ((ObjectNode) product(value, 12).path("specification")).put("pcieLanes", 4));
        rejects(value -> ((ObjectNode) product(value, 13).path("specification")).put("capacityBytes", 1073741824000L));
        rejects(value -> ((ObjectNode) product(value, 0).path("specification")).put("coreCount", 7));
        rejects(value -> ((ObjectNode) product(value, 8).path("priceReview").path("priceImportItem").path("offer")).put("moduleCount", 1));
        rejects(value -> ((ObjectNode) product(value, 2).path("priceReview")).set("priceImportItem", product(value, 0).path("priceReview").path("priceImportItem")));
    }

    @Test
    void rejectsDuplicateTrailingUnknownMissingAndCoercedJsonValues() throws Exception {
        rejects(value -> value.put("unexpected", true));
        rejects(value -> ((ObjectNode) value.path("summary")).put("reviewedParts", "14"));
        rejects(value -> ((ObjectNode) value.path("approvalScope")).remove("databaseApplyApproved"));
        rejects(value -> ((ObjectNode) product(value, 0).path("specification")).put("coreCount", "6"));
        String raw = new String(bytes(), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> loader.read((raw + " {}").getBytes(StandardCharsets.UTF_8), repositoryRoot)).isInstanceOf(IllegalArgumentException.class);
        String duplicate = raw.replaceFirst("\\\"schemaVersion\\\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1");
        assertThatThrownBy(() -> loader.read(duplicate.getBytes(StandardCharsets.UTF_8), repositoryRoot)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsApplyAndUnknownArgumentsBeforeReadingFiles() {
        for (String arg : new String[]{"--apply", "--apply=true", "-PapplyPilot=true", "--APPLY=false"}) {
            assertThatThrownBy(() -> CatalogPilotPreviewApplication.Options.parse(new String[]{arg}))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("apply is unavailable");
        }
        assertThatThrownBy(() -> CatalogPilotPreviewApplication.Options.parse(new String[]{"--source-root"})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CatalogPilotPreviewApplication.Options.parse(new String[]{"--unknown"})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CatalogPilotPreviewApplication.Options.parse(new String[]{"--source-root", ".", "--source-root", "."})).isInstanceOf(IllegalArgumentException.class);
    }

    private void rejects(Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode tree = tree(); mutation.accept(tree);
        assertThatThrownBy(() -> loader.read(mapper.writeValueAsBytes(tree), repositoryRoot)).isInstanceOf(IllegalArgumentException.class);
    }
    private ObjectNode tree() throws Exception { return (ObjectNode) mapper.readTree(bytes()); }
    private byte[] bytes() throws Exception { return Files.readAllBytes(repositoryRoot.resolve(CatalogPilotPreviewLoader.PREVIEW_FILE)); }
    private static ObjectNode product(ObjectNode value, int index) { return (ObjectNode) value.path("products").get(index); }
}
