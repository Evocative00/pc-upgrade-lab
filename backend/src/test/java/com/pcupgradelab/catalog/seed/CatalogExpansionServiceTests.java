package com.pcupgradelab.catalog.seed;

import com.pcupgradelab.auth.SessionCurrentUser;
import com.pcupgradelab.catalog.CatalogEntryService;
import com.pcupgradelab.catalog.memory.CpuMemorySeedLoader;
import com.pcupgradelab.catalog.memory.CpuMemorySeedService;
import com.pcupgradelab.catalog.support.MotherboardCpuSeedLoader;
import com.pcupgradelab.catalog.support.MotherboardCpuSeedService;
import com.pcupgradelab.catalog.support.MotherboardCpuSupportRepository;
import com.pcupgradelab.compatibility.CompatibilityCheckService;
import com.pcupgradelab.compatibility.CompatibilityDtos;
import com.pcupgradelab.pc.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 테스트 트랜잭션 없이 100종·호환 자료의 실제 커밋/롤백/재실행과 기존 PC 보존을 검증한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:catalog-expand100-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "catalog.seed.enabled=false", "catalog.cpu-memory.seed.enabled=false", "catalog.motherboard-cpu.seed.enabled=false"
})
@ActiveProfiles({"local", "test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogExpansionServiceTests {
    private static final List<String> TABLES = List.of("pc_part", "pc_configuration", "motherboard_cpu_support",
            "motherboard_cpu_support_profile", "cpu_memory_type_support", "cpu_memory_support", "catalog_product_source",
            "gpu_power_connector", "monitor_spec", "gpu_spec", "cpu_spec", "motherboard_spec", "ram_spec",
            "catalog_reference_price", "catalog_product");
    @Autowired CatalogExpansionService expansion;
    @Autowired CatalogSeedService products;
    @Autowired CatalogSeedLoader productLoader;
    @Autowired CatalogEntryService entries;
    @Autowired CpuMemorySeedService memory;
    @Autowired MotherboardCpuSeedService boards;
    @Autowired MotherboardCpuSeedLoader boardLoader;
    @Autowired MotherboardCpuSupportRepository support;
    @Autowired CompatibilityCheckService compatibility;
    @Autowired PcService pcs;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext web;
    private MockMvc mvc;

    @BeforeEach
    void prepareDedicatedDatabase() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:catalog-expand100-test");
        }
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        for (String table : TABLES) assertThat(count(table)).as(table).isZero();
        mvc = MockMvcBuilders.webAppContextSetup(web).build();
        // PcService direct calls use a logged-in request; MockMvc requests remain anonymous.
        var request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(SessionCurrentUser.SESSION_ATTRIBUTE, 1L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void removeOnlyDedicatedFixtures() {
        RequestContextHolder.resetRequestAttributes();
        for (String table : TABLES) jdbc.update("DELETE FROM " + table);
    }

    @Test
    void commitsOneHundredProductsAndCompleteCrossCatalogSupportPreservingOriginalRowsAndPrices() {
        baseline();
        reviewPrice(id("Ryzen 5 5600X"));
        var before = snapshot();
        var result = expansion.seed();
        assertThat(result.products().created()).isEqualTo(100);
        assertThat(result.products().skipped()).isZero();
        assertThat(result.cpuMemory().created()).isEqualTo(25);
        assertThat(result.boardSupport().created()).isEqualTo(25);
        assertThat(result.boardSupport().extended()).isEqualTo(16);
        assertThat(result.boardSupport().skipped()).isZero();
        assertThat(result.boardSupport().cpuPairs()).isEqualTo(505);
        assertThat(result.boardSupport().variantRows()).isEqualTo(577);
        assertCounts();
        for (String table : TABLES) {
            if (table.equals("motherboard_cpu_support_profile")) continue; // 현재 전체 발췌의 출처 연결만 교체된다.
            assertThat(rows(table)).as(table).containsAll(before.get(table));
        }
        for (var input : boardLoader.load().items()) {
            var actual = support.findByRevision(productId(input.board().externalId()), input.support().resolve(this::productId).revisionKey()).orElseThrow();
            assertThat(actual.support().revisionScope()).isEqualTo(input.support().revisionScope());
            assertThat(actual.support().hardwareRevision()).isEqualTo(input.support().hardwareRevision());
            assertThat(actual.support().conditions()).isEqualTo(input.support().conditions());
            assertThat(actual.support().entries()).containsAll(input.support().resolve(this::productId).entries());
        }
        for (var request : productLoader.load(CatalogSeedBatch.EXPAND_100)) {
            String externalId = request.sources().getFirst().externalId();
            var actual = entries.findById(productId(externalId)).orElseThrow();
            assertThat(actual.specification()).isEqualTo(request.specification());
            assertThat(actual.product().referencePrice().amountKrw()).isNull();
            assertThat(actual.product().referencePrice().status().name()).isEqualTo("UNCONFIRMED");
        }
    }

    @Test
    void repeatedExpansionAndAllOriginalSeedsKeepIdsReviewedPricesAndEveryRow() {
        baseline();
        expansion.seed();
        reviewPrice(id("Core i9-14900K"));
        var before = snapshot();
        var result = expansion.seed();
        assertThat(result.products().created()).isZero();
        assertThat(result.products().skipped()).isEqualTo(100);
        assertThat(result.cpuMemory().created()).isZero();
        assertThat(result.cpuMemory().skipped()).isEqualTo(25);
        assertThat(result.boardSupport().created()).isZero();
        assertThat(result.boardSupport().extended()).isZero();
        assertThat(result.boardSupport().skipped()).isEqualTo(41);
        memory.seed(); boards.seed();
        for (var batch : CatalogSeedBatch.values()) if (batch != CatalogSeedBatch.EXPAND_100 && batch != CatalogSeedBatch.EXPAND_300) products.seed(batch);
        assertThat(snapshot()).isEqualTo(before);
        assertCounts();
    }

    @Test
    void lastBoardEvidenceFailureRollsBackAllProductsMemoryAndEarlierProfileExtensions() {
        baseline();
        var before = snapshot();
        String lastUrl = boardLoader.load(MotherboardCpuSeedLoader.EXPANSION).items().getLast().sourceUrl();
        jdbc.execute("ALTER TABLE catalog_product_source ADD CONSTRAINT ck_expand100_last_source CHECK (source_revision IS NULL OR source_revision <> 'motherboard-cpu-expand100' OR source_url <> '" + lastUrl + "')");
        try {
            assertThatThrownBy(expansion::seed).isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(error -> assertThat(NestedExceptionUtils.getMostSpecificCause(error).getMessage()).containsIgnoringCase("ck_expand100_last_source"));
            assertThat(snapshot()).isEqualTo(before);
            assertThat(count("catalog_product")).isEqualTo(61);
        } finally { jdbc.execute("ALTER TABLE catalog_product_source DROP CONSTRAINT ck_expand100_last_source"); }
    }

    @Test
    void mismatchedOriginalSupportIsNeverOverwrittenAndExpansionDoesNotPartiallyCommit() {
        baseline();
        jdbc.update("UPDATE motherboard_cpu_support SET minimum_bios_version = 'TEST99', manufacturer_bios_label = 'TEST99' WHERE motherboard_product_id = ?", id("B550-A PRO"));
        var before = snapshot();
        assertThatThrownBy(expansion::seed).isInstanceOf(IllegalStateException.class).hasMessageContaining("existing CPU support or evidence differs");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void missingBaselineStopsBeforeRegistrationAndConflictingNewProductRollsBack() {
        assertThatThrownBy(expansion::seed).isInstanceOf(IllegalStateException.class).hasMessageContaining("original 61");
        assertThat(count("catalog_product")).isZero();
        baseline();
        var conflicting = productLoader.load(CatalogSeedBatch.EXPAND_100).stream()
                .filter(item -> item.product().type() == PartType.MONITOR).reduce((a,b) -> b).orElseThrow();
        var existing = entries.create(conflicting);
        assertThat(jdbc.update("UPDATE monitor_spec SET native_width_px = 3840 WHERE product_id = ?", existing.product().id())).isEqualTo(1);
        var before = snapshot();
        assertThatThrownBy(expansion::seed).isInstanceOf(IllegalStateException.class).hasMessageContaining("product or specification differs");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void catalogPagesDetailsAndNewProductLinksUseExpandedSpecificationsWithoutMutatingStoredPc() throws Exception {
        baseline(); expansion.seed();
        for (var expected : Map.of("CPU", 37, "MOTHERBOARD", 41, "RAM", 41, "GPU", 34, "MONITOR", 8).entrySet()) {
            mvc.perform(get("/api/catalog/products").param("type", expected.getKey()).param("size", "100"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(expected.getValue()));
        }
        mvc.perform(get("/api/catalog/products").param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(161)).andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/catalog/products/{id}", id("Core i9-14900K")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.specification.performanceCoreCount").value(8))
                .andExpect(jsonPath("$.specification.efficientCoreCount").value(16));
        mvc.perform(get("/api/catalog/products/{id}/memory-support", id("Core i9-14900K")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.supportedTypes.length()").value(2));
        String cpu=id("Core i5-12400F"), board=id("PRO B660M-A DDR4"), ram=part("KVR32N22S8/16");
        var pc = pcs.create(new PcDtos.Request("기존 PC 보존", List.of(new PartInput(PartType.CPU,
                "사용자 CPU", "Intel CPU raw name", 1, InputSource.AUTO, cpu, MatchStatus.MATCHED, Map.of()))));
        var storedPc = pcs.findById(pc.id());
        var before=snapshot();
        var oldCpuNewBoard=checkKnown(cpu, board, ram, 2);
        assertThat(checkStatus(oldCpuNewBoard,"CPU_MANUFACTURER_SUPPORT")).isEqualTo("COMPATIBLE");
        assertThat(checkStatus(oldCpuNewBoard,"CPU_BIOS")).isEqualTo("COMPATIBLE");
        var newCpuOldBoard=checkKnown(id("Core i9-14900K"),id("PRO B760M-A WIFI DDR4"),ram,2);
        assertThat(checkStatus(newCpuOldBoard,"CPU_MANUFACTURER_SUPPORT")).isEqualTo("COMPATIBLE");
        assertThat(newCpuOldBoard.fullPcCompatibilityChecked()).isFalse();
        mvc.perform(get("/api/catalog/products/{id}/cpu-support", id("PRO B760M-A WIFI DDR4"))
                        .param("cpuProductId", id("Core i9-14900K")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasRecordedEntries").value(true))
                .andExpect(jsonPath("$.profiles[0].source.sourceRevision").value(MotherboardCpuSeedLoader.EXPANSION));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(pcs.findById(pc.id())).isEqualTo(storedPc);
    }

    @Test
    void exactModuleNumbersKeepOemAndXmpCapacityDistinctFromUpstreamNamesAndDdr5OnDieEcc() {
        baseline(); expansion.seed();
        var gskill = entries.findById(part("F4-3200C16D-32GVK")).orElseThrow();
        var kit = (com.pcupgradelab.catalog.CatalogSpecification.Ram) gskill.specification();
        assertThat(kit.moduleCapacityBytes()).isEqualTo(16L << 30);
        assertThat(kit.moduleCount()).isEqualTo(2);
        assertThat(kit.dataRateMts()).isEqualTo(3200);
        assertThat(kit.voltageV()).isEqualByComparingTo("1.35");
        assertThat(kit.pinCount()).isNull();
        var hynix = (com.pcupgradelab.catalog.CatalogSpecification.Ram) entries.findById(part("HMA451U6AFR8N-TF")).orElseThrow().specification();
        assertThat(hynix.moduleCapacityBytes()).isEqualTo(4L << 30);
        assertThat(hynix.isEcc()).isFalse();
        var samsung = entries.findById(part("M378A1K43CB2-CTD")).orElseThrow();
        assertThat(samsung.product().modelName()).doesNotContain("DB2");
        var ddr5 = (com.pcupgradelab.catalog.CatalogSpecification.Ram) entries.findById(part("KVR56U46BD8-32")).orElseThrow().specification();
        assertThat(ddr5.isEcc()).isFalse();
        assertThat(ddr5.bufferType()).isEqualTo("UNBUFFERED");
    }

    @Test
    void expandedConnectionsRejectDdrSocketAndSlotContradictionsAndKeepUnknownRevisionAndSupportUnresolved() {
        baseline(); expansion.seed();
        String ddr5=part("KVR56U46BD8-32"), ddr4=part("KVR32N22S8/16");
        var mismatch=checkKnown(id("Core i9-14900K"),id("PRO B660M-A DDR4"),ddr5,2);
        assertThat(mismatch.status().name()).isEqualTo("INCOMPATIBLE");
        assertThat(checkStatus(mismatch,"MOTHERBOARD_RAM_TYPE_0")).isEqualTo("INCOMPATIBLE");
        var slots=checkKnown(id("Core i5-12400F"),id("PRO B660M-A DDR4"),ddr4,5);
        assertThat(checkStatus(slots,"RAM_SLOT_COUNT")).isEqualTo("INCOMPATIBLE");
        var socket=compatibility.check(new CompatibilityDtos.Request(id("Ryzen 7 9800X3D"),id("PRO B660M-A DDR4"),
                List.of(new CompatibilityDtos.RamInput(ddr4,2)),null,null,null));
        assertThat(checkStatus(socket,"CPU_SOCKET")).isEqualTo("INCOMPATIBLE");
        var revision=compatibility.check(new CompatibilityDtos.Request(id("Ryzen 5 7600X"),id("B650 EAGLE AX"),
                List.of(new CompatibilityDtos.RamInput(ddr5,2)),null,null,null));
        assertThat(checkStatus(revision,"CPU_MANUFACTURER_SUPPORT")).isEqualTo("NEEDS_CHECK");
        var unknown=compatibility.check(new CompatibilityDtos.Request(id("Ryzen 7 9800X3D"),id("B650M Pro RS"),
                List.of(new CompatibilityDtos.RamInput(ddr5,2)),null,null,null));
        assertThat(checkStatus(unknown,"CPU_MANUFACTURER_SUPPORT")).isEqualTo("NEEDS_CHECK");
        assertThat(checkStatus(unknown,"CPU_BIOS")).isEqualTo("NEEDS_CHECK");
        String kit=part("KF432C16BBK2/64");
        var kitResult=checkKnown(id("Core i5-12400F"),id("PRO B660M-A DDR4"),kit,2);
        assertThat(kitResult.memory().installedModuleCount()).isEqualTo(2);
        assertThat(kitResult.memory().totalCapacityBytes()).isEqualTo(java.math.BigInteger.valueOf(64L << 30));
    }

    private void baseline() {
        for (var batch : CatalogSeedBatch.values()) if (batch != CatalogSeedBatch.EXPAND_100 && batch != CatalogSeedBatch.EXPAND_300) products.seed(batch);
        memory.seed(); boards.seed();
    }
    private CompatibilityDtos.Result checkKnown(String cpu,String board,String ram,int modules) {
        var profile=support.findByMotherboardId(board).getFirst().support();
        var row=profile.entries().stream().filter(e -> e.cpuProductId().equals(cpu)).findFirst().orElseThrow();
        return compatibility.check(new CompatibilityDtos.Request(cpu,board,List.of(new CompatibilityDtos.RamInput(ram,modules)),
                profile.hardwareRevision(),row.cpuStepping(),row.minimumBiosVersion()));
    }
    private static String checkStatus(CompatibilityDtos.Result result,String code) {
        return result.checks().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow().status().name();
    }
    private void assertCounts() {
        var expected=Map.of("catalog_product",161,"catalog_reference_price",161,"cpu_spec",37,"motherboard_spec",41,
                "ram_spec",41,"gpu_spec",34,"monitor_spec",8,"cpu_memory_support",37,"motherboard_cpu_support_profile",41,
                "motherboard_cpu_support",577);
        expected.forEach((table,n) -> assertThat(count(table)).as(table).isEqualTo(n));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT DISTINCT motherboard_product_id,cpu_product_id FROM motherboard_cpu_support) pairs",Integer.class)).isEqualTo(505);
    }
    private String id(String name) { return jdbc.queryForObject("SELECT id FROM catalog_product WHERE model_name = ?",String.class,name); }
    private String part(String number) { return jdbc.queryForObject("SELECT id FROM catalog_product WHERE type = 'RAM' AND part_number = ?",String.class,number); }
    private String productId(String externalId) { return jdbc.queryForObject("SELECT product_id FROM catalog_product_source WHERE source_name = 'BUILDCORES' AND external_id = ?",String.class,externalId); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    private List<Map<String,Object>> rows(String table) {
        var rows=jdbc.queryForList("SELECT * FROM "+table+(table.equals("motherboard_cpu_support") ? " ORDER BY 1,2,3,4" : " ORDER BY 1,2"));
        for(var row:rows) row.replaceAll((key,value) -> value instanceof byte[] data ? HexFormat.of().formatHex(data) : value);
        return rows;
    }
    private Map<String,List<Map<String,Object>>> snapshot() {
        var result=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:TABLES) result.put(table,rows(table));
        return result;
    }
    private void reviewPrice(String id) {
        jdbc.update("UPDATE catalog_product SET verification_status = 'CORE_VERIFIED', is_active = TRUE WHERE id = ?",id);
        jdbc.update("""
                UPDATE catalog_reference_price SET amount_krw = ?, status = 'CONFIRMED', method = 'MEDIAN_DAILY_6M_V1',
                  period_start = DATE '2026-03-29', period_end = DATE '2026-09-28', observed_day_count = 184, sample_count = 368,
                  price_basis = 'Test fixture', evidence_ref = 'test-fixture://expand100/price', calculated_at = CURRENT_TIMESTAMP,
                  confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE product_id = ?
                """,new BigDecimal("123456.78"),id);
    }
}
