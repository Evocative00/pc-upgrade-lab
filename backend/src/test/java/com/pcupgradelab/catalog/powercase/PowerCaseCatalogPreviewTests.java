package com.pcupgradelab.catalog.powercase;

import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.CatalogSpecification;
import com.pcupgradelab.catalog.CatalogVerificationStatus;
import com.pcupgradelab.catalog.identity.CanonicalCatalogIds;
import com.pcupgradelab.pc.PartType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
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

/** 실제 공개 7종 자료를 임시 저장소에 복사한다. Spring·DB·네트워크를 사용하지 않는다. */
class PowerCaseCatalogPreviewTests {
    private static final String A650 = "psu-msi-mag-a650bn";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final PowerCaseCatalogPreviewLoader loader = new PowerCaseCatalogPreviewLoader();
    private final Path repositoryRoot = Path.of("..").toAbsolutePath().normalize();
    @TempDir Path directory;
    private String original;

    @BeforeEach
    void copyOnlyThePublicReview() throws Exception {
        Path realRoot = repositoryRoot.toRealPath();
        Path source = realRoot.resolve(PowerCaseCatalogPreviewLoader.FILE);
        assertThat(source.toRealPath().startsWith(realRoot)).isTrue();
        assertThat(Files.isSymbolicLink(source)).isFalse();
        original = Files.readString(source, StandardCharsets.UTF_8);
        Path target = directory.resolve(PowerCaseCatalogPreviewLoader.FILE);
        Files.createDirectories(target.getParent());
        Files.writeString(target, original, StandardCharsets.UTF_8);
    }

    @Test
    void actualSevenProductsProduceRegistrationPlansWithoutActivatingOrPublishingHeldPrices() {
        var plan = loader.load(directory);
        assertThat(plan.registrations()).hasSize(7);
        assertThat(plan.registrations().stream().filter(r -> r.entry().product().type() == PartType.PSU)).hasSize(4);
        assertThat(plan.registrations().stream().filter(r -> r.entry().product().type() == PartType.CASE)).hasSize(3);
        assertThat(plan.registrations()).allSatisfy(r -> {
            assertThat(r.active()).isFalse();
            assertThat(r.verificationStatus()).isEqualTo(CatalogVerificationStatus.UNVERIFIED);
            var primary = r.entry().sources().getFirst();
            assertThat(r.canonicalId()).isEqualTo(CanonicalCatalogIds.productForReviewedSource(primary.sourceName(), primary.externalId()));
            assertThat(r.entry().sources().stream().skip(1)).allSatisfy(s -> assertThat(s.externalId()).isNull());
        });
        long expectedPrices = plan.review().products().stream().filter(p -> p.currentPrice() != null).count();
        assertThat(plan.prices()).hasSize((int) expectedPrices);
        assertThat(plan.report().priceHeld()).isEqualTo(7 - (int) expectedPrices);
        assertThat(plan.report().productsByType()).containsExactlyInAnyOrderEntriesOf(Map.of("PSU", 4, "CASE", 3));
        assertThat(plan.report().proposedRegistrationRows()).containsEntry("catalog_product", 7)
                .containsEntry("catalog_reference_price", 7).containsEntry("psu_spec", 4).containsEntry("case_spec", 3)
                .containsEntry("catalog_model", 0);
        assertThat(plan.report().proposedRegistrationRows().get("catalog_product_source"))
                .isEqualTo(plan.registrations().stream().mapToInt(r -> r.entry().sources().size()).sum());
        assertThat(plan.report().databaseAccess()).isZero();
        assertThat(plan.report().databaseWrites()).isZero();
        assertThat(plan.report().publications()).isZero();
        assertThat(plan.report().products().stream().filter(p -> !p.priceCandidate()))
                .allSatisfy(p -> assertThat(p.heldReasons()).isNotEmpty());
        var a650 = plan.report().products().stream().filter(p -> p.key().equals(A650)).findFirst().orElseThrow();
        assertThat(a650.unknownFields()).contains("specification.atxVersion",
                "specification.powerConnectors[2].independentCableCount");
        assertThat(a650.conditionsRequiringReview()).isNotEmpty();
        assertThat(plan.centralExtension().products()).hasSize(7).allSatisfy(p -> assertThat(p.modelId()).isNull());
    }

