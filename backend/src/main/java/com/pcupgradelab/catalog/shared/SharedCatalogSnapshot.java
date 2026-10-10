package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.CatalogProductView;
import com.pcupgradelab.catalog.CatalogSpecification;
import com.pcupgradelab.catalog.CatalogVerificationStatus;
import com.pcupgradelab.catalog.pilot.CatalogPilotImportLoader;
import com.pcupgradelab.catalog.price.CatalogPriceImportBatch;
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
    }

    public static SharedCatalogSnapshot load() {
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
            return load(root);
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

    public static SharedCatalogSnapshot load(Path repositoryRoot) {
        requirePinned(repositoryRoot.resolve("data/catalog-review/pilot-import-preview-2026-10-10.json"), PREVIEW_HASH);
        requirePinned(repositoryRoot.resolve("data/catalog-review/pilot-approved-prices-2026-10-10.json"), PRICES_HASH);
        return new SharedCatalogSnapshot(new CatalogPilotImportLoader().load(repositoryRoot));
    }

    public Map<String, Entry> products() { return products; }
    public String version() { return "pilot-2026-10-10-" + PREVIEW_HASH.substring(0, 16) + "-" + PRICES_HASH.substring(0, 16); }
    public record Entry(SharedPriceDtos.Identity identity, CatalogProductView.CurrentPrice price) {}

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
