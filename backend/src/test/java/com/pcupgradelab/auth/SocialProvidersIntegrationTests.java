package com.pcupgradelab.auth;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 세 제공자의 동시 설정과 인증 시작을 검증한다. 가짜 키와 정적 주소로 외부 호출을 피한다. */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-google",
        "spring.security.oauth2.client.registration.google.client-secret=test-secret",
        "spring.security.oauth2.client.registration.google.redirect-uri=http://127.0.0.1:5173/login/oauth2/code/google",
        "spring.security.oauth2.client.registration.kakao.client-id=test-kakao",
        "spring.security.oauth2.client.registration.kakao.client-secret=test-secret",
        "spring.security.oauth2.client.registration.kakao.client-authentication-method=client_secret_post",
        "spring.security.oauth2.client.registration.kakao.authorization-grant-type=authorization_code",
        "spring.security.oauth2.client.registration.kakao.redirect-uri=http://127.0.0.1:5173/login/oauth2/code/kakao",
        "spring.security.oauth2.client.registration.kakao.scope=openid,profile_nickname",
        "spring.security.oauth2.client.provider.kakao.authorization-uri=https://kauth.kakao.com/oauth/authorize",
        "spring.security.oauth2.client.provider.kakao.token-uri=https://kauth.kakao.com/oauth/token",
        "spring.security.oauth2.client.provider.kakao.jwk-set-uri=https://kauth.kakao.com/.well-known/jwks.json",
        "spring.security.oauth2.client.provider.kakao.user-name-attribute=sub",
        "spring.security.oauth2.client.registration.naver.client-id=test-naver",
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
class SocialProvidersIntegrationTests {
    @Autowired WebApplicationContext context;

    @Test
    void listsAllConfiguredProvidersAndStartsEachWithItsOwnFrontendCallback() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("google"))
                .andExpect(jsonPath("$[1]").value("kakao"))
                .andExpect(jsonPath("$[2]").value("naver"))
                .andExpect(jsonPath("$[3]").doesNotExist());

        var starts = List.of(
                new ProviderStart("google", "https://accounts.google.com/o/oauth2/v2/auth"),
                new ProviderStart("kakao", "https://kauth.kakao.com/oauth/authorize"),
                new ProviderStart("naver", "https://nid.naver.com/oauth2/authorize"));
        for (var start : starts) {
            var redirect = mvc.perform(get("/oauth2/authorization/" + start.provider()))
                    .andExpect(status().is3xxRedirection())
                    .andReturn().getResponse().getRedirectedUrl();
            assertThat(redirect).isNotNull();
            var uri = UriComponentsBuilder.fromUriString(redirect).build();
            assertThat(uri.getScheme() + "://" + uri.getHost() + uri.getPath())
                    .isEqualTo(start.authorizationUri());
            var query = uri.getQueryParams();
            assertThat(decode(query.getFirst("redirect_uri")))
                    .isEqualTo("http://127.0.0.1:5173/login/oauth2/code/" + start.provider());
            assertThat(decode(query.getFirst("client_id"))).isEqualTo("test-" + start.provider());
            assertThat(decode(query.getFirst("response_type"))).isEqualTo("code");
            assertThat(decode(query.getFirst("scope")).split(" ")).contains("openid");
            assertThat(query.getFirst("state")).isNotBlank();
            assertThat(query.getFirst("nonce")).isNotBlank();
        }
    }

    private static String decode(String value) {
        assertThat(value).isNotNull();
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private record ProviderStart(String provider, String authorizationUri) { }
}
