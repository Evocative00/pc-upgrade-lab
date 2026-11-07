package com.pcupgradelab.pricetrend;

import com.pcupgradelab.BackendApplication;
import com.pcupgradelab.catalog.price.CatalogPriceImportBatch;
import com.pcupgradelab.catalog.price.CatalogPriceImportLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import tools.jackson.databind.json.JsonMapper;

/**
 * 명시적인 Gradle 작업(importPriceHistory·analyzePriceTrends)에서만 실행한다.
 * 웹 서버·Flyway·seed·중앙 가격 연결을 켜지 않으며 기존 카탈로그 테이블에는 쓰지 않는다.
 */
public final class PriceTrendApplication {
    /** 검토·승인된 기존 관측 파일. 승인 형식(schemaVersion·items)을 그대로 읽는다. */
    static final List<String> APPROVED_FILES = List.of(
            "data/catalog-current-prices/observations-2026-10-06.json",
            "data/catalog-current-prices/observations-expansion50-2026-10-08.json",
            "data/catalog-review/pilot-approved-prices-2026-10-10.json",
            "data/catalog-shared/active-approved-prices.json",
            "data/catalog-shared/approved-retail-extension-2026-10-10.json");
    /** 팀이 앞으로 검토해 추가하는 관측 파일 위치(observations-YYYY-MM-DD.json). */
    static final String MANUAL_DIRECTORY = "data/price-history";
    private static final JsonMapper TREE = JsonMapper.builder().build();

    private PriceTrendApplication() { }

    public static void main(String[] args) {
        var options = Options.parse(args);
        if (options.mode() == Mode.HISTORY) {
            var batches = loadFiles(options.sourceRoot(), new CatalogPriceImportLoader());
            int items = batches.stream().mapToInt(batch -> batch.batch().items().size()).sum();
            var keys = new HashSet<PricePoint.ObservationKey>();
            for (var batch : batches) {
                for (var item : batch.batch().items()) {
                    keys.add(new PricePoint.ObservationKey(item.offer().sourceName(), item.offer().externalId(),
                            item.price().observedAt()));
                }
                System.out.printf("PRICE HISTORY FILE: %s items=%d%n", batch.file(), batch.batch().items().size());
            }
            System.out.printf("PRICE HISTORY FILE CHECK: files=%d, items=%d, uniqueObservations=%d, databaseAccess=0, databaseWrites=0%n",
                    batches.size(), items, keys.size());
            if (!options.checkDb() && !options.apply()) {
                System.out.println("Use -PcheckPriceHistoryDb=true for a read-only DB preview or -PapplyPriceHistory=true to commit.");
                return;
            }
            try (var context = context()) {
                var service = context.getBean(PriceHistoryImportService.class);
                print(service.preview(batches));
                if (options.apply()) print(service.apply(batches, Instant.now()));
            }
            return;
        }
        try (var context = context()) {
            var service = context.getBean(PriceTrendAnalysisService.class);
            var now = Instant.now();
            var summary = options.apply() ? service.apply(now) : service.preview(now);
            System.out.printf("PRICE ANALYSIS %s: products=%d, verdicts=%s, method=%s%n",
                    summary.dryRun() ? "PREVIEW (databaseWrites=0)" : "COMMITTED",
                    summary.products(), summary.verdicts(), PriceTrendAnalyzer.METHOD_VERSION);
            if (!options.apply()) System.out.println("Use -PapplyPriceAnalysis=true to store the analysis snapshot.");
        }
    }

    static List<PriceHistoryImportService.NamedBatch> loadFiles(Path root, CatalogPriceImportLoader loader) {
        var files = new ArrayList<String>(APPROVED_FILES);
        Path manual = root.resolve(MANUAL_DIRECTORY);
        if (Files.isDirectory(manual)) {
            try (Stream<Path> listing = Files.list(manual)) {
                listing.filter(path -> path.getFileName().toString().matches("observations-\\d{4}-\\d{2}-\\d{2}.*\\.json"))
                        .sorted()
                        .forEach(path -> files.add(MANUAL_DIRECTORY + "/" + path.getFileName()));
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to list " + MANUAL_DIRECTORY, ex);
            }
        }
        var batches = new ArrayList<PriceHistoryImportService.NamedBatch>();
        for (String file : files) {
            Path path = root.resolve(file).normalize();
            if (!path.startsWith(root) || Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
                throw new IllegalStateException("Price history file is missing or not a regular file: " + file);
            }
            batches.add(new PriceHistoryImportService.NamedBatch(file, read(path, loader)));
        }
        return batches;
    }

    /** 승인 확장 파일은 승인 정보와 함께 prices 아래에 같은 형식의 관측을 담고 있다. */
    private static CatalogPriceImportBatch read(Path path, CatalogPriceImportLoader loader) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            var tree = TREE.readTree(bytes);
            if (tree != null && tree.isObject() && tree.has("prices") && !tree.has("items")) {
                bytes = TREE.writeValueAsBytes(tree.get("prices"));
            }
            return loader.read(bytes);
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("Invalid price history file: " + path.getFileName(), ex);
        }
    }

    private static void print(PriceHistoryImportService.Result result) {
        System.out.printf("PRICE HISTORY %s: checked=%d, newHistory=%d, alreadyInCatalog=%d, alreadyInHistory=%d, "
                        + "duplicateInFiles=%d, skippedUnknownProduct=%d, skippedMismatch=%d%n",
                result.dryRun() ? "PREVIEW" : "COMMITTED", result.checked(), result.created(), result.alreadyInCatalog(),
                result.alreadyInHistory(), result.duplicateInFiles(), result.skippedUnknownProduct(), result.skippedMismatch());
        result.skipped().forEach(line -> System.out.println("  SKIPPED " + line));
    }

    private static org.springframework.context.ConfigurableApplicationContext context() {
        var app = new SpringApplication(BackendApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return app.run("--spring.profiles.active=local", "--spring.main.web-application-type=none",
                "--spring.flyway.enabled=false", "--spring.sql.init.mode=never", "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.autoconfigure.exclude=org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration",
                "--catalog.seed.enabled=false", "--catalog.cpu-memory.seed.enabled=false",
                "--catalog.motherboard-cpu.seed.enabled=false", "--catalog.price-import.enabled=false",
                "--catalog.shared-prices.enabled=false");
    }

    enum Mode { HISTORY, ANALYZE }

    record Options(Path sourceRoot, Mode mode, boolean checkDb, boolean apply) {
        static Options parse(String[] args) {
            Path root = null;
            Mode mode = null;
            boolean check = false, apply = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--source-root" -> {
                        if (root != null || ++i >= args.length || args[i].isBlank())
                            throw new IllegalArgumentException("Use one --source-root <repository-root>");
                        root = Path.of(args[i]).toAbsolutePath().normalize();
                    }
                    case "--history" -> mode = set(mode, Mode.HISTORY);
                    case "--analyze" -> mode = set(mode, Mode.ANALYZE);
                    case "--check-db" -> check = true;
                    case "--apply" -> apply = true;
                    default -> throw new IllegalArgumentException("Unsupported price trend argument: " + args[i]);
                }
            }
            if (root == null || mode == null) throw new IllegalArgumentException("--source-root and --history or --analyze are required");
            if (check && apply) throw new IllegalArgumentException("Choose only one of --check-db or --apply");
            if (mode == Mode.ANALYZE && check) throw new IllegalArgumentException("Analysis preview already reads the DB without writes");
            return new Options(root, mode, check, apply);
        }

        private static Mode set(Mode current, Mode next) {
            if (current != null) throw new IllegalArgumentException("Choose one mode");
            return next;
        }
    }
}