    @Test
    void exactModelWithoutAPublishedPartNumberCanProposeAReviewOnlyPriceWithItsCanonicalEvidence() throws Exception {
        mutate(tree -> readyA650(product(tree, A650)));
        var price = loader.load(directory).prices().stream().filter(p -> p.key().equals(A650)).findFirst().orElseThrow();
        assertThat(price.product().partNumber()).isNull();
        assertThat(price.product().modelName()).isEqualTo("MAG A650BN");
        assertThat(price.manufacturerIdentity().sourceName()).isEqualTo(CatalogSourceName.MANUFACTURER);
        assertThat(price.manufacturerIdentity().externalId()).isEqualTo("MAG A650BN");
        assertThat(price.price().amountKrw()).isEqualTo(123456L);
        assertThat(price.identityEvidence()).isNotEmpty();
    }

    @Test
    void approvalFlagsNeverTurnThePreviewIntoAnApplicationOrPublicationPath() throws Exception {
        for (String field : List.of("databaseApplyApproved", "publicationApproved")) {
            mutate(tree -> tree.put(field, true));
            assertInvalid("preview only");
        }
        mutate(tree -> tree.put("schemaVersion", 2));
        assertInvalid("preview only");
    }

    @Test
    void scopeIdentityDuplicatesOrInventedRegionalAndModelVariantsAreRejected() throws Exception {
        mutate(tree -> product(tree, A650).put("modelName", "MAG A650BN PCIE5 II"));
        assertInvalid("Exact manufacturer model");
        mutate(tree -> product(tree, A650).put("canonicalId", "00000000-0000-0000-0000-000000000000"));
        assertInvalid("Canonical manufacturer identity");
        mutate(tree -> product(tree, A650).put("type", "CPU"));
        assertInvalid("Exact manufacturer model");
        mutate(tree -> ((ArrayNode) tree.get("products")).set(1, product(tree, A650).deepCopy()));
        assertInvalid("repeated");
        mutate(tree -> product(tree, A650).put("key", "psu-unapproved"));
        assertInvalid("outside");
    }

    @Test
    void invalidSpecificationsAndRepeatedSupplementalSourceIdentityCannotReachARegistrationPlan() throws Exception {
        mutate(tree -> ((ObjectNode) product(tree, A650).get("specification")).put("ratedPowerW", 0));
        assertInvalid("ratedPowerW");
        mutate(tree -> ((ObjectNode) product(tree, A650).get("specification")).put("powerConnectorsKnown", false));
        assertInvalid("Unknown power connectors");
        mutate(tree -> ((ObjectNode) product(tree, "psu-msi-mag-a750gl-pcie5-ii").get("sources").get(1))
                .put("externalId", "MAG A750GL PCIE5 II"));
        assertInvalid("Supplemental manufacturer");
    }

    @Test
    void manufacturerEvidenceRequiresItsOwnOfficialHttpsDomainAndCannotHideCredentialsOrAnotherHost() throws Exception {
        for (String invalid : List.of("http://www.msi.com/Power-Supply/MAG-A650BN/Specification",
                "https://www.corsair.com/msi/specifications", "https://evil-msi.com/specifications",
                "https://www.msi.com.evil.invalid/specifications", "https://user@www.msi.com/specifications",
                "https://www.msi.com:8443/specifications")) {
            mutate(tree -> ((ObjectNode) product(tree, A650).get("sources").get(0)).put("sourceUrl", invalid));
            assertInvalid("official domain over HTTPS");
        }
        mutate(tree -> ((ObjectNode) product(tree, A650).get("sources").get(0))
                .put("sourceUrl", "https://www.msi.com:443/Power-Supply/MAG-A650BN/Specification"));
        assertThat(loader.load(directory).registrations()).hasSize(7);
    }

