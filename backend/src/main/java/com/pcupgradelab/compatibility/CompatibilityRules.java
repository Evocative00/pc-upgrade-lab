package com.pcupgradelab.compatibility;

import com.pcupgradelab.catalog.CatalogDtos;
import com.pcupgradelab.catalog.CatalogSpecification;
import com.pcupgradelab.catalog.memory.CpuMemoryQueryService;
import com.pcupgradelab.catalog.support.MotherboardCpuQueryService;
import com.pcupgradelab.catalog.support.MotherboardCpuSupport;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static com.pcupgradelab.compatibility.CompatibilityDtos.Status.*;

/** DB와 분리한 공표 규격 검사. 자료 부재·불완전한 목록·다른 BIOS 버전을 미지원으로 추정하지 않는다. */
final class CompatibilityRules {
    private final CompatibilityDtos.Request request;
    private final Map<String, CatalogDtos.Detail> details;
    private final CpuMemoryQueryService.View memory;
    private final MotherboardCpuQueryService.View support;
    private final List<CompatibilityDtos.Check> checks = new ArrayList<>();
    private final List<CompatibilityDtos.Component> components = new ArrayList<>();
    private final List<CompatibilityDtos.SupportCandidate> candidates = new ArrayList<>();
    private final List<String> notices = new ArrayList<>(List.of(
            "CPU·메인보드·RAM의 공표 규격 검사입니다. GPU·파워·케이스·쿨러·모니터 및 전체 PC 동작은 검사하지 않습니다.",
            "RAM quantity는 실제 장착할 모듈 수입니다. 카탈로그의 묶음 구성(moduleCount)을 다시 곱하지 않습니다.",
            "RAM의 표시 속도, XMP/EXPO, 개별 RAM QVL, 모듈 혼용 안정성은 이번 검사에서 보장하지 않습니다."));

    CompatibilityRules(CompatibilityDtos.Request request, Map<String, CatalogDtos.Detail> details,
                       CpuMemoryQueryService.View memory, MotherboardCpuQueryService.View support) {
        this.request = request; this.details = details; this.memory = memory; this.support = support;
    }

    CompatibilityDtos.Result evaluate() {
        var cpuDetail = component("cpuProductId", request.cpuProductId());
        var boardDetail = component("motherboardProductId", request.motherboardProductId());
        var cpu = cpuDetail == null ? null : (CatalogSpecification.Cpu) cpuDetail.specification();
        var board = boardDetail == null ? null : (CatalogSpecification.Motherboard) boardDetail.specification();
        compare("CPU_SOCKET", cpu == null ? null : cpu.socketCode(), board == null ? null : board.socketCode(),
                List.of("cpuProductId", "motherboardProductId"), sources(cpuDetail, boardDetail), "CPU와 메인보드 소켓");
        checkManufacturerSupport();

        BigInteger knownBytes = BigInteger.ZERO;
        int modules = 0;
        boolean capacityComplete = !request.ram().isEmpty();
        var unknownCapacities = new ArrayList<String>();
        if (request.ram().isEmpty()) {
            add("RAM_SELECTION", NEEDS_CHECK, "실제 장착할 RAM을 지정해 주세요.", List.of("ram"), List.of("ram"), List.of());
        }
        for (int i = 0; i < request.ram().size(); i++) {
            var item = request.ram().get(i);
            String field = "ram[" + i + "].catalogProductId";
            var detail = component(field, item.catalogProductId());
            var ram = detail == null ? null : (CatalogSpecification.Ram) detail.specification();
            modules += item.quantity();
            if (ram == null || ram.moduleCapacityBytes() == null) {
                capacityComplete = false; unknownCapacities.add(field);
            } else {
                knownBytes = knownBytes.add(BigInteger.valueOf(ram.moduleCapacityBytes())
                        .multiply(BigInteger.valueOf(item.quantity())));
            }
            checkCpuMemoryType(i, field, ram);
            compare("MOTHERBOARD_RAM_TYPE_" + i, board == null ? null : board.memoryType(),
                    ram == null ? null : ram.memoryType(), List.of("motherboardProductId", field),
                    sources(boardDetail, detail), "메인보드와 RAM의 DDR 종류");
            compare("MOTHERBOARD_RAM_FORM_" + i, board == null ? null : board.memoryFormFactor(),
                    ram == null ? null : ram.moduleFormFactor(), List.of("motherboardProductId", field),
                    sources(boardDetail, detail), "메인보드와 RAM의 장착 규격");
            // 보드의 supportsEcc만으로 RDIMM/LRDIMM 지원이나 CPU별 ECC 동작을 단정하지 않는다.
            boolean ordinaryMemory = ram != null && Boolean.FALSE.equals(ram.isEcc())
                    && "UNBUFFERED".equals(ram.bufferType());
            add("RAM_MODULE_FEATURES_" + i, ordinaryMemory ? COMPATIBLE : NEEDS_CHECK,
                    ordinaryMemory ? "일반 비 ECC·Unbuffered RAM입니다."
                            : "ECC·Registered/Load-reduced 여부와 CPU·메인보드의 지원 조건을 별도로 확인해야 합니다.",
                    List.of(field), ordinaryMemory ? List.of() : List.of(field, "manufacturerMemoryModuleSupport"),
                    sources(detail));
            addSpeedNotice(i, ram);
        }
        var summary = new CompatibilityDtos.MemorySummary(modules, knownBytes,
                capacityComplete ? knownBytes : null, capacityComplete);
        checkSlots(board, boardDetail, summary);
        checkCapacity("CPU_RAM_CAPACITY", memory == null || !memory.dataAvailable() ? null : memory.maxMemoryBytes(),
                "CPU의 공표 최대 메모리 용량", summary, unknownCapacities,
                List.of("cpuProductId", "ram"), "cpuMemorySupport.maxMemoryBytes", memoryEvidence(),
                memory == null ? null : memory.capacityConditions());
        checkCapacity("MOTHERBOARD_RAM_CAPACITY", board == null ? null : board.maxMemoryBytes(),
                "메인보드의 공표 최대 메모리 용량", summary, unknownCapacities,
                List.of("motherboardProductId", "ram"), "motherboardSpecification.maxMemoryBytes",
                sources(boardDetail), null);
        var status = checks.stream().anyMatch(check -> check.status() == INCOMPATIBLE) ? INCOMPATIBLE
                : checks.stream().anyMatch(check -> check.status() == NEEDS_CHECK) ? NEEDS_CHECK : COMPATIBLE;
        return new CompatibilityDtos.Result("CPU_MOTHERBOARD_RAM_V1", status, false, checks, summary,
                components, candidates, notices, CatalogDtos.ATTRIBUTIONS);
    }

