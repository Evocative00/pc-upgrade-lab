package com.pcupgradelab.catalog.memory;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** 공표된 CPU 메모리 한계. 속도는 MT/s이며 XMP/EXPO 속도나 호환 판정 결과가 아니다. */
public record CpuMemorySupport(boolean memoryTypesKnown, Long maxMemoryBytes, Integer channelCount,
                               String capacityConditions, List<TypeSupport> supportedTypes) {
    public CpuMemorySupport {
        if (maxMemoryBytes != null && maxMemoryBytes <= 0) {
            throw new IllegalArgumentException("maxMemoryBytes must be positive or null");
        }
        if (channelCount != null && (channelCount <= 0 || channelCount > Short.MAX_VALUE)) {
            throw new IllegalArgumentException("channelCount must be 1..32767 or null");
        }
        capacityConditions = conditions(capacityConditions, "capacityConditions");
        if (supportedTypes == null) throw new IllegalArgumentException("supportedTypes is required");
        var seen = new HashSet<MemoryType>();
        for (var type : supportedTypes) {
            if (type == null || !seen.add(type.memoryType())) {
                throw new IllegalArgumentException("supportedTypes must contain distinct non-null memory types");
            }
        }
        if (memoryTypesKnown && supportedTypes.isEmpty()) {
            throw new IllegalArgumentException("a known complete memory type list must not be empty");
        }
        supportedTypes = supportedTypes.stream().sorted(Comparator.comparing(TypeSupport::memoryType)).toList();
    }

    public enum MemoryType { DDR4, DDR5 }

    /** 최고 공표 속도와 적용 조건. 실제 장착 개수/rank를 모르면 이 속도의 달성을 보장하지 않는다. */
    public record TypeSupport(MemoryType memoryType, Integer maxStandardDataRateMts, String dataRateConditions) {
        public TypeSupport {
            if (memoryType == null) throw new IllegalArgumentException("memoryType is required");
            if (maxStandardDataRateMts != null && maxStandardDataRateMts <= 0) {
                throw new IllegalArgumentException("maxStandardDataRateMts must be positive or null");
            }
            dataRateConditions = conditions(dataRateConditions, "dataRateConditions");
        }
    }

    private static String conditions(String value, String field) {
        if (value == null || value.isBlank() || value.strip().length() > 2000) {
            throw new IllegalArgumentException(field + " must contain 1..2000 characters");
        }
        return value.strip();
    }
}
