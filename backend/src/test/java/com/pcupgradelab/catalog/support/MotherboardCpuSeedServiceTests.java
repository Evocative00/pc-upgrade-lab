package com.pcupgradelab.catalog.support;

import com.pcupgradelab.catalog.memory.CpuMemorySeedService;
import com.pcupgradelab.catalog.seed.CatalogSeedBatch;
import com.pcupgradelab.catalog.seed.CatalogSeedService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 테스트 트랜잭션 없이 실제 커밋/전체 롤백과 기존 61종·검토 상태·가격·CPU 메모리 자료 보존을 확인한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:motherboard-cpu-seed-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "catalog.seed.enabled=false", "catalog.cpu-memory.seed.enabled=false", "catalog.motherboard-cpu.seed.enabled=false"
})
@ActiveProfiles({"local", "test"})
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MotherboardCpuSeedServiceTests {
    private static final List<String> OLD_TABLES = List.of("cpu_memory_type_support", "cpu_memory_support", "catalog_product_source",
            "gpu_power_connector", "monitor_spec", "gpu_spec", "cpu_spec", "motherboard_spec", "ram_spec", "catalog_reference_price", "catalog_product");
    private static final List<String> NEW_TABLES = List.of("motherboard_cpu_support", "motherboard_cpu_support_profile");
    @Autowired CatalogSeedService originalSeeds;
    @Autowired CpuMemorySeedService memorySeeds;
    @Autowired MotherboardCpuSeedService supportSeeds;
    @Autowired MotherboardCpuSeedLoader loader;
    @Autowired MotherboardCpuSupportRepository support;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;
    @Autowired WebApplicationContext web;
    private MockMvc mvc;

    @BeforeEach
    void requireEmptyDedicatedDatabase() throws Exception {
        assertDedicatedDatabase();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        for (String table : allTables()) assertThat(count(table)).as(table).isZero();
        mvc = MockMvcBuilders.webAppContextSetup(web).build();
    }
    @AfterEach
    void cleanOnlyDedicatedFixtures() throws Exception {
        assertDedicatedDatabase();
        for (String table : allTables()) jdbc.update("DELETE FROM " + table);
    }

    @Test
    void commitsSixteenProfilesSixtyFourPairsSeventyOneVariantRowsPreservingExistingValues() {
        seedExistingCatalog();
        var before = OLD_TABLES.stream().map(this::rows).toList();
        var result = supportSeeds.seed();
        assertThat(result.created()).isEqualTo(16);
        assertThat(result.skipped()).isZero();
        assertThat(result.cpuPairs()).isEqualTo(64);
        assertThat(result.variantRows()).isEqualTo(71);
        assertThat(result.items()).hasSize(16).allSatisfy(item -> assertThat(item.created()).isTrue());
        for (var item : loader.load().items()) {
            var expected = item.support().resolve(this::productId);
            assertThat(support.findByRevision(productId(item.board().externalId()), expected.revisionKey()).orElseThrow().support()).isEqualTo(expected);
        }
        for (int i = 0; i < OLD_TABLES.size(); i++) {
            var after = rows(OLD_TABLES.get(i));
            if (OLD_TABLES.get(i).equals("catalog_product_source")) assertThat(after).containsAll(before.get(i));
            else assertThat(after).as(OLD_TABLES.get(i)).isEqualTo(before.get(i));
        }
        assertCounts();
        assertThat(context.getBeansOfType(MotherboardCpuSeedRunner.class)).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void repeatedEnrichmentAndOldSeedBatchesPreserveEveryRowAndIdentity() {
        seedExistingCatalog();
        var first = supportSeeds.seed();
        var before = allTables().stream().map(this::rows).toList();
        var repeated = supportSeeds.seed();
        assertThat(repeated.created()).isZero();
        assertThat(repeated.skipped()).isEqualTo(16);
        assertThat(repeated.items().stream().map(MotherboardCpuSeedService.Item::productId).toList())
                .containsExactlyElementsOf(first.items().stream().map(MotherboardCpuSeedService.Item::productId).toList());
        assertThat(memorySeeds.seed().created()).isZero();
        for (var batch : CatalogSeedBatch.values()) if (batch != CatalogSeedBatch.EXPAND_100 && batch != CatalogSeedBatch.EXPAND_300) assertThat(originalSeeds.seed(batch).created()).isZero();
        assertUnchanged(before);
        assertCounts();
    }

    @Test
    void missingLastBoardSourceRollsBackAllEarlierSupportAndEvidence() {
        seedExistingCatalog();
        var last = loader.load().items().getLast();
        jdbc.update("DELETE FROM catalog_product_source WHERE source_name = 'BUILDCORES' AND external_id = ?", last.board().externalId());
        var before = allTables().stream().map(this::rows).toList();
        assertThatThrownBy(() -> supportSeeds.seed()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("catalog product is missing").hasMessageContaining(last.board().modelName());
        assertUnchanged(before);
    }

    @Test
    void mismatchedCpuSocketIsNeverSilentlyAccepted() {
        seedExistingCatalog();
        String cpu = productId(loader.load().cpus().getLast().externalId());
        jdbc.update("UPDATE cpu_spec SET socket_code = 'AM4' WHERE product_id = ?", cpu);
        var before = allTables().stream().map(this::rows).toList();
        assertThatThrownBy(() -> supportSeeds.seed()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("existing identity, socket or BuildCores revision differs");
        assertUnchanged(before);
    }

    @Test
    void lastVariantDatabaseFailureRollsBackJpaEvidenceAndJdbcRowsTogether() {
        seedExistingCatalog();
        String last = productId(loader.load().items().getLast().board().externalId());
        var before = allTables().stream().map(this::rows).toList();
        jdbc.execute("ALTER TABLE motherboard_cpu_support ADD CONSTRAINT ck_test_mb_cpu_last CHECK (motherboard_product_id <> '" + last + "')");
        try {
            assertThatThrownBy(() -> supportSeeds.seed()).isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(error -> assertThat(NestedExceptionUtils.getMostSpecificCause(error).getMessage()).containsIgnoringCase("ck_test_mb_cpu_last"));
            assertUnchanged(before);
        } finally { jdbc.execute("ALTER TABLE motherboard_cpu_support DROP CONSTRAINT ck_test_mb_cpu_last"); }
    }

    @Test
    void existingChangedLastSupportIsPreservedAndEarlierNewProfilesRollBack() {
        seedExistingCatalog();
        var first = supportSeeds.seed();
        for (var item : first.items().subList(0, 15)) {
            long evidence = support.findByRevision(item.productId(), item.revisionKey()).orElseThrow().evidenceSourceId();
            jdbc.update("DELETE FROM motherboard_cpu_support WHERE motherboard_product_id = ?", item.productId());
            jdbc.update("DELETE FROM motherboard_cpu_support_profile WHERE motherboard_product_id = ?", item.productId());
            jdbc.update("DELETE FROM catalog_product_source WHERE id = ?", evidence);
        }
        jdbc.update("UPDATE motherboard_cpu_support SET minimum_bios_version = 'F99', manufacturer_bios_label = 'F99' WHERE motherboard_product_id = ?",
                first.items().getLast().productId());
        var before = allTables().stream().map(this::rows).toList();
        assertThatThrownBy(() -> supportSeeds.seed()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("existing CPU support or evidence differs");
        assertUnchanged(before);
    }

    @Test
    void apiSeparatesMissingRecordsFromUnsupportedAndReturnsSteppingBiosAndRevisionConditions() throws Exception {
        seedExistingCatalog();
        String board = idForModel("B550-A PRO"), cpu = idForModel("Ryzen 5 5600X");
        mvc.perform(get("/api/catalog/products/{id}/cpu-support", board)).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataAvailable").value(false)).andExpect(jsonPath("$.listComplete").value(false))
                .andExpect(jsonPath("$.hasRecordedEntries").value(false)).andExpect(jsonPath("$.profiles.length()").value(0));
        supportSeeds.seed();
        mvc.perform(get("/api/catalog/products/{id}/cpu-support", board).param("cpuProductId", cpu)).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataAvailable").value(true)).andExpect(jsonPath("$.listComplete").value(false))
                .andExpect(jsonPath("$.hasRecordedEntries").value(true))
                .andExpect(jsonPath("$.profiles[0].revisionScope").value("MODEL"))
                .andExpect(jsonPath("$.profiles[0].hardwareRevision").value(nullValue()))
                .andExpect(jsonPath("$.profiles[0].entries.length()").value(2))
                .andExpect(jsonPath("$.profiles[0].entries[0].support.cpuStepping").value("B0"))
                .andExpect(jsonPath("$.profiles[0].entries[0].support.minimumBiosVersion").value("7C56vA4"))
                .andExpect(jsonPath("$.profiles[0].entries[1].support.cpuStepping").value("B2"))
                .andExpect(jsonPath("$.profiles[0].entries[1].support.minimumBiosVersion").value("7C56vA7"))
                .andExpect(jsonPath("$.profiles[0].source.sourceName").value("MANUFACTURER"))
                .andExpect(jsonPath("$.profiles[0].source.rawPayload").doesNotExist())
                .andExpect(jsonPath("$.profiles[0].source.id").doesNotExist())
                .andExpect(jsonPath("$.compatible").doesNotExist()).andExpect(jsonPath("$.supported").doesNotExist());
        mvc.perform(get("/api/catalog/products/{id}/cpu-support", idForModel("B450M PRO-VDH MAX")).param("cpuProductId", cpu))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profiles[0].entries[1].support.supportStatus").value("LISTED"))
                .andExpect(jsonPath("$.profiles[0].entries[1].support.biosRequirement").value("UNKNOWN"))
                .andExpect(jsonPath("$.profiles[0].entries[1].support.minimumBiosVersion").value(nullValue()))
                .andExpect(jsonPath("$.profiles[0].entries[1].support.manufacturerBiosLabel").value("Latest Beta BIOS"));
        mvc.perform(get("/api/catalog/products/{id}/cpu-support", idForModel("B550M Pro4")).param("cpuProductId", idForModel("Ryzen 5 3600")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profiles[0].entries[0].support.biosRequirement").value("ALL"))
                .andExpect(jsonPath("$.profiles[0].entries[0].support.minimumBiosVersion").value(nullValue()));
        mvc.perform(get("/api/catalog/products/{id}/cpu-support", idForModel("B760M DS3H DDR4")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profiles[0].revisionScope").value("EXACT"))
                .andExpect(jsonPath("$.profiles[0].hardwareRevision").value("1.0"));
        mvc.perform(get("/api/catalog/products/{id}/cpu-support", board).param("cpuProductId", idForModel("Ryzen 5 7600")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasRecordedEntries").value(false))
                .andExpect(jsonPath("$.listComplete").value(false)).andExpect(jsonPath("$.profiles[0].entries.length()").value(0))
                .andExpect(jsonPath("$.unsupported").doesNotExist());
        mvc.perform(get("/api/catalog/products/{id}/cpu-support", cpu)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MOTHERBOARD_CPU_REQUIRES_CORRECT_TYPE"));
        mvc.perform(get("/api/catalog/products/{id}/cpu-support", board).param("cpuProductId", board)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/products/{id}/cpu-support", board).param("cpuProductId", "missing"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CATALOG_PRODUCT_NOT_FOUND"));
        mvc.perform(get("/api/catalog/products/absent-id/cpu-support")).andExpect(status().isNotFound());
        assertCounts();
    }

    private void seedExistingCatalog() {
        for (var batch : CatalogSeedBatch.values()) if (batch != CatalogSeedBatch.EXPAND_100 && batch != CatalogSeedBatch.EXPAND_300) originalSeeds.seed(batch);
        memorySeeds.seed();
        assertThat(count("catalog_product")).isEqualTo(61);
        assertThat(count("catalog_product_source")).isEqualTo(149);
        for (String id : List.of(idForModel("B550-A PRO"), idForModel("Ryzen 5 5600X"))) {
            jdbc.update("UPDATE catalog_product SET verification_status = 'CORE_VERIFIED', is_active = TRUE WHERE id = ?", id);
            jdbc.update("""
                    UPDATE catalog_reference_price SET amount_krw = ?, status = 'CONFIRMED', method = 'MEDIAN_DAILY_6M_V1',
                        period_start = DATE '2026-03-29', period_end = DATE '2026-09-28', observed_day_count = 184, sample_count = 368,
                        price_basis = 'Test fixture', evidence_ref = 'test-fixture://motherboard-cpu/price',
                        calculated_at = CURRENT_TIMESTAMP, confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE product_id = ?
                    """, new BigDecimal("123456.78"), id);
        }
    }
    private void assertCounts() {
        for (var expected : Map.of("catalog_product", 61, "catalog_product_source", 165, "catalog_reference_price", 61,
                "cpu_memory_support", 12, "cpu_memory_type_support", 16, "motherboard_cpu_support_profile", 16, "motherboard_cpu_support", 71).entrySet()) {
            assertThat(count(expected.getKey())).as(expected.getKey()).isEqualTo(expected.getValue());
        }
    }
    private String productId(String externalId) {
        return jdbc.queryForObject("SELECT product_id FROM catalog_product_source WHERE source_name = 'BUILDCORES' AND external_id = ?", String.class, externalId);
    }
    private String idForModel(String model) { return jdbc.queryForObject("SELECT id FROM catalog_product WHERE model_name = ?", String.class, model); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private List<Map<String, Object>> rows(String table) {
        var rows = jdbc.queryForList("SELECT * FROM " + table + (table.equals("motherboard_cpu_support") ? " ORDER BY 1, 2, 3, 4" : " ORDER BY 1, 2"));
        for (var row : rows) row.replaceAll((key, value) -> value instanceof byte[] data ? HexFormat.of().formatHex(data) : value);
        return rows;
    }
    private static List<String> allTables() { var tables = new ArrayList<>(NEW_TABLES); tables.addAll(OLD_TABLES); return List.copyOf(tables); }
    private void assertUnchanged(List<List<Map<String, Object>>> before) {
        for (int i = 0; i < allTables().size(); i++) assertThat(rows(allTables().get(i))).as(allTables().get(i)).isEqualTo(before.get(i));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }
    private void assertDedicatedDatabase() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL().split(";", 2)[0]).isEqualTo("jdbc:h2:mem:motherboard-cpu-seed-test");
        }
    }
}
