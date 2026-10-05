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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 가짜 클라이언트 설정으로 OAuth 시작 URL과 5173 콜백 주소 생성을 확인한다. Google 호출은 하지 않는다. */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-client",
        "spring.security.oauth2.client.registration.google.client-secret=test-secret",
        "spring.security.oauth2.client.registration.google.redirect-uri=http://127.0.0.1:5173/login/oauth2/code/google"
})
@ActiveProfiles("test")
class GoogleOAuthStartTests {
    @Autowired WebApplicationContext context;

    @Test
    void startsGoogleAuthorizationWithFrontendCallback() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var response = mvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse();
        assertThat(response.getRedirectedUrl()).startsWith("https://accounts.google.com/");
        assertThat(response.getRedirectedUrl()).contains("redirect_uri=http://127.0.0.1:5173/login/oauth2/code/google");
    }
}
