package com.pcupgradelab.compatibility;

import com.pcupgradelab.catalog.CatalogDtos;
import com.pcupgradelab.catalog.CatalogSpecification;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.pcupgradelab.compatibility.CompatibilityDtos.Status.*;

/** 공표된 장착 공간·직접 연결 케이블만 검사한다. 어댑터나 미확인 배선 조건을 만들어 내지 않는다. */
final class HardwareCompatibilityRules {
    static final String SCOPE = "CPU_MOTHERBOARD_RAM_GPU_PSU_CASE_V2";
    private final CompatibilityDtos.Request request;
    private final Map<String, CatalogDtos.Detail> details;
    private final List<CompatibilityDtos.Check> checks = new ArrayList<>();
    private final List<CompatibilityDtos.Component> components = new ArrayList<>();

    HardwareCompatibilityRules(CompatibilityDtos.Request request, Map<String, CatalogDtos.Detail> details) {
        this.request = request;
        this.details = details;
    }

    CompatibilityDtos.Result extend(CompatibilityDtos.Result base) {
        boolean gpuPresent = present("GPU", request.gpuProductId());
        boolean psuPresent = present("PSU", request.psuProductId());
        boolean casePresent = present("CASE", request.caseProductId());
        var gpuDetail = component(gpuPresent, "gpuProductId", request.gpuProductId(), "그래픽카드");
        var psuDetail = component(psuPresent, "psuProductId", request.psuProductId(), "파워");
        var caseDetail = component(casePresent, "caseProductId", request.caseProductId(), "케이스");
        var boardDetail = request.motherboardProductId() == null ? null : details.get(request.motherboardProductId());
        var gpu = gpuDetail == null ? null : (CatalogSpecification.Gpu) gpuDetail.specification();
        var psu = psuDetail == null ? null : (CatalogSpecification.Psu) psuDetail.specification();
        var enclosure = caseDetail == null ? null : (CatalogSpecification.Case) caseDetail.specification();
        var board = boardDetail == null ? null : (CatalogSpecification.Motherboard) boardDetail.specification();
        if (gpu != null && !psuPresent && !casePresent) add("GPU_PAIR_SELECTION", NEEDS_CHECK,
                "파워 또는 케이스를 선택하면 그래픽카드의 전원·장착 조건을 함께 검사할 수 있습니다.",
                List.of("gpuProductId"), List.of("psuProductId", "caseProductId"), sources(gpuDetail));
        if (psu != null && !gpuPresent && !casePresent) add("PSU_PAIR_SELECTION", NEEDS_CHECK,
                "그래픽카드 또는 케이스를 선택하면 파워의 전원·장착 조건을 함께 검사할 수 있습니다.",
                List.of("psuProductId"), List.of("gpuProductId", "caseProductId"), sources(psuDetail));
        if (casePresent) checkBoardCase(board, enclosure, boardDetail, caseDetail);
        if (gpuPresent && casePresent) checkGpuCase(gpu, enclosure, gpuDetail, caseDetail);
        if (psuPresent && casePresent) checkPsuCase(psu, enclosure, psuDetail, caseDetail);
        if (gpuPresent && psuPresent) checkGpuPsu(gpu, psu, gpuDetail, psuDetail);

        var combined = new ArrayList<>(base.checks());
        combined.addAll(checks);
        var allComponents = new ArrayList<>(base.components());
        allComponents.addAll(components);
        var notices = new ArrayList<>(base.notices());
        notices.removeIf(notice -> notice.startsWith("CPU·메인보드·RAM의 공표 규격 검사입니다."));
        notices.add("선택한 부품의 공표 규격과 케이스 장착 공간·파워 직접 연결 조건을 검사합니다. 전체 PC 동작을 보장하지 않습니다.");
        notices.add("메인보드의 그래픽카드 슬롯·파워의 CPU 전원 연결·쿨러·저장장치·피크 전력과 전체 시스템 전력 여유는 이번 검사 범위에 포함되지 않습니다.");
        var status = combined.stream().anyMatch(check -> check.status() == INCOMPATIBLE) ? INCOMPATIBLE
                : combined.stream().anyMatch(check -> check.status() == NEEDS_CHECK) ? NEEDS_CHECK : COMPATIBLE;
        return new CompatibilityDtos.Result(SCOPE, status, false, combined, base.memory(), allComponents,
                base.cpuSupportCandidates(), notices, base.attributions());
    }

