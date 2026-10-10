package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.BackendApplication;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import tools.jackson.databind.json.JsonMapper;

/** No DB on the default path. Explicit approved DB modes never start Flyway, the web server, seed jobs or OAuth. */
public final class RetailCatalogApplyApplication {
    private RetailCatalogApplyApplication() { }
    public static void main(String[] args) {
        var options = Options.parse(args);
        var plan = new RetailCatalogApplyLoader().load(options.sourceRoot());
        System.out.println("APPROVED RETAIL DB-FREE PREVIEW: newProducts=1 existingModelsReused=1 priceObservations=1 activation=0 databaseWrites=0 previewSha256=" + plan.previewSha256());
        if (!options.checkDb() && !options.apply()) return;
        var app = new SpringApplication(BackendApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run("--spring.profiles.active=local", "--spring.main.web-application-type=none",
                "--spring.flyway.enabled=false", "--spring.sql.init.mode=never", "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.autoconfigure.exclude=org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration",
                "--catalog.seed.enabled=false", "--catalog.cpu-memory.seed.enabled=false", "--catalog.motherboard-cpu.seed.enabled=false",
                "--catalog.price-import.enabled=false", "--catalog.shared-prices.enabled=false")) {
            var service = context.getBean(RetailCatalogApplyService.class);
            var result = options.checkDb() ? service.preview(plan) : service.apply(plan);
            if (options.apply()) System.out.println("RETAIL_DB_TRANSACTION_COMMITTED: createdProducts=" + result.createdProducts()
                    + " priceObservations=" + result.createdPriceObservations() + " unchangedProducts=" + result.unchangedProducts());
            else System.out.println("RETAIL_READ_ONLY_PREFLIGHT_COMPLETE: databaseWrites=0");
            // Public audit report: IDs, counts and digests only. No database rows, URLs, credentials or private settings.
            System.out.println("RETAIL_APPLY_RESULT " + JsonMapper.builder().build().writeValueAsString(result));
        }
    }
    record Options(Path sourceRoot, boolean checkDb, boolean apply) {
        static Options parse(String[] args) {
            if (args == null) throw new IllegalArgumentException("Arguments are required");
            Path root = null; boolean check = false, apply = false;
            for (int i=0; i<args.length; i++) {
                if (args[i] == null) throw new IllegalArgumentException("Arguments cannot be null");
                switch (args[i]) {
                    case "--source-root" -> {
                        if (root != null || ++i >= args.length || args[i] == null || args[i].isBlank())
                            throw new IllegalArgumentException("Use one --source-root <repository-directory>");
                        root = Path.of(args[i]).toAbsolutePath().normalize();
                    }
                    case "--check-db" -> {
                        if (check || apply) throw new IllegalArgumentException("Choose one of --check-db or --apply");
                        check=true;
                    }
                    case "--apply" -> {
                        if (check || apply) throw new IllegalArgumentException("Choose one of --check-db or --apply");
                        apply=true;
                    }
                    default -> throw new IllegalArgumentException("Unsupported retail apply argument");
                }
            }
            if (root == null) throw new IllegalArgumentException("--source-root is required");
            return new Options(root,check,apply);
        }
    }
}
