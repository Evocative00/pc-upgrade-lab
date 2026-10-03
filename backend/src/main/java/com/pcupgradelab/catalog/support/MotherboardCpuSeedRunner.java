package com.pcupgradelab.catalog.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "catalog.motherboard-cpu.seed.enabled", havingValue = "true")
public class MotherboardCpuSeedRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MotherboardCpuSeedRunner.class);
    private final MotherboardCpuSeedService service;
    public MotherboardCpuSeedRunner(MotherboardCpuSeedService service) { this.service = service; }
    @Override
    public void run(ApplicationArguments args) {
        var result = service.seed();
        log.info("Motherboard CPU support {} committed: created={}, skipped={}, checked={}, cpuPairs={}, variantRows={}",
                MotherboardCpuSeedLoader.SEED_NAME, result.created(), result.skipped(), result.items().size(), result.cpuPairs(), result.variantRows());
        for (var item : result.items()) log.info("Motherboard CPU support {}: {} [{}], revision={}, cpuPairs={}, variantRows={}",
                item.created() ? "CREATED" : "SKIPPED", item.modelName(), item.productId(), item.revisionKey(), item.cpuPairs(), item.variantRows());
    }
}
