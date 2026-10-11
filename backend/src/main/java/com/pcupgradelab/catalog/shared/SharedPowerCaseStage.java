package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogProductView;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.identity.CanonicalCatalogIds;
import com.pcupgradelab.catalog.powercase.PowerCaseCatalogPreviewLoader;
import com.pcupgradelab.catalog.powercase.PowerCaseCatalogPreviewLoader.Plan;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** Explicit, immutable staging only. Never selected by loadActive(), Spring configuration or production exports. */
public final class SharedPowerCaseStage {
    public static final String FILE = "data/catalog-shared/power-case-stage-2026-10-11.json";
    public static final String MODE = "STAGED_PREVIEW_ONLY";
    public static final String SOURCE_SHA256 = "e3575f9217ea88797ceec8788adf8f86e1440f2521c672e41a4ba42fc17e5830";
    private static final JsonMapper MAPPER = SharedReferencePriceClient.strictMapper();
    public record Manifest(Integer schemaVersion, String mode, Boolean publicationApproved,
                           Boolean databaseApplyApproved, String sourceFile, String sourceSha256,
                           Integer newProducts, Integer newPrices) { }
    public record Addition(String key, PowerCaseCatalogPreviewLoader.SourceIdentity sourceIdentity,
                           List<String> officialEvidenceUrls, SharedCatalogSnapshot.Entry entry) {
        public Addition { officialEvidenceUrls = List.copyOf(officialEvidenceUrls); }
    }
    public record Envelope(int schemaVersion, String mode, boolean publicationApproved, boolean databaseApplyApproved,
                           String sourceFile, String sourceSha256, String baseCatalogVersion, String basePriceVersion,
                           List<Addition> additions, SharedCatalogExportApplication.CatalogV2 catalog,
                           SharedReferencePriceDtos.Snapshot references) {
        public Envelope { additions = List.copyOf(additions); }
    }
    private final SharedCatalogSnapshot catalog;
    private final SharedReferencePriceDtos.Snapshot references;
    private final Envelope envelope;
    private SharedPowerCaseStage(SharedCatalogSnapshot catalog, SharedReferencePriceDtos.Snapshot references, Envelope envelope) {
        this.catalog = catalog; this.references = references; this.envelope = envelope;
    }
    public SharedCatalogSnapshot catalog() { return catalog; }
    public SharedReferencePriceDtos.Snapshot references() { return references; }
    public Envelope envelope() { return envelope; }

    public static SharedPowerCaseStage load(Path repository) {
        return fromPlan(repository, new PowerCaseCatalogPreviewLoader().load(repository));
    }

