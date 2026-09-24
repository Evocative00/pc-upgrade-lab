package com.pcupgradelab.scan;

import com.pcupgradelab.common.ApiException;
import com.pcupgradelab.pc.InputSource;
import com.pcupgradelab.pc.MatchStatus;
import com.pcupgradelab.pc.PartType;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static com.pcupgradelab.scan.ScanDtos.*;

/**
 * 브라우저와 Windows 수집기가 같은 검사 결과를 주고받도록 임시 세션을 관리한다.
 * 흐름: create → start → complete 또는 fail. 진행 중 제한 시간이 지나면 EXPIRED가 된다.
 * local 프로필에서만 사용하며, 세션은 메모리에 있어 서버 재시작 시 사라진다.
 * 사용자가 PC 구성을 MySQL에 저장하는 기능은 별도의 PC API가 담당한다.
 */
@Service
@Profile("local")
public class ScanService {
    // 생성 후 2분 안에 수집을 끝내야 한다. 결과 조회는 이 만료 시각으로부터 10분 더 허용한다.
    private static final Duration TTL = Duration.ofMinutes(2);
    private static final Duration RETENTION = Duration.ofMinutes(10);
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public ScanService() { this(Clock.systemUTC()); }
    // 테스트에서는 가짜 시계를 넣어 실제로 2분을 기다리지 않고 만료 시점의 동작을 확인한다.
    ScanService(Clock clock) { this.clock = clock; }

    // synchronized는 이 서비스 객체 안에서 세션을 다루는 요청이 동시에 상태를 바꾸지 않게 한다.
    public synchronized Created create() {
        var now = clock.instant();
        // 정리용 별도 스케줄러 없이, 새 검사 생성 시 보관 기간이 끝난 세션을 제거한다.
        sessions.values().removeIf(s -> !now.isBefore(s.expiresAt.plus(RETENTION)));
        if (sessions.size() >= 1000) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SCAN_LIMIT", "검사 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        var id = UUID.randomUUID();
        // 브라우저는 조회용 토큰을, 수집기는 실행 URI에 들어 있는 쓰기용 토큰을 사용한다.
        // 두 토큰은 서로 대체할 수 없으며 로그인 계정의 인증 토큰도 아니다.
        var read = token();
        var write = token();
        var session = new Session(id, read, write, now.plus(TTL));
        sessions.put(id, session);
        return new Created(id, read, "pcupgradelab://scan/?id=" + id + "&token=" + write, session.expiresAt);
    }

    /** 브라우저의 반복 조회용. 진행 중인 세션은 조회 시에도 만료 여부를 확인한다. */
    public synchronized View read(UUID id, String token) {
        var session = authorized(id, token, false);
        expire(session);
        return new View(id, session.status, session.expiresAt, session.result, session.failure);
    }

    /** 수집기가 하드웨어를 읽기 전에 한 번만 시작을 등록한다. 중복 실행은 상태 충돌로 거절한다. */
    public synchronized void start(UUID id, String token) {
        var session = writable(id, token);
        if (session.status != ScanStatus.CREATED) conflict();
        session.status = ScanStatus.RUNNING;
    }

