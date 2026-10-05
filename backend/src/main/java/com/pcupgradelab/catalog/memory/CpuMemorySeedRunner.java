package com.pcupgradelab.catalog.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** --catalog.cpu-memory.seed.enabled=true 지정 시에만 기존 CPU의 제조사 자료를 보완한다. */
@Component
@ConditionalOnProperty(prefix = "catalog.cpu-memory.seed", name = "enabled", havingValue = "true")
public class CpuMemorySeedRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(CpuMemorySeedRunner.class);
    private final CpuMemorySeedService service;

    public CpuMemorySeedRunner(CpuMemorySeedService service) { this.service = service; }

    @Override
    public void run(String... args) {
        var result = service.seed();
        log.info("CPU memory support enrichment committed: created={}, skipped={}, checked={}",
                result.created(), result.skipped(), result.items().size());
        for (var item : result.items()) {
            log.info("CPU memory support {}: {} [{}]", item.created() ? "CREATED" : "SKIPPED",
                    item.modelName(), item.productId());
        }
    }
}
