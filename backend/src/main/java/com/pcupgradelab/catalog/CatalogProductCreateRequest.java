package com.pcupgradelab.catalog;

import com.pcupgradelab.pc.PartType;

/**
 * 내부 등록 서비스에 전달할 최소 정보. 공개 HTTP API 요청은 아직 아니다.
 * ID·검증 상태·노출 여부·가격은 호출자가 정하지 않고 서비스가 초기화한다.
 */
public record CatalogProductCreateRequest(
        PartType type,
        String manufacturer,
        String modelName,
        String partNumber
) {
}
