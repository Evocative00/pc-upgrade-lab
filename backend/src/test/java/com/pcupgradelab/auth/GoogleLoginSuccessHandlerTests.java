package com.pcupgradelab.auth;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GoogleLoginSuccessHandlerTests {
    @Autowired GoogleLoginSuccessHandler handler;
    @Autowired UserAccountRepository users;
    @Autowired SocialAccountRepository socialAccounts;

    @Test
    void storesVerifiedGoogleUserIdInSessionAndRedirectsToFrontend() throws Exception {
        var google = mock(OidcUser.class);
        when(google.getSubject()).thenReturn("google-sub-handler");
        when(google.getFullName()).thenReturn("Google 회원");
        when(google.getEmail()).thenReturn("member@example.test");
        var authentication = new OAuth2AuthenticationToken(google, List.of(), "google");
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute(SessionCurrentUser.SESSION_ATTRIBUTE, Long.MAX_VALUE);
        var response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication);

        var userId = (Long) request.getSession(false).getAttribute(SessionCurrentUser.SESSION_ATTRIBUTE);
        assertThat(userId).isPositive();
        assertThat(users.findById(userId).orElseThrow().getEmail()).isEqualTo("member@example.test");
        assertThat(socialAccounts.findByProviderAndProviderUserId("google", "google-sub-handler")
                .orElseThrow().getUserId()).isEqualTo(userId);
        assertThat(response.getRedirectedUrl()).isEqualTo("http://127.0.0.1:5173/#/login/success");
    }

    @Test
    void kakaoUsesItsOwnAccountEvenWhenGoogleHasTheSameSubjectAndEmail() throws Exception {
        var google = mock(OidcUser.class);
        when(google.getSubject()).thenReturn("shared-subject");
        when(google.getFullName()).thenReturn("Google 회원");
        when(google.getEmail()).thenReturn("shared@example.test");
        var kakao = mock(OidcUser.class);
        when(kakao.getSubject()).thenReturn("shared-subject");
        when(kakao.getFullName()).thenReturn("Kakao 회원");
        when(kakao.getClaimAsString("nickname")).thenReturn("카카오별명");
        when(kakao.getEmail()).thenReturn("shared@example.test");
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response,
                new OAuth2AuthenticationToken(google, List.of(), "google"));
        var googleId = (Long) request.getSession(false).getAttribute(SessionCurrentUser.SESSION_ATTRIBUTE);
        var kakaoResponse = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(request, kakaoResponse,
                new OAuth2AuthenticationToken(kakao, List.of(), "kakao"));
        var kakaoId = (Long) request.getSession(false).getAttribute(SessionCurrentUser.SESSION_ATTRIBUTE);

        assertThat(kakaoId).isNotEqualTo(googleId);
        assertThat(users.findById(kakaoId).orElseThrow().getName()).isEqualTo("카카오별명");
        assertThat(socialAccounts.findByProviderAndProviderUserId("kakao", "shared-subject")
                .orElseThrow().getUserId()).isEqualTo(kakaoId);
        assertThat(kakaoResponse.getRedirectedUrl()).isEqualTo("http://127.0.0.1:5173/#/login/success");
    }

    @Test
    void newKakaoConsentUpdatesExistingFallbackNameOnNextLogin() throws Exception {
        var kakao = mock(OidcUser.class);
        when(kakao.getSubject()).thenReturn("nickname-later");
        var firstRequest = new MockHttpServletRequest();
        handler.onAuthenticationSuccess(firstRequest, new MockHttpServletResponse(),
                new OAuth2AuthenticationToken(kakao, List.of(), "kakao"));
        var userId = (Long) firstRequest.getSession(false).getAttribute(SessionCurrentUser.SESSION_ATTRIBUTE);
        assertThat(users.findById(userId).orElseThrow().getName()).isEqualTo("카카오 사용자");

        when(kakao.getClaimAsString("nickname")).thenReturn("새 카카오 닉네임");
        var secondRequest = new MockHttpServletRequest();
        handler.onAuthenticationSuccess(secondRequest, new MockHttpServletResponse(),
                new OAuth2AuthenticationToken(kakao, List.of(), "kakao"));

        assertThat(secondRequest.getSession(false).getAttribute(SessionCurrentUser.SESSION_ATTRIBUTE)).isEqualTo(userId);
        assertThat(users.findById(userId).orElseThrow().getName()).isEqualTo("새 카카오 닉네임");
    }

    @Test
    void unknownProviderCannotCreateAnAccountOrKeepThePreviousSession() throws Exception {
        var identity = mock(OidcUser.class);
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute(SessionCurrentUser.SESSION_ATTRIBUTE, 42L);
        var response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response,
                new OAuth2AuthenticationToken(identity, List.of(), "unknown"));

        assertThat(request.getSession(false)).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("http://127.0.0.1:5173/#/login/failure?reason=error");
    }

    @Test
    void failedAccountConnectionClearsPreviousUserAndSecuritySession() throws Exception {
        var loginService = mock(SocialLoginService.class);
        when(loginService.findOrCreateGoogleUser("new-google-sub", "새 회원", "new@example.test"))
                .thenThrow(new IllegalArgumentException("invalid provider profile"));
        var google = mock(OidcUser.class);
        when(google.getSubject()).thenReturn("new-google-sub");
        when(google.getFullName()).thenReturn("새 회원");
        when(google.getEmail()).thenReturn("new@example.test");
        var authentication = new OAuth2AuthenticationToken(google, List.of(), "google");
        var request = new MockHttpServletRequest();
        var previousSession = request.getSession();
        previousSession.setAttribute(SessionCurrentUser.SESSION_ATTRIBUTE, 42L);
        var response = new MockHttpServletResponse();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            new GoogleLoginSuccessHandler(loginService, "http://127.0.0.1:5173")
                    .onAuthenticationSuccess(request, response, authentication);

            assertThat(request.getSession(false)).isNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(response.getRedirectedUrl()).isEqualTo("http://127.0.0.1:5173/#/login/failure?reason=error");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