    private CatalogDtos.Detail component(String field, String id) {
        var detail = id == null ? null : details.get(id);
        if (detail == null) {
            add("CATALOG_LINK_" + field, NEEDS_CHECK, "부품을 정확한 카탈로그 제품과 연결해야 합니다.",
                    List.of(field), List.of(field), List.of());
        } else {
            components.add(new CompatibilityDtos.Component(field, id, detail.product().modelName(),
                    detail.product().verificationStatus()));
        }
        return detail;
    }

    private void compare(String code, String left, String right, List<String> fields,
                         List<CatalogDtos.Source> evidence, String label) {
        if (left == null || right == null) {
            add(code, NEEDS_CHECK, label + " 정보가 부족합니다.", fields, fields, evidence);
        } else {
            boolean same = left.equals(right);
            add(code, same ? COMPATIBLE : INCOMPATIBLE, label + (same ? "가 일치합니다: " : "가 다릅니다: ")
                    + left + (same ? "" : " / " + right), fields, List.of(), evidence);
        }
    }

    private void checkCpuMemoryType(int index, String field, CatalogSpecification.Ram ram) {
        var fields = List.of("cpuProductId", field);
        if (ram == null || ram.memoryType() == null || memory == null || !memory.dataAvailable()) {
            add("CPU_RAM_TYPE_" + index, NEEDS_CHECK, "CPU 또는 RAM의 DDR 지원 자료가 부족합니다.",
                    fields, List.of("cpuMemorySupport.supportedTypes", field), memoryEvidence());
            return;
        }
        boolean listed = memory.supportedTypes().stream().anyMatch(type -> type.memoryType().name().equals(ram.memoryType()));
        add("CPU_RAM_TYPE_" + index, listed ? COMPATIBLE : memory.memoryTypesKnown() ? INCOMPATIBLE : NEEDS_CHECK,
                listed ? "CPU가 " + ram.memoryType() + "를 지원합니다."
                        : memory.memoryTypesKnown() ? "CPU의 전체 DDR 지원 목록에 " + ram.memoryType() + "가 없습니다."
                        : "DDR 지원 목록이 불완전하여 " + ram.memoryType() + " 미지원으로 판단할 수 없습니다.",
                fields, listed || memory.memoryTypesKnown() ? List.of() : List.of("cpuMemorySupport.supportedTypes"),
                memoryEvidence());
    }

