package com.pcupgradelab.scan;

import com.pcupgradelab.common.ApiException;
import com.pcupgradelab.pc.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static com.pcupgradelab.scan.ScanDtos.*;

class ScanServiceTests {
    private final MutableClock clock = new MutableClock();
    private final ScanService service = new ScanService(clock);

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

    @Test
    void rejectsLateResultsAtExactDeadline() {
        var scan = service.create();
        service.start(scan.sessionId(), write(scan));
        clock.now = scan.expiresAt();
        assertThat(service.read(scan.sessionId(), scan.readToken()).status()).isEqualTo(ScanStatus.EXPIRED);
        assertThatThrownBy(() -> service.complete(scan.sessionId(), write(scan), result(List.of(part(PartType.CPU)))))
                .isInstanceOf(ApiException.class).hasMessageContaining("만료");
    }

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
