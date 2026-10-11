package com.pcupgradelab.catalog.storage;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MotherboardStorageProfileTests {
    @Test
    void readApiIsOnlyRegisteredInLocalProfile() {
        for (String profile : new String[]{"test", "prod", "local"}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().setActiveProfiles(profile);
                context.registerBean(MotherboardStorageQueryService.class, () -> mock(MotherboardStorageQueryService.class));
                context.register(MotherboardStorageController.class);
                context.refresh();
                assertThat(context.getBeansOfType(MotherboardStorageController.class))
                        .hasSize(profile.equals("local") ? 1 : 0);
            }
        }
    }
}
