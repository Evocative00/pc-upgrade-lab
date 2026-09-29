package com.pcupgradelab.catalog.seed;

import com.pcupgradelab.pc.PartType;
import java.util.Set;

/** 실행할 수 있는 자료 묶음의 허용 목록. 외부 입력을 리소스 경로로 직접 사용하지 않는다. */
public enum CatalogSeedBatch {
    INITIAL("week2-initial", 9, Set.of(PartType.CPU, PartType.MOTHERBOARD, PartType.RAM)),
    GPU("week2-gpu", 6, Set.of(PartType.GPU)),
    MONITOR("week2-monitor", 3, Set.of(PartType.MONITOR));

    private final String resourceName;
    private final int expectedCount;
    private final Set<PartType> supportedTypes;

    CatalogSeedBatch(String resourceName, int expectedCount, Set<PartType> supportedTypes) {
        this.resourceName = resourceName;
        this.expectedCount = expectedCount;
        this.supportedTypes = Set.copyOf(supportedTypes);
    }

    public String resourceName() { return resourceName; }

    public int expectedCount() { return expectedCount; }

    public boolean supports(PartType type) { return type != null && supportedTypes.contains(type); }

    /** 철자·대소문자까지 일치해야 한다. 임의 경로, 빈 값, 알 수 없는 묶음은 실행하지 않는다. */
    public static CatalogSeedBatch fromName(String name) {
        for (CatalogSeedBatch batch : values()) {
            if (batch.resourceName.equals(name)) return batch;
        }
        throw new IllegalArgumentException("Catalog seed batch must be week2-initial, week2-gpu or week2-monitor");
    }
}
