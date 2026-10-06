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

/** 가짜 키와 정적 엔드포인트로 네이버 OIDC 시작 주소를 확인한다. 외부 서비스는 호출하지 않는다. */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.naver.client-id=test-client",
        "spring.security.oauth2.client.registration.naver.client-secret=test-secret",
        "spring.security.oauth2.client.registration.naver.client-authentication-method=client_secret_post",
        "spring.security.oauth2.client.registration.naver.authorization-grant-type=authorization_code",
        "spring.security.oauth2.client.registration.naver.redirect-uri=http://127.0.0.1:5173/login/oauth2/code/naver",
        "spring.security.oauth2.client.registration.naver.scope=openid,profile",
        "spring.security.oauth2.client.provider.naver.authorization-uri=https://nid.naver.com/oauth2/authorize",
        "spring.security.oauth2.client.provider.naver.token-uri=https://nid.naver.com/oauth2/token",
        "spring.security.oauth2.client.provider.naver.jwk-set-uri=https://nid.naver.com/oauth2/jwks",
        "spring.security.oauth2.client.provider.naver.user-name-attribute=sub"
})
@ActiveProfiles("test")
class NaverOAuthStartTests {
    @Autowired WebApplicationContext context;

    @Test
    void listsNaverOnlyWhenConfiguredAndUsesFrontendCallback() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("google"))
                .andExpect(jsonPath("$[1]").value("naver"));

        var response = mvc.perform(get("/oauth2/authorization/naver"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse();
        assertThat(response.getRedirectedUrl()).startsWith("https://nid.naver.com/oauth2/authorize");
        assertThat(response.getRedirectedUrl()).contains("redirect_uri=http://127.0.0.1:5173/login/oauth2/code/naver");
        assertThat(response.getRedirectedUrl()).contains("scope=openid");
    }
}
