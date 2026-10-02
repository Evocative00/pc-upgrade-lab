package com.pcupgradelab.catalog;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 신규 DB뿐 아니라 V6의 기존 자료를 V7로 올리는 경로와 DB 제약을 확인한다. */
class CatalogCpuMigrationTests {
    private static final List<String> NEW_COLUMNS = List.of("processor_base_power_w", "maximum_turbo_power_w",
            "performance_core_count", "efficient_core_count", "performance_core_base_clock_mhz",
            "efficient_core_base_clock_mhz", "performance_core_boost_clock_mhz", "efficient_core_boost_clock_mhz");

    @Test
    void upgradingV6PreservesCpuReviewedProductConfirmedPriceAndSource() {
        try (var dataSource = database()) {
            var jdbc = new JdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).target(MigrationVersion.fromVersion("6")).load().migrate();
            String id = insertProduct(jdbc);
            jdbc.update("""
                    UPDATE catalog_product SET verification_status = 'CORE_VERIFIED', is_active = TRUE
                    WHERE id = ?
                    """, id);
            jdbc.update("""
                    INSERT INTO cpu_spec (product_id, socket_code, core_count, thread_count,
                        base_clock_mhz, boost_clock_mhz, tdp_w, has_integrated_graphics)
                    VALUES (?, 'AM4', 6, 12, 3600, 4200, 65, FALSE)
                    """, id);
            jdbc.update("""
                    INSERT INTO catalog_reference_price (product_id, amount_krw, status, method,
                        period_start, period_end, observed_day_count, sample_count, price_basis,
                        evidence_ref, calculated_at, confirmed_at, updated_at)
                    VALUES (?, 217000, 'CONFIRMED', 'MEDIAN', '2026-03-01', '2026-08-31', 10, 20,
                        'Migration test fixture', 'test://migration-price',
                        '2026-09-01 00:00:00', '2026-09-01 01:00:00', '2026-09-01 01:00:00')
                    """, id);
            jdbc.update("""
                    INSERT INTO catalog_product_source (product_id, source_name, source_url, retrieved_at)
                    VALUES (?, 'MANUFACTURER', 'https://example.com/legacy-cpu-fixture', '2026-09-01 00:00:00')
                    """, id);
            var before = new HashMap<String, Map<String, Object>>();
            for (String table : List.of("catalog_product", "cpu_spec", "catalog_reference_price", "catalog_product_source")) {
                before.put(table, row(jdbc, table, id));
            }

            var latest = Flyway.configure().dataSource(dataSource).target(MigrationVersion.fromVersion("7")).load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            for (String table : before.keySet()) {
                var after = row(jdbc, table, id);
                if (table.equals("cpu_spec")) {
                    for (String column : NEW_COLUMNS) assertThat(after.get(column.toUpperCase(Locale.ROOT))).isNull();
                    after.keySet().removeIf(key -> NEW_COLUMNS.contains(key.toLowerCase(Locale.ROOT)));
                }
                assertThat(after).as("Preserve existing %s values", table).isEqualTo(before.get(table));
            }
            assertThat(latest.migrate().migrationsExecuted).isZero();
        }
    }

    @Test
    void v7DatabaseRejectsContradictionsAndKeepsUnknownAndAbsentCoresDistinct() {
        try (var dataSource = database()) {
            var jdbc = new JdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).target(MigrationVersion.fromVersion("7")).load().migrate();
            String id = insertProduct(jdbc);
            jdbc.update("INSERT INTO cpu_spec (product_id) VALUES (?)", id);
            for (String column : NEW_COLUMNS) {
                assertThat(jdbc.queryForMap("SELECT * FROM cpu_spec WHERE product_id = ?", id).get(column)).isNull();
                if (column.endsWith("_count")) {
                    for (int invalid : List.of(-1, 32768)) {
                        assertThatThrownBy(() -> jdbc.update("UPDATE cpu_spec SET " + column + " = ? WHERE product_id = ?",
                                invalid, id)).isInstanceOf(DataIntegrityViolationException.class);
                    }
                    jdbc.update("UPDATE cpu_spec SET " + column + " = 0 WHERE product_id = ?", id);
                    Integer absent = jdbc.queryForObject("SELECT " + column + " FROM cpu_spec WHERE product_id = ?",
                            Integer.class, id);
                    assertThat(absent).isZero();
                    jdbc.update("UPDATE cpu_spec SET " + column + " = NULL WHERE product_id = ?", id);
                } else {
                    for (int invalid : List.of(0, -1)) {
                        assertThatThrownBy(() -> jdbc.update("UPDATE cpu_spec SET " + column + " = ? WHERE product_id = ?",
                                invalid, id)).isInstanceOf(DataIntegrityViolationException.class);
                    }
                }
            }
            jdbc.update("""
                    UPDATE cpu_spec SET core_count = 14, boost_clock_mhz = 5100,
                        performance_core_count = 6, efficient_core_count = 8,
                        processor_base_power_w = 125, maximum_turbo_power_w = 181,
                        performance_core_base_clock_mhz = 3500, efficient_core_base_clock_mhz = 2600,
                        performance_core_boost_clock_mhz = 5100, efficient_core_boost_clock_mhz = 3900
                    WHERE product_id = ?
                    """, id);
            for (String assignments : List.of("performance_core_count = 7", "efficient_core_count = 7",
                    "core_count = 13", "performance_core_count = 15", "efficient_core_count = 15",
                    "performance_core_count = 0", "efficient_core_count = 0", "base_clock_mhz = 3500",
                    "maximum_turbo_power_w = 124", "performance_core_boost_clock_mhz = 3400",
                    "efficient_core_boost_clock_mhz = 2500", "performance_core_boost_clock_mhz = 5200",
                    "efficient_core_boost_clock_mhz = 5200")) {
                assertThatThrownBy(() -> jdbc.update("UPDATE cpu_spec SET " + assignments + " WHERE product_id = ?", id))
                        .as("Reject %s", assignments).isInstanceOf(DataIntegrityViolationException.class);
            }
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE cpu_spec SET core_count = NULL, performance_core_count = 0, efficient_core_count = 0,
                        performance_core_base_clock_mhz = NULL, efficient_core_base_clock_mhz = NULL,
                        performance_core_boost_clock_mhz = NULL, efficient_core_boost_clock_mhz = NULL
                    WHERE product_id = ?
                    """, id)).isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("""
                    UPDATE cpu_spec SET core_count = 6, efficient_core_count = 0,
                        efficient_core_base_clock_mhz = NULL, efficient_core_boost_clock_mhz = NULL,
                        maximum_turbo_power_w = NULL, base_clock_mhz = 3500
                    WHERE product_id = ?
                    """, id);
            var stored = row(jdbc, "cpu_spec", id);
            assertThat(((Number) stored.get("EFFICIENT_CORE_COUNT")).intValue()).isZero();
            assertThat(stored.get("MAXIMUM_TURBO_POWER_W")).isNull();
            assertThat(stored.get("PROCESSOR_BASE_POWER_W")).isEqualTo(new BigDecimal("125.00"));
        }
    }

    private SingleConnectionDataSource database() {
        // Flyway와 JdbcTemplate이 같은 물리 연결을 쓰고 테스트가 끝날 때 한 번만 닫는다.
        // CHECK를 만든 H2 세션을 중간에 종료하지 않는다. close 억제는 이 테스트에서만 사용한다.
        // 기본 DB_CLOSE_DELAY=0으로 연결을 닫으면 이 테스트의 전용 메모리 DB도 정리된다.
        return new SingleConnectionDataSource("jdbc:h2:mem:cpu-v7-" + UUID.randomUUID()
                + ";MODE=MySQL", "sa", "", true);
    }

    private String insertProduct(JdbcTemplate jdbc) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO catalog_product (id, type, manufacturer, model_name, created_at, updated_at)
                VALUES (?, 'CPU', 'Test Manufacturer', 'Migration fixture',
                    '2026-09-01 00:00:00', '2026-09-01 00:00:00')
                """, id);
        return id;
    }

    private Map<String, Object> row(JdbcTemplate jdbc, String table, String id) {
        String key = table.equals("catalog_product") ? "id" : "product_id";
        return new HashMap<>(jdbc.queryForMap("SELECT * FROM " + table + " WHERE " + key + " = ?", id));
    }
}
