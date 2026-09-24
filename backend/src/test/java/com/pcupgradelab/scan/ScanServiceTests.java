package com.pcupgradelab.scan;

import com.pcupgradelab.common.ApiException;
import com.pcupgradelab.pc.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static com.pcupgradelab.scan.ScanDtos.*;

/** 서버·DB 없이 세션 서비스의 상태와 권한 규칙을 확인한다. 가짜 Clock으로 만료 시점도 즉시 검사한다. */
class ScanServiceTests {
    private final MutableClock clock = new MutableClock();
    private final ScanService service = new ScanService(clock);

    // 다른 검사 결과를 읽거나, 조회 토큰으로 쓰거나, 완료 결과를 재전송할 수 없어야 한다.
    @Test
    void isolatesSessionsAndRejectsReplayAndWrongTokenPurpose() {
        var first = service.create();
        var second = service.create();
        assertThatThrownBy(() -> service.read(first.sessionId(), second.readToken())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.start(first.sessionId(), first.readToken())).isInstanceOf(ApiException.class);
        service.start(first.sessionId(), write(first));
        service.complete(first.sessionId(), write(first), result(List.of(part(PartType.CPU), part(PartType.GPU), part(PartType.RAM))));
        assertThat(service.read(first.sessionId(), first.readToken()).status()).isEqualTo(ScanStatus.COMPLETED);
        assertThat(service.read(second.sessionId(), second.readToken()).status()).isEqualTo(ScanStatus.CREATED);
        assertThatThrownBy(() -> service.complete(first.sessionId(), write(first), result(List.of()))).isInstanceOf(ApiException.class);
    }

    // 일부 부품 누락은 경고가 있는 완료, 전체 누락은 실패로 구분한다.
    @Test
    void incompleteAndEmptyCollectionNeverPretendFullSuccess() {
        var partial = service.create();
        service.start(partial.sessionId(), write(partial));
        service.complete(partial.sessionId(), write(partial), result(List.of(part(PartType.CPU))));
        var view = service.read(partial.sessionId(), partial.readToken());
        assertThat(view.status()).isEqualTo(ScanStatus.COMPLETED_WITH_WARNINGS);
        assertThat(view.result().warnings()).hasSize(2);
        var empty = service.create();
        service.start(empty.sessionId(), write(empty));
        service.complete(empty.sessionId(), write(empty), result(List.of()));
        assertThat(service.read(empty.sessionId(), empty.readToken()).status()).isEqualTo(ScanStatus.FAILED);
    }

    // 정확히 만료 시각에 도달한 결과도 늦은 요청으로 처리해야 한다.
    @Test
    void rejectsLateResultsAtExactDeadline() {
        var scan = service.create();
        service.start(scan.sessionId(), write(scan));
        clock.now = scan.expiresAt();
        assertThat(service.read(scan.sessionId(), scan.readToken()).status()).isEqualTo(ScanStatus.EXPIRED);
        assertThatThrownBy(() -> service.complete(scan.sessionId(), write(scan), result(List.of(part(PartType.CPU)))))
                .isInstanceOf(ApiException.class).hasMessageContaining("만료");
    }

    // 시작 등록이 선행되어야 하며, 수집기가 임의로 카탈로그 연결 완료를 주장할 수 없어야 한다.
    @Test
    void requiresStartAndRejectsCatalogClaimsFromCollector() {
        var scan = service.create();
        assertThatThrownBy(() -> service.complete(scan.sessionId(), write(scan), result(List.of(part(PartType.CPU)))))
                .isInstanceOf(ApiException.class);
        service.start(scan.sessionId(), write(scan));
        var forged = new PartInput(PartType.CPU, "CPU", "CPU", 1, InputSource.MANUAL, "123", MatchStatus.MATCHED, Map.of());
        assertThatThrownBy(() -> service.complete(scan.sessionId(), write(scan), result(List.of(forged))))
                .isInstanceOf(ApiException.class);
    }

    private Result result(List<PartInput> parts) { return new Result(1, "0.1.0", clock.instant(), parts, List.of()); }
    private PartInput part(PartType type) {
        return new PartInput(type, type.name(), type.name(), 1, InputSource.AUTO, null, MatchStatus.UNMATCHED, Map.of());
    }
    private String write(Created scan) { return scan.launchUri().split("token=")[1]; }
    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-23T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
