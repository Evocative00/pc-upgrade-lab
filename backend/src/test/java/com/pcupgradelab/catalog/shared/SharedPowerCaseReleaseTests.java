package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.powercase.PowerCaseCatalogPreviewLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/** Operational approval is independent of the unchanged false-only source/stage and older runtime releases. */
class SharedPowerCaseReleaseTests {
    private static final List<String> INPUTS = List.of(SharedPowerCaseRelease.FILE, SharedPowerCaseRelease.REFERENCES_FILE,
            SharedPowerCaseStage.FILE, PowerCaseCatalogPreviewLoader.FILE,
            "data/catalog-shared/all-catalog-identities-2026-10-10.json", SharedCatalogSnapshot.ACTIVE_PRICES_FILE,
            SharedCatalogSnapshot.APPROVED_RETAIL_FILE, RetailCatalogPreviewLoader.FILE,
            SharedReferencePriceSnapshot.FILE, SharedReferencePriceSnapshot.ACTIVE_FILE);
    private final Path repository = Path.of("..").toAbsolutePath().normalize();
    private final JsonMapper mapper = JsonMapper.builder().build();
    @TempDir Path temporary;
    @BeforeEach void copyPublicReleaseOnly() throws Exception {
        for (String file : INPUTS) {
            var target = temporary.resolve(file); Files.createDirectories(target.getParent());
            Files.copy(repository.resolve(file), target);
        }
    }

    @Test void approved315RuntimeAndBundlePreserve308PricesAnd227ReferenceEvidence() {
        var stage = SharedPowerCaseStage.load(temporary);
        var release = SharedPowerCaseRelease.load(temporary);
        var bundled = SharedPowerCaseRelease.loadBundled();
        var old = SharedCatalogSnapshot.loadApprovedRetail(temporary);
        assertThat(SharedCatalogSnapshot.loadActive(temporary).products()).hasSize(315).containsAllEntriesOf(old.products());
        assertThat(release.catalog().products()).isEqualTo(stage.catalog().products()).isEqualTo(bundled.catalog().products());
        assertThat(release.catalog().version()).isEqualTo(stage.catalog().version()).isEqualTo(bundled.catalog().version());
        assertThat(release.catalog().priceVersion()).isEqualTo(stage.catalog().priceVersion()).isEqualTo(bundled.catalog().priceVersion());
        assertThat(release.catalog().products().values().stream().filter(p -> p.price() != null)).hasSize(83);
        assertThat(stage.envelope().additions().stream().filter(p -> p.entry().price() == null)).hasSize(5);
        assertThat(release.references().publicationApproved()).isTrue();
        assertThat(release.references().products()).containsExactlyElementsOf(stage.references().products()).hasSize(227);
        assertThat(release.references().referenceVersion()).isEqualTo(stage.references().referenceVersion());
        assertThat(release.references().generatedAt()).isEqualTo(stage.references().generatedAt());
        assertThat(stage.references().publicationApproved()).isFalse();
        assertThat(stage.envelope().publicationApproved()).isFalse();
        assertThat(stage.envelope().databaseApplyApproved()).isFalse();
        assertThat(SharedReferencePriceSnapshot.load(temporary, release.catalog())).isEqualTo(release.references());
        assertThat(SharedReferencePriceSnapshot.loadBundled(release.catalog())).isEqualTo(release.references());
        var review = SharedReferencePriceSnapshot.loadReview(temporary, release.catalog());
        assertThat(review.publicationApproved()).isFalse();
        assertThat(review.products()).isEqualTo(release.references().products());
    }

    @Test void publicationRejectsScopeVersionOrObservationChangesWithoutAnOlderReleaseFallback() throws Exception {
        var file = temporary.resolve(SharedPowerCaseRelease.FILE);
        String original = Files.readString(file);
        for (String key : List.of("publicationApproved", "databaseApplyApproved", "newProducts", "newPrices", "preservedReferences", "sourceSha256", "catalogVersion")) {
            var tree = (ObjectNode) mapper.readTree(original);
            switch (key) {
                case "publicationApproved", "databaseApplyApproved" -> tree.put(key, false);
                case "sourceSha256", "catalogVersion" -> tree.put(key, "altered");
                default -> tree.put(key, 0);
            }
            Files.writeString(file, mapper.writeValueAsString(tree));
            assertThatThrownBy(() -> SharedCatalogSnapshot.loadActive(temporary)).as(key).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no older-release fallback");
        }
        Files.writeString(file, original);
        var tree = (ObjectNode) mapper.readTree(original);
        ((ObjectNode) tree.get("priceObservations").get(0)).put("amountKrw", 1);
        Files.writeString(file, mapper.writeValueAsString(tree));
        assertThatThrownBy(() -> SharedPowerCaseRelease.load(temporary)).isInstanceOf(IllegalArgumentException.class);
        Files.delete(file);
        assertThatThrownBy(() -> SharedCatalogSnapshot.loadActive(temporary)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no older-release fallback");
        assertThat(SharedCatalogSnapshot.loadApprovedRetail(temporary).products()).hasSize(308);
    }

    @Test void approvedReferencesCannotChangeAnAmountEvidenceDateOrAddAHeldProduct() throws Exception {
        var file = temporary.resolve(SharedPowerCaseRelease.REFERENCES_FILE);
        String original = Files.readString(file);
        for (boolean amount : List.of(true, false)) {
            var tree = (ObjectNode) mapper.readTree(original);
            var estimate = (ObjectNode) tree.get("products").get(0).get("referenceEstimate");
            if (amount) estimate.put("amountKrw", 1); else estimate.put("notes", "changed evidence");
            Files.writeString(file, mapper.writeValueAsString(tree));
            assertThatThrownBy(() -> SharedPowerCaseRelease.load(temporary)).isInstanceOf(IllegalArgumentException.class);
        }
        Files.writeString(file, original);
        assertThat(SharedReferencePriceSnapshot.decode(original.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                SharedCatalogSnapshot.loadActive(temporary)).products()).hasSize(227);
    }
}
