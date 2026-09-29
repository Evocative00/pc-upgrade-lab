package com.pcupgradelab.catalog;

import java.time.Instant;
import java.util.Map;

/** 출처 조회 결과. 중첩 원본을 복사하여 조회 결과로 엔티티 상태를 바꿀 수 없게 한다. */
public record CatalogSourceView(
        Long id,
        CatalogSourceName sourceName,
        String externalId,
        String sourceRevision,
        String sourceUrl,
        Map<String, Object> rawPayload,
        Instant retrievedAt
) {
    public CatalogSourceView {
        rawPayload = CatalogJsonValues.immutableObject(rawPayload);
    }
}
