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

/**
 * mysqlTest로 선택 실행하는 실제 DB 검사. 기존 V1 테이블과 로컬 MySQL 접속 설정이 필요하다.
 * PC 저장 → 수정 → Spring 컨텍스트 종료 → 새 컨텍스트에서 재조회 → 검증용 데이터 정리를 확인한다.
 * 웹 서버는 실행하지 않으므로 HTTP API·화면 전체 흐름이나 별도 bootRun 프로세스를 재시작하는 검사는 아니다.
 */
class MySqlPersistenceTests {
    // 실행마다 고유한 소유자 값을 사용해 기존 사용자 데이터와 이번 검증 데이터를 구분한다.
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

            // 저장 트랜잭션이 반영된 뒤 별도 트랜잭션으로 조회·수정한다. 메모리 객체만 바꾼 검사가 되지 않게 한다.
            transactions.executeWithoutResult(status -> {
                var pc = repository.findByIdAndOwnerKey(pcId, ownerKey).orElseThrow();
                assertThat(pc.getParts()).hasSize(4);
                assertThat(repository.findByIdAndOwnerKey(pcId, ownerKey + "-other")).isEmpty();
                pc.update("MySQL 수정 확인", List.of(
                        part(PartType.RAM, "교체 RAM", 34359738368L),
                        part(PartType.STORAGE, "교체 SSD", 2000000000000L)));
            });
        }

        // 첫 번째 컨텍스트와 DB 연결 풀을 닫은 뒤 새로 시작한다. DB에 남은 수정 결과를 다시 읽어야 한다.
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

    // 검사가 끝나면 이번 pcId와 ownerKey에 해당하는 데이터만 지운다. 전체 테이블을 초기화하지 않는다.
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

    // 기존 스키마만 검증한다. 여기서는 마이그레이션을 실행하거나 테이블을 새로 만들지 않는다.
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
