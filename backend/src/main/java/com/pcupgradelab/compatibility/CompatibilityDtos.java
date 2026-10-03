package com.pcupgradelab.compatibility;

import com.pcupgradelab.catalog.CatalogDtos;
import com.pcupgradelab.catalog.CatalogVerificationStatus;
import com.pcupgradelab.catalog.support.MotherboardCpuSupport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 임시 구성 검사 전용 계약. quantity는 구매 묶음 수가 아니라 실제 장착할 RAM 모듈 수다. */
public final class CompatibilityDtos {
    private CompatibilityDtos() { }

    public enum Status { COMPATIBLE, INCOMPATIBLE, NEEDS_CHECK }

    public record Request(
            @Size(max = 128) String cpuProductId,
            @Size(max = 128) String motherboardProductId,
            @NotNull @Size(max = 64) List<@NotNull @Valid RamInput> ram,
            @Size(max = 48) String motherboardRevision,
            @Size(max = 64) String cpuStepping,
            @Size(max = 64) String currentBiosVersion) {
        public Request {
            cpuProductId = optional(cpuProductId);
            motherboardProductId = optional(motherboardProductId);
            motherboardRevision = optional(motherboardRevision);
            cpuStepping = optional(cpuStepping);
            currentBiosVersion = optional(currentBiosVersion);
            // null 항목은 @Valid에서 필드 위치와 함께 검증한다.
            if (ram != null) ram = Collections.unmodifiableList(new ArrayList<>(ram));
        }
    }

    public record RamInput(@Size(max = 128) String catalogProductId,
                           @NotNull @Min(1) @Max(64) Integer quantity) {
        public RamInput { catalogProductId = optional(catalogProductId); }
    }

    public record Check(String code, Status status, String message, List<String> affectedFields,
                        List<String> requiredInformation, List<CatalogDtos.Source> evidence) {
        public Check {
            affectedFields = List.copyOf(affectedFields);
            requiredInformation = List.copyOf(requiredInformation);
            evidence = List.copyOf(evidence);
        }
    }

    public record Component(String field, String productId, String modelName,
                            CatalogVerificationStatus catalogVerificationStatus) { }

    /** 일부 용량이 불명확하면 totalCapacityBytes=null. knownCapacityBytes는 확인된 모듈의 합계다. */
    public record MemorySummary(int installedModuleCount, BigInteger knownCapacityBytes,
                                BigInteger totalCapacityBytes, boolean capacityComplete) { }

    /** 리비전/스테핑별 원문을 보존한다. 서로 다른 최소 BIOS를 하나의 문자열로 합치지 않는다. */
    public record SupportCandidate(MotherboardCpuSupport.RevisionScope revisionScope, String hardwareRevision,
                                   String profileConditions, String variantKey,
                                   MotherboardCpuSupport.SupportStatus supportStatus, String cpuStepping,
                                   MotherboardCpuSupport.BiosRequirement biosRequirement, String minimumBiosVersion,
                                   String manufacturerBiosLabel, String sourceUrl, String conditions,
                                   CatalogDtos.Source evidence) { }

    public record Result(String scope, Status status, boolean fullPcCompatibilityChecked,
                         List<Check> checks, MemorySummary memory, List<Component> components,
                         List<SupportCandidate> cpuSupportCandidates, List<String> notices,
                         List<CatalogDtos.Attribution> attributions) {
        public Result {
            checks = List.copyOf(checks);
            components = List.copyOf(components);
            cpuSupportCandidates = List.copyOf(cpuSupportCandidates);
            notices = List.copyOf(notices);
            attributions = List.copyOf(attributions);
        }
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
