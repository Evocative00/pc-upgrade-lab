package com.pcupgradelab.catalog.memory;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CpuMemoryProfileTests {
    @Test
    void normalStartupNeverEnrichesAndHttpControllerRequiresLocalProfile() {
        check("test", Map.of(), 0, 0);
        check("prod", Map.of(), 0, 0);
        check("local", Map.of(), 1, 0);
        check("local", Map.of("catalog.cpu-memory.seed.enabled", "false"), 1, 0);
        check("local", Map.of("catalog.cpu-memory.seed.enabled", "true"), 1, 1);
    }

    private static void check(String profile, Map<String, Object> properties, int controllers, int runners) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profile);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-options", properties));
            context.registerBean(CpuMemoryQueryService.class, () -> mock(CpuMemoryQueryService.class));
            context.registerBean(CpuMemorySeedService.class, () -> mock(CpuMemorySeedService.class));
            context.register(CpuMemoryController.class, CpuMemorySeedRunner.class);
            context.refresh();
            assertThat(context.getBeansOfType(CpuMemoryController.class)).hasSize(controllers);
            assertThat(context.getBeansOfType(CpuMemorySeedRunner.class)).hasSize(runners);
        }
    }
}
