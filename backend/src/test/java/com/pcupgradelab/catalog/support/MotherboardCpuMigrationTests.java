package com.pcupgradelab.catalog.support;

import com.pcupgradelab.catalog.memory.CpuMemorySupport;
import com.pcupgradelab.catalog.memory.CpuMemorySupportRepository;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static com.pcupgradelab.catalog.support.MotherboardCpuSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MotherboardCpuMigrationTests {
    @Test
    void v9PreservesV8RowsIncludingMemoryEvidenceAndOnlyCreatesEmptySupportTables() {
        try (var ds = database()) {
            var jdbc = new JdbcTemplate(ds);
            Flyway.configure().dataSource(ds).target(MigrationVersion.fromVersion("8")).load().migrate();
            String cpu = product(jdbc, "CPU"), board = product(jdbc, "MOTHERBOARD");
            jdbc.update("INSERT INTO cpu_spec (product_id, socket_code) VALUES (?, 'AM4')", cpu);
            jdbc.update("INSERT INTO motherboard_spec (product_id, socket_code) VALUES (?, 'AM4')", board);
            long evidence = source(jdbc, cpu);
            source(jdbc, board);
            new CpuMemorySupportRepository(jdbc).insert(cpu, evidence, new CpuMemorySupport(false, null, null, "미확인", List.of()));
            jdbc.update("INSERT INTO catalog_reference_price (product_id, updated_at) VALUES (?, CURRENT_TIMESTAMP)", cpu);
            var tables = List.of("catalog_product", "catalog_product_source", "cpu_spec", "motherboard_spec",
                    "catalog_reference_price", "cpu_memory_support", "cpu_memory_type_support");
            var before = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1")).toList();
            var latest = Flyway.configure().dataSource(ds).target(MigrationVersion.fromVersion("9")).load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            for (int i = 0; i < tables.size(); i++) assertThat(jdbc.queryForList("SELECT * FROM " + tables.get(i) + " ORDER BY 1")).isEqualTo(before.get(i));
            for (String table : List.of("motherboard_cpu_support_profile", "motherboard_cpu_support")) {
                Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
                assertThat(count).isZero();
            }
            assertThat(latest.migrate().migrationsExecuted).isZero();
        }
    }

    @Test
    void databaseSeparatesRevisionsVariantsAllAndUnknownAndRejectsContradictionsOrWrongEvidence() {
        try (var ds = database()) {
            var jdbc = new JdbcTemplate(ds);
            Flyway.configure().dataSource(ds).target(MigrationVersion.fromVersion("9")).load().migrate();
            String board = product(jdbc, "MOTHERBOARD"), otherBoard = product(jdbc, "MOTHERBOARD"), cpu = product(jdbc, "CPU"), ram = product(jdbc, "RAM");
            jdbc.update("INSERT INTO motherboard_spec (product_id) VALUES (?)", board);
            jdbc.update("INSERT INTO motherboard_spec (product_id) VALUES (?)", otherBoard);
            jdbc.update("INSERT INTO cpu_spec (product_id) VALUES (?)", cpu);
            long boardEvidence = source(jdbc, board), otherEvidence = source(jdbc, otherBoard), cpuEvidence = source(jdbc, cpu);
            var repository = new MotherboardCpuSupportRepository(jdbc);
            var all = new Entry(cpu, "B0", SupportStatus.LISTED, "Test CPU", "B0", BiosRequirement.ALL, null, "All", URL, "제조사 조건");
            var unknown = new Entry(cpu, "B2", SupportStatus.LISTED, "Test CPU", "B2", BiosRequirement.UNKNOWN, null, "Latest Beta BIOS", URL, "제조사 지시");
            var model = new MotherboardCpuSupport(RevisionScope.MODEL, null, "모델 목록", List.of(all, unknown));
            assertThatThrownBy(() -> repository.insert(board, otherEvidence, model)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> repository.insert(cpu, cpuEvidence, model)).isInstanceOf(DataIntegrityViolationException.class);
            repository.insert(board, boardEvidence, model);
            repository.insert(board, boardEvidence, new MotherboardCpuSupport(RevisionScope.EXACT, "1.0", "PCB 1.0", List.of(all)));
            assertThat(repository.findByMotherboardId(board)).hasSize(2);
            assertThat(repository.findByRevision(board, "MODEL").orElseThrow().support()).isEqualTo(model);
            assertThatThrownBy(() -> repository.insert(board, boardEvidence, model)).isInstanceOf(DataIntegrityViolationException.class);
            for (String assignment : List.of("revision_scope = 'UNKNOWN'", "hardware_revision = '1.0'", "conditions = ' '", "evidence_source_id = " + otherEvidence)) {
                assertThatThrownBy(() -> jdbc.update("UPDATE motherboard_cpu_support_profile SET " + assignment
                        + " WHERE motherboard_product_id = ? AND revision_key = 'MODEL'", board)).isInstanceOf(DataIntegrityViolationException.class);
            }
            for (String assignment : List.of("bios_requirement = 'VERSION'", "minimum_bios_version = '0'", "manufacturer_bios_label = NULL",
                    "support_status = 'UNVERIFIED'", "cpu_product_id = '" + ram + "'", "cpu_stepping = ' '", "conditions = ''", "bios_requirement = 'COMPATIBLE'")) {
                assertThatThrownBy(() -> jdbc.update("UPDATE motherboard_cpu_support SET " + assignment
                        + " WHERE motherboard_product_id = ? AND revision_key = 'MODEL' AND variant_key = 'B0'", board)).isInstanceOf(DataIntegrityViolationException.class);
            }
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE motherboard_cpu_support SET bios_requirement = 'VERSION', minimum_bios_version = 'ALL'
                    WHERE motherboard_product_id = ? AND revision_key = 'MODEL' AND variant_key = 'B0'
                    """, board)).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(repository.findByRevision(board, "MODEL").orElseThrow().support()).isEqualTo(model);
        }
    }

    private static final String URL = "https://www.msi.com/Motherboard/B550-A-PRO/support";
    private static SingleConnectionDataSource database() {
        return new SingleConnectionDataSource("jdbc:h2:mem:motherboard-cpu-migration-" + UUID.randomUUID() + ";MODE=MySQL", "sa", "", true);
    }
    private static String product(JdbcTemplate jdbc, String type) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO catalog_product (id, type, manufacturer, model_name, verification_status, is_active, created_at, updated_at)
                VALUES (?, ?, 'Test fixture', 'Test product', 'CORE_VERIFIED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, type);
        return id;
    }
    private static long source(JdbcTemplate jdbc, String productId) {
        jdbc.update("""
                INSERT INTO catalog_product_source (product_id, source_name, source_url, retrieved_at)
                VALUES (?, 'MANUFACTURER', ?, CURRENT_TIMESTAMP)
                """, productId, URL);
        Long id = jdbc.queryForObject("SELECT id FROM catalog_product_source WHERE product_id = ?", Long.class, productId);
        return id;
    }
}
