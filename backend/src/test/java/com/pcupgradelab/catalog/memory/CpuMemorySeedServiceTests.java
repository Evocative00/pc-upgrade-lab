package com.pcupgradelab.catalog.memory;

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

/** 테스트 트랜잭션 없이 JPA 출처와 JDBC 보완 제원의 실제 커밋/전체 롤백/기존 61종 보존을 검사한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cpu-memory-seed-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "catalog.seed.enabled=false",
        "catalog.cpu-memory.seed.enabled=false"
})
@ActiveProfiles({"local", "test"})
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CpuMemorySeedServiceTests {
    private static final List<String> ORIGINAL_TABLES = List.of("catalog_product_source", "gpu_power_connector",
            "monitor_spec", "gpu_spec", "cpu_spec", "motherboard_spec", "ram_spec",
            "catalog_reference_price", "catalog_product");
    private static final List<String> MEMORY_TABLES = List.of("cpu_memory_type_support", "cpu_memory_support");
    @Autowired CatalogSeedService originalSeeds;
    @Autowired CpuMemorySeedService memorySeeds;
    @Autowired CpuMemorySeedLoader loader;
    @Autowired CpuMemorySupportRepository memory;
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
    void commitsTwelveProfilesSixteenTypesAndTwelveEvidenceRowsPreservingAllExistingValues() {
        seedOriginalCatalog();
        var before = ORIGINAL_TABLES.stream().map(this::rows).toList();
        var result = memorySeeds.seed();
        assertThat(result.created()).isEqualTo(12);
        assertThat(result.skipped()).isZero();
        assertThat(result.items()).hasSize(12).allSatisfy(item -> assertThat(item.created()).isTrue());
        var expected = loader.load();
        for (int i = 0; i < result.items().size(); i++) {
            assertThat(memory.findByProductId(result.items().get(i).productId()).orElseThrow().support())
                    .isEqualTo(expected.get(i).support());
        }
        for (int i = 0; i < ORIGINAL_TABLES.size(); i++) {
            var after = rows(ORIGINAL_TABLES.get(i));
            if (ORIGINAL_TABLES.get(i).equals("catalog_product_source")) assertThat(after).containsAll(before.get(i));
            else assertThat(after).isEqualTo(before.get(i));
        }
        assertCounts();
        assertThat(context.getBeansOfType(CpuMemorySeedRunner.class)).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void repeatedEnrichmentAndAllOriginalSeedBatchesPreserveEveryRowAndReviewedPrices() {
        seedOriginalCatalog();
        var first = memorySeeds.seed();
        var before = allTables().stream().map(this::rows).toList();
        var repeated = memorySeeds.seed();
        assertThat(repeated.created()).isZero();
        assertThat(repeated.skipped()).isEqualTo(12);
        assertThat(repeated.items().stream().map(CpuMemorySeedService.Item::productId).toList())
                .containsExactlyElementsOf(first.items().stream().map(CpuMemorySeedService.Item::productId).toList());
        for (var batch : CatalogSeedBatch.values()) if (batch != CatalogSeedBatch.EXPAND_100 && batch != CatalogSeedBatch.EXPAND_300) assertThat(originalSeeds.seed(batch).created()).isZero();
        for (int i = 0; i < allTables().size(); i++) assertThat(rows(allTables().get(i))).isEqualTo(before.get(i));
        assertCounts();
    }

    @Test
    void missingLastCpuSourceRollsBackEarlierMemoryProfilesAndEvidence() {
        seedOriginalCatalog();
        jdbc.update("DELETE FROM catalog_product_source WHERE source_name = 'BUILDCORES' AND external_id = ?",
                loader.load().getLast().externalId());
        var before = allTables().stream().map(this::rows).toList();
        assertThatThrownBy(() -> memorySeeds.seed()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CPU is missing").hasMessageContaining(loader.load().getLast().modelName());
        assertUnchanged(before);
    }

    @Test
    void lastDdr5InsertFailureRollsBackBothJpaEvidenceAndJdbcProfiles() {
        seedOriginalCatalog();
        var before = allTables().stream().map(this::rows).toList();
        String last = productId(loader.load().getLast().externalId());
        jdbc.execute("ALTER TABLE cpu_memory_type_support ADD CONSTRAINT ck_test_cpu_memory_last CHECK (product_id <> '"
                + last + "' OR memory_type <> 'DDR5')");
        try {
            assertThatThrownBy(() -> memorySeeds.seed()).isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(error -> assertThat(NestedExceptionUtils.getMostSpecificCause(error).getMessage())
                            .containsIgnoringCase("ck_test_cpu_memory_last"));
            assertUnchanged(before);
        } finally {
            jdbc.execute("ALTER TABLE cpu_memory_type_support DROP CONSTRAINT ck_test_cpu_memory_last");
        }
    }

    @Test
    void conflictingLastProfileIsNeverOverwrittenAndEarlierNewProfilesAreRolledBack() {
        seedOriginalCatalog();
        var seeded = memorySeeds.seed();
        for (var item : seeded.items().subList(0, 11)) {
            long evidence = memory.findByProductId(item.productId()).orElseThrow().evidenceSourceId();
            jdbc.update("DELETE FROM cpu_memory_type_support WHERE product_id = ?", item.productId());
            jdbc.update("DELETE FROM cpu_memory_support WHERE product_id = ?", item.productId());
            jdbc.update("DELETE FROM catalog_product_source WHERE id = ?", evidence);
        }
        jdbc.update("UPDATE cpu_memory_support SET max_memory_bytes = ? WHERE product_id = ?",
                64L * 1024 * 1024 * 1024, seeded.items().getLast().productId());
        var before = allTables().stream().map(this::rows).toList();
        assertThatThrownBy(() -> memorySeeds.seed()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("existing memory support or evidence differs");
        assertUnchanged(before);
    }

    @Test
    void memoryApiExplicitlyReturnsUnknownUntilEnrichedAndSeparatesDualDdrChoicesFromCompatibility() throws Exception {
        seedOriginalCatalog();
        var input = loader.load().getLast();
        String id = productId(input.externalId());
        mvc.perform(get("/api/catalog/products/{id}/memory-support", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataAvailable").value(false))
                .andExpect(jsonPath("$.memoryTypesKnown").value(false))
                .andExpect(jsonPath("$.supportedTypes.length()").value(0))
                .andExpect(jsonPath("$.maxMemoryBytes").value(nullValue()))
                .andExpect(jsonPath("$.source").value(nullValue()));
        memorySeeds.seed();
        mvc.perform(get("/api/catalog/products/{id}/memory-support", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataAvailable").value(true))
                .andExpect(jsonPath("$.memoryTypesKnown").value(true))
                .andExpect(jsonPath("$.maxMemoryBytes").value(206158430208L))
                .andExpect(jsonPath("$.supportedTypes[0].memoryType").value("DDR4"))
                .andExpect(jsonPath("$.supportedTypes[1].memoryType").value("DDR5"))
                .andExpect(jsonPath("$.supportedTypes[1].maxStandardDataRateMts").value(5600))
                .andExpect(jsonPath("$.source.sourceName").value("MANUFACTURER"))
                .andExpect(jsonPath("$.source.sourceUrl").value(input.sourceUrl()))
                .andExpect(jsonPath("$.source.rawPayload").doesNotExist())
                .andExpect(jsonPath("$.source.id").doesNotExist())
                .andExpect(jsonPath("$.compatible").doesNotExist());
        mvc.perform(get("/api/catalog/products/{id}/memory-support", productId(loader.load().getFirst().externalId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.maxMemoryBytes").value(nullValue()));
        mvc.perform(get("/api/catalog/products/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.product.type").value("CPU"))
                .andExpect(jsonPath("$.specification.socketCode").value("LGA1700"))
                .andExpect(jsonPath("$.specification.maxMemoryBytes").doesNotExist());
        String ram = jdbc.queryForList("SELECT id FROM catalog_product WHERE type = 'RAM' ORDER BY id", String.class).getFirst();
        mvc.perform(get("/api/catalog/products/{id}/memory-support", ram)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CPU_MEMORY_REQUIRES_CPU"));
        mvc.perform(get("/api/catalog/products/absent-id/memory-support")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATALOG_PRODUCT_NOT_FOUND"));
        assertCounts();
    }

    private void seedOriginalCatalog() {
        for (var batch : CatalogSeedBatch.values()) if (batch != CatalogSeedBatch.EXPAND_100 && batch != CatalogSeedBatch.EXPAND_300) originalSeeds.seed(batch);
        assertThat(count("catalog_product")).isEqualTo(61);
        assertThat(count("catalog_product_source")).isEqualTo(137);
        // 실제 검토 상태/가격을 먼저 변경한 후 보완 적재가 그대로 보존하는지 검사한다.
        for (var input : List.of(loader.load().getFirst(), loader.load().getLast())) {
            String id = productId(input.externalId());
            jdbc.update("UPDATE catalog_product SET verification_status = 'CORE_VERIFIED', is_active = TRUE WHERE id = ?", id);
            jdbc.update("""
                    UPDATE catalog_reference_price SET amount_krw = ?, status = 'CONFIRMED',
                        method = 'MEDIAN_DAILY_6M_V1', period_start = DATE '2026-03-29', period_end = DATE '2026-09-28',
                        observed_day_count = 184, sample_count = 368, price_basis = 'Test fixture',
                        evidence_ref = 'test-fixture://cpu-memory/price', calculated_at = CURRENT_TIMESTAMP,
                        confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE product_id = ?
                    """, new BigDecimal("123456.78"), id);
        }
    }

    private void assertCounts() {
        for (var expected : Map.of("catalog_product", 61, "catalog_reference_price", 61, "catalog_product_source", 149,
                "cpu_spec", 12, "motherboard_spec", 16, "ram_spec", 16, "gpu_spec", 14, "monitor_spec", 3,
                "cpu_memory_support", 12, "cpu_memory_type_support", 16).entrySet()) {
            assertThat(count(expected.getKey())).as(expected.getKey()).isEqualTo(expected.getValue());
        }
    }

    private String productId(String externalId) {
        return jdbc.queryForObject("SELECT product_id FROM catalog_product_source WHERE source_name = 'BUILDCORES' AND external_id = ?",
                String.class, externalId);
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private List<Map<String, Object>> rows(String table) {
        var rows = jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1, 2");
        for (var row : rows) row.replaceAll((key, value) -> value instanceof byte[] data ? HexFormat.of().formatHex(data) : value);
        return rows;
    }

    private static List<String> allTables() {
        var tables = new ArrayList<>(MEMORY_TABLES);
        tables.addAll(ORIGINAL_TABLES);
        return List.copyOf(tables);
    }

    private void assertUnchanged(List<List<Map<String, Object>>> before) {
        for (int i = 0; i < allTables().size(); i++) assertThat(rows(allTables().get(i))).as(allTables().get(i)).isEqualTo(before.get(i));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private void assertDedicatedDatabase() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL().split(";", 2)[0]).isEqualTo("jdbc:h2:mem:cpu-memory-seed-test");
        }
    }
}