    @Test
    void readyPriceRequiresNewStockExactIdentityPositiveAmountAndApprovedProviderEvidence() throws Exception {
        for (Consumer<ObjectNode> damage : List.<Consumer<ObjectNode>>of(
                p -> ((ObjectNode) p.get("priceReview")).put("exactVariantVerified", false),
                p -> ((ObjectNode) p.get("priceReview")).put("saleCondition", "USED"),
                p -> ((ObjectNode) p.get("priceReview")).put("inStock", false),
                p -> ((ObjectNode) p.get("currentPrice")).put("amountKrw", 0),
                p -> ((ObjectNode) p.get("currentPrice")).put("sourceUrl", "https://example.invalid/?pcode=15283025"),
                p -> ((ObjectNode) p.get("priceReview")).put("offerExternalId", "1"),
                p -> ((ObjectNode) p.get("priceReview")).putArray("identityEvidence"))) {
            mutate(tree -> { var p = product(tree, A650); readyA650(p); damage.accept(p); });
            assertThatThrownBy(() -> loader.load(directory)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void heldPricesCannotCreateRowsAndAMissingPriceCannotPretendToBeVerified() throws Exception {
        mutate(tree -> { var p = product(tree, A650); readyA650(p);
            ((ObjectNode) p.get("priceReview")).put("status", "HELD_SHIPPING_BASIS_CONFLICT"); });
        assertInvalid("Only verified exact");
        mutate(tree -> { var p = product(tree, A650); p.putNull("currentPrice");
            ((ObjectNode) p.get("priceReview")).put("status", "EXACT_VARIANT_OBSERVED"); });
        assertInvalid("held decision");
    }

    @Test
    void strictParsingRejectsDuplicateKeysUnknownFieldsAndFractionalWon() throws Exception {
        Path file = directory.resolve(PowerCaseCatalogPreviewLoader.FILE);
        Files.writeString(file, original.replaceFirst("\\{", "{\"schemaVersion\":1,"));
        assertThatThrownBy(() -> loader.load(directory)).isInstanceOf(IllegalArgumentException.class);
        mutate(tree -> product(tree, A650).put("apply", true));
        assertThatThrownBy(() -> loader.load(directory)).isInstanceOf(IllegalArgumentException.class);
        mutate(tree -> { var p = product(tree, A650); readyA650(p);
            ((ObjectNode) p.get("currentPrice")).put("amountKrw", 123456.5); });
        assertThatThrownBy(() -> loader.load(directory)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cliRejectsApplyAndWritesOnlyIgnoredProposalsWithoutReadingPrivateOrActiveFiles() throws Exception {
        assertThatThrownBy(() -> PowerCaseCatalogPreviewApplication.main(new String[]{"--source-root", directory.toString(), "--apply"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unavailable");
        List<String> untouched = List.of("backend/src/main/resources/application-local.properties",
                "data/catalog-shared/active-approved-prices.json", "data/catalog-shared/active-reference-prices.json");
        for (String relative : untouched) {
            Path target = directory.resolve(relative); Files.createDirectories(target.getParent());
            Files.writeString(target, "not an input to this preview", StandardCharsets.UTF_8);
        }
        String originalHash = hash(directory.resolve(PowerCaseCatalogPreviewLoader.FILE));
        PowerCaseCatalogPreviewApplication.main(new String[]{"--source-root", directory.toString()});
        assertThat(hash(directory.resolve(PowerCaseCatalogPreviewLoader.FILE))).isEqualTo(originalHash);
        for (String relative : untouched) assertThat(Files.readString(directory.resolve(relative))).isEqualTo("not an input to this preview");
        Path output = directory.resolve(PowerCaseCatalogPreviewApplication.OUTPUT);
        try (var files = Files.list(output)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactlyInAnyOrder(
                    "registrations-proposed.json", "prices-proposed.json", "central-extension-proposed.json", "report.json");
        }
        var report = mapper.readTree(Files.readString(output.resolve("report.json")));
        assertThat(report.get("mode").asText()).isEqualTo("PREVIEW_ONLY");
        assertThat(report.get("databaseWrites").asInt()).isZero();
        assertThat(report.get("publications").asInt()).isZero();
        assertThat(report.get("databaseApplyApproved").asBoolean()).isFalse();
        assertThat(report.get("publicationApproved").asBoolean()).isFalse();
    }

    private void mutate(Consumer<ObjectNode> change) throws Exception {
        var tree = (ObjectNode) mapper.readTree(original); change.accept(tree);
        Files.writeString(directory.resolve(PowerCaseCatalogPreviewLoader.FILE), mapper.writeValueAsString(tree), StandardCharsets.UTF_8);
    }
    private ObjectNode product(ObjectNode root, String key) {
        for (var item : root.get("products")) if (key.equals(item.get("key").asText())) return (ObjectNode) item;
        throw new IllegalArgumentException("Missing fixture key: " + key);
    }
    private void readyA650(ObjectNode product) {
        var observation = product.putObject("currentPrice");
        observation.put("amountKrw", 123456L).put("sourceName", "DANAWA")
                .put("sourceUrl", "https://prod.danawa.com/info/?pcode=15283025").put("observedAt", "2026-10-11T00:00:00Z");
        var review = (ObjectNode) product.get("priceReview");
        review.put("status", "EXACT_VARIANT_OBSERVED").put("exactVariantVerified", true).put("saleCondition", "NEW")
                .put("inStock", true).put("offerExternalId", "15283025").put("saleSku", "MAG A650BN fixture")
                .put("verifiedAt", "2026-10-10T00:00:00Z");
        review.putArray("identityEvidence").add("테스트 전용 정확 모델·판매 구성 대조 근거");
        review.putArray("notes").add("테스트 전용 관측이며 실제 가격 조사가 아니다");
        review.putArray("sourceUrls").add("https://prod.danawa.com/info/?pcode=15283025");
    }
    private void assertInvalid(String message) {
        assertThatThrownBy(() -> loader.load(directory)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }
    private String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
