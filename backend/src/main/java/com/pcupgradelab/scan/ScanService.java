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

/** Local development only. PC records persist in MySQL; transient scan sessions do not. */
@Service
@Profile("local")
public class ScanService {
    private static final Duration TTL = Duration.ofMinutes(2);
    private static final Duration RETENTION = Duration.ofMinutes(10);
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public ScanService() { this(Clock.systemUTC()); }
    ScanService(Clock clock) { this.clock = clock; }

    public synchronized Created create() {
        var now = clock.instant();
        sessions.values().removeIf(s -> !now.isBefore(s.expiresAt.plus(RETENTION)));
        if (sessions.size() >= 1000) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SCAN_LIMIT", "검사 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        var id = UUID.randomUUID();
        var read = token();
        var write = token();
        var session = new Session(id, read, write, now.plus(TTL));
        sessions.put(id, session);
        return new Created(id, read, "pcupgradelab://scan/?id=" + id + "&token=" + write, session.expiresAt);
    }

    public synchronized View read(UUID id, String token) {
        var session = authorized(id, token, false);
        expire(session);
        return new View(id, session.status, session.expiresAt, session.result, session.failure);
    }

    public synchronized void start(UUID id, String token) {
        var session = writable(id, token);
        if (session.status != ScanStatus.CREATED) conflict();
        session.status = ScanStatus.RUNNING;
    }

    public synchronized void complete(UUID id, String token, Result result) {
        var session = writable(id, token);
        if (session.status != ScanStatus.RUNNING) conflict();
        if (result.parts().stream().anyMatch(p -> p.source() != InputSource.AUTO
                || p.matchStatus() != MatchStatus.UNMATCHED || p.catalogProductId() != null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SCAN_RESULT", "수집 결과의 입력 방식이 올바르지 않습니다.");
        }
        // The server adds warnings if a collector forgets to report a missing required category.
        var warnings = new ArrayList<>(result.warnings());
        for (var type : List.of(PartType.CPU, PartType.GPU, PartType.RAM)) {
            if (result.parts().stream().noneMatch(p -> p.type() == type)
                    && warnings.stream().noneMatch(w -> w.scope().equals(type.name()))) {
                warnings.add(new Warning(type.name(), "NO_DEVICE", type + " 정보를 읽지 못했습니다."));
            }
        }
        session.result = new Result(result.schemaVersion(), result.collectorVersion(), result.collectedAt(), result.parts(), warnings);
        if (result.parts().isEmpty()) {
            session.status = ScanStatus.FAILED;
            session.failure = new Failure("NO_PARTS", "부품 정보를 읽지 못했습니다.");
        } else {
            session.status = warnings.isEmpty() ? ScanStatus.COMPLETED : ScanStatus.COMPLETED_WITH_WARNINGS;
        }
    }

    public synchronized void fail(UUID id, String token, Failure failure) {
        var session = writable(id, token);
        if (session.status != ScanStatus.RUNNING) conflict();
        session.failure = failure;
        session.status = ScanStatus.FAILED;
    }

    private Session writable(UUID id, String token) {
        var session = authorized(id, token, true);
        expire(session);
        if (session.status == ScanStatus.EXPIRED) {
            throw new ApiException(HttpStatus.GONE, "SCAN_EXPIRED", "검사 시간이 만료되었습니다. 다시 시작해 주세요.");
        }
        return session;
    }

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

    private void expire(Session session) {
        if ((session.status == ScanStatus.CREATED || session.status == ScanStatus.RUNNING)
                && !clock.instant().isBefore(session.expiresAt)) session.status = ScanStatus.EXPIRED;
    }
    private void conflict() {
        throw new ApiException(HttpStatus.CONFLICT, "SCAN_STATE_CONFLICT", "이미 처리된 검사 요청입니다.");
    }
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
