package com.pcupgradelab.compatibility;

import com.pcupgradelab.catalog.CatalogDtos;
import com.pcupgradelab.catalog.CatalogQueryService;
import com.pcupgradelab.catalog.memory.CpuMemoryQueryService;
import com.pcupgradelab.catalog.support.MotherboardCpuQueryService;
import com.pcupgradelab.common.ApiException;
import com.pcupgradelab.pc.PartType;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        if (request.presentComponents() != null && (request.presentComponents().size() > 3
                || request.presentComponents().stream().anyMatch(type -> type == null || !Set.of("GPU", "PSU", "CASE").contains(type))
                || new HashSet<>(request.presentComponents()).size() != request.presentComponents().size())) {
            throw invalid("추가 검사 부품 목록에는 그래픽카드·파워·케이스를 중복 없이 지정해 주세요.");
        }
        Map<String, CatalogDtos.Detail> details = new LinkedHashMap<>();
        load(details, request.cpuProductId(), PartType.CPU);
        load(details, request.motherboardProductId(), PartType.MOTHERBOARD);
        load(details, request.gpuProductId(), PartType.GPU);
        load(details, request.psuProductId(), PartType.PSU);
        load(details, request.caseProductId(), PartType.CASE);
        for (var ram : request.ram()) {
            if (ram == null || ram.quantity() == null || ram.quantity() < 1 || ram.quantity() > 64) {
                throw invalid("RAM 수량에는 실제 장착할 모듈 수 1~64를 입력해 주세요.");
            }
            load(details, ram.catalogProductId(), PartType.RAM);
        }
        var cpuMemory = request.cpuProductId() == null ? null : memory.findByProductId(request.cpuProductId());
        var cpuSupport = request.cpuProductId() == null || request.motherboardProductId() == null ? null
                : support.find(request.motherboardProductId(), request.cpuProductId());
        boolean legacySelected = request.cpuProductId() != null || request.motherboardProductId() != null || !request.ram().isEmpty();
        var result = !request.hardwareScopeRequested() || legacySelected
                ? new CompatibilityRules(request, details, cpuMemory, cpuSupport).evaluate()
                : new CompatibilityDtos.Result("CPU_MOTHERBOARD_RAM_V1", CompatibilityDtos.Status.COMPATIBLE, false,
                        List.of(), new CompatibilityDtos.MemorySummary(0, java.math.BigInteger.ZERO, null, false),
                        List.of(), List.of(), List.of(), CatalogDtos.ATTRIBUTIONS);
        return request.hardwareScopeRequested() ? new HardwareCompatibilityRules(request, details).extend(result) : result;
    }

    private void load(Map<String, CatalogDtos.Detail> details, String id, PartType expectedType) {
        // 미연결 제품은 추가 확인이다. 명시적으로 지정한 ID가 없거나 종류가 다르면 잘못된 요청이다.
        if (id == null) return;
        var detail = details.computeIfAbsent(id, catalog::findById);
        if (detail.product().type() != expectedType || detail.specification().type() != expectedType) {
            throw invalid("선택한 위치의 부품 종류와 연결한 상품의 종류가 일치하지 않습니다.");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_COMPATIBILITY_INPUT", message);
    }
}
