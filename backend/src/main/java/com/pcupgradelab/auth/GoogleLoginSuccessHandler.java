package com.pcupgradelab.auth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/** 제공자의 OIDC 검증이 끝난 뒤 우리 회원 ID를 세션에 담고 프런트로 돌려보낸다. */
@Component
public class GoogleLoginSuccessHandler implements AuthenticationSuccessHandler {
    private static final Logger log = LoggerFactory.getLogger(GoogleLoginSuccessHandler.class);
    private final SocialLoginService socialLogin;
    private final String frontend;

    public GoogleLoginSuccessHandler(SocialLoginService socialLogin,
                                     @Value("${app.frontend.base-url}") String frontendBaseUrl) {
        this.socialLogin = socialLogin;
        this.frontend = frontendBaseUrl.replaceAll("/+$", "");
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        // 계정 전환 시 이전 회원 ID가 새 인증 실패 뒤에도 남아 있지 않게 먼저 제거한다.
        var previousSession = request.getSession(false);
        if (previousSession != null) previousSession.removeAttribute(SessionCurrentUser.SESSION_ATTRIBUTE);
        if (!(authentication instanceof OAuth2AuthenticationToken token)
                || !(token.getPrincipal() instanceof OidcUser identity)) {
            failLogin(request, response);
            return;
        }
        String provider = token.getAuthorizedClientRegistrationId();
        UserAccount user;
        try {
            user = switch (provider) {
                case "google" -> socialLogin.findOrCreateGoogleUser(
                        identity.getSubject(), identity.getFullName(), identity.getEmail());
                case "kakao" -> socialLogin.findOrCreateKakaoUser(
                        identity.getSubject(), kakaoDisplayName(identity), identity.getEmail());
                default -> throw new IllegalArgumentException("Unsupported login provider");
            };
        } catch (RuntimeException exception) {
            // 제공자 프로필·DB 예외의 원문에는 개인정보가 있을 수 있으므로 종류만 기록한다.
            log.warn("Social account connection failed: {}", exception.getClass().getSimpleName());
            failLogin(request, response);
            return;
        }
        request.getSession(true).setAttribute(SessionCurrentUser.SESSION_ATTRIBUTE, user.getId());
        response.sendRedirect(frontend + "/#/login/success");
    }

    private static String kakaoDisplayName(OidcUser identity) {
        String nickname = identity.getClaimAsString("nickname");
        return nickname != null && !nickname.isBlank() ? nickname : identity.getFullName();
    }

    private void failLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        clearLoginSession(request);
        response.sendRedirect(frontend + "/#/login/failure?reason=error");
    }

    static void clearLoginSession(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }
}
