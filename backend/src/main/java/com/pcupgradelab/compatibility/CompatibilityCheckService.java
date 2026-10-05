package com.pcupgradelab.compatibility;

import com.pcupgradelab.catalog.CatalogDtos;
import com.pcupgradelab.catalog.CatalogQueryService;
import com.pcupgradelab.catalog.memory.CpuMemoryQueryService;
import com.pcupgradelab.catalog.support.MotherboardCpuQueryService;
import com.pcupgradelab.common.ApiException;
import com.pcupgradelab.pc.PartType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 조회만 수행한다. 저장된 PC, 제품 검토 상태, 기준가격, 출처, 지원 정보를 변경하지 않는다. */
@Service
@Transactional(readOnly = true)
public class CompatibilityCheckService {
    private final CatalogQueryService catalog;
    private final CpuMemoryQueryService memory;
    private final MotherboardCpuQueryService support;

    public CompatibilityCheckService(CatalogQueryService catalog, CpuMemoryQueryService memory,
                                      MotherboardCpuQueryService support) {
        this.catalog = catalog; this.memory = memory; this.support = support;
    }

    public CompatibilityDtos.Result check(CompatibilityDtos.Request request) {
        if (request == null || request.ram() == null || request.ram().size() > 64) {
            throw invalid("ram 목록은 필수이며 최대 64개 항목까지 검사할 수 있습니다.");
        }
        Map<String, CatalogDtos.Detail> details = new LinkedHashMap<>();
        load(details, request.cpuProductId(), PartType.CPU);
        load(details, request.motherboardProductId(), PartType.MOTHERBOARD);
        for (var ram : request.ram()) {
            if (ram == null || ram.quantity() == null || ram.quantity() < 1 || ram.quantity() > 64) {
                throw invalid("RAM quantity에는 실제 장착할 모듈 수 1~64를 입력해 주세요.");
            }
            load(details, ram.catalogProductId(), PartType.RAM);
        }
        var cpuMemory = request.cpuProductId() == null ? null : memory.findByProductId(request.cpuProductId());
        var cpuSupport = request.cpuProductId() == null || request.motherboardProductId() == null ? null
                : support.find(request.motherboardProductId(), request.cpuProductId());
        return new CompatibilityRules(request, details, cpuMemory, cpuSupport).evaluate();
    }

    private void load(Map<String, CatalogDtos.Detail> details, String id, PartType expectedType) {
        // 미연결 제품은 추가 확인이다. 명시적으로 지정한 ID가 없거나 종류가 다르면 잘못된 요청이다.
        if (id == null) return;
        var detail = details.computeIfAbsent(id, catalog::findById);
        if (detail.product().type() != expectedType || detail.specification().type() != expectedType) {
            throw invalid("해당 위치의 부품 종류와 catalogProductId의 제품 종류가 일치하지 않습니다.");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_COMPATIBILITY_INPUT", message);
    }
}
