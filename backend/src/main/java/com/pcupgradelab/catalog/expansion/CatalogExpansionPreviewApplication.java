package com.pcupgradelab.catalog.expansion;

import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit read-only CLI. It does not start Spring, Flyway, JDBC or a web server. */
public final class CatalogExpansionPreviewApplication {
    private CatalogExpansionPreviewApplication() { }

    public static void main(String[] args) {
        var options = Options.parse(args);
        var preview = new CatalogExpansionPreviewLoader().load(options.sourceRoot());
        var summary = preview.summary();
        System.out.printf("DB-free catalog expansion preview: existing=%d, approved historical prices=%d, research=%d, latest-first=%d, followup=%d, held=%d%n",
                summary.existingProductCount(), summary.retainedApprovedPriceCount(), summary.researchCandidateCount(),
                summary.latestFirstCandidateCount(), summary.followupCandidateCount(), summary.heldAdditionalModelCount());
        System.out.printf("Verified source hashes=%d. Local IDs were not queried. New products=0; new prices=0; apply is unavailable.%n",
                preview.sourceHashes().size());
        for (var candidate : preview.candidates()) {
            System.out.printf("%02d | %s | %s | %s | %s | PN=%s | NEEDS_REVIEW | NOT_COLLECTED%n",
                    candidate.reviewOrder(), candidate.reviewPhase(), candidate.category(),
                    candidate.manufacturer(), candidate.modelName(),
                    candidate.researchPartNumber() == null ? "UNKNOWN" : candidate.researchPartNumber());
        }
        System.out.println("Product adoption, exact sale SKUs, compatibility and any database apply require a later user confirmation.");
    }

    record Options(Path sourceRoot) {
        static Options parse(String[] args) {
            if (args == null) throw new IllegalArgumentException("Preview arguments are required");
            Path root = null;
            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                if (arg != null && (arg.startsWith("--apply") || arg.startsWith("-Papply"))) {
                    throw new IllegalArgumentException("Catalog expansion apply is not implemented or authorized; only a DB-free preview is available");
                }
                if (!"--source-root".equals(arg) || root != null || ++index >= args.length) {
                    throw new IllegalArgumentException("Only one --source-root <repository-directory> preview option is supported");
                }
                root = Path.of(args[index]);
            }
            if (root == null) {
                Path current = Path.of("").toAbsolutePath();
                root = Files.isRegularFile(current.resolve("data/catalog-review/catalog-selection-2026-10-09.json"))
                        ? current : current.getParent();
            }
            return new Options(root);
        }
    }
}
