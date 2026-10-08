package com.pcupgradelab.catalog.seed;

import com.pcupgradelab.catalog.CatalogProductSourceRepository;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.memory.CpuMemorySeedLoader;
import com.pcupgradelab.catalog.memory.CpuMemorySeedService;
import com.pcupgradelab.catalog.price.CatalogPriceImportBatch;
import com.pcupgradelab.catalog.price.CatalogPriceImportService;
import com.pcupgradelab.catalog.support.MotherboardCpuSeedLoader;
import com.pcupgradelab.catalog.support.MotherboardCpuSeedService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 명시적으로 요청한 개발 카탈로그 초기화만 수행한다. 실패하면 제품·지원 자료·가격 모두 롤백한다. */
@Service
public class CatalogDevBootstrapService {
    private final CatalogSeedLoader loader;
    private final CatalogProductSourceRepository sources;
    private final CatalogSeedService products;
    private final CatalogExpansionService expansion;
    private final CpuMemorySeedService memory;
    private final MotherboardCpuSeedService boards;
    private final CatalogPriceImportService priceImport;

    public CatalogDevBootstrapService(CatalogSeedLoader loader, CatalogProductSourceRepository sources,
                                      CatalogSeedService products, CatalogExpansionService expansion,
                                      CpuMemorySeedService memory, MotherboardCpuSeedService boards,
                                      CatalogPriceImportService priceImport) {
        this.loader = loader;
        this.sources = sources;
        this.products = products;
        this.expansion = expansion;
        this.memory = memory;
        this.boards = boards;
        this.priceImport = priceImport;
    }

    @Transactional
    public Result apply(CatalogPriceImportBatch prices) {
        if (prices == null) throw new IllegalArgumentException("Reviewed development prices are required");
        int createdProducts = 0;
        if (allApprovedProductsExist()) {
            // 이미 초기화한 DB에서는 누락된 최신 지원 자료를 자동 복구하지 않는다.
            memory.verify(CpuMemorySeedLoader.EXPANSION_300);
            boards.verify(MotherboardCpuSeedLoader.EXPANSION_300);
        } else {
            for (var batch : CatalogSeedBatch.values()) {
                if (batch != CatalogSeedBatch.EXPAND_100 && batch != CatalogSeedBatch.EXPAND_300) {
                    createdProducts += products.seed(batch).created();
                }
            }
            createdProducts += expansion.seed().products().created();
        }
        createdProducts += expansion.seed300().products().created();
        var imported = priceImport.apply(prices);
        return new Result(createdProducts, imported);
    }

    private boolean allApprovedProductsExist() {
        for (var batch : CatalogSeedBatch.values()) {
            for (var entry : loader.load(batch)) {
                var identity = entry.sources().stream()
                        .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES)
                        .findFirst().orElseThrow();
                if (!sources.existsBySourceNameAndExternalId(identity.sourceName(), identity.externalId())) {
                    return false;
                }
            }
        }
        return true;
    }

    public record Result(int createdProducts, CatalogPriceImportService.Result prices) { }
}
