package com.pcupgradelab.auth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/** Google 검증이 끝난 뒤 우리 회원 ID를 세션에 담고 프런트로 돌려보낸다. */
@Component
public class GoogleLoginSuccessHandler implements AuthenticationSuccessHandler {
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
        if (!(authentication instanceof OAuth2AuthenticationToken token)
                || !"google".equals(token.getAuthorizedClientRegistrationId())
                || !(token.getPrincipal() instanceof OidcUser google)) {
            response.sendRedirect(frontend + "/#/login/failure?reason=error");
            return;
        }
        var user = socialLogin.findOrCreateGoogleUser(
                google.getSubject(), google.getFullName(), google.getEmail());
        request.getSession(true).setAttribute(SessionCurrentUser.SESSION_ATTRIBUTE, user.getId());
        response.sendRedirect(frontend + "/#/login/success");
    }
}
