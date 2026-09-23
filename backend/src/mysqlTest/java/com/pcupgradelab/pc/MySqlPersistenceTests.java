package com.pcupgradelab.pc;

import com.pcupgradelab.BackendApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in check: requires the existing V1 schema and local MySQL credentials. */
class MySqlPersistenceTests {
    private final String ownerKey = "mysql-verification-" + UUID.randomUUID();
    private Long pcId;

    @Test
    void persistsUpdatedPartsAcrossApplicationRestart() throws Exception {
        try (var app = application()) {
            try (var connection = app.getBean(DataSource.class).getConnection()) {
                assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            }
            var repository = app.getBean(PcConfigurationRepository.class);
            var transactions = app.getBean(TransactionTemplate.class);

            pcId = transactions.execute(status -> repository.saveAndFlush(new PcConfiguration(
                    ownerKey, "MySQL 검증용 PC", List.of(
                    part(PartType.RAM, "검증 RAM A", 17179869184L),
                    part(PartType.RAM, "검증 RAM B", 17179869184L),
                    part(PartType.STORAGE, "검증 SSD A", 1000000000000L),
                    part(PartType.STORAGE, "검증 SSD B", 2000000000000L)))).getId());
            assertThat(pcId).isNotNull();

            // A separate committed transaction reads and replaces the saved aggregate.
            transactions.executeWithoutResult(status -> {
                var pc = repository.findByIdAndOwnerKey(pcId, ownerKey).orElseThrow();
                assertThat(pc.getParts()).hasSize(4);
                assertThat(repository.findByIdAndOwnerKey(pcId, ownerKey + "-other")).isEmpty();
                pc.update("MySQL 수정 확인", List.of(
                        part(PartType.RAM, "교체 RAM", 34359738368L),
                        part(PartType.STORAGE, "교체 SSD", 2000000000000L)));
            });
        }

        // The first Spring context and connection pool have been closed completely.
        try (var restarted = application()) {
            var repository = restarted.getBean(PcConfigurationRepository.class);
            var transactions = restarted.getBean(TransactionTemplate.class);
            transactions.executeWithoutResult(status -> {
                var pc = repository.findByIdAndOwnerKey(pcId, ownerKey).orElseThrow();
                assertThat(pc.getId()).isEqualTo(pcId);
                assertThat(pc.getName()).isEqualTo("MySQL 수정 확인");
                assertThat(pc.getVersion()).isGreaterThan(0);
                var parts = pc.getParts().stream().map(PcPart::toInput).toList();
                assertThat(parts).extracting(PartInput::displayName).containsExactly("교체 RAM", "교체 SSD");
                assertThat(parts.getFirst().rawName()).isEqualTo("검증 원문: 교체 RAM");
                assertThat(parts.getFirst().catalogProductId()).isNull();
                assertThat(parts.getFirst().matchStatus()).isEqualTo(MatchStatus.UNMATCHED);
                assertThat(parts.getFirst().source()).isEqualTo(InputSource.AUTO);
                assertThat(parts.getFirst().quantity()).isEqualTo(1);
                assertThat(parts.getFirst().specs().get("capacityBytes")).isInstanceOf(Number.class);
                assertThat(((Number) parts.getFirst().specs().get("capacityBytes")).longValue())
                        .isEqualTo(34359738368L);
                assertThat(parts.getFirst().specs()).containsEntry("reportedSpeedMHz", null);
                assertThat(restarted.getBean(JdbcTemplate.class).queryForObject(
                        "SELECT COUNT(*) FROM pc_part WHERE pc_id = ?", Long.class, pcId)).isEqualTo(2L);
            });
        }
    }

    @AfterEach
    void removeOnlyThisRunsRecord() {
        if (pcId == null) return;
        try (var app = application()) {
            var repository = app.getBean(PcConfigurationRepository.class);
            app.getBean(TransactionTemplate.class).executeWithoutResult(status ->
                    repository.findByIdAndOwnerKey(pcId, ownerKey).ifPresent(repository::delete));
            var jdbc = app.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pc_configuration WHERE id = ? AND owner_key = ?",
                    Long.class, pcId, ownerKey)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pc_part WHERE pc_id = ?", Long.class, pcId)).isZero();
        }
    }

    private ConfigurableApplicationContext application() {
        return new SpringApplicationBuilder(BackendApplication.class)
                .profiles("local")
                .web(WebApplicationType.NONE)
                .run("--spring.main.web-application-type=none",
                        "--spring.main.banner-mode=off",
                        "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--spring.flyway.enabled=false");
    }

    private PartInput part(PartType type, String name, long capacityBytes) {
        var specs = new LinkedHashMap<String, Object>();
        specs.put("capacityBytes", capacityBytes);
        if (type == PartType.RAM) specs.put("reportedSpeedMHz", null);
        return new PartInput(type, name, "검증 원문: " + name, 1, InputSource.AUTO,
                null, MatchStatus.UNMATCHED, specs);
    }
}
