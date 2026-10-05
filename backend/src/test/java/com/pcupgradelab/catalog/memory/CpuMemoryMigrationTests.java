package com.pcupgradelab.catalog.memory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CpuMemoryMigrationTests {
    @Test
    void v8PreservesExistingV7RowsAndCreatesEmptyMemoryTablesOnlyOnce() {
        try (var ds = database()) {
            var jdbc = new JdbcTemplate(ds);
            Flyway.configure().dataSource(ds).target(MigrationVersion.fromVersion("7")).load().migrate();
            String id = product(jdbc, "CPU");
            jdbc.update("INSERT INTO cpu_spec (product_id, socket_code) VALUES (?, 'AM4')", id);
            jdbc.update("INSERT INTO catalog_reference_price (product_id, updated_at) VALUES (?, CURRENT_TIMESTAMP)", id);
            source(jdbc, id);
            var before = new HashMap<String, List<Map<String, Object>>>();
            for (String table : List.of("catalog_product", "cpu_spec", "catalog_reference_price", "catalog_product_source")) {
                before.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1"));
            }
            var latest = Flyway.configure().dataSource(ds).target(MigrationVersion.fromVersion("8")).load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            for (var entry : before.entrySet()) {
                assertThat(jdbc.queryForList("SELECT * FROM " + entry.getKey() + " ORDER BY 1")).isEqualTo(entry.getValue());
            }
            for (String table : List.of("cpu_memory_support", "cpu_memory_type_support")) {
                Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
                assertThat(count).isZero();
            }
            assertThat(latest.migrate().migrationsExecuted).isZero();
        }
    }

    @Test
    void constraintsTieEvidenceToSameCpuAndRejectInvalidCapacityTypeRateOrDuplicateRows() {
        try (var ds = database()) {
            var jdbc = new JdbcTemplate(ds);
            Flyway.configure().dataSource(ds).target(MigrationVersion.fromVersion("8")).load().migrate();
            String first = product(jdbc, "CPU"), second = product(jdbc, "CPU"), gpu = product(jdbc, "GPU");
            jdbc.update("INSERT INTO cpu_spec (product_id) VALUES (?)", first);
            jdbc.update("INSERT INTO cpu_spec (product_id) VALUES (?)", second);
            long firstSource = source(jdbc, first), secondSource = source(jdbc, second), gpuSource = source(jdbc, gpu);
            var repository = new CpuMemorySupportRepository(jdbc);
            var unknown = new CpuMemorySupport(false, null, null, "미확인", List.of());
            assertThatThrownBy(() -> repository.insert(first, secondSource, unknown)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> repository.insert(gpu, gpuSource, unknown)).isInstanceOf(DataIntegrityViolationException.class);
            repository.insert(first, firstSource, unknown);
            assertThat(repository.findByProductId(first).orElseThrow().support()).isEqualTo(unknown);
            for (String assignments : List.of("max_memory_bytes = 0", "max_memory_bytes = -1", "channel_count = 0",
                    "channel_count = -1", "capacity_conditions = ' '", "evidence_source_id = " + secondSource)) {
                assertThatThrownBy(() -> jdbc.update("UPDATE cpu_memory_support SET " + assignments + " WHERE product_id = ?", first))
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
            for (String type : List.of("DDR3", "UNKNOWN")) {
                assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO cpu_memory_type_support (product_id, memory_type, data_rate_conditions)
                        VALUES (?, ?, '조건 미확인')
                        """, first, type)).isInstanceOf(DataIntegrityViolationException.class);
            }
            jdbc.update("""
                    INSERT INTO cpu_memory_type_support (product_id, memory_type, data_rate_conditions)
                    VALUES (?, 'DDR4', '속도 미확인')
                    """, first);
            for (String assignments : List.of("max_standard_data_rate_mts = 0", "max_standard_data_rate_mts = -1", "data_rate_conditions = ''")) {
                assertThatThrownBy(() -> jdbc.update("UPDATE cpu_memory_type_support SET " + assignments + " WHERE product_id = ?", first))
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO cpu_memory_type_support (product_id, memory_type, data_rate_conditions)
                    VALUES (?, 'DDR4', '중복')
                    """, first)).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(repository.findByProductId(first).orElseThrow().support().supportedTypes().getFirst().maxStandardDataRateMts()).isNull();
        }
    }

    private static SingleConnectionDataSource database() {
        return new SingleConnectionDataSource("jdbc:h2:mem:cpu-memory-migration-" + UUID.randomUUID() + ";MODE=MySQL", "sa", "", true);
    }

    private static String product(JdbcTemplate jdbc, String type) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO catalog_product (id, type, manufacturer, model_name, verification_status,
                    is_active, created_at, updated_at)
                VALUES (?, ?, 'Test fixture', 'Test CPU', 'CORE_VERIFIED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, type);
        return id;
    }

    private static long source(JdbcTemplate jdbc, String id) {
        jdbc.update("""
                INSERT INTO catalog_product_source (product_id, source_name, source_url, retrieved_at)
                VALUES (?, 'MANUFACTURER', 'https://example.com/test-cpu', CURRENT_TIMESTAMP)
                """, id);
        Long result = jdbc.queryForObject("SELECT id FROM catalog_product_source WHERE product_id = ?", Long.class, id);
        return result;
    }
}