    private boolean present(String type, String id) {
        return id != null || request.presentComponents() != null && request.presentComponents().contains(type);
    }

    private CatalogDtos.Detail component(boolean present, String field, String id, String label) {
        if (!present) return null;
        var detail = id == null ? null : details.get(id);
        if (detail == null) add("CATALOG_LINK_" + field, NEEDS_CHECK,
                label + "를 정확한 카탈로그 상품에 연결해야 규격을 검사할 수 있습니다.", List.of(field), List.of(field), List.of());
        else components.add(new CompatibilityDtos.Component(field, id, detail.product().modelName(), detail.product().verificationStatus()));
        return detail;
    }

    private void checkBoardCase(CatalogSpecification.Motherboard board, CatalogSpecification.Case enclosure,
                                CatalogDtos.Detail boardDetail, CatalogDtos.Detail caseDetail) {
        var fields = List.of("motherboardProductId", "caseProductId");
        var evidence = sources(boardDetail, caseDetail);
        formFactor("MOTHERBOARD_CASE_FORM", board == null ? null : board.formFactor(),
                enclosure != null && enclosure.motherboardFormFactorsKnown(),
                enclosure == null ? List.of() : enclosure.motherboardFormFactors(), "메인보드 장착 규격",
                fields, evidence);
    }

    private void checkGpuCase(CatalogSpecification.Gpu gpu, CatalogSpecification.Case enclosure,
                              CatalogDtos.Detail gpuDetail, CatalogDtos.Detail caseDetail) {
        var fields = List.of("gpuProductId", "caseProductId");
        var evidence = sources(gpuDetail, caseDetail);
        boolean unconditional = enclosure != null && enclosure.gpuClearanceUnconditional();
        dimension("GPU_CASE_LENGTH", gpu == null ? null : gpu.lengthMm(), enclosure == null ? null : enclosure.maxGpuLengthMm(),
                unconditional, "그래픽카드 길이", "mm", fields, evidence);
        dimension("GPU_CASE_HEIGHT", gpu == null ? null : gpu.heightMm(), enclosure == null ? null : enclosure.maxGpuHeightMm(),
                unconditional, "그래픽카드 높이", "mm", fields, evidence);
        dimension("GPU_CASE_THICKNESS", gpu == null ? null : gpu.thicknessMm(), enclosure == null ? null : enclosure.maxGpuThicknessMm(),
                unconditional, "그래픽카드 두께", "mm", fields, evidence);
        dimension("GPU_CASE_SLOTS", gpu == null ? null : gpu.slotWidth(),
                enclosure == null || enclosure.expansionSlotCount() == null ? null : BigDecimal.valueOf(enclosure.expansionSlotCount()),
                true, "그래픽카드 슬롯 폭", "슬롯", fields, evidence);
        if (enclosure != null && (!unconditional || enclosure.gpuClearanceConditions() != null)) add("GPU_CASE_INSTALLATION", NEEDS_CHECK,
                "라디에이터·팬·드라이브 베이와 전원 케이블의 여유 공간을 포함해 실제 그래픽카드 장착 조건을 확인해 주세요.",
                fields, List.of("gpuInstallationConditions"), evidence);
    }