    private void checkSlots(CatalogSpecification.Motherboard board, CatalogDtos.Detail detail,
                            CompatibilityDtos.MemorySummary summary) {
        var fields = List.of("motherboardProductId", "ram");
        if (request.ram().isEmpty() || board == null || board.memorySlotCount() == null) {
            add("RAM_SLOT_COUNT", NEEDS_CHECK, "RAM 구성 또는 메인보드 슬롯 수를 확인해야 합니다.",
                    fields, List.of("ram", "motherboardSpecification.memorySlotCount"), sources(detail));
        } else {
            boolean fits = summary.installedModuleCount() <= board.memorySlotCount();
            add("RAM_SLOT_COUNT", fits ? COMPATIBLE : INCOMPATIBLE,
                    "실제 장착 모듈 " + summary.installedModuleCount() + "개 / 메인보드 슬롯 " + board.memorySlotCount()
                            + "개" + (fits ? ": 슬롯 수 이내입니다." : ": 슬롯 수를 초과합니다."),
                    fields, List.of(), sources(detail));
        }
    }

    private void checkCapacity(String code, Long maximum, String label, CompatibilityDtos.MemorySummary summary,
                               List<String> unknown, List<String> fields, String missingLimit,
                               List<CatalogDtos.Source> evidence, String conditions) {
        String suffix = conditions == null ? "" : " 적용 조건: " + conditions;
        if (maximum != null && summary.knownCapacityBytes().compareTo(BigInteger.valueOf(maximum)) > 0) {
            add(code, INCOMPATIBLE, "확인된 RAM 용량 " + summary.knownCapacityBytes()
                    + " bytes만으로도 " + label + " " + maximum + " bytes를 초과합니다." + suffix,
                    fields, List.of(), evidence);
        } else if (maximum == null || !summary.capacityComplete()) {
            var required = new ArrayList<>(unknown);
            if (request.ram().isEmpty()) required.add("ram");
            if (maximum == null) required.add(missingLimit);
            add(code, NEEDS_CHECK, "총 RAM 용량 또는 " + label + "을 확인해야 합니다." + suffix,
                    fields, required, evidence);
        } else {
            add(code, COMPATIBLE, "총 RAM 용량 " + summary.totalCapacityBytes() + " bytes / " + label
                    + " " + maximum + " bytes 이내입니다." + suffix, fields, List.of(), evidence);
        }
    }

    private void checkManufacturerSupport() {
        var fields = List.of("cpuProductId", "motherboardProductId", "motherboardRevision", "cpuStepping");
        if (support == null || !support.dataAvailable()) {
            add("CPU_MANUFACTURER_SUPPORT", NEEDS_CHECK, "제조사의 해당 CPU·메인보드 지원 자료가 없습니다. 자료 부재는 미지원이 아닙니다.",
                    fields, List.of("manufacturerCpuSupport"), List.of());
            addUnresolvedBios();
            return;
        }
        for (var profile : support.profiles()) {
            for (var row : profile.entries()) {
                var entry = row.support();
                candidates.add(new CompatibilityDtos.SupportCandidate(profile.revisionScope(), profile.hardwareRevision(),
                        profile.conditions(), entry.variantKey(), entry.supportStatus(), entry.cpuStepping(),
                        entry.biosRequirement(), entry.minimumBiosVersion(), entry.manufacturerBiosLabel(),
                        entry.sourceUrl(), entry.conditions(), profile.source()));
            }
        }
        var profiles = support.profiles().stream().filter(profile ->
                profile.revisionScope() == MotherboardCpuSupport.RevisionScope.MODEL
                || Objects.equals(profile.hardwareRevision(), request.motherboardRevision())).toList();
        var evidence = support.profiles().stream().map(MotherboardCpuQueryService.Profile::source)
                .filter(Objects::nonNull).distinct().toList();
        if (profiles.isEmpty()) {
            add("CPU_MANUFACTURER_SUPPORT", NEEDS_CHECK,
                    request.motherboardRevision() == null ? "실제 메인보드 리비전을 확인해야 지원 목록을 적용할 수 있습니다."
                            : "지정한 메인보드 리비전의 지원 자료가 없습니다. 다른 리비전의 목록을 적용하지 않습니다.",
                    fields, List.of("motherboardRevision", "manufacturerCpuSupport"), evidence);
            addUnresolvedBios();
            return;
        }
        var recorded = profiles.stream().flatMap(profile -> profile.entries().stream())
                .map(MotherboardCpuQueryService.Entry::support).toList();
        var selected = recorded.stream().filter(entry -> entry.cpuStepping() == null
                || request.cpuStepping() != null && steppingMatches(entry.cpuStepping(), request.cpuStepping())).toList();
        if (selected.isEmpty() || selected.stream().anyMatch(entry ->
                entry.supportStatus() != MotherboardCpuSupport.SupportStatus.LISTED)) {
            add("CPU_MANUFACTURER_SUPPORT", NEEDS_CHECK,
                    recorded.isEmpty() ? "해당 CPU의 지원 기록이 없습니다. 목록은 일부 발췌이므로 미지원으로 판단하지 않습니다."
                            : request.cpuStepping() == null ? "CPU 스테핑을 확인해야 해당 지원 항목을 선택할 수 있습니다."
                            : "해당 CPU 스테핑의 확인된 지원 항목이 없습니다. 미지원으로 추정하지 않습니다.",
                    fields, List.of("cpuStepping", "manufacturerCpuSupport"), evidence);
            addUnresolvedBios();
            return;
        }
        // 스테핑 미입력 시 일반 모델 행과 별도 스테핑 행이 섞여 있으면 조건을 생략하지 않는다.
        if (request.cpuStepping() == null && recorded.stream().anyMatch(entry -> entry.cpuStepping() != null)) {
            add("CPU_MANUFACTURER_SUPPORT", NEEDS_CHECK, "제조사가 스테핑별로 구분한 CPU입니다. 실제 CPU 스테핑을 확인해 주세요.",
                    fields, List.of("cpuStepping"), evidence);
            addUnresolvedBios();
            return;
        }
        add("CPU_MANUFACTURER_SUPPORT", COMPATIBLE, "해당 모델·리비전·스테핑 범위에서 제조사의 CPU 지원 항목이 확인되었습니다.",
                fields, List.of(), evidence);
        var biosFields = List.of("motherboardProductId", "motherboardRevision", "cpuStepping", "currentBiosVersion");
        if (selected.stream().anyMatch(entry -> entry.biosRequirement() == MotherboardCpuSupport.BiosRequirement.UNKNOWN)) {
            add("CPU_BIOS", NEEDS_CHECK, "최소 BIOS가 불명확하거나 베타 BIOS 확인이 필요한 항목입니다. 제조사 안내를 확인해 주세요.",
                    biosFields, List.of("manufacturerBiosRequirement"), evidence);
        } else if (selected.stream().allMatch(entry -> entry.biosRequirement() == MotherboardCpuSupport.BiosRequirement.ALL
                || entry.minimumBiosVersion().equals(request.currentBiosVersion()))) {
            add("CPU_BIOS", COMPATIBLE, "제조사의 ALL 표기 또는 해당 최소 BIOS와 현재 버전의 정확한 일치가 확인되었습니다.",
                    biosFields, List.of(), evidence);
        } else {
            add("CPU_BIOS", NEEDS_CHECK, request.currentBiosVersion() == null
                            ? "현재 BIOS를 확인해 주세요. 리비전·스테핑별 최소 BIOS는 cpuSupportCandidates에 있습니다."
                            : "현재 BIOS가 공표된 최소 버전과 다릅니다. 제조사 버전 이력 없이 문자열 크기만으로 더 최신인지 판단하지 않습니다.",
                    biosFields, List.of("currentBiosVersion", "manufacturerBiosVersionHistory"), evidence);
        }
    }

