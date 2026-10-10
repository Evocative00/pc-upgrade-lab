package com.pcupgradelab.catalog.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/** Approval gate and content integrity are tested without a running application or database. */
class SharedReferencePriceSnapshotTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SharedCatalogSnapshot catalog = SharedCatalogSnapshot.loadActive();

    @Test void unpublished227ReviewCoversOnlyMissingCurrentPricesWithNoChangesToTheApproved81() {
        var prices = catalog.products().values().stream().filter(p -> p.price() != null).map(p -> p.price()).toList();
        var references = SharedReferencePriceSnapshot.loadReviewBundled(catalog);
        assertThat(references.publicationApproved()).isFalse();
        assertThat(references.products()).hasSize(227);
        assertThat(references.products()).allSatisfy(p -> {
            assertThat(catalog.products().get(p.identity().canonicalId()).price()).isNull();
            assertThat(p.referenceEstimate()).isNotNull();
        });
        assertThat(catalog.products().values().stream().filter(p -> p.price() != null).map(p -> p.price()).toList())
                .containsExactlyElementsOf(prices).hasSize(81);
    }

    @Test void approvedActiveBundleHasTheSame227ValuesAndLeavesTheOriginalReviewUnpublished() {
        var active = SharedReferencePriceSnapshot.loadBundled(catalog);
        var review = SharedReferencePriceSnapshot.loadReviewBundled(catalog);
        assertThat(active.publicationApproved()).isTrue();
        assertThat(review.publicationApproved()).isFalse();
        assertThat(active.products()).containsExactlyElementsOf(review.products());
        assertThat(active.referenceVersion()).isEqualTo(review.referenceVersion());
    }

    @Test void activePathIsStrictAndInvalidActiveNeverFallsBackToAnUnpublishedReview(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var directory = java.nio.file.Files.createDirectories(root.resolve("data/catalog-shared"));
        var review = tree();
        java.nio.file.Files.write(root.resolve(SharedReferencePriceSnapshot.FILE), mapper.writeValueAsBytes(review));
        assertThat(SharedReferencePriceSnapshot.load(root, catalog).publicationApproved()).isFalse();
        var active = review.deepCopy(); active.put("publicationApproved", true);
        java.nio.file.Files.write(root.resolve(SharedReferencePriceSnapshot.ACTIVE_FILE), mapper.writeValueAsBytes(active));
        assertThat(SharedReferencePriceSnapshot.load(root, catalog).publicationApproved()).isTrue();
        assertThat(SharedReferencePriceSnapshot.loadReview(root, catalog).publicationApproved()).isFalse();
        java.nio.file.Files.writeString(root.resolve(SharedReferencePriceSnapshot.ACTIVE_FILE), "{}");
        assertThatThrownBy(() -> SharedReferencePriceSnapshot.load(root, catalog)).isInstanceOf(RuntimeException.class);
        assertThat(SharedReferencePriceSnapshot.loadReview(root, catalog).publicationApproved()).isFalse();
    }

    @Test void changedAmountWithoutUpdatingContentRevisionIsRejected() throws Exception {
        var root = tree();
        ((ObjectNode) root.withArray("products").get(0).get("referenceEstimate")).put("amountKrw", 1);
        assertThatThrownBy(() -> SharedReferencePriceSnapshot.decode(mapper.writeValueAsBytes(root), catalog))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("revision");
    }

    @Test void missingTargetDuplicateTargetAndOverwritingCurrentPriceRemainInvalidEvenWithANewRevision() throws Exception {
        var root = tree(); root.withArray("products").remove(0); resign(root);
        var missing = root;
        assertThatThrownBy(() -> SharedReferencePriceSnapshot.decode(mapper.writeValueAsBytes(missing), catalog))
                .isInstanceOf(IllegalArgumentException.class);
        root = tree(); root.withArray("products").add(root.withArray("products").get(0).deepCopy()); resign(root);
        var duplicated = root;
        assertThatThrownBy(() -> SharedReferencePriceSnapshot.decode(mapper.writeValueAsBytes(duplicated), catalog))
                .isInstanceOf(IllegalArgumentException.class);
        root = tree();
        var priced = catalog.products().values().stream().filter(p -> p.price() != null).findFirst().orElseThrow();
        ((ObjectNode) root.withArray("products").get(0)).set("identity", mapper.valueToTree(priced.identity())); resign(root);
        var overwritten = root;
        assertThatThrownBy(() -> SharedReferencePriceSnapshot.decode(mapper.writeValueAsBytes(overwritten), catalog))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("current prices");
    }

    @Test void extraFieldDuplicateJsonAndMalformedApprovalCannotBeAccepted() throws Exception {
        var root = tree(); root.put("secret", "not-public");
        var extra = root;
        assertThatThrownBy(() -> SharedReferencePriceSnapshot.decode(mapper.writeValueAsBytes(extra), catalog))
                .isInstanceOf(RuntimeException.class);
        var raw = mapper.writeValueAsString(tree()).replaceFirst("\\\"schemaVersion\\\":1", "\"schemaVersion\":1,\"schemaVersion\":1");
        assertThatThrownBy(() -> SharedReferencePriceSnapshot.decode(raw.getBytes(StandardCharsets.UTF_8), catalog))
                .isInstanceOf(RuntimeException.class);
        root = tree(); root.put("publicationApproved", "true");
        var invalid = root;
        assertThatThrownBy(() -> SharedReferencePriceSnapshot.decode(mapper.writeValueAsBytes(invalid), catalog))
                .isInstanceOf(RuntimeException.class);
    }

    private ObjectNode tree() throws Exception {
        try (var stream = getClass().getResourceAsStream("/catalog/shared/reference/reference-prices-preview-2026-10-10.json")) {
            assertThat(stream).isNotNull(); return (ObjectNode) mapper.readTree(stream.readAllBytes());
        }
    }
    private void resign(ObjectNode root) throws Exception {
        root.put("referenceVersion", "references-v1-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(root.get("products").toString().getBytes(StandardCharsets.UTF_8))));
    }
}
