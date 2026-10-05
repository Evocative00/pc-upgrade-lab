package com.pcupgradelab.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuthControllerTests {
    @Autowired WebApplicationContext context;
    @Autowired SocialLoginService socialLogin;

    @Test
    void reportsSessionUserAndClearsItOnLogout() throws Exception {
        var user = socialLogin.findOrCreateGoogleUser("controller-sub", "테스트 회원", "user@example.test");
        var session = new MockHttpSession();
        session.setAttribute(SessionCurrentUser.SESSION_ATTRIBUTE, user.getId());
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

        mvc.perform(get("/api/auth/providers"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0]").value("google"));
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.name").value("테스트 회원"))
                .andExpect(jsonPath("$.email").value("user@example.test"))
                .andExpect(jsonPath("$.provider").value("google"));
        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(session.isInvalid()).isTrue();
    }
}
