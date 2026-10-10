package com.pcupgradelab.catalog.pilot;

import com.pcupgradelab.BackendApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/** Explicit one-shot pilot import. The default path does not initialize Spring or connect to a database. */
public final class CatalogPilotImportApplication {
    private CatalogPilotImportApplication() { }

    public static void main(String[] args) {
        var options = Options.parse(args);
        var review = new CatalogPilotPreviewLoader().load(options.sourceRoot());
        System.out.printf("PILOT DB-FREE REVIEW: parts=%d, newProducts=%d, models=%d, storageSlots=%d, databaseWrites=0%n",
                review.parts(), review.newProducts(), review.models(), review.storageSlots());
        if (!options.checkDb() && !options.apply()) {
            System.out.println("Use --check-db for a read-only local database preflight or --apply for the approved transaction.");
            return;
        }
        var plan = new CatalogPilotImportLoader().load(options.sourceRoot());
        var app = new SpringApplication(BackendApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        // Command-line properties override private local seed settings; this command never runs Flyway or SQL init.
        try (var context = app.run(
                "--spring.profiles.active=local",
                "--spring.main.web-application-type=none",
                "--spring.flyway.enabled=false",
                "--spring.sql.init.mode=never",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.autoconfigure.exclude=org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration",
                "--catalog.seed.enabled=false",
                "--catalog.cpu-memory.seed.enabled=false",
                "--catalog.motherboard-cpu.seed.enabled=false",
                "--catalog.price-import.enabled=false")) {
            var service = context.getBean(CatalogPilotImportService.class);
            System.out.println("PILOT DB PREVIEW: " + service.preview(plan));
            if (options.apply()) System.out.println("PILOT COMMITTED: " + service.apply(plan));
        }
    }

    record Options(Path sourceRoot, boolean checkDb, boolean apply) {
        static Options parse(String[] args) {
            if (args == null) throw new IllegalArgumentException("Pilot arguments are required");
            Path root = null;
            boolean checkDb = false;
            boolean apply = false;
            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                if (arg == null) throw new IllegalArgumentException("Pilot arguments must not be null");
                switch (arg) {
                    case "--source-root" -> {
                        if (root != null || ++index >= args.length || args[index] == null || args[index].isBlank())
                            throw new IllegalArgumentException("Use one --source-root <repository-directory> option");
                        root = Path.of(args[index]);
                    }
                    case "--check-db" -> {
                        if (checkDb || apply) throw new IllegalArgumentException("Choose one of --check-db or --apply");
                        checkDb = true;
                    }
                    case "--apply" -> {
                        if (apply || checkDb) throw new IllegalArgumentException("Choose one of --check-db or --apply");
                        apply = true;
                    }
                    default -> throw new IllegalArgumentException("Unsupported pilot argument: " + arg);
                }
            }
            if (root == null) {
                Path current = Path.of("").toAbsolutePath();
                root = Files.isRegularFile(current.resolve(CatalogPilotPreviewLoader.PREVIEW_FILE))
                        ? current : current.getParent();
            }
            return new Options(root, checkDb, apply);
        }
    }
}
