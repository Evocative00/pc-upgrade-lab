package com.pcupgradelab.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"app.security.csrf.enabled=true", "app.auth.dev-header.enabled=false"})
@ActiveProfiles({"test", "local"})
class AuthSecurityTests {
    @Autowired WebApplicationContext context;

    @Test
    void anonymousPcRequestIsJson401AndUnsafeRequestRequiresCsrf() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/pcs"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/auth/logout"))
                .andExpect(status().isForbidden());
        var response = mvc.perform(get("/api/auth/providers"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var csrfCookie = response.getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        assertThat(csrfCookie.isHttpOnly()).isFalse();
        mvc.perform(post("/api/auth/logout").cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().isNoContent());
    }

    @Test
    void guestCanQueryCatalogAndCheckTemporaryPcWithCsrf() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/catalog/products")).andExpect(status().isOk());
        String payload = "{\"ram\":[]}";
        mvc.perform(post("/api/compatibility/check").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isForbidden());
        var csrfCookie = mvc.perform(get("/api/auth/providers")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();

        mvc.perform(post("/api/compatibility/check").cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NEEDS_CHECK"));
    }

    @Test
    void guestScanCreationUsesCsrfButCollectorWritesUseOnlyTheirOwnToken() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(post("/api/scan-sessions").header("X-PCUL-Client", "web"))
                .andExpect(status().isForbidden());
        var csrfCookie = mvc.perform(get("/api/auth/providers")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        var created = mvc.perform(post("/api/scan-sessions").header("X-PCUL-Client", "web")
                        .cookie(csrfCookie).header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var body = JsonMapper.builder().build().readTree(created);
        String sessionId = body.get("sessionId").stringValue();
        var writeToken = UriComponentsBuilder.fromUriString(body.get("launchUri").stringValue())
                .build().getQueryParams().getFirst("token");
        assertThat(writeToken).isNotNull();

        // 브라우저의 읽기 토큰으로는 수집기 쓰기 요청을 할 수 없다. CSRF 검사를 건너뛰어도 403이다.
        mvc.perform(post("/api/scan-sessions/{id}/start", sessionId)
                        .header("Authorization", "Bearer " + body.get("readToken").stringValue()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SCAN_FORBIDDEN"));
        mvc.perform(post("/api/scan-sessions/{id}/start", sessionId).header("Authorization", "Bearer " + writeToken))
                .andExpect(status().isNoContent());
    }
}
