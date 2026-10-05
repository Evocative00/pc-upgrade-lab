package com.pcupgradelab.pc;

import com.pcupgradelab.auth.SessionCurrentUser;
import com.pcupgradelab.auth.SocialLoginService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 보안 필터와 로그인 세션을 함께 사용해 회원별 PC 접근을 확인한다. */
@SpringBootTest(properties = {"app.security.csrf.enabled=true", "app.auth.dev-header.enabled=false"})
@ActiveProfiles("test")
@Transactional
class PcSessionSecurityTests {
    @Autowired WebApplicationContext context;
    @Autowired SocialLoginService socialLogin;

    @Test
    void sessionOwnershipAndCsrfStayEnforcedForAllPcMutations() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var userA = socialLogin.findOrCreateGoogleUser("security-owner-a", "회원 A", null);
        var userB = socialLogin.findOrCreateGoogleUser("security-owner-b", "회원 B", null);
        var sessionA = new MockHttpSession();
        var sessionB = new MockHttpSession();
        sessionA.setAttribute(SessionCurrentUser.SESSION_ATTRIBUTE, userA.getId());
        sessionB.setAttribute(SessionCurrentUser.SESSION_ATTRIBUTE, userB.getId());

        // 개발 헤더만으로는 로그인할 수 없고, 로그인한 A의 소유자도 B로 바뀌지 않는다.
        mvc.perform(get("/api/pcs").header(SessionCurrentUser.DEV_HEADER, userA.getId()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/auth/me").session(sessionA).header(SessionCurrentUser.DEV_HEADER, userB.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(userA.getId()));
        Cookie xsrf = mvc.perform(get("/api/auth/me").session(sessionA))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();

        mvc.perform(post("/api/pcs").session(sessionA).contentType(MediaType.APPLICATION_JSON).content(request("A PC")))
                .andExpect(status().isForbidden());
        var created = mvc.perform(post("/api/pcs").session(sessionA).cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue()).contentType(MediaType.APPLICATION_JSON).content(request("A PC")))
                .andExpect(status().isCreated()).andReturn().getResponse();
        long id = JsonMapper.builder().build().readTree(created.getContentAsByteArray()).get("id").longValue();

        mvc.perform(get("/api/pcs").session(sessionB)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get("/api/pcs/{id}", id).session(sessionB)).andExpect(status().isNotFound());
        mvc.perform(put("/api/pcs/{id}", id).session(sessionB).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(request("B attempted update")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/pcs/{id}", id).session(sessionB).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/pcs/{id}", id).session(sessionA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("A PC"));

        // 소유자도 토큰 없는 수정·삭제는 할 수 없으며 올바른 토큰이면 정상 처리한다.
        mvc.perform(put("/api/pcs/{id}", id).session(sessionA).contentType(MediaType.APPLICATION_JSON).content(request("A edited")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/pcs/{id}", id).session(sessionA)).andExpect(status().isForbidden());
        mvc.perform(put("/api/pcs/{id}", id).session(sessionA).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(request("A edited")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("A edited"));
        mvc.perform(delete("/api/pcs/{id}", id).session(sessionA).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/pcs/{id}", id).session(sessionA)).andExpect(status().isNotFound());
    }

    private static String request(String name) {
        return """
                {"name":"%s","parts":[{"type":"CPU","displayName":"직접 입력 CPU","rawName":null,
                "quantity":1,"source":"MANUAL","catalogProductId":null,"matchStatus":"UNMATCHED","specs":{}}]}
                """.formatted(name);
    }
}
