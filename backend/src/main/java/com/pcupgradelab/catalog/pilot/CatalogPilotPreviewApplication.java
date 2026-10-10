package com.pcupgradelab.catalog.pilot;

import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit read-only command; no Spring, Flyway, JDBC, private settings or network. */
public final class CatalogPilotPreviewApplication {
    private CatalogPilotPreviewApplication() { }

    public static void main(String[] args) {
        var result = new CatalogPilotPreviewLoader().load(Options.parse(args).sourceRoot());
        System.out.printf("DB-free pilot: parts=%d; reuse=%d; new proposals=%d; models=%d; model sources=%d; bindings=%d%n",
                result.parts(), result.reusedProducts(), result.newProducts(), result.models(),
                result.modelSources(), result.bindings());
        System.out.printf("Verified source hashes=%d; storage profiles=%d; slots=%d; existing price candidates=%d; held model quotes=%d; reviewed SSD seller quotes=%d%n",
                result.sourceHashes(), result.storageProfiles(), result.storageSlots(), result.priceCandidates(),
                result.heldModelQuotes(), result.storageSellerQuotes());
        System.out.println("Local product/model/source IDs were not queried. DB reads=0; DB writes=0; apply is unavailable.");
    }

    record Options(Path sourceRoot) {
        static Options parse(String[] args) {
            if (args == null) throw new IllegalArgumentException("Preview arguments are required");
            Path root = null;
            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                if (arg != null && (arg.toLowerCase(java.util.Locale.ROOT).startsWith("--apply")
                        || arg.toLowerCase(java.util.Locale.ROOT).startsWith("-papply"))) {
                    throw new IllegalArgumentException("Pilot apply is unavailable; only a DB-free preview is implemented");
                }
                if (!"--source-root".equals(arg) || root != null || ++index >= args.length
                        || args[index] == null || args[index].isBlank()) {
                    throw new IllegalArgumentException("Only one --source-root <repository-directory> option is supported");
                }
                root = Path.of(args[index]);
            }
            if (root == null) {
                Path current = Path.of("").toAbsolutePath();
                root = Files.isRegularFile(current.resolve(CatalogPilotPreviewLoader.PREVIEW_FILE))
                        ? current : current.getParent();
            }
            return new Options(root);
        }
    }
}
