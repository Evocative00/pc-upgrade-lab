package com.pcupgradelab.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCurrentUserTests {
    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void localHeaderIsDisabledUnlessExplicitlyEnabled() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        var request = requestWithHeader();
        assertThat(new SessionCurrentUser(environment).id()).isEmpty();

        environment.setProperty("app.auth.dev-header.enabled", "true");
        assertThat(new SessionCurrentUser(environment).id()).contains(42L);
        request.getSession().setAttribute(SessionCurrentUser.SESSION_ATTRIBUTE, 7L);
        assertThat(new SessionCurrentUser(environment).id()).contains(7L);
    }

    @Test
    void otherProfilesIgnoreHeaderEvenWhenPropertyIsEnabled() {
        var environment = new MockEnvironment().withProperty("app.auth.dev-header.enabled", "true");
        environment.setActiveProfiles("production");
        requestWithHeader();
        assertThat(new SessionCurrentUser(environment).id()).isEmpty();
    }

    private MockHttpServletRequest requestWithHeader() {
        var request = new MockHttpServletRequest();
        request.addHeader(SessionCurrentUser.DEV_HEADER, "42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }
}
