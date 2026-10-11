package com.pcupgradelab.catalog.pilot;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies explicit DB mode boundaries without starting Spring or accessing private configuration. */
class CatalogPilotImportOptionsTests {
    @Test
    void defaultModeIsDatabaseFree() {
        var options = CatalogPilotImportApplication.Options.parse(new String[0]);
        assertThat(options.sourceRoot()).isNotNull();
        assertThat(options.checkDb()).isFalse();
        assertThat(options.apply()).isFalse();
    }

    @Test
    void acceptsOneExplicitDatabaseModeAndRepositoryRoot() {
        var check = CatalogPilotImportApplication.Options.parse(new String[]{"--source-root", ".", "--check-db"});
        assertThat(check.sourceRoot()).isEqualTo(Path.of("."));
        assertThat(check.checkDb()).isTrue();
        assertThat(check.apply()).isFalse();
        var apply = CatalogPilotImportApplication.Options.parse(new String[]{"--apply", "--source-root", "."});
        assertThat(apply.apply()).isTrue();
        assertThat(apply.checkDb()).isFalse();
    }

    @Test
    void rejectsAmbiguousModesMissingValuesAndConfigurationOverrides() {
        for (String[] args : new String[][]{
                {"--check-db", "--apply"}, {"--apply", "--check-db"}, {"--apply", "--apply"},
                {"--check-db", "--check-db"}, {"--source-root"}, {"--source-root", ""},
                {"--source-root", ".", "--source-root", "."}, {"--apply=true"},
                {"--spring.flyway.enabled=true"}, {"--catalog.seed.enabled=true"}, {"--unknown"}}) {
            assertThatThrownBy(() -> CatalogPilotImportApplication.Options.parse(args)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> CatalogPilotImportApplication.Options.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
