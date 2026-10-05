package com.pcupgradelab.catalog.support;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MotherboardCpuProfileTests {
    @Test
    void normalStartupNeverEnrichesAndHttpControllerRequiresLocalProfile() {
        check("test", Map.of(), 0, 0);
        check("prod", Map.of(), 0, 0);
        check("local", Map.of(), 1, 0);
        check("local", Map.of("catalog.motherboard-cpu.seed.enabled", "false"), 1, 0);
        check("local", Map.of("catalog.motherboard-cpu.seed.enabled", "true"), 1, 1);
    }
    private static void check(String profile, Map<String, Object> properties, int controllers, int runners) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profile);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-options", properties));
            context.registerBean(MotherboardCpuQueryService.class, () -> mock(MotherboardCpuQueryService.class));
            context.registerBean(MotherboardCpuSeedService.class, () -> mock(MotherboardCpuSeedService.class));
            context.register(MotherboardCpuController.class, MotherboardCpuSeedRunner.class);
            context.refresh();
            assertThat(context.getBeansOfType(MotherboardCpuController.class)).hasSize(controllers);
            assertThat(context.getBeansOfType(MotherboardCpuSeedRunner.class)).hasSize(runners);
        }
    }
}