    /** 수집 결과를 받아 완료 상태를 정한다. 일부 정보가 없어도 읽은 부품과 경고는 함께 보존한다. */
    public synchronized void complete(UUID id, String token, Result result) {
        var session = writable(id, token);
        if (session.status != ScanStatus.RUNNING) conflict();
        // 수집기는 자동 검출 원문만 보낸다. 카탈로그의 정확한 제품을 확정하는 역할은 맡지 않는다.
        if (result.parts().stream().anyMatch(p -> p.source() != InputSource.AUTO
                || p.matchStatus() != MatchStatus.UNMATCHED || p.catalogProductId() != null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SCAN_RESULT", "수집 결과의 입력 방식이 올바르지 않습니다.");
        }
        // 수집기가 필수 종류(CPU/GPU/RAM)의 누락 경고를 빠뜨렸어도 서버에서 보완한다.
        var warnings = new ArrayList<>(result.warnings());
        for (var type : List.of(PartType.CPU, PartType.GPU, PartType.RAM)) {
            if (result.parts().stream().noneMatch(p -> p.type() == type)
                    && warnings.stream().noneMatch(w -> w.scope().equals(type.name()))) {
                warnings.add(new Warning(type.name(), "NO_DEVICE", type + " 정보를 읽지 못했습니다."));
            }
        }
        session.result = new Result(result.schemaVersion(), result.collectorVersion(), result.collectedAt(), result.parts(), warnings);
        // 하나도 읽지 못하면 실패, 일부라도 읽으면 경고 유무에 따라 완료 상태를 구분한다.
        if (result.parts().isEmpty()) {
            session.status = ScanStatus.FAILED;
            session.failure = new Failure("NO_PARTS", "부품 정보를 읽지 못했습니다.");
        } else {
            session.status = warnings.isEmpty() ? ScanStatus.COMPLETED : ScanStatus.COMPLETED_WITH_WARNINGS;
        }
    }

    /** 수집기가 시작 후 치명적인 실패를 보고할 때 사용한다. 완료된 결과를 나중에 덮어쓸 수는 없다. */
    public synchronized void fail(UUID id, String token, Failure failure) {
        var session = writable(id, token);
        if (session.status != ScanStatus.RUNNING) conflict();
        session.failure = failure;
        session.status = ScanStatus.FAILED;
    }

    // 결과를 변경하는 요청은 쓰기 권한과 만료를 먼저 검사하고, 각 메서드에서 현재 상태도 검사한다.
    private Session writable(UUID id, String token) {
        var session = authorized(id, token, true);
        expire(session);
        if (session.status == ScanStatus.EXPIRED) {
            throw new ApiException(HttpStatus.GONE, "SCAN_EXPIRED", "검사 시간이 만료되었습니다. 다시 시작해 주세요.");
        }
        return session;
    }

    // 존재 여부 → 보관 기간 → 해당 용도의 토큰을 순서대로 확인한다. 토큰 값은 오류에 포함하지 않는다.
    private Session authorized(UUID id, String token, boolean write) {
        var session = sessions.get(id);
        if (session == null || !clock.instant().isBefore(session.expiresAt.plus(RETENTION))) {
            sessions.remove(id);
            throw new ApiException(HttpStatus.NOT_FOUND, "SCAN_NOT_FOUND", "검사 요청을 찾을 수 없습니다.");
        }
        String expected = write ? session.writeToken : session.readToken;
        if (token == null || token.length() != 43 || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), token.getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SCAN_FORBIDDEN", "이 검사 요청에 접근할 수 없습니다.");
        }
        return session;
    }

    // 완료·실패 상태는 유지하고, 아직 진행 중인 세션만 EXPIRED로 바꾼다.
    private void expire(Session session) {
        if ((session.status == ScanStatus.CREATED || session.status == ScanStatus.RUNNING)
                && !clock.instant().isBefore(session.expiresAt)) session.status = ScanStatus.EXPIRED;
    }
    private void conflict() {
        throw new ApiException(HttpStatus.CONFLICT, "SCAN_STATE_CONFLICT", "이미 처리된 검사 요청입니다.");
    }
    // 예측하기 어려운 32바이트를 URL에 넣을 수 있는 43자 문자열로 변환한다.
    private String token() {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static class Session {
        final UUID id;
        final String readToken;
        final String writeToken;
        final Instant expiresAt;
        ScanStatus status = ScanStatus.CREATED;
        Result result;
        Failure failure;
        Session(UUID id, String readToken, String writeToken, Instant expiresAt) {
            this.id = id; this.readToken = readToken; this.writeToken = writeToken; this.expiresAt = expiresAt;
        }
    }
}
