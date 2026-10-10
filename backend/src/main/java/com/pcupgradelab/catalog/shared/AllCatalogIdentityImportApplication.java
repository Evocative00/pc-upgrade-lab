package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.BackendApplication;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/** Default review is DB-free. Local database reads/writes require explicit, mutually exclusive flags. */
public final class AllCatalogIdentityImportApplication {
    private AllCatalogIdentityImportApplication() { }
    public static void main(String[] args) {
        var options = Options.parse(args);
        var plan = new AllCatalogManifestGenerator().load(options.sourceRoot());
        System.out.println("ALL CATALOG DB-FREE PREVIEW: products=307 canonicalBindings=293 approvedPrices=74 missingPrices=233 databaseWrites=0");
        if (!options.checkDb() && !options.apply()) return;
        var app = new SpringApplication(BackendApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run("--spring.profiles.active=local","--spring.main.web-application-type=none",
                "--spring.flyway.enabled=false","--spring.sql.init.mode=never","--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.autoconfigure.exclude=org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration",
                "--catalog.seed.enabled=false","--catalog.cpu-memory.seed.enabled=false","--catalog.motherboard-cpu.seed.enabled=false",
                "--catalog.price-import.enabled=false","--catalog.shared-prices.enabled=false")) {
            var service = context.getBean(AllCatalogIdentityImportService.class);
            if (options.checkDb()) System.out.println("ALL CATALOG READ-ONLY DB PREFLIGHT: " + service.preview(plan));
            else System.out.println("ALL CATALOG CANONICAL BINDINGS COMMITTED: " + service.apply(plan));
        }
    }
    record Options(Path sourceRoot,boolean checkDb,boolean apply) {
        static Options parse(String[] args) {
            if (args == null) throw new IllegalArgumentException("Arguments are required");
            Path root = null;
            boolean check = false,apply = false;
            for (int i = 0;i < args.length;i++) {
                if (args[i] == null) throw new IllegalArgumentException("Arguments must not be null");
                switch (args[i]) {
                    case "--source-root" -> {
                        if (root != null || ++i >= args.length || args[i] == null || args[i].isBlank())
                            throw new IllegalArgumentException("Use one --source-root <repository-directory>");
                        root = Path.of(args[i]).toAbsolutePath().normalize();
                    }
                    case "--check-db" -> {
                        if (check || apply) throw new IllegalArgumentException("Choose one of --check-db or --apply");
                        check = true;
                    }
                    case "--apply" -> {
                        if (check || apply) throw new IllegalArgumentException("Choose one of --check-db or --apply");
                        apply = true;
                    }
                    default -> throw new IllegalArgumentException("Unsupported full-catalog argument");
                }
            }
            if (root == null) throw new IllegalArgumentException("--source-root is required");
            return new Options(root,check,apply);
        }
    }
}
