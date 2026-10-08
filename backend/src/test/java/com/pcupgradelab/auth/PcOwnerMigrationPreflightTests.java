package com.pcupgradelab.auth;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PcOwnerMigrationPreflightTests {
    @Autowired Flyway applicationFlyway;

    @Test
    void springBootRegistersThePreflightWithApplicationFlyway() {
        assertThat(applicationFlyway.getConfiguration().getCallbacks())
                .anyMatch(callback -> callback instanceof PcOwnerMigrationPreflight);
    }

    @Test
    void v11PreservesLegacyNullOwnerAndRunsOnlyOnce() {
        try (var ds = database()) {
            var jdbc = new JdbcTemplate(ds);
            migrateV10(ds);
            insertPc(jdbc, null);
            var before = jdbc.queryForList("SELECT * FROM pc_configuration");
            var latest = Flyway.configure().dataSource(ds).target(MigrationVersion.fromVersion("11"))
                    .callbacks(new PcOwnerMigrationPreflight()).load();

            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT * FROM pc_configuration")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class)).isZero();
            assertThat(latest.migrate().migrationsExecuted).isZero();
        }
    }

    @Test
    void existingNumericOwnerStopsV11BeforeItCreatesAnyTables() {
        try (var ds = database()) {
            var jdbc = new JdbcTemplate(ds);
            migrateV10(ds);
            insertPc(jdbc, 42L);
            var before = jdbc.queryForList("SELECT * FROM pc_configuration");
            var latest = Flyway.configure().dataSource(ds).callbacks(new PcOwnerMigrationPreflight()).load();

            assertThatThrownBy(latest::migrate).hasStackTraceContaining("V11 preflight refused")
                    .hasStackTraceContaining("Do not auto-assign or delete");
            assertThat(jdbc.queryForList("SELECT * FROM pc_configuration")).isEqualTo(before);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME IN ('USERS', 'SOCIAL_ACCOUNTS')
                    """, Long.class)).isZero();
            assertThat(latest.info().current().getVersion()).isEqualTo(MigrationVersion.fromVersion("10"));
        }
    }

    private static SingleConnectionDataSource database() {
        return new SingleConnectionDataSource("jdbc:h2:mem:pc-owner-migration-" + UUID.randomUUID()
                + ";MODE=MySQL", "sa", "", true);
    }

    private static void migrateV10(SingleConnectionDataSource ds) {
        Flyway.configure().dataSource(ds).target(MigrationVersion.fromVersion("10")).load().migrate();
    }

    private static void insertPc(JdbcTemplate jdbc, Long userId) {
        jdbc.update("""
                INSERT INTO pc_configuration (owner_key, user_id, name, name_normalized, version, created_at, updated_at)
                VALUES ('local-dev', ?, '기존 PC', '기존 pc', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, userId);
    }
}
