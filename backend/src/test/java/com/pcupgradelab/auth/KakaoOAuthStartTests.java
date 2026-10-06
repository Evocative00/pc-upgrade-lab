package com.pcupgradelab.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 가짜 키와 정적 엔드포인트로 카카오 시작 주소를 검증한다. 외부 서비스는 호출하지 않는다. */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.kakao.client-id=test-client",
        "spring.security.oauth2.client.registration.kakao.client-secret=test-secret",
        "spring.security.oauth2.client.registration.kakao.client-authentication-method=client_secret_post",
        "spring.security.oauth2.client.registration.kakao.authorization-grant-type=authorization_code",
        "spring.security.oauth2.client.registration.kakao.redirect-uri=http://127.0.0.1:5173/login/oauth2/code/kakao",
        "spring.security.oauth2.client.registration.kakao.scope=openid",
        "spring.security.oauth2.client.provider.kakao.authorization-uri=https://kauth.kakao.com/oauth/authorize",
        "spring.security.oauth2.client.provider.kakao.token-uri=https://kauth.kakao.com/oauth/token",
        "spring.security.oauth2.client.provider.kakao.jwk-set-uri=https://kauth.kakao.com/.well-known/jwks.json",
        "spring.security.oauth2.client.provider.kakao.user-name-attribute=sub"
})
@ActiveProfiles("test")
class KakaoOAuthStartTests {
    @Autowired WebApplicationContext context;

    @Test
    void listsKakaoOnlyWhenConfiguredAndUsesFrontendCallback() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("google"))
                .andExpect(jsonPath("$[1]").value("kakao"));

        var response = mvc.perform(get("/oauth2/authorization/kakao"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse();
        assertThat(response.getRedirectedUrl()).startsWith("https://kauth.kakao.com/oauth/authorize");
        assertThat(response.getRedirectedUrl()).contains("redirect_uri=http://127.0.0.1:5173/login/oauth2/code/kakao");
        assertThat(response.getRedirectedUrl()).contains("scope=openid");
    }
}
