package com.pcupgradelab.catalog.shared;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** Separate human-approved release; the original review/stage and prior 308 release remain immutable. */
public final class SharedPowerCaseRelease {
    public static final String FILE = "data/catalog-shared/approved-power-case-extension-2026-10-11.json";
    public static final String REFERENCES_FILE = "data/catalog-shared/approved-power-case-reference-prices-2026-10-11.json";
    public static final String DECISION = "APPROVED_POWER_CASE_RELEASE_2026_10_11";
    private static final JsonMapper MAPPER = SharedReferencePriceClient.strictMapper();
    public record Observation(String canonicalId, String sourceName, String sourceUrl, Long amountKrw, Instant observedAt) { }
    public record Approval(Integer schemaVersion, String decision, Boolean databaseApplyApproved, Boolean publicationApproved,
            Boolean autoActivation, String stageManifestFile, String sourceFile, String sourceSha256,
            Integer newProducts, Integer newPrices, Integer preservedProducts, Integer preservedCurrentPrices, Integer preservedReferences,
            String catalogVersion, String priceVersion, String referenceVersion, String referencesFile,
            List<String> canonicalIds, List<Observation> priceObservations) { }
    private final SharedPowerCaseStage stage;
    private final Approval approval;
    private final SharedReferencePriceDtos.Snapshot references;
    private SharedPowerCaseRelease(SharedPowerCaseStage stage, Approval approval, SharedReferencePriceDtos.Snapshot references) {
        this.stage = stage; this.approval = approval; this.references = references;
    }
    public SharedCatalogSnapshot catalog() { return stage.catalog(); }
    public Approval approval() { return approval; }
    public SharedReferencePriceDtos.Snapshot references() { return references; }
    public SharedPowerCaseStage stage() { return stage; }

    public static SharedPowerCaseRelease load(Path repository) {
        try {
            Path root = Objects.requireNonNull(repository).toRealPath();
            var stage = SharedPowerCaseStage.load(root);
            var accepted = MAPPER.readValue(readPublic(root, FILE), Approval.class);
            var observations = stage.envelope().additions().stream().filter(p -> p.entry().price() != null).map(p -> {
                var price = p.entry().price();
                return new Observation(p.entry().identity().canonicalId(), price.sourceName(), price.sourceUrl(), price.amountKrw(), price.observedAt());
            }).toList();
            var expected = new Approval(1, DECISION, true, true, false, SharedPowerCaseStage.FILE, stage.envelope().sourceFile(),
                    SharedPowerCaseStage.SOURCE_SHA256, 7, 2, 308, 81, 227, stage.catalog().version(), stage.catalog().priceVersion(),
                    stage.references().referenceVersion(), REFERENCES_FILE,
                    stage.envelope().additions().stream().map(p -> p.entry().identity().canonicalId()).toList(), observations);
            require(expected.equals(accepted), "Exact separate seven-product/two-price publication approval is required");
            var references = MAPPER.readValue(readPublic(root, REFERENCES_FILE), SharedReferencePriceDtos.Snapshot.class);
            var previous = stage.references();
            var published = new SharedReferencePriceDtos.Snapshot(1, stage.catalog().version(), previous.referenceVersion(),
                    previous.generatedAt(), true, previous.products());
            require(published.equals(references), "Published reference release must preserve all 227 values and evidence");
            return new SharedPowerCaseRelease(stage, accepted, references);
        } catch (java.io.IOException | RuntimeException ex) {
            throw new IllegalArgumentException("Approved PSU/CASE release cannot be loaded; no older-release fallback", ex);
        }
    }

    /** Reuses the same public-file validation for packaged applications; no private configuration or database. */
    public static SharedPowerCaseRelease loadBundled() {
        var written = new ArrayList<Path>();
        Path root = null;
        try {
            root = Files.createTempDirectory("pc-upgrade-approved-power-case-");
            for (var file : List.of(
                    new Bundled("data/catalog-shared/all-catalog-identities-2026-10-10.json", "/catalog/shared/full/all-catalog-identities-2026-10-10.json"),
                    new Bundled(SharedCatalogSnapshot.ACTIVE_PRICES_FILE, "/catalog/shared/full/active-approved-prices.json"),
                    new Bundled(SharedCatalogSnapshot.APPROVED_RETAIL_FILE, "/catalog/shared/full/approved-retail-extension-2026-10-10.json"),
                    new Bundled(RetailCatalogPreviewLoader.FILE, "/catalog/shared/retail-four-preview-2026-10-10.json"),
                    new Bundled(SharedReferencePriceSnapshot.FILE, "/catalog/shared/reference/reference-prices-preview-2026-10-10.json"),
                    new Bundled(SharedReferencePriceSnapshot.ACTIVE_FILE, "/catalog/shared/reference/active-reference-prices.json"),
                    new Bundled(com.pcupgradelab.catalog.powercase.PowerCaseCatalogPreviewLoader.FILE, "/catalog/shared/powercase/power-case-pilot-2026-10-11.json"),
                    new Bundled(SharedPowerCaseStage.FILE, "/catalog/shared/powercase/power-case-stage-2026-10-11.json"),
                    new Bundled(FILE, "/catalog/shared/powercase/approved-power-case-extension-2026-10-11.json"),
                    new Bundled(REFERENCES_FILE, "/catalog/shared/powercase/approved-power-case-reference-prices-2026-10-11.json"))) {
                try (var stream = SharedPowerCaseRelease.class.getResourceAsStream(file.resource())) {
                    require(stream != null, "Approved power/case public resource is missing");
                    byte[] bytes = stream.readNBytes(5 * 1024 * 1024 + 1);
                    require(bytes.length <= 5 * 1024 * 1024, "Approved power/case public resource is too large");
                    Path target = root.resolve(file.relative());
                    Files.createDirectories(target.getParent()); written.add(target); Files.write(target, bytes);
                }
            }
            return load(root);
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Bundled PSU/CASE release cannot be loaded; no older-release fallback", ex);
        } finally {
            for (Path file : written) delete(file);
            if (root != null) {
                delete(root.resolve("data/catalog-review")); delete(root.resolve("data/catalog-shared"));
                delete(root.resolve("data")); delete(root);
            }
        }
    }
    private record Bundled(String relative, String resource) { }
    private static String readPublic(Path root, String relative) throws java.io.IOException {
        Path file = root.resolve(relative).normalize();
        require(file.startsWith(root) && file.toRealPath().startsWith(root) && Files.isRegularFile(file)
                && Files.size(file) <= 5 * 1024 * 1024, "Publication must use bounded public repository files");
        for (Path current = file; !current.equals(root); current = current.getParent()) require(!Files.isSymbolicLink(current), "Publication must not use symlinks");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }
    private static void delete(Path path) { try { Files.deleteIfExists(path); } catch (java.io.IOException ex) { path.toFile().deleteOnExit(); } }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
