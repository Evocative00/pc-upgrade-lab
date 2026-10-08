package com.pcupgradelab.catalog.price;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 명시적으로 활성화한 파일만 처리한다. 일반 서버 시작에서는 가격을 가져오지 않는다. */
@Component
@ConditionalOnProperty(prefix = "catalog.price-import", name = "enabled", havingValue = "true")
public class CatalogPriceImportRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(CatalogPriceImportRunner.class);
    private final CatalogPriceImportLoader loader;
    private final CatalogPriceImportService service;
    private final Path file;
    private final boolean dryRun;

    public CatalogPriceImportRunner(CatalogPriceImportLoader loader, CatalogPriceImportService service,
                                    @Value("${catalog.price-import.file}") String file,
                                    @Value("${catalog.price-import.dry-run:true}") boolean dryRun) {
        this.loader = loader;
        this.service = service;
        this.file = Path.of(file);
        this.dryRun = dryRun;
    }

    @Override
    public void run(String... args) {
        var batch = loader.load(file);
        var result = dryRun ? service.preview(batch) : service.apply(batch);
        log.info("Catalog price import {}: checked={}, newMappings={}, newObservations={}, unchanged={}",
                result.dryRun() ? "PREVIEW" : "COMMITTED", result.checked(), result.createdMappings(),
                result.createdObservations(), result.unchangedObservations());
    }
}
