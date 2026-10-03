package com.pcupgradelab.catalog.seed;

import com.pcupgradelab.catalog.CatalogProductSourceRepository;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.memory.CpuMemorySeedLoader;
import com.pcupgradelab.catalog.memory.CpuMemorySeedService;
import com.pcupgradelab.catalog.support.MotherboardCpuSeedLoader;
import com.pcupgradelab.catalog.support.MotherboardCpuSeedService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 100종 제원과 호환 근거를 함께 커밋한다. 어느 단계든 실패하면 이번 실행 전체를 롤백한다. */
@Service
public class CatalogExpansionService {
    private final CatalogSeedLoader loader;
    private final CatalogProductSourceRepository sources;
    private final CatalogSeedService catalog;
    private final CpuMemorySeedService memory;
    private final MotherboardCpuSeedService boards;

    public CatalogExpansionService(CatalogSeedLoader loader, CatalogProductSourceRepository sources,
                                   CatalogSeedService catalog, CpuMemorySeedService memory,
                                   MotherboardCpuSeedService boards) {
        this.loader = loader; this.sources = sources; this.catalog = catalog;
        this.memory = memory; this.boards = boards;
    }

    @Transactional
    public Result seed() {
        for (var batch : CatalogSeedBatch.values()) {
            if (batch == CatalogSeedBatch.EXPAND_100) continue;
            for (var item : loader.load(batch)) {
                String externalId = item.sources().stream()
                        .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES)
                        .findFirst().orElseThrow().externalId();
                if (!sources.existsBySourceNameAndExternalId(CatalogSourceName.BUILDCORES, externalId)) {
                    throw new IllegalStateException("Register the original 61 catalog products before week2-expand100; missing "
                            + externalId);
                }
            }
        }
        // 원래 12 CPU/16 보드 자료를 먼저 검증하며, v1 출처는 확장 후에도 그대로 보존한다.
        memory.seed();
        boards.seed();
        var products = catalog.seed(CatalogSeedBatch.EXPAND_100);
        var cpuMemory = memory.seed(CpuMemorySeedLoader.EXPANSION);
        var boardSupport = boards.seed(MotherboardCpuSeedLoader.EXPANSION);
        return new Result(products, cpuMemory, boardSupport);
    }

    public record Result(CatalogSeedService.Result products, CpuMemorySeedService.Result cpuMemory,
                         MotherboardCpuSeedService.Result boardSupport) { }
}