    private void addUnresolvedBios() {
        add("CPU_BIOS", NEEDS_CHECK, "CPU·보드 리비전·스테핑의 지원 항목을 확인한 뒤 BIOS 조건을 검사해야 합니다.",
                List.of("currentBiosVersion"), List.of("manufacturerCpuSupport"), List.of());
    }

    private static boolean steppingMatches(String reported, String actual) {
        // 제조사가 명시한 C0 / H0 목록과 B-0 표기만 정규화한다. 다른 스테핑을 추정하지 않는다.
        return Arrays.stream(reported.split("/")).anyMatch(value -> steppingCode(value).equals(steppingCode(actual)));
    }

    private static String steppingCode(String value) {
        return value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private void addSpeedNotice(int index, CatalogSpecification.Ram ram) {
        if (ram == null || ram.dataRateMts() == null || memory == null || !memory.dataAvailable()) return;
        memory.supportedTypes().stream().filter(type -> type.memoryType().name().equals(ram.memoryType())
                && type.maxStandardDataRateMts() != null && ram.dataRateMts() > type.maxStandardDataRateMts())
                .forEach(type -> notices.add("ram[" + index + "]의 표시 속도 " + ram.dataRateMts()
                        + " MT/s는 CPU 공표 기본 속도 " + type.maxStandardDataRateMts()
                        + " MT/s보다 높습니다. 이것만으로 호환 불가로 판정하지 않으며 실제 동작 속도는 별도 확인합니다. "
                        + type.dataRateConditions()));
    }

    private List<CatalogDtos.Source> memoryEvidence() {
        return memory == null || memory.source() == null ? List.of() : List.of(memory.source());
    }

    private static List<CatalogDtos.Source> sources(CatalogDtos.Detail... values) {
        var result = new LinkedHashSet<CatalogDtos.Source>();
        for (var value : values) if (value != null) result.addAll(value.sources());
        return List.copyOf(result);
    }

    private void add(String code, CompatibilityDtos.Status status, String message, List<String> fields,
                     List<String> required, List<CatalogDtos.Source> evidence) {
        checks.add(new CompatibilityDtos.Check(code, status, message, fields,
                required.stream().distinct().toList(), evidence));
    }
}
