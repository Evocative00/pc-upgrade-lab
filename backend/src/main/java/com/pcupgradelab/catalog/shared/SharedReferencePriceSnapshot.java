package com.pcupgradelab.catalog.shared;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import static com.pcupgradelab.catalog.shared.SharedReferencePriceValidation.require;

/** Public reference review is a publication gate, never a fallback for a failed central request. */
public final class SharedReferencePriceSnapshot {
    public static final String FILE = "data/catalog-shared/reference-prices-preview-2026-10-10.json";
    public static final String ACTIVE_FILE = "data/catalog-shared/active-reference-prices.json";
    private static final String RESOURCE = "/catalog/shared/reference/reference-prices-preview-2026-10-10.json";
    private static final String ACTIVE_RESOURCE = "/catalog/shared/reference/active-reference-prices.json";
    private SharedReferencePriceSnapshot() { }

    public static SharedReferencePriceDtos.Snapshot load(Path root, SharedCatalogSnapshot catalog) {
        if (catalog.products().size() == 315) {
            var release = SharedPowerCaseRelease.load(root);
            require(release.catalog().version().equals(catalog.version()), "Published PSU/CASE reference catalog differs");
            return release.references();
        }
        var active = root.resolve(ACTIVE_FILE);
        // Existence is the only fallback decision. Invalid/unreadable active content must never load the old review instead.
        return loadFile(Files.notExists(active, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? root.resolve(FILE) : active, catalog);
    }

    public static SharedReferencePriceDtos.Snapshot loadReview(Path root, SharedCatalogSnapshot catalog) {
        if (catalog.products().size() == 315) return rebindReview(loadReview(root, SharedCatalogSnapshot.loadApprovedRetail(root)), catalog);
        var review = loadFile(root.resolve(FILE), catalog);
        require(!review.publicationApproved(), "Original reference review must remain unpublished");
        return review;
    }

    private static SharedReferencePriceDtos.Snapshot loadFile(Path file, SharedCatalogSnapshot catalog) {
        try (var stream = Files.newInputStream(file)) { return decode(stream.readNBytes(2 * 1024 * 1024 + 1), catalog); }
        catch (java.io.IOException ex) { throw new IllegalArgumentException("Reference review cannot be loaded", ex); }
    }

    public static SharedReferencePriceDtos.Snapshot loadBundled(SharedCatalogSnapshot catalog) {
        if (catalog.products().size() == 315) {
            var release = SharedPowerCaseRelease.loadBundled();
            require(release.catalog().version().equals(catalog.version()), "Bundled PSU/CASE reference catalog differs");
            return release.references();
        }
        try (var active = SharedReferencePriceSnapshot.class.getResourceAsStream(ACTIVE_RESOURCE)) {
            if (active != null) return decode(active.readNBytes(2 * 1024 * 1024 + 1), catalog);
        } catch (java.io.IOException ex) { throw new IllegalArgumentException("Bundled active references cannot be loaded", ex); }
        return loadReviewBundled(catalog);
    }

    public static SharedReferencePriceDtos.Snapshot loadReviewBundled(SharedCatalogSnapshot catalog) {
        if (catalog.products().size() == 315) return rebindReview(loadReviewBundled(SharedCatalogSnapshot.loadApprovedRetail()), catalog);
        try (var stream = SharedReferencePriceSnapshot.class.getResourceAsStream(RESOURCE)) {
            require(stream != null, "Bundled reference review is missing");
            var review = decode(stream.readNBytes(2 * 1024 * 1024 + 1), catalog);
            require(!review.publicationApproved(), "Original bundled reference review must remain unpublished");
            return review;
        } catch (java.io.IOException ex) { throw new IllegalArgumentException("Bundled reference review cannot be loaded", ex); }
    }

    public static SharedReferencePriceDtos.Snapshot decode(byte[] bytes, SharedCatalogSnapshot catalog) {
        require(bytes != null && bytes.length <= 2 * 1024 * 1024, "Reference review is too large");
        var mapper = SharedReferencePriceClient.strictMapper();
        var tree = mapper.readTree(bytes);
        var result = mapper.treeToValue(tree, SharedReferencePriceDtos.Snapshot.class);
        require(result.schemaVersion() == 1 && catalog.version().equals(result.catalogVersion())
                && SharedReferencePriceValidation.version(result.referenceVersion())
                && result.generatedAt() != null && !result.generatedAt().isBefore(java.time.Instant.EPOCH)
                && !result.generatedAt().isAfter(java.time.Instant.now())
                && result.products() != null && result.products().size() <= 1000, "Reference review metadata is invalid");
        String contentVersion;
        try {
            contentVersion = "references-v1-" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(tree.get("products").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        require(contentVersion.equals(result.referenceVersion()), "Reference review content revision differs");
        var ids = new HashSet<String>();
        for (var product : result.products()) {
            require(product != null && product.identity() != null
                    && ids.add(product.identity().canonicalId()), "Reference review identity is missing or duplicated");
            var existing = catalog.products().get(product.identity().canonicalId());
            require(existing != null && existing.identity().equals(product.identity()) && existing.price() == null,
                    "Reference review must preserve approved identities and current prices");
            SharedReferencePriceValidation.validate(product.referenceEstimate(), product.identity(), catalog, result.generatedAt());
        }
        var expected = catalog.products().entrySet().stream().filter(e -> e.getValue().price() == null)
                .map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        if (catalog.products().size() == 315) {
            var approved = SharedPowerCaseRelease.loadBundled().references();
            require(result.products().equals(approved.products()) && result.referenceVersion().equals(approved.referenceVersion())
                    && result.generatedAt().equals(approved.generatedAt()), "PSU/CASE release must preserve the original 227 references");
        } else require(catalog.products().size() == 308 && expected.size() == 227 && ids.equals(expected),
                "Reference review must cover exactly the approved 227 unpriced products");
        return result;
    }

    private static SharedReferencePriceDtos.Snapshot rebindReview(SharedReferencePriceDtos.Snapshot review, SharedCatalogSnapshot catalog) {
        return new SharedReferencePriceDtos.Snapshot(1, catalog.version(), review.referenceVersion(), review.generatedAt(), false, review.products());
    }
}
