package com.pcupgradelab.pc;

import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 수집기, PC 등록·수정 API, 화면이 함께 사용하는 부품 한 항목의 데이터 형식.
 * DTO는 데이터를 전달하는 객체이며, 이 record 자체가 DB에 저장되는 엔티티는 아니다.
 * 필드 이름을 바꾸면 프런트의 types.ts와 docs/week1-contract.md도 함께 검토해야 한다.
 *
 * @param displayName 화면에 표시할 이름
 * @param rawName 자동 검출한 원문 이름. 표시 이름이나 제원을 보완해도 원문을 보존한다.
 * @param quantity 장치 개수. specs의 capacityBytes는 장치 한 개의 용량(bytes)이다.
 * @param source AUTO는 자동 수집, MANUAL은 직접 입력한 부품이다.
 * @param catalogProductId 제품 카탈로그와 아직 연결하지 못했다면 null
 * @param matchStatus 실제 카탈로그 연결 여부. 이름이 비슷하다는 이유만으로 MATCHED로 바꾸지 않는다.
 * @param specs 부품별 제원. 값은 문자열·숫자·불리언·null이며 중첩 객체와 배열은 허용하지 않는다.
 */
public record PartInput(
        @NotNull PartType type,
        @NotBlank @Size(max = 255) String displayName,
        @Size(max = 500) String rawName,
        @NotNull @Min(1) @Max(64) Integer quantity,
        @NotNull InputSource source,
        @Size(max = 128) String catalogProductId,
        @NotNull MatchStatus matchStatus,
        @NotNull @Size(max = 40) Map<String, Object> specs) {

    public PartInput {
        // 호출한 쪽이 원본 Map을 바꿔도 이 데이터가 달라지지 않도록 복사한다.
        // 미확인 제원의 null을 보존해야 하므로 null을 허용하지 않는 Map.copyOf는 사용하지 않는다.
        if (specs != null) specs = Collections.unmodifiableMap(new LinkedHashMap<>(specs));
    }

    // @AssertTrue는 검증 규칙이고, @JsonIgnore는 이 검증 결과를 JSON 필드로 내보내지 않게 한다.
    @JsonIgnore
    @AssertTrue(message = "catalogProductId and matchStatus must agree")
    public boolean isCatalogLinkValid() {
        // 미연결이면 ID가 없어야 하고, 연결 상태라면 비어 있지 않은 ID가 있어야 한다.
        return matchStatus == MatchStatus.UNMATCHED ? catalogProductId == null
                : matchStatus == MatchStatus.MATCHED && catalogProductId != null && !catalogProductId.isBlank();
    }

    @JsonIgnore
    @AssertTrue(message = "specs must contain bounded scalar values; known capacities must be positive")
    public boolean isSpecsValid() {
        // 제원 키와 값의 크기를 제한한다. 용량을 모르면 0 대신 null로 전달한다.
        if (specs == null || specs.size() > 40) return false;
        for (var entry : specs.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            if (key == null || !key.matches("[a-zA-Z][a-zA-Z0-9]{0,49}")) return false;
            if (value != null && !(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean)) return false;
            if (value instanceof String text && text.length() > 500) return false;
            if (value instanceof Number number && !Double.isFinite(number.doubleValue())) return false;
            if ((key.equals("capacityBytes") || key.equals("vramBytes")) && value != null
                    && (!(value instanceof Number number) || number.doubleValue() <= 0)) return false;
        }
        return true;
    }
}
