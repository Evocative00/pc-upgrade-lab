package com.pcupgradelab.catalog.memory;

import com.pcupgradelab.catalog.CatalogDtos;
import com.pcupgradelab.catalog.CatalogProductRepository;
import com.pcupgradelab.catalog.CatalogProductSourceRepository;
import com.pcupgradelab.common.ApiException;
import com.pcupgradelab.pc.PartType;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CpuMemoryQueryService {
    private final CatalogProductRepository products;
    private final CatalogProductSourceRepository sources;
    private final CpuMemorySupportRepository memory;

    public CpuMemoryQueryService(CatalogProductRepository products, CatalogProductSourceRepository sources,
                                 CpuMemorySupportRepository memory) {
        this.products = products;
        this.sources = sources;
        this.memory = memory;
    }

    public View findByProductId(String id) {
        var product = products.findById(id).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "CATALOG_PRODUCT_NOT_FOUND", "부품을 찾을 수 없습니다."));
        if (product.getType() != PartType.CPU) throw new ApiException(
                HttpStatus.BAD_REQUEST, "CPU_MEMORY_REQUIRES_CPU", "CPU의 메모리 지원 정보만 조회할 수 있습니다.");
        var stored = memory.findByProductId(id);
        // 자료 없음은 미지원이 아니다. 해당 DDR이 없다는 판정은 목록이 완전할 때만 가능하다.
        if (stored.isEmpty()) return new View(id, false, false, null, null, null, List.of(), null);
        var actual = stored.orElseThrow();
        var support = actual.support();
        var source = sources.findById(actual.evidenceSourceId()).orElseThrow();
        return new View(id, true, support.memoryTypesKnown(), support.maxMemoryBytes(), support.channelCount(),
                support.capacityConditions(), support.supportedTypes(), new CatalogDtos.Source(source.getSourceName(),
                source.getExternalId(), source.getSourceRevision(), source.getSourceUrl(), source.getRetrievedAt()));
    }

    /** 원본 payload/출처 내부 ID와 호환 판정은 HTTP 응답에 포함하지 않는다. */
    public record View(String productId, boolean dataAvailable, boolean memoryTypesKnown, Long maxMemoryBytes,
                       Integer channelCount, String capacityConditions,
                       List<CpuMemorySupport.TypeSupport> supportedTypes, CatalogDtos.Source source) {
        public View { supportedTypes = List.copyOf(supportedTypes); }
    }
}
