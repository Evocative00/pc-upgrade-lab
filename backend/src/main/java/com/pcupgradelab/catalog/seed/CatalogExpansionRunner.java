package com.pcupgradelab.catalog.seed;

import com.pcupgradelab.catalog.memory.CpuMemorySeedLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "catalog.seed", name = "enabled", havingValue = "true")
public class CatalogExpansionRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(CatalogExpansionRunner.class);
    private final CatalogExpansionService service;
    private final CatalogSeedBatch batch;

    public CatalogExpansionRunner(CatalogExpansionService service,
                                 @Value("${catalog.seed.batch:week2-initial}") String batchName) {
        this.service = service;
        this.batch = CatalogSeedBatch.fromName(batchName);
    }

    @Override
    public void run(String... args) {
        if (batch != CatalogSeedBatch.EXPAND_100) return;
        var result = service.seed();
        log.info("Catalog expansion {} committed: created={}, skipped={}, checked={}", batch.resourceName(),
                result.products().created(), result.products().skipped(), result.products().items().size());
        log.info("CPU memory support {} committed: created={}, skipped={}, checked={}", CpuMemorySeedLoader.EXPANSION,
                result.cpuMemory().created(), result.cpuMemory().skipped(), result.cpuMemory().items().size());
        log.info("Motherboard CPU support expansion committed: created={}, extended={}, skipped={}, checked={}, cpuPairs={}, variantRows={}",
                result.boardSupport().created(), result.boardSupport().extended(), result.boardSupport().skipped(),
                result.boardSupport().items().size(), result.boardSupport().cpuPairs(), result.boardSupport().variantRows());
        for (var item : result.products().items()) {
            log.info("Catalog seed {} {}: {} [{}]", batch.resourceName(), item.created() ? "CREATED" : "SKIPPED",
                    item.modelName(), item.productId());
        }
    }

}