    private void checkPsuCase(CatalogSpecification.Psu psu, CatalogSpecification.Case enclosure,
                              CatalogDtos.Detail psuDetail, CatalogDtos.Detail caseDetail) {
        var fields = List.of("psuProductId", "caseProductId");
        var evidence = sources(psuDetail, caseDetail);
        formFactor("PSU_CASE_FORM", psu == null ? null : psu.formFactor(),
                enclosure != null && enclosure.psuFormFactorsKnown(), enclosure == null ? List.of() : enclosure.psuFormFactors(),
                "파워 장착 규격", fields, evidence);
        dimension("PSU_CASE_LENGTH", psu == null ? null : psu.lengthMm(), enclosure == null ? null : enclosure.maxPsuLengthMm(),
                enclosure != null && enclosure.psuClearanceUnconditional(), "파워 길이", "mm", fields, evidence);
        if (enclosure != null && (!enclosure.psuClearanceUnconditional() || enclosure.psuClearanceConditions() != null)) add("PSU_CASE_INSTALLATION", NEEDS_CHECK,
                "드라이브 베이·케이블 배선과 파워 장착 공간의 적용 조건을 확인해 주세요.",
                fields, List.of("psuInstallationConditions"), evidence);
    }

    private void formFactor(String code, String actual, boolean complete, List<String> supported, String label,
                            List<String> fields, List<CatalogDtos.Source> evidence) {
        if (actual == null || !complete) add(code, NEEDS_CHECK, label + " 또는 케이스의 전체 지원 목록을 확인해야 합니다.", fields, fields, evidence);
        else if (!supported.contains(actual)) add(code, INCOMPATIBLE,
                "케이스의 공표 지원 목록에 선택한 " + label + "이 없습니다.", fields, List.of(), evidence);
        else if (actual.equals("E_ATX")) add(code, NEEDS_CHECK,
                "확장 ATX 메인보드는 모델마다 폭이 다르므로 실제 치수와 케이스의 장착 조건을 확인해야 합니다.", fields, fields, evidence);
        else add(code, COMPATIBLE, "케이스가 선택한 " + label + "을 지원합니다.", fields, List.of(), evidence);
    }

    private void dimension(String code, BigDecimal actual, BigDecimal maximum, boolean unconditional,
                           String label, String unit, List<String> fields, List<CatalogDtos.Source> evidence) {
        if (actual == null || maximum == null) add(code, NEEDS_CHECK, label + " 또는 케이스의 허용 공간을 확인해야 합니다.", fields, fields, evidence);
        else if (!unconditional) add(code, NEEDS_CHECK,
                label + " " + number(actual) + " " + unit + " / 케이스 공표값 " + number(maximum) + " " + unit
                        + ": 부가 장치에 따라 달라지는 장착 조건을 확인해야 합니다.", fields, List.of("caseInstallationConditions"), evidence);
        else add(code, actual.compareTo(maximum) > 0 ? INCOMPATIBLE : COMPATIBLE,
                label + " " + number(actual) + " " + unit + " / 케이스 허용값 " + number(maximum) + " " + unit
                        + (actual.compareTo(maximum) > 0 ? ": 허용 공간을 초과합니다." : ": 공표된 허용 공간 이내입니다."),
                fields, List.of(), evidence);
    }