    /** Caller-owned proposals are rechecked against the pinned public review, rather than trusted as approval. */
    public static SharedPowerCaseStage fromPlan(Path repository, Plan plan) {
        try {
            Path root = repository.toRealPath();
            var manifest = MAPPER.readValue(readPublic(root, FILE), Manifest.class);
            require(Integer.valueOf(1).equals(manifest.schemaVersion()) && MODE.equals(manifest.mode())
                    && Boolean.FALSE.equals(manifest.publicationApproved()) && Boolean.FALSE.equals(manifest.databaseApplyApproved())
                    && PowerCaseCatalogPreviewLoader.FILE.equals(manifest.sourceFile())
                    && SOURCE_SHA256.equals(manifest.sourceSha256()) && Integer.valueOf(7).equals(manifest.newProducts())
                    && Integer.valueOf(2).equals(manifest.newPrices()), "Only the exact unpublished seven-product stage is permitted");
            String review = readPublic(root, manifest.sourceFile());
            require(SOURCE_SHA256.equals(hash(review)), "PSU/CASE stage review differs from the pinned evidence");
            require(Objects.equals(plan, new PowerCaseCatalogPreviewLoader().load(root)),
                    "Stage plan differs from the pinned review");
            require(plan.registrations().size() == 7 && plan.prices().size() == 2,
                    "Stage requires seven identities and only two exact observed prices");
            var base = SharedCatalogSnapshot.loadApprovedRetail(root);
            require(base.products().size() == 308 && base.products().values().stream().filter(p -> p.price() != null).count() == 81,
                    "Stage requires the approved 308-product/81-price base");
            var entries = new LinkedHashMap<>(base.products());
            var additions = new ArrayList<Addition>();
            for (var registration : plan.registrations()) {
                var product = registration.entry().product();
                var sources = registration.entry().sources();
                var primary = sources.getFirst();
                require(primary.sourceName() == CatalogSourceName.MANUFACTURER
                        && CanonicalCatalogIds.productForReviewedSource(primary.sourceName(), primary.externalId()).equals(registration.canonicalId()),
                        "Stage requires the reviewed official manufacturer identity");
                var identity = new SharedPriceDtos.Identity(registration.canonicalId(), product.type(), product.manufacturer(),
                        product.modelName(), product.partNumber(), registration.identityKind(), registration.role(),
                        registration.verificationStatus(), registration.active(), "PRODUCT", null);
                var quotes = plan.prices().stream().filter(p -> p.canonicalId().equals(registration.canonicalId())).toList();
                require(quotes.size() <= 1, "Duplicate staged price");
                CatalogProductView.CurrentPrice current = null;
                if (!quotes.isEmpty()) {
                    var quote = quotes.getFirst();
                    require(quote.product().equals(product) && quote.manufacturerIdentity().sourceName() == primary.sourceName()
                            && quote.manufacturerIdentity().externalId().equals(primary.externalId()) && !quote.identityEvidence().isEmpty(),
                            "Price differs from the reviewed exact physical product");
                    current = new CatalogProductView.CurrentPrice(quote.price().amountKrw(), quote.offer().sourceName(),
                            quote.offer().sourceUrl(), quote.price().observedAt());
                }
                var entry = new SharedCatalogSnapshot.Entry(identity, current);
                require(entries.putIfAbsent(registration.canonicalId(), entry) == null, "Staged identity conflicts with the approved base");
                additions.add(new Addition(registration.key(), new PowerCaseCatalogPreviewLoader.SourceIdentity(primary.sourceName(),
                        primary.externalId()), sources.stream().map(s -> s.sourceUrl()).toList(), entry));
            }
            var staged = SharedCatalogSnapshot.powerCaseStage(entries);
            require(!staged.version().equals(base.version()) && !staged.priceVersion().equals(base.priceVersion()),
                    "Stage must use distinct catalog and price revisions");
            var preservedReferences = SharedReferencePriceSnapshot.load(root, base);
            var references = new SharedReferencePriceDtos.Snapshot(1, staged.version(), preservedReferences.referenceVersion(),
                    preservedReferences.generatedAt(), false, preservedReferences.products());
            require(references.products().size() == 227 && references.products().stream().allMatch(p ->
                    base.products().containsKey(p.identity().canonicalId()) && staged.products().get(p.identity().canonicalId()).price() == null
                    && staged.products().get(p.identity().canonicalId()).identity().equals(p.identity())),
                    "Stage must preserve the existing 227 reference products without adding estimates");
            var exported = new SharedCatalogExportApplication.CatalogV2(2, staged.version(), staged.priceVersion(),
                    SharedPriceFreshness.defaults().toView(), List.copyOf(staged.products().values()));
            var envelope = new Envelope(1, MODE, false, false, manifest.sourceFile(), manifest.sourceSha256(), base.version(),
                    base.priceVersion(), additions, exported, references);
            return new SharedPowerCaseStage(staged, references, envelope);
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException ex) {
            throw new IllegalArgumentException("Public PSU/CASE stage cannot be read", ex);
        }
    }

    private static String readPublic(Path root, String relative) throws java.io.IOException {
        Path file = root.resolve(relative).normalize();
        require(file.startsWith(root) && file.toRealPath().startsWith(root) && Files.isRegularFile(file)
                && Files.size(file) <= 5 * 1024 * 1024, "Stage input must be a bounded public repository file");
        for (Path current = file; !current.equals(root); current = current.getParent())
            require(!Files.isSymbolicLink(current), "Stage input must not use symlinks");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return (text.startsWith("\uFEFF") ? text.substring(1) : text).replace("\r\n", "\n");
    }
    private static String hash(String text) throws java.security.NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
