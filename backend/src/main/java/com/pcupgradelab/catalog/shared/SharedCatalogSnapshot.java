package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogProductView;
import com.pcupgradelab.catalog.CatalogSpecification;
import com.pcupgradelab.catalog.CatalogVerificationStatus;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.identity.CanonicalCatalogIds;
import com.pcupgradelab.catalog.pilot.CatalogPilotImportLoader;
import com.pcupgradelab.catalog.price.CatalogPriceImportBatch;
import com.pcupgradelab.catalog.price.ReviewedMotherboardSaleConfiguration;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Immutable, pinned public review. Loading never starts Spring or opens a database. */
public final class SharedCatalogSnapshot {
    private static final String PREVIEW_HASH = "28cc2bacb534dcc284292359f61748de6a9bf0642318a3771f80937d7cbf0b21";
    private static final String PRICES_HASH = "3028116ce4cae9adc1917ae3b42e4404b821eac83d589c0c1082dfe8051318c0";
    private static final List<String> FILES = List.of(
            "pilot-import-preview-2026-10-10.json", "pilot-approved-prices-2026-10-10.json",
            "pilot-apply-approval-2026-10-10.json", "pilot-selection-2026-10-10.json",
            "catalog-selection-2026-10-09.json", "pilot-platform-evidence-2026-10-10.json",
            "pilot-ram-gpu-evidence-2026-10-10.json", "pilot-storage-evidence-2026-10-10.json",
            "pilot-existing-prices-2026-10-10.json", "pilot-existing-prices-2026-10-10.json.report.json",
            "pilot-existing-price-assessment-2026-10-10.json", "pilot-model-price-review-2026-10-10.json",
            "pilot-storage-price-review-2026-10-10.json");
    private final Map<String, Entry> products;
    private final boolean full;
    private final String catalogVersion;
    private final String priceVersion;
    private static final String FULL_IDENTITIES = "all-catalog-identities-2026-10-10.json";
    private static final String FULL_PRICES = "approved-prices-2026-10-10.json";
    public static final String ACTIVE_PRICES_FILE = "data/catalog-shared/active-approved-prices.json";
    public static final String APPROVED_RETAIL_FILE = "data/catalog-shared/approved-retail-extension-2026-10-10.json";
    private static final String APPROVED_RETAIL_DECISION = "ASUS 1종 실제 반영 승인 (추천)";
    private static final String APPROVED_RETAIL_CANONICAL = "f5d69333-0ab1-382d-bc72-9434811daccf";
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(tools.jackson.databind.cfg.EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private SharedCatalogSnapshot(CatalogPilotImportLoader.Plan plan) {
        var entries = new LinkedHashMap<String, Entry>();
        var matchedPrices = new ArrayList<CatalogPriceImportBatch.Item>();
        for (var part : plan.parts()) {
            var quotes = plan.prices().items().stream().filter(item ->
                    item.product().sourceName() == part.sourceIdentity().sourceName()
                    && item.product().externalId().equals(part.sourceIdentity().externalId())).toList();
            require(quotes.size() <= 1, "Duplicate shared price identity");
            var product = part.product();
            Integer moduleCount = part.specification() instanceof CatalogSpecification.Ram ram ? ram.moduleCount() : null;
            String saleUnit = moduleCount == null ? "PRODUCT" : "RAM_KIT";
            CatalogProductView.CurrentPrice price = null;
            if (!quotes.isEmpty()) {
                var quote = quotes.getFirst();
                require(product.manufacturer().equals(quote.product().manufacturer())
                        && product.modelName().equals(quote.product().modelName())
                        && Objects.equals(product.partNumber(), quote.product().partNumber())
                        && saleUnit.equals(quote.offer().saleUnit().name())
                        && Objects.equals(moduleCount, quote.offer().moduleCount()), "Shared price sales variant differs");
                price = new CatalogProductView.CurrentPrice(quote.price().amountKrw(), quote.offer().sourceName(),
                        quote.offer().sourceUrl(), quote.price().observedAt());
                matchedPrices.add(quote);
            }
            var identity = new SharedPriceDtos.Identity(part.proposedCanonicalId(), product.type(), product.manufacturer(),
                    product.modelName(), product.partNumber(), part.identityKind(), part.role(),
                    CatalogVerificationStatus.UNVERIFIED, false, saleUnit, moduleCount);
            require(entries.putIfAbsent(identity.canonicalId(), new Entry(identity, price)) == null,
                    "Duplicate shared canonical ID");
        }
        require(plan.models().size() == 13 && entries.size() == 14 && matchedPrices.size() == 8
                && matchedPrices.containsAll(plan.prices().items()), "Shared scope must remain 14 products and 8 observations");
        products = Collections.unmodifiableMap(entries);
        full = false;
        catalogVersion = "pilot-2026-10-10-" + PREVIEW_HASH.substring(0, 16) + "-" + PRICES_HASH.substring(0, 16);
        priceVersion = "prices-v1-" + PRICES_HASH;
    }

    public static SharedCatalogSnapshot load() {
        return loadFull();
    }

    public static SharedCatalogSnapshot load(Path repositoryRoot) { return loadFull(repositoryRoot); }

    public static SharedCatalogSnapshot loadPilot() {
        Path root = null;
        var written = new ArrayList<Path>();
        try {
            root = Files.createTempDirectory("pc-upgrade-shared-review-");
            Path review = Files.createDirectories(root.resolve("data/catalog-review"));
            for (String file : FILES) {
                try (var stream = SharedCatalogSnapshot.class.getResourceAsStream("/catalog/shared/" + file)) {
                    require(stream != null, "Bundled shared review is missing");
                    byte[] bytes = stream.readNBytes(5 * 1024 * 1024 + 1);
                    require(bytes.length <= 5 * 1024 * 1024, "Bundled shared review is too large");
                    Path target = review.resolve(file);
                    written.add(target);
                    Files.write(target, bytes);
                }
            }
            return loadPilot(root);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Bundled shared review cannot be loaded", ex);
        } finally {
            // Only paths created above are removed; never walk or recursively delete an input directory.
            for (Path file : written) deleteTemporary(file);
            if (root != null) {
                deleteTemporary(root.resolve("data/catalog-review"));
                deleteTemporary(root.resolve("data"));
                deleteTemporary(root);
            }
        }
    }

    public static SharedCatalogSnapshot loadPilot(Path repositoryRoot) {
        requirePinned(repositoryRoot.resolve("data/catalog-review/pilot-import-preview-2026-10-10.json"), PREVIEW_HASH);
        requirePinned(repositoryRoot.resolve("data/catalog-review/pilot-approved-prices-2026-10-10.json"), PRICES_HASH);
        return new SharedCatalogSnapshot(new CatalogPilotImportLoader().load(repositoryRoot));
    }

    public Map<String, Entry> products() { return products; }
    public String version() { return catalogVersion; }
    public String priceVersion() { return priceVersion; }
    public boolean isFull() { return full; }
    public int schemaVersion() { return full ? 2 : 1; }
    public String apiPath() { return full ? "/api/v2/prices" : "/api/v1/prices"; }
    public record Entry(SharedPriceDtos.Identity identity, CatalogProductView.CurrentPrice price) {}

    public record SourceIdentity(CatalogSourceName sourceName, String externalId) { }
    public record ProductIdentity(SourceIdentity sourceIdentity, SharedPriceDtos.Identity identity,
                                  @JsonInclude(JsonInclude.Include.NON_NULL)
                                  ReviewedMotherboardSaleConfiguration reviewedSaleConfiguration) {
        public ProductIdentity(SourceIdentity sourceIdentity, SharedPriceDtos.Identity identity) {
            this(sourceIdentity, identity, null);
        }
    }
    public record IdentityManifest(int schemaVersion, List<ProductIdentity> products) { }
    public record RetailApproval(String decision, String reviewFile, String reviewSha256,
                                 Integer newProducts, Integer newPriceObservations, Boolean autoActivation) { }
    public record ApprovedRetailExtension(Integer schemaVersion, RetailApproval approval,
                                          IdentityManifest identities, CatalogPriceImportBatch prices) { }

    /** Operational approved extension. Missing approval/resources never fall back to the 307-product baseline. */
    public static SharedCatalogSnapshot loadActive() {
        try {
            return approvedRetail(readBundled(FULL_IDENTITIES), readBundled("active-approved-prices.json"),
                    readBundled("approved-retail-extension-2026-10-10.json"),
                    readBundledResource("/catalog/shared/retail-four-preview-2026-10-10.json"));
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid approved active retail catalog", ex); }
    }

    public static SharedCatalogSnapshot loadActive(Path repositoryRoot) {
        require(repositoryRoot != null, "Repository root is required");
        try {
            return approvedRetail(readFile(repositoryRoot.resolve("data/catalog-shared/" + FULL_IDENTITIES)),
                    readFile(repositoryRoot.resolve(ACTIVE_PRICES_FILE)),
                    readFile(repositoryRoot.resolve(APPROVED_RETAIL_FILE)),
                    readFile(repositoryRoot.resolve(RetailCatalogPreviewLoader.FILE)));
        } catch (IOException ex) {
            throw new IllegalArgumentException("Approved active retail catalog cannot be read; no baseline fallback", ex);
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid approved active retail catalog", ex); }
    }

    private static SharedCatalogSnapshot approvedRetail(String identityJson, String activePriceJson,
                                                        String extensionJson, String reviewJson) {
        var extension = decode(extensionJson, ApprovedRetailExtension.class);
        var approval = extension.approval();
        require(Integer.valueOf(1).equals(extension.schemaVersion()) && approval != null
                        && APPROVED_RETAIL_DECISION.equals(approval.decision())
                        && RetailCatalogPreviewLoader.FILE.equals(approval.reviewFile())
                        && approval.reviewSha256() != null && approval.reviewSha256().matches("[0-9a-f]{64}")
                        && approval.reviewSha256().equals(hash(reviewJson))
                        && Integer.valueOf(1).equals(approval.newProducts())
                        && Integer.valueOf(1).equals(approval.newPriceObservations())
                        && Boolean.FALSE.equals(approval.autoActivation()),
                "Explicit exact one-product retail approval and unchanged review hash are required");
        var base = decode(identityJson, IdentityManifest.class);
        var active = decode(activePriceJson, CatalogPriceImportBatch.class);
        require(base.schemaVersion() == 1 && base.products() != null && base.products().size() == 307
                        && active.items().size() == 80 && extension.identities() != null
                        && extension.identities().products() != null && extension.identities().products().size() == 308
                        && extension.prices() != null && extension.prices().items().size() == 81,
                "Approved retail catalog must preserve 307 products and 80 prices, adding exactly one of each");
        // Validate the baseline as well as comparing complete records, including all original observation times.
        new SharedCatalogSnapshot(identityJson, activePriceJson);
        require(extension.identities().products().containsAll(base.products()),
                "Approved retail extension changed or removed an existing identity");
        require(extension.prices().items().containsAll(active.items()),
                "Approved retail extension changed or removed an approved price");
        var additions = extension.identities().products().stream().filter(p -> !base.products().contains(p)).toList();
        var quotes = extension.prices().items().stream().filter(p -> !active.items().contains(p)).toList();
        var review = decode(reviewJson, RetailCatalogPreviewLoader.Review.class);
        require(review.schemaVersion() == 1 && review.implementationApproved() && !review.databaseApplyApproved()
                        && !review.pricePublicationApproved() && review.candidates() != null
                        && review.candidates().size() == 4 && review.candidates().stream().allMatch(Objects::nonNull),
                "Approved retail extension requires the original four-candidate preview");
        var ready = review.candidates().stream()
                .filter(p -> p.status() == RetailCatalogPreviewLoader.Status.READY_FOR_APPROVAL).toList();
        require(ready.size() == 1 && review.candidates().stream()
                        .filter(p -> p.status() == RetailCatalogPreviewLoader.Status.HELD).count() == 3
                        && review.candidates().stream().filter(p -> p.status() == RetailCatalogPreviewLoader.Status.HELD)
                            .allMatch(p -> p.proposedSaleProduct() == null),
                "Only the reviewed ASUS sale product is approved; held candidates cannot be published");
        var sale = ready.getFirst().proposedSaleProduct();
        require(sale != null && additions.size() == 1 && quotes.size() == 1
                        && additions.getFirst().equals(sale.identity()) && quotes.getFirst().equals(sale.price())
                        && APPROVED_RETAIL_CANONICAL.equals(additions.getFirst().identity().canonicalId())
                        && "TUF GAMING B860-PLUS WIFI".equals(ready.getFirst().modelName())
                        && "688c711d-70d2-39f5-9642-d1c2a87d4bb7".equals(ready.getFirst().existingCanonicalId()),
                "Approved retail addition or price differs from the reviewed exact ASUS sale product");
        var snapshot = new SharedCatalogSnapshot(MAPPER.writeValueAsString(extension.identities()),
                MAPPER.writeValueAsString(extension.prices()), base);
        require(snapshot.products().size() == 308 && snapshot.products().values().stream()
                        .filter(p -> p.price() != null).count() == 81,
                "Approved retail scope must remain 308 products and 81 priced products");
        return snapshot;
    }

    public static SharedCatalogSnapshot loadFull() {
        return new SharedCatalogSnapshot(readBundled(FULL_IDENTITIES), readBundled(FULL_PRICES));
    }

    /** Filesystem exports use the approved active bundle; a missing or invalid bundle never falls back to older prices. */
    public static SharedCatalogSnapshot loadFull(Path repositoryRoot) {
        require(repositoryRoot != null, "Repository root is required");
        return loadFull(repositoryRoot, repositoryRoot.resolve(ACTIVE_PRICES_FILE));
    }

    /** Explicit complete approved bundle override; backend catalog identity remains unchanged. */
    public static SharedCatalogSnapshot loadFull(Path repositoryRoot, Path reviewedPriceFile) {
        try {
            return new SharedCatalogSnapshot(readFile(repositoryRoot.resolve("data/catalog-shared/" + FULL_IDENTITIES)),
                    readFile(reviewedPriceFile));
        } catch (IOException ex) {
            throw new IllegalArgumentException("Full shared catalog cannot be read", ex);
        }
    }

    /** Explicit append-only review preview. Never replaces production inputs or writes a database. */
    public static SharedCatalogSnapshot loadReviewedExtension(Path repositoryRoot, Path reviewedIdentityFile,
                                                              Path reviewedPriceFile) {
        try {
            var base = decode(readFile(repositoryRoot.resolve("data/catalog-shared/" + FULL_IDENTITIES)),
                    IdentityManifest.class);
            var currentPrices = decode(readFile(repositoryRoot.resolve(ACTIVE_PRICES_FILE)),
                    CatalogPriceImportBatch.class);
            String priceJson = readFile(reviewedPriceFile);
            var previewPrices = decode(priceJson, CatalogPriceImportBatch.class);
            for (var approved : currentPrices.items())
                require(previewPrices.items().contains(approved), "Extension changed or removed an approved price");
            var existingSources = base.products().stream().map(p -> sourceKey(p.sourceIdentity())).toList();
            for (var proposed : previewPrices.items()) {
                if (currentPrices.items().contains(proposed)) continue;
                require(!existingSources.contains(sourceKey(new SourceIdentity(proposed.product().sourceName(),
                                proposed.product().externalId()))),
                        "Extension cannot add a price to an existing installed product identity");
            }
            return new SharedCatalogSnapshot(readFile(reviewedIdentityFile), priceJson, base);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Reviewed catalog extension cannot be read", ex);
        }
    }

    private SharedCatalogSnapshot(String identityJson, String priceJson) {
        this(identityJson, priceJson, null);
    }

    private SharedCatalogSnapshot(String identityJson, String priceJson, IdentityManifest requiredBase) {
        var manifest = decode(identityJson, IdentityManifest.class);
        var prices = decode(priceJson, CatalogPriceImportBatch.class);
        require(manifest.schemaVersion() == 1 && manifest.products() != null
                        && (requiredBase == null ? manifest.products().size() == 307
                            : manifest.products().size() >= 307 && manifest.products().size() <= 1000),
                "Full shared catalog must contain 307 reviewed source identities");
        require(manifest.products().stream().allMatch(p -> p != null && p.sourceIdentity() != null && p.identity() != null),
                "Shared identity is missing");
        if (requiredBase != null) {
            require(requiredBase.schemaVersion() == 1 && requiredBase.products().size() == 307,
                    "Extension requires the preserved 307-product base");
            for (var existing : requiredBase.products())
                require(manifest.products().contains(existing), "Extension changed or removed an existing identity");
            for (var addition : manifest.products()) {
                if (requiredBase.products().contains(addition)) continue;
                var id = addition.identity(); var configuration = addition.reviewedSaleConfiguration();
                require(addition.sourceIdentity().sourceName() == CatalogSourceName.MANUFACTURER
                                && id.type() == com.pcupgradelab.pc.PartType.MOTHERBOARD
                                && id.identityKind() == com.pcupgradelab.catalog.identity.CatalogIdentityKind.PHYSICAL_VARIANT
                                && (id.role() == com.pcupgradelab.catalog.identity.CatalogRole.PURCHASE_CANDIDATE
                                    || id.role() == com.pcupgradelab.catalog.identity.CatalogRole.BOTH)
                                && configuration != null
                                && id.partNumber() == null && !id.active()
                                && id.verificationStatus() == CatalogVerificationStatus.UNVERIFIED
                                && configuration.role() == id.role()
                                && configuration.manufacturerModelName().equals(id.modelName()),
                        "Extension requires a reviewed separate motherboard purchase product");
            }
        }
        var entries = new LinkedHashMap<String, Entry>();
        var sourceIds = new LinkedHashMap<String, String>();
        manifest.products().stream().sorted(java.util.Comparator.comparing(p -> p.identity().canonicalId())).forEach(p -> {
            require(p != null && p.sourceIdentity() != null && p.identity() != null, "Shared identity is missing");
            var source = p.sourceIdentity(); var id = p.identity();
            require((source.sourceName() == CatalogSourceName.BUILDCORES || source.sourceName() == CatalogSourceName.MANUFACTURER)
                    && CanonicalCatalogIds.productForReviewedSource(source.sourceName(), source.externalId()).equals(id.canonicalId()),
                    "Shared canonical ID differs from reviewed source identity");
            require(id.type() != null && id.manufacturer() != null && !id.manufacturer().isBlank()
                    && id.modelName() != null && !id.modelName().isBlank() && id.identityKind() != null
                    && id.role() != null && id.verificationStatus() != null, "Shared product identity is incomplete");
            boolean ram = id.type() == com.pcupgradelab.pc.PartType.RAM;
            require(ram ? id.moduleCount() != null && id.moduleCount() > 0
                    && (id.moduleCount() == 1 ? "PRODUCT".equals(id.saleUnit()) : "RAM_KIT".equals(id.saleUnit()))
                    : "PRODUCT".equals(id.saleUnit()) && id.moduleCount() == null, "Shared sale unit is inconsistent");
            require(entries.putIfAbsent(id.canonicalId(), new Entry(id, null)) == null, "Duplicate shared canonical ID");
            require(sourceIds.putIfAbsent(sourceKey(source), id.canonicalId()) == null, "Duplicate shared source identity");
        });
        var quoted = new HashSet<String>();
        for (var quote : prices.items()) {
            String canonical = sourceIds.get(sourceKey(new SourceIdentity(quote.product().sourceName(), quote.product().externalId())));
            require(canonical != null && quoted.add(canonical), "Price does not identify one reviewed catalog product");
            var identity = entries.get(canonical).identity();
            var identityRecord = manifest.products().stream()
                    .filter(p -> p.identity().canonicalId().equals(canonical)).findFirst().orElseThrow();
            var configuration = quote.product().reviewedSaleConfiguration();
            if (configuration != null) {
                require(configuration.equals(identityRecord.reviewedSaleConfiguration())
                                && identity.type() == configuration.partType()
                                && identity.identityKind() == configuration.identityKind()
                                && identity.role() == configuration.role()
                                && identity.modelName().equals(configuration.manufacturerModelName())
                                && configuration.evidenceUrls().contains(quote.offer().sourceUrl())
                                && quote.offer().saleSku().equals(configuration.saleSku())
                                && !quote.offer().verifiedAt().isBefore(configuration.verifiedAt()),
                        "Shared price does not match its reviewed motherboard sale configuration");
            }
            require(identity.manufacturer().equals(quote.product().manufacturer())
                    && identity.modelName().equals(quote.product().modelName())
                    && Objects.equals(identity.partNumber(), quote.product().partNumber())
                    && identity.saleUnit().equals(quote.offer().saleUnit().name())
                    && Objects.equals("RAM_KIT".equals(identity.saleUnit()) ? identity.moduleCount() : null,
                        quote.offer().moduleCount()), "Shared price sales variant differs");
            require(validPriceSource(quote.offer().sourceName(), quote.offer().sourceUrl()), "Unapproved price source");
            require(quote.price().evidenceUrl().equals(quote.offer().sourceUrl())
                    && SharedPriceSourcePolicy.matches(quote.offer().sourceName(), quote.offer().sourceUrl(),
                        quote.offer().externalId()),
                    "Shared price evidence or provider product ID differs from its offer");
            require(identity.partNumber() == null || identity.partNumber().equals(quote.offer().saleSku()),
                    "Shared offer does not use the reviewed exact part number");
            require(!quote.price().observedAt().isBefore(quote.offer().verifiedAt()), "Shared price predates the verified offer");
            entries.put(canonical, new Entry(identity, new CatalogProductView.CurrentPrice(quote.price().amountKrw(),
                    quote.offer().sourceName(), quote.offer().sourceUrl(), quote.price().observedAt())));
        }
        products = Collections.unmodifiableMap(entries);
        full = true;
        catalogVersion = "all-catalog-v1-" + hash(MAPPER.writeValueAsString(entries.values().stream().map(Entry::identity).toList()));
        priceVersion = "prices-v1-" + hash(priceJson);
    }

    /** Only the reviewed domestic provider is accepted; query credentials and foreign price hosts are rejected. */
    public static boolean validPriceSource(String sourceName, String sourceUrl) {
        return SharedPriceSourcePolicy.accepts(sourceName, sourceUrl);
    }

    private static String sourceKey(SourceIdentity source) { return source.sourceName() + "/" + source.externalId(); }
    private static String readBundled(String file) {
        return readBundledResource("/catalog/shared/full/" + file);
    }
    private static String readBundledResource(String resource) {
        try (var input = SharedCatalogSnapshot.class.getResourceAsStream(resource)) {
            require(input != null, "Bundled full shared catalog is missing");
            byte[] bytes = input.readNBytes(5 * 1024 * 1024 + 1);
            require(bytes.length <= 5 * 1024 * 1024, "Full shared catalog is too large");
            return normalize(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException ex) { throw new IllegalArgumentException("Bundled full shared catalog cannot be read", ex); }
    }
    private static String readFile(Path path) throws IOException {
        require(Files.size(path) <= 5 * 1024 * 1024, "Full shared catalog is too large");
        return normalize(Files.readString(path, StandardCharsets.UTF_8));
    }
    private static String normalize(String value) { return (value.startsWith("\uFEFF") ? value.substring(1) : value).replace("\r\n", "\n"); }
    private static <T> T decode(String value, Class<T> type) {
        return MAPPER.treeToValue(MAPPER.readTree(value), type);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalize(value).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static void requirePinned(Path path, String expected) {
        try {
            require(Files.size(path) <= 5 * 1024 * 1024, "Shared review is too large");
            String text = Files.readString(path, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            text = text.replace("\r\n", "\n");
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
            require(expected.equals(hash), "Shared review differs from the approved fixed snapshot");
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalArgumentException("Shared review cannot be verified", ex);
        }
    }

    private static void deleteTemporary(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException ex) { path.toFile().deleteOnExit(); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