    private void checkGpuPsu(CatalogSpecification.Gpu gpu, CatalogSpecification.Psu psu,
                             CatalogDtos.Detail gpuDetail, CatalogDtos.Detail psuDetail) {
        var fields = List.of("gpuProductId", "psuProductId");
        var evidence = sources(gpuDetail, psuDetail);
        if (gpu == null || psu == null || gpu.psuRequirementW() == null || psu.ratedPowerW() == null) add("GPU_PSU_WATTAGE", NEEDS_CHECK,
                "그래픽카드의 공표 시스템 요구 출력과 파워의 정격 출력을 확인해야 합니다.", fields, fields, evidence);
        else {
            boolean enough = psu.ratedPowerW() >= gpu.psuRequirementW();
            var status = enough ? COMPATIBLE : "MINIMUM".equals(gpu.psuRequirementBasis()) ? INCOMPATIBLE : NEEDS_CHECK;
            add("GPU_PSU_WATTAGE", status,
                    "파워 정격 " + psu.ratedPowerW() + " W / 그래픽카드 제조사 "
                            + ("MINIMUM".equals(gpu.psuRequirementBasis()) ? "최소" : "권장") + " 시스템 출력 " + gpu.psuRequirementW() + " W"
                            + (enough ? ": 공표 요구 출력 이상입니다. 전체 PC 전력 여유는 별도 확인이 필요합니다."
                            : status == INCOMPATIBLE ? ": 공표 최소 출력에 미달합니다." : ": 권장 출력보다 낮아 실제 시스템 조건을 확인해야 합니다."),
                    fields, enough || status == INCOMPATIBLE ? List.of() : List.of("systemPowerConditions"), evidence);
        }
        if (gpu == null || !gpu.powerConnectorsKnown()) {
            add("GPU_PSU_CONNECTORS", NEEDS_CHECK, "그래픽카드 보조전원 단자와 파워의 제공 케이블 목록을 확인해야 합니다.", fields, fields, evidence);
            return;
        }
        if (gpu.powerConnectors().isEmpty()) {
            add("GPU_PSU_CONNECTORS", COMPATIBLE, "그래픽카드는 별도 보조전원 단자를 요구하지 않습니다. 메인보드 슬롯의 전원 공급은 별도 검사 대상입니다.", fields, List.of(), evidence);
            return;
        }
        if (psu == null || !psu.powerConnectorsKnown()) {
            add("GPU_PSU_CONNECTORS", NEEDS_CHECK, "파워가 제공하는 보조전원 케이블 목록을 확인해야 합니다.", fields, fields, evidence);
            return;
        }
        var needs = new LinkedHashMap<String, Integer>();
        for (var connector : gpu.powerConnectors()) needs.merge(connector.connectorType(), connector.connectorCount(), Integer::sum);
        boolean unspecified16 = needs.remove("PCIE_16PIN_UNSPECIFIED") != null;
        var plugs = allocate(needs, psu.powerConnectors(), false);
        if (!plugs.complete()) {
            boolean missing16 = plugs.missingTypes().stream().noneMatch(type -> type.equals("PCIE_8PIN") || type.equals("PCIE_6PIN"));
            add("GPU_PSU_CONNECTORS", missing16 ? NEEDS_CHECK : INCOMPATIBLE,
                    missing16 ? "그래픽카드와 같은 16핀 규격의 직접 연결 케이블을 확인해야 합니다. 다른 16핀 규격이나 어댑터는 확인 근거 없이 대체하지 않습니다."
                            : "파워가 제공하는 직접 연결 보조전원 플러그 수가 부족합니다. 분배 케이블이나 변환 어댑터의 사용은 별도 확인이 필요합니다.",
                    fields, missing16 ? List.of("gpuPowerCableEvidence") : List.of(), evidence);
            return;
        }
        if (unspecified16) {
            add("GPU_PSU_CONNECTORS", NEEDS_CHECK, "그래픽카드의 16핀 세부 규격을 확인해야 합니다. 모양만으로 케이블이나 변환 어댑터의 호환성을 확정하지 않습니다.", fields, fields, evidence);
            return;
        }
        var cables = allocate(needs, psu.powerConnectors(), true);
        if (!cables.complete()) {
            add("GPU_PSU_CONNECTORS", NEEDS_CHECK, "플러그 수는 맞지만 독립된 전원 케이블 수가 부족하거나 미확인입니다. 하나의 케이블에 여러 단자를 연결하는 배선은 제조사 근거를 확인해야 합니다.",
                    fields, List.of("independentGpuPowerCables"), evidence);
            return;
        }
        add("GPU_PSU_CONNECTORS", COMPATIBLE, "같은 보조전원 규격의 플러그와 독립 케이블 수가 충족됩니다. 제공 케이블의 실제 장착 상태를 확인해 주세요.", fields, List.of(), evidence);
        if (!cables.powerComplete() || gpu.cardPowerW() == null) add("GPU_PSU_CABLE_POWER", NEEDS_CHECK,
                "그래픽카드 공표 소비전력과 케이블별 허용 출력을 확인해야 합니다.", fields, List.of("gpuPowerCableRating"), evidence);
        else {
            boolean enoughTotal = new BigDecimal(cables.powerW()).compareTo(gpu.cardPowerW()) >= 0;
            // 여러 케이블의 출력 합계만으로 개별 케이블의 과부하가 없다고 판단하지 않는다.
            // 단자별 소비전력 자료가 없는 현재 계약에서는 각 케이블이 카드 전체 공표 전력을 감당하는 경우만 확정한다.
            boolean enoughEach = new BigDecimal(cables.minimumCablePowerW()).compareTo(gpu.cardPowerW()) >= 0;
            boolean enough = enoughTotal && enoughEach;
            add("GPU_PSU_CABLE_POWER", enough ? COMPATIBLE : NEEDS_CHECK,
                    "사용할 독립 케이블의 공표 출력 합계 " + cables.powerW() + " W / 그래픽카드 공표 소비전력 " + number(gpu.cardPowerW()) + " W"
                            + (enough ? ": 각 케이블이 카드 공표 전력 이상을 전달할 수 있습니다. 실제 배선과 피크 부하는 별도 확인 대상입니다."
                            : enoughTotal ? ": 합계는 충분하지만 단자별 전력 분배와 개별 케이블의 허용 출력을 확인해야 합니다."
                            : ": 메인보드 슬롯과 보조전원 단자 사이의 전력 분배·케이블 허용 조건을 확인해야 합니다."),
                    fields, enough ? List.of() : List.of("gpuPowerDistribution"), evidence);
        }
    }

