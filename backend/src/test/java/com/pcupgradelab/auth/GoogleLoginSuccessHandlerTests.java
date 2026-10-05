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
