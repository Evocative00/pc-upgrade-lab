package com.pcupgradelab.catalog.expansion;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** No Spring context, database, private settings or network is needed for these boundary checks. */
class CatalogExpansionPreviewTests {
    private final CatalogExpansionPreviewLoader loader = new CatalogExpansionPreviewLoader();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Path repositoryRoot = Path.of("..").toAbsolutePath().normalize();

    @Test
    void verifiesSourceHashesAndSeparatesResearchFromExistingApprovedData() {
        var preview = loader.load(repositoryRoot);
        assertThat(preview.sourceHashes()).hasSize(328);
        assertThat(preview.existingProductMappings()).hasSize(300).allSatisfy(mapping -> {
            assertThat(mapping.localProductId()).isNull();
            assertThat(mapping.canonicalProductId().version()).isEqualTo(3);
            assertThat(mapping.mappingApproved()).isFalse();
            assertThat(mapping.lookupStrategy()).isEqualTo("EXACT_REVIEWED_SOURCE_IDENTITY");
        });
        assertThat(preview.existingProductMappings()).extracting(mapping -> mapping.canonicalProductId()).doesNotHaveDuplicates();
        assertThat(preview.candidates()).hasSize(78).allSatisfy(candidate -> {
            assertThat(candidate.eligibility()).isEqualTo("NEEDS_REVIEW");
            assertThat(candidate.adoptionDecision()).isEqualTo("NOT_APPROVED");
            assertThat(candidate.proposedCanonicalProductId()).isNull();
            assertThat(candidate.localProductId()).isNull();
            assertThat(candidate.priceStatus()).isEqualTo("NOT_COLLECTED");
            assertThat(candidate.compatibilityReviewStatus()).isEqualTo("NOT_REVIEWED");
            assertThat(candidate.userConfirmationRequired()).isTrue();
        });
        assertThat(preview.candidates().subList(0, 52)).allSatisfy(candidate -> assertThat(candidate.reviewPhase()).isEqualTo("LATEST_FIRST"));
        assertThat(preview.candidates().subList(52, 78)).allSatisfy(candidate -> assertThat(candidate.reviewPhase()).isEqualTo("FOLLOWUP"));
        assertThat(preview.heldAdditionalModels()).hasSize(6).allSatisfy(held -> {
            assertThat(held.status()).isEqualTo("HOLD_RETAIL_AND_BIOS_NOT_VERIFIED");
            assertThat(held.proposedCanonicalProductId()).isNull();
        });
        assertThat(preview.summary().retainedApprovedPriceCount()).isEqualTo(72);
        assertThat(preview.summary().newProductsCreated()).isZero();
        assertThat(preview.summary().newPriceObservationsCreated()).isZero();
    }

    @Test
    void retainsUnknownModuleIdentityDecimalSsdCapacityAndManufacturerEndOfLife() {
        var preview = loader.load(repositoryRoot);
        var ram = preview.candidates().stream().filter(candidate -> candidate.category().equals("RAM")).findFirst().orElseThrow();
        assertThat(ram.researchPartNumber()).isNull();
        assertThat(ram.identificationLevel()).isEqualTo("BRAND_SPEED_CAPACITY_RANGE");
        assertThat(ram.knownSpecificationFacts().get("pinCount").isNull()).isTrue();
        assertThat(ram.unconfirmedFactPaths()).contains("specification.pinCount", "specification.isEcc");
        var nvme = preview.candidates().stream().filter(candidate -> candidate.category().equals("STORAGE")
                && candidate.knownSpecificationFacts().get("advertisedCapacityGb").intValue() == 1000).findFirst().orElseThrow();
        assertThat(nvme.knownSpecificationFacts().get("capacityBytes").longValue()).isEqualTo(1_000_000_000_000L);
        var sata = preview.candidates().stream().filter(candidate -> candidate.category().equals("STORAGE")
                && candidate.knownSpecificationFacts().get("busInterface").stringValue().equals("SATA")).findFirst().orElseThrow();
        assertThat(sata.knownSpecificationFacts().get("interfaceProtocol").isNull()).isTrue();
        assertThat(sata.knownSpecificationFacts().get("pcieLanes").isNull()).isTrue();
        var eol = preview.candidates().stream().filter(candidate -> candidate.modelName().equals("B840M-HVS")).findFirst().orElseThrow();
        assertThat(eol.lifeCycleStatus()).isEqualTo("MANUFACTURER_EOL");
        assertThat(eol.role()).isEqualTo("INSTALLED_PC_REFERENCE");
    }

    @Test
    void comparesOnlyExactNumericValuesAcrossFormattingAndPreservesJsonStructure() {
        assertThat(sameFacts("{\"tdpW\":70}", "{\"tdpW\":70.0}")).isTrue();
        assertThat(sameFacts("{\"tdpW\":70}", "{\"tdpW\":70.01}")).isFalse();
        assertThat(sameFacts("{\"tdpW\":70}", "{\"tdpW\":\"70\"}")).isFalse();
        assertThat(sameFacts("{\"tdpW\":null}", "{}")).isFalse();
        assertThat(sameFacts("{\"tdpW\":null}", "{\"tdpW\":0}")).isFalse();
        assertThat(sameFacts("[70,71]", "[71,70]")).isFalse();
        assertThat(sameFacts("{\"tdpW\":70,\"unknown\":null}", "{\"tdpW\":70}")).isFalse();
        assertThat(sameFacts("{\"tdpW\":70}", "{\"otherField\":70}")).isFalse();
        assertThat(sameFacts("{\"outer\":[70.0,{\"clock\":3.50}],\"unknown\":null}",
                "{\"unknown\":null,\"outer\":[70,{\"clock\":3.5}]}")).isTrue();
        var exactNumbers = JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
        assertThat(CatalogExpansionPreviewLoader.sameJsonFacts(exactNumbers.readTree("0.1234567890123456789"),
                exactNumbers.readTree("0.1234567890123456788"))).isFalse();
    }