    private record Allocation(boolean complete, List<String> missingTypes, boolean powerComplete,
                              BigInteger powerW, BigInteger minimumCablePowerW) { }

    /** 6+2핀 한 플러그를 6핀과 8핀 요구에 동시에 사용하지 않는다. 독립 케이블도 같은 방식으로 배정한다. */
    private static Allocation allocate(Map<String, Integer> needs, List<CatalogSpecification.PsuPowerConnector> provided, boolean independent) {
        var remaining = new int[provided.size()];
        for (int i = 0; i < provided.size(); i++) remaining[i] = independent
                ? Objects.requireNonNullElse(provided.get(i).independentCableCount(), 0) : provided.get(i).connectorCount();
        var missing = new ArrayList<String>();
        boolean powerComplete = true;
        BigInteger power = BigInteger.ZERO;
        BigInteger minimumPower = null;
        for (String type : List.of("PCIE_8PIN", "PCIE_6PIN", "PCIE_12VHPWR", "PCIE_12V_2X6")) {
            int needed = needs.getOrDefault(type, 0);
            for (int pass = 0; pass < 2 && needed > 0; pass++) {
                for (int i = 0; i < provided.size() && needed > 0; i++) {
                    var available = provided.get(i);
                    boolean match = pass == 0 ? available.connectorType().equals(type)
                            : (type.equals("PCIE_8PIN") || type.equals("PCIE_6PIN")) && available.connectorType().equals("PCIE_6_2PIN");
                    if (!match) continue;
                    int used = Math.min(needed, remaining[i]);
                    remaining[i] -= used;
                    needed -= used;
                    if (used > 0) {
                        if (available.maxPowerW() == null) powerComplete = false;
                        else {
                            var rating = BigInteger.valueOf(available.maxPowerW());
                            power = power.add(rating.multiply(BigInteger.valueOf(used)));
                            minimumPower = minimumPower == null ? rating : minimumPower.min(rating);
                        }
                    }
                }
            }
            if (needed > 0) missing.add(type);
        }
        return new Allocation(missing.isEmpty(), missing, powerComplete, power, minimumPower);
    }

    private static String number(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static List<CatalogDtos.Source> sources(CatalogDtos.Detail... entries) {
        return java.util.Arrays.stream(entries).filter(Objects::nonNull).flatMap(entry -> entry.sources().stream()).distinct().toList();
    }
    private void add(String code, CompatibilityDtos.Status status, String message, List<String> fields,
                     List<String> required, List<CatalogDtos.Source> evidence) {
        checks.add(new CompatibilityDtos.Check(code, status, message, fields, required, evidence));
    }
}
