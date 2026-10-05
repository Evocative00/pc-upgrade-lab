package com.pcupgradelab.compatibility;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CompatibilityProfileTests {
    @Test
    void compatibilityEndpointIsExposedOnlyUnderTheLocalProfile() {
        for (String profile : new String[]{"test", "prod", "local"}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().setActiveProfiles(profile);
                context.registerBean(CompatibilityCheckService.class, () -> mock(CompatibilityCheckService.class));
                context.register(CompatibilityController.class);
                context.refresh();
                assertThat(context.getBeansOfType(CompatibilityController.class)).hasSize(profile.equals("local") ? 1 : 0);
            }
        }
    }
}
