package com.pcupgradelab.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;

/** 브라우저 세션/CSRF 보안과 Google OIDC 로그인을 기존 PC 소유권 규격에 연결한다. */
@Configuration
public class AuthSecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            ObjectProvider<ClientRegistrationRepository> registrations,
                                            GoogleLoginSuccessHandler successHandler,
                                            @Value("${app.frontend.base-url}") String frontendBaseUrl,
                                            @Value("${app.security.csrf.enabled:true}") boolean csrfEnabled) throws Exception {
        // PC 소유권은 PcService가 CurrentUser로 검증한다. 나머지 공개 API는 비회원도 사용할 수 있다.
        http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        http.formLogin(form -> form.disable());
        http.httpBasic(basic -> basic.disable());
        String frontend = frontendBaseUrl.replaceAll("/+$", "");

        if (csrfEnabled) {
            http.csrf(csrf -> csrf.spa().ignoringRequestMatchers(
                    "/api/scan-sessions/*/start", "/api/scan-sessions/*/result",
                    "/api/scan-sessions/*/failure"));
        } else {
            // H2의 기존 API 단위 테스트는 Security 필터를 통하지 않는다. 별도 보안 테스트에서 CSRF를 켠다.
            http.csrf(csrf -> csrf.disable());
        }

        // GOOGLE_CLIENT_ID/SECRET 설정 전에도 서버와 기존 H2 테스트가 실행돼야 한다.
        if (registrations.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .successHandler(successHandler)
                    .failureHandler((request, response, exception) -> {
                        String reason = exception instanceof OAuth2AuthenticationException oauthError
                                && "access_denied".equals(oauthError.getError().getErrorCode())
                                ? "cancelled" : "error";
                        redirect(response, frontend + "/#/login/failure?reason=" + reason);
                    }));
        }
        return http.build();
    }

    private static void redirect(HttpServletResponse response, String location) throws IOException {
        response.sendRedirect(location);
    }
}
