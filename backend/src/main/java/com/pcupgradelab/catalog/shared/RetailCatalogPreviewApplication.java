package com.pcupgradelab.catalog.shared;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Explicit read-only preview. Output is confined to ignored backend/build files. */
public final class RetailCatalogPreviewApplication {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private RetailCatalogPreviewApplication() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !"--source-root".equals(args[0]))
            throw new IllegalArgumentException("Use --source-root <repository-root>; apply is unavailable");
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        var plan = new RetailCatalogPreviewLoader().load(root);
        Path output = Files.createDirectories(root.resolve("backend/build/catalog-retail-preview"));
        Path identities = output.resolve("identities-preview.json"), prices = output.resolve("prices-preview.json");
        write(identities, plan.identities()); write(prices, plan.prices());
        var preview = SharedCatalogSnapshot.loadReviewedExtension(root, identities, prices);
        var base = SharedCatalogSnapshot.loadFull(root);
        SharedCatalogExportApplication.exportReviewedPreview(root, preview);
        int added = plan.registrations().size();
        long basePrices = base.products().values().stream().filter(e -> e.price() != null).count();
        var report = Map.of("schemaVersion", 1, "mode", "PREVIEW_ONLY", "requested", 4,
                "readyForApproval", added, "held", 4 - added, "databaseWrites", 0, "publications", 0,
                "production", Map.of("products", base.products().size(), "prices", basePrices,
                        "catalogVersion", base.version(), "priceVersion", base.priceVersion()),
                "preview", Map.of("products", preview.products().size(), "prices", basePrices + added,
                        "catalogVersion", preview.version(), "priceVersion", preview.priceVersion()),
                "candidates", plan.review().candidates());
        write(output.resolve("report.json"), report);
        System.out.println("Retail preview: candidates=4 ready=" + added + " held=" + (4 - added)
                + " productionProducts=" + base.products().size() + " productionPrices=" + basePrices
                + " databaseAccess=0 publications=0 output=backend/build/catalog-retail-preview");
    }

    private static void write(Path target, Object value) throws Exception {
        Files.writeString(target, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value)
                .replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8);
    }
}
