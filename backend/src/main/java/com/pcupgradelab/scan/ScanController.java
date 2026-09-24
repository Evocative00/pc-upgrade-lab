package com.pcupgradelab.scan;

import com.pcupgradelab.common.ApiException;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import static com.pcupgradelab.scan.ScanDtos.*;

/**
 * 자동 인식용 HTTP 진입점. 요청 형식은 여기서 검사하고 세션의 상태 처리는 ScanService에 맡긴다.
 * 브라우저: create/read, Windows 수집기: start/result/failure.
 * PC 등록·조회·수정 API와는 다른 기능이며 local 프로필에서만 활성화된다.
 */
@RestController
@Profile("local")
@RequestMapping("/api/scan-sessions")
public class ScanController {
    private final ScanService service;
    public ScanController(ScanService service) { this.service = service; }

    // X-PCUL-Client는 브라우저용 요청 형식을 확인하는 값이며 사용자 로그인 인증을 대신하지 않는다.
    // 생성·조회 응답은 임시 토큰/사양을 담으므로 브라우저 캐시에 남기지 않도록 no-store를 지정한다.
    @PostMapping
    public ResponseEntity<Created> create(@RequestHeader("X-PCUL-Client") String client) {
        if (!"web".equals(client)) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CLIENT", "요청 형식을 확인해 주세요.");
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.create());
    }

    // 브라우저에는 읽기 전용 토큰을 사용한다. 수집기의 Authorization 헤더와 구분한다.
    @GetMapping("/{id}")
    public ResponseEntity<View> read(@PathVariable UUID id, @RequestHeader("X-Scan-Token") String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(id, token));
    }

    // 수집기는 쓰기용 토큰을 Bearer 형식으로 보낸다. 아래 세 POST는 성공 시 본문 없이 204를 반환한다.
    @PostMapping("/{id}/start") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void start(@PathVariable UUID id, @RequestHeader("Authorization") String token) {
        service.start(id, bearer(token));
    }

    // @Valid는 Result 내부의 부품 목록까지 검사한다. 세부 규칙은 ScanDtos와 PartInput에 있다.
    @PostMapping("/{id}/result") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void result(@PathVariable UUID id, @RequestHeader("Authorization") String token,
                       @Valid @RequestBody Result result) {
        service.complete(id, bearer(token), result);
    }

    @PostMapping("/{id}/failure") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void failure(@PathVariable UUID id, @RequestHeader("Authorization") String token,
                        @Valid @RequestBody Failure failure) {
        service.fail(id, bearer(token), failure);
    }

    private String bearer(String header) {
        if (!header.matches("Bearer [A-Za-z0-9_-]{43}")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SCAN_FORBIDDEN", "유효한 검사 토큰이 필요합니다.");
        }
        return header.substring(7);
    }
}