    @Test
    void rejectsApplyBeforeReadingAnyFiles() {
        for (String apply : new String[]{"--apply", "--apply=true", "-PapplyCatalogExpansion=true"}) {
            assertThatThrownBy(() -> CatalogExpansionPreviewApplication.Options.parse(new String[]{apply}))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not implemented or authorized");
        }
        assertThatThrownBy(() -> CatalogExpansionPreviewApplication.Options.parse(new String[]{"--source-root"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CatalogExpansionPreviewApplication.Options.parse(new String[]{"--unknown"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsApprovalFlagsNewProductIdsAndChangedMappingTypes() throws Exception {
        rejects(tree -> object(tree, "authorization").put("databaseApplyAuthorized", true));
        rejects(tree -> candidate(tree, 0).put("adoptionDecision", "APPROVED"));
        rejects(tree -> candidate(tree, 0).put("proposedCanonicalProductId", UUID.randomUUID().toString()));
        rejects(tree -> object(tree.get("existingProductMappings").get(0), "expectedProduct").put("category", "GPU"));
        rejects(tree -> ((ObjectNode) tree.get("existingProductMappings").get(0)).put("localProductId", UUID.randomUUID().toString()));
    }

    @Test
    void rejectsStaleSourceHashesAndPrivateOrEscapingPaths() throws Exception {
        rejects(tree -> ((ObjectNode) tree.get("sourceHashes").get(0)).put("normalizedSha256", "0".repeat(64)));
        rejects(tree -> ((ObjectNode) tree.get("sourceHashes").get(0)).put("file", "backend/src/main/resources/application-local.properties"));
        rejects(tree -> ((ObjectNode) tree.get("sourceHashes").get(0)).put("file", "data/catalog-review/../../outside.json"));
    }

    @Test
    void rejectsInferredCapacityFractionalBytesAndSaleKitChanges() throws Exception {
        rejects(tree -> object(candidate(tree, 0), "knownSpecificationFacts").put("capacityBytes", 536_870_912_000L));
        rejects(tree -> object(candidate(tree, 0), "knownSpecificationFacts").put("capacityBytes", 500_000_000_000.5));
        rejects(tree -> object(candidate(tree, 68), "knownSpecificationFacts").put("moduleCount", 2));
        rejects(tree -> object(candidate(tree, 68), "knownSpecificationFacts").put("pinCount", 288));
    }

    @Test
    void rejectsMalformedUnknownAndMissingPrimitiveFields() throws Exception {
        rejects(tree -> tree.put("unexpected", true));
        rejects(tree -> object(tree, "authorization").remove("databaseApplyAuthorized"));
        rejects(tree -> object(tree, "authorization").putNull("databaseApplyAuthorized"));
        String raw = new String(bytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> loader.read((raw + " {}").getBytes(java.nio.charset.StandardCharsets.UTF_8), repositoryRoot))
                .isInstanceOf(IllegalArgumentException.class);
        String duplicate = raw.replaceFirst("\\\"schemaVersion\\\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1");
        assertThatThrownBy(() -> loader.read(duplicate.getBytes(java.nio.charset.StandardCharsets.UTF_8), repositoryRoot))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsTheSingleBundledPreviewFromAJar(@TempDir Path directory) throws Exception {
        Path jarFile = directory.resolve("expansion-preview.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jarFile))) {
            output.putNextEntry(new JarEntry(CatalogExpansionPreviewLoader.RESOURCE));
            output.write(bytes());
            output.closeEntry();
        }
        try (var classLoader = new URLClassLoader(new URL[]{jarFile.toUri().toURL()}, null)) {
            var preview = loader.load(classLoader, repositoryRoot);
            assertThat(preview.candidates()).hasSize(78);
            assertThat(preview.existingProductMappings()).hasSize(300);
        }
    }

    private void rejects(Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode tree = (ObjectNode) mapper.readTree(bytes());
        mutation.accept(tree);
        assertThatThrownBy(() -> loader.read(mapper.writeValueAsBytes(tree), repositoryRoot))
                .isInstanceOf(IllegalArgumentException.class);
    }
    private boolean sameFacts(String left, String right) {
        return CatalogExpansionPreviewLoader.sameJsonFacts(mapper.readTree(left), mapper.readTree(right));
    }
    private byte[] bytes() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(CatalogExpansionPreviewLoader.RESOURCE)) {
            if (input == null) throw new IllegalStateException("Preview test resource is missing");
            return input.readAllBytes();
        }
    }
    private static ObjectNode candidate(ObjectNode tree, int index) { return (ObjectNode) tree.get("candidates").get(index); }
    private static ObjectNode object(tools.jackson.databind.JsonNode parent, String field) { return (ObjectNode) parent.get(field); }
}
