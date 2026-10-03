package com.pcupgradelab.catalog.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 실행 옵션 --catalog.seed.enabled=true 를 지정한 경우에만 초기 자료를 등록한다. */
@Component
@ConditionalOnProperty(prefix = "catalog.seed", name = "enabled", havingValue = "true")
public class CatalogSeedRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(CatalogSeedRunner.class);
    private final CatalogSeedService service;
    private final CatalogSeedBatch batch;

    public CatalogSeedRunner(CatalogSeedService service,
                             @Value("${catalog.seed.batch:week2-initial}") String batchName) {
        this.service = service;
        this.batch = CatalogSeedBatch.fromName(batchName);
    }

    @Override
    public void run(String... args) {
        // 100종 확장은 전용 runner가 제원·호환 자료를 한 트랜잭션으로 함께 적재한다.
        if (batch == CatalogSeedBatch.EXPAND_100) return;
        // 별도 서비스의 트랜잭션 커밋까지 성공한 뒤에만 완료 로그를 남긴다.
        var result = service.seed(batch);
        log.info("Catalog seed {} committed: created={}, skipped={}, checked={}",
                batch.resourceName(), result.created(), result.skipped(), result.items().size());
        for (var item : result.items()) {
            log.info("Catalog seed {} {}: {} [{}]", batch.resourceName(),
                    item.created() ? "CREATED" : "SKIPPED", item.modelName(), item.productId());
        }
    }
}
