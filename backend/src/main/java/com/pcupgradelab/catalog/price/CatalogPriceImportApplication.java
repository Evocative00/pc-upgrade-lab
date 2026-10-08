package com.pcupgradelab.catalog.price;

import com.pcupgradelab.BackendApplication;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.seed.CatalogDevBootstrapService;
import com.pcupgradelab.catalog.seed.CatalogSeedBatch;
import com.pcupgradelab.catalog.seed.CatalogSeedLoader;
import java.util.HashSet;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/** 명시적인 Gradle 작업에서만 실행하는 일회성 가져오기. 개인 OAuth 설정과 포트가 필요 없다. */
public final class CatalogPriceImportApplication {
    private CatalogPriceImportApplication() { }

    public static void main(String[] args) {
        var options = Options.parse(args);
        var batch = new CatalogPriceSnapshotLoader(new CatalogPriceImportLoader()).load();
        if (options.setupCatalog() && !options.apply()) {
            // 초기 설정 미리보기는 DB와 비밀번호가 없어도 리소스만 검증한다.
            var loader = new CatalogSeedLoader();
            var identities = new HashSet<String>();
            int products = 0;
            for (var seed : CatalogSeedBatch.values()) {
                for (var entry : loader.load(seed)) {
                    products++;
                    for (var source : entry.sources()) {
                        if (source.sourceName() == CatalogSourceName.BUILDCORES && !identities.add(source.externalId())) {
                            throw new IllegalStateException("The reviewed development catalog contains duplicate source identities");
                        }
                    }
                }
            }
            if (products != 300) throw new IllegalStateException("The reviewed development catalog must contain 300 products");
            System.out.printf("CATALOG PREVIEW: reviewedProducts=%d, reviewedPrices=%d, databaseWrites=0%n", products, batch.items().size());
            System.out.println("Use -PapplyDevCatalog=true to initialize the reviewed catalog and prices.");
            return;
        }
        var app = new SpringApplication(BackendApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        // 파일의 seed/import 활성화와 관계없이 추가 runner와 스키마 변경을 막는다.
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
            if (options.setupCatalog()) {
                var result = context.getBean(CatalogDevBootstrapService.class).apply(batch);
                System.out.printf("CATALOG COMMITTED: createdProducts=%d, reviewedProducts=300%n", result.createdProducts());
                print("COMMITTED", result.prices());
                return;
            }
            var service = context.getBean(CatalogPriceImportService.class);
            try {
                print("PREVIEW", service.preview(batch));
                if (options.apply()) print("COMMITTED", service.apply(batch));
                else System.out.println("Use -PapplyPrices=true to commit the reviewed prices.");
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("Price import refused. Check the reviewed product identities and existing price data. "
                        + "For a database without the development catalog, run setupDevCatalog -PapplyDevCatalog=true first.", ex);
            }
        }
    }

    private static void print(String phase, CatalogPriceImportService.Result result) {
        System.out.printf("PRICE %s: checked=%d, newMappings=%d, newObservations=%d, unchanged=%d%n",
                phase, result.checked(), result.createdMappings(), result.createdObservations(), result.unchangedObservations());
    }

    record Options(boolean apply, boolean setupCatalog) {
        static Options parse(String[] args) {
            boolean apply = false;
            boolean setup = false;
            for (String argument : args) {
                switch (argument) {
                    case "--apply" -> apply = true;
                    case "--setup-catalog" -> setup = true;
                    default -> throw new IllegalArgumentException("Unsupported import argument: " + argument);
                }
            }
            return new Options(apply, setup);
        }
    }
}
