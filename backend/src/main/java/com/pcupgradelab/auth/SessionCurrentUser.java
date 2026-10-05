package com.pcupgradelab.auth;

import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 로그인 성공 시 HTTP 세션에 저장한 회원 ID를 읽는다.
 * local·test 프로필에서는 로그인 구현 전 A/B 회원 확인을 위해 X-Dev-User-Id 헤더도 인정한다.
 * 이 헤더는 같은 PC에서만 접속하는 개발 환경용이며 다른 프로필에서는 무시한다.
 */
@Component
public class SessionCurrentUser implements CurrentUser {
    /** 로그인 처리에서 users.id(Long)를 저장할 세션 속성 이름. */
    public static final String SESSION_ATTRIBUTE = "LOGIN_USER_ID";
    public static final String DEV_HEADER = "X-Dev-User-Id";

    private final boolean devHeaderEnabled;

    public SessionCurrentUser(Environment environment) {
        this.devHeaderEnabled = environment.acceptsProfiles(Profiles.of("local", "test"));
    }

    @Override
    public Optional<Long> id() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Optional.empty();
        }
        var request = attributes.getRequest();
        // 세션이 없으면 새로 만들지 않는다. 비로그인 요청마다 빈 세션이 생기지 않게 한다.
        var session = request.getSession(false);
        if (session != null && session.getAttribute(SESSION_ATTRIBUTE) instanceof Long id && id > 0) {
            return Optional.of(id);
        }
        if (devHeaderEnabled) {
            var header = request.getHeader(DEV_HEADER);
            if (header != null && header.matches("[1-9]\\d{0,17}")) {
                return Optional.of(Long.valueOf(header));
            }
        }
        return Optional.empty();
    }
}
