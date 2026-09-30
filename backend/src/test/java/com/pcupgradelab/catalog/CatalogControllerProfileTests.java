package com.pcupgradelab.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** HTTP 진입점은 local 프로필에만 등록한다. DB 접속 없이 프로필 조건을 검사한다. */
class CatalogControllerProfileTests {
    @Test
    void noEndpointBeanWithoutLocalProfile() {
        assertControllerCount(0);
        assertControllerCount(0, "test");
        assertControllerCount(0, "prod");
    }

    @Test
    void registersTheEndpointWithLocalProfile() {
        assertControllerCount(1, "local");
    }

    private static void assertControllerCount(int expected, String... profiles) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profiles);
            context.registerBean(CatalogQueryService.class, () -> mock(CatalogQueryService.class));
            context.register(CatalogController.class);
            context.refresh();
            assertThat(context.getBeansOfType(CatalogController.class)).hasSize(expected);
        }
    }
}
