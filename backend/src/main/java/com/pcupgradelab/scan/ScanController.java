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

@RestController
@Profile("local")
@RequestMapping("/api/scan-sessions")
public class ScanController {
    private final ScanService service;
    public ScanController(ScanService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<Created> create(@RequestHeader("X-PCUL-Client") String client) {
        if (!"web".equals(client)) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CLIENT", "요청 형식을 확인해 주세요.");
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.create());
    }

    @GetMapping("/{id}")
    public ResponseEntity<View> read(@PathVariable UUID id, @RequestHeader("X-Scan-Token") String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(id, token));
    }

    @PostMapping("/{id}/start") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void start(@PathVariable UUID id, @RequestHeader("Authorization") String token) {
        service.start(id, bearer(token));
    }

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
