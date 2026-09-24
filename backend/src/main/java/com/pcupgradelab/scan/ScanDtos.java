package com.pcupgradelab.scan;

import com.pcupgradelab.pc.PartInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 자동 인식 API에서 주고받는 JSON 형식. 프런트의 pc-scan/types.ts와 필드 이름을 맞춘다. */
public final class ScanDtos {
    private ScanDtos() { }

    // 검사 생성 응답. launchUri에는 수집기용 쓰기 토큰이 있고 readToken은 브라우저의 조회용이다.
    public record Created(UUID sessionId, String readToken, String launchUri, Instant expiresAt) { }
    // scope는 부품 종류, code는 화면에서 구분할 경고 코드, message는 사용자 안내다.
    public record Warning(@NotBlank @Size(max = 20) String scope,
                          @NotBlank @Size(max = 50) String code,
                          @NotBlank @Size(max = 300) String message) { }
    // 현재 규격 버전은 1. parts가 빈 배열이면 서비스가 FAILED로 처리하고, 부분 결과는 경고와 함께 보존한다.
    public record Result(@NotNull @Min(1) @Max(1) Integer schemaVersion,
                         @NotBlank @Size(max = 30) String collectorVersion,
                         @NotNull Instant collectedAt,
                         @NotNull @Size(max = 64) List<@Valid @NotNull PartInput> parts,
                         @NotNull @Size(max = 30) List<@Valid @NotNull Warning> warnings) {
        public Result {
            // 전달받은 목록을 나중에 호출자가 변경해도 세션에 보관한 결과는 바뀌지 않게 한다.
            if (parts != null) parts = List.copyOf(parts);
            if (warnings != null) warnings = List.copyOf(warnings);
        }
    }
    // 실패 이유는 사용자에게 보여줄 수 있는 내용만 보낸다. 토큰·원본 예외는 포함하지 않는다.
    public record Failure(@NotBlank @Size(max = 50) String code,
                          @NotBlank @Size(max = 300) String message) { }
    // 상태 조회 응답. 처리 단계에 따라 result 또는 failure가 null일 수 있다.
    public record View(UUID sessionId, ScanStatus status, Instant expiresAt, Result result, Failure failure) { }
}
