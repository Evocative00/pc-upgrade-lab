package com.pcupgradelab.catalog.seed;

import com.pcupgradelab.auth.SessionCurrentUser;
import com.pcupgradelab.catalog.CatalogEntryService;
import com.pcupgradelab.catalog.CatalogSpecification;
import com.pcupgradelab.catalog.memory.CpuMemorySeedLoader;
import com.pcupgradelab.catalog.memory.CpuMemorySeedService;
import com.pcupgradelab.catalog.support.MotherboardCpuSeedLoader;
import com.pcupgradelab.catalog.support.MotherboardCpuSeedService;
import com.pcupgradelab.catalog.support.MotherboardCpuSupportRepository;
import com.pcupgradelab.compatibility.CompatibilityCheckService;
import com.pcupgradelab.compatibility.CompatibilityDtos;
import com.pcupgradelab.pc.*;
import java.math.BigDecimal;
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

/** Actual commits outside test transactions verify the third expansion and every earlier replay. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:catalog300-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "catalog.seed.enabled=false", "catalog.cpu-memory.seed.enabled=false", "catalog.motherboard-cpu.seed.enabled=false"
})
@ActiveProfiles({"local", "test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Catalog300ExpansionServiceTests {
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
    void verifyDedicatedEmptyDatabase() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:catalog300-test");
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
    void cleanOnlyDedicatedFixtures() {
        RequestContextHolder.resetRequestAttributes();
        for (String table : TABLES) jdbc.update("DELETE FROM " + table);
    }

    @Test
    void commitsApproved139AndAll70ConnectionsWithoutChangingSavedPcReviewedPriceOrOriginalRows() {
        baseline161();
        reviewPrice(id("Ryzen 5 5600X"));
        var pc = pcs.create(new PcDtos.Request("Existing PC", List.of(new PartInput(PartType.CPU,
                "User CPU", "AMD CPU raw", 1, InputSource.AUTO, id("Ryzen 5 5600X"), MatchStatus.MATCHED, Map.of()))));
        var saved = pcs.findById(pc.id());
        var before = snapshot();
        var result = expansion.seed300();
        assertThat(result.products().created()).isEqualTo(139);
        assertThat(result.products().skipped()).isZero();
        assertThat(result.cpuMemory().created()).isEqualTo(33);
        assertThat(result.boardSupport().created()).isEqualTo(29);
        assertThat(result.boardSupport().extended()).isEqualTo(41);
        assertThat(result.boardSupport().cpuPairs()).isEqualTo(1638);
        assertCounts300();
        for (String table : TABLES) {
            if (!table.equals("motherboard_cpu_support_profile")) {
                assertThat(rows(table)).as(table).containsAll(before.get(table));
            }
        }
        assertThat(pcs.findById(pc.id())).isEqualTo(saved);
        for (var request : productLoader.load(CatalogSeedBatch.EXPAND_300)) {
            var actual = entries.findById(productId(request.sources().getFirst().externalId())).orElseThrow();
            assertThat(actual.specification()).isEqualTo(request.specification());
            assertThat(actual.product().referencePrice().amountKrw()).isNull();
            assertThat(actual.product().referencePrice().status().name()).isEqualTo("UNCONFIRMED");
        }
    }

    @Test
    void repeated300AndEveryEarlierSeedPreserveAllRowsIdsAndEvidence() {
        baseline161(); expansion.seed300(); reviewPrice(id("Core i5-14500"));
        var before = snapshot();
        var repeat = expansion.seed300();
        assertThat(repeat.products().created()).isZero();
        assertThat(repeat.products().skipped()).isEqualTo(139);
        assertThat(repeat.cpuMemory().skipped()).isEqualTo(33);
        assertThat(repeat.boardSupport().created()).isZero();
        assertThat(repeat.boardSupport().extended()).isZero();
        assertThat(repeat.boardSupport().skipped()).isEqualTo(70);
        for (var batch : CatalogSeedBatch.values()) products.seed(batch);
        memory.seed(); memory.seed(CpuMemorySeedLoader.EXPANSION); memory.seed(CpuMemorySeedLoader.EXPANSION_300);
        boards.seed(); boards.seed(MotherboardCpuSeedLoader.EXPANSION); boards.seed(MotherboardCpuSeedLoader.EXPANSION_300);
        var previous = expansion.seed();
        assertThat(previous.products().created()).isZero();
        assertThat(previous.boardSupport().skipped()).isEqualTo(41);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void lastBoardSourceFailureRollsBack139Products33MemoryAndAllEarlierBoardExtensions() {
        baseline161(); var before = snapshot();
        String lastUrl = boardLoader.load(MotherboardCpuSeedLoader.EXPANSION_300).items().getLast().sourceUrl();
        jdbc.execute("ALTER TABLE catalog_product_source ADD CONSTRAINT ck_catalog300_last_source CHECK (source_revision IS NULL OR source_revision <> 'motherboard-cpu-expand300' OR source_url <> '" + lastUrl.replace("'", "''") + "')");
        try {
            assertThatThrownBy(expansion::seed300).isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(error -> assertThat(NestedExceptionUtils.getMostSpecificCause(error).getMessage()).containsIgnoringCase("ck_catalog300_last_source"));
            assertThat(snapshot()).isEqualTo(before);
            assertThat(count("catalog_product")).isEqualTo(161);
        } finally { jdbc.execute("ALTER TABLE catalog_product_source DROP CONSTRAINT ck_catalog300_last_source"); }
    }

    @Test
    void changedExistingBiosOrEvidenceCannotBeSilentlyReplaced() {
        baseline161();
        jdbc.update("UPDATE motherboard_cpu_support SET minimum_bios_version = 'TEST99', manufacturer_bios_label = 'TEST99' WHERE motherboard_product_id = ? AND bios_requirement = 'VERSION'", id("B550-A PRO"));
        var before = snapshot();
        assertThatThrownBy(expansion::seed300).isInstanceOf(IllegalStateException.class);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void missingBaselineOrMemoryProfileStopsWithoutRepairOrPartialRegistration() {
        assertThatThrownBy(expansion::seed300).isInstanceOf(IllegalStateException.class);
        assertThat(count("catalog_product")).isZero();
        baseline161();
        String cpu = id("Core i5-12400F");
        jdbc.update("DELETE FROM cpu_memory_type_support WHERE product_id = ?", cpu);
        jdbc.update("DELETE FROM cpu_memory_support WHERE product_id = ?", cpu);
        var before = snapshot();
        assertThatThrownBy(expansion::seed300).isInstanceOf(IllegalStateException.class);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(count("cpu_memory_support")).isEqualTo(36);
    }

    @Test
    void newAndOldPartsConnectConservativelyAndDdrSocketSlotsStillRejectContradictions() {
        baseline161(); expansion.seed300();
        var oldCpuNewBoard = checkKnown(id("Ryzen 5 5600X"), id("MAG B550 TOMAHAWK"), part("KVR32N22S8/16"), 2);
        assertThat(checkStatus(oldCpuNewBoard, "CPU_MANUFACTURER_SUPPORT")).isEqualTo("COMPATIBLE");
        assertThat(checkStatus(oldCpuNewBoard, "CPU_BIOS")).isEqualTo("COMPATIBLE");
        var newCpuOldBoard = checkKnown(id("Ryzen 9 7900"), id("PRO B650M-A WIFI"), part("KVR56U46BD8-32"), 2);
        assertThat(checkStatus(newCpuOldBoard, "CPU_MANUFACTURER_SUPPORT")).isEqualTo("COMPATIBLE");
        var newParts = checkKnown(id("Core i5-14500"), id("PRO B760-P WIFI DDR4"), part("KVR26N19D8/16"), 2);
        assertThat(checkStatus(newParts, "CPU_BIOS")).isEqualTo("COMPATIBLE");
        assertThat(newParts.fullPcCompatibilityChecked()).isFalse();
        var wrongDdr = checkKnown(id("Core i5-14500"), id("PRO B760-P WIFI DDR4"), part("KVR56U46BS6-8"), 2);
        assertThat(checkStatus(wrongDdr, "MOTHERBOARD_RAM_TYPE_0")).isEqualTo("INCOMPATIBLE");
        var slots = checkKnown(id("Core i5-14500"), id("PRO B760-P WIFI DDR4"), part("KVR26N19D8/16"), 5);
        assertThat(checkStatus(slots, "RAM_SLOT_COUNT")).isEqualTo("INCOMPATIBLE");
        var socket = compatibility.check(new CompatibilityDtos.Request(id("Ryzen 9 7900"), id("PRO B760-P WIFI DDR4"),
                List.of(new CompatibilityDtos.RamInput(part("KVR26N19D8/16"), 2)), null, null, null));
        assertThat(checkStatus(socket, "CPU_SOCKET")).isEqualTo("INCOMPATIBLE");
        var unknown = compatibility.check(new CompatibilityDtos.Request(id("Ryzen 9 7900"), id("B650 EAGLE AX"),
                List.of(new CompatibilityDtos.RamInput(part("KVR56U46BD8-32"), 2)), null, null, null));
        assertThat(checkStatus(unknown, "CPU_MANUFACTURER_SUPPORT")).isEqualTo("NEEDS_CHECK");
    }

    @Test
    void apiCountsAndExactKitNativeOcAndUnknownPowerSemanticsRemainVisible() throws Exception {
        baseline161(); expansion.seed300();
        for (var expected : Map.of("CPU", 70, "MOTHERBOARD", 70, "RAM", 60, "GPU", 80, "MONITOR", 20).entrySet()) {
            mvc.perform(get("/api/catalog/products").param("type", expected.getKey()).param("size", "100"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(expected.getValue()));
        }
        mvc.perform(get("/api/catalog/products").param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(300))
                .andExpect(jsonPath("$.totalPages").value(3));
        var kit = (CatalogSpecification.Ram) entries.findById(part("CMK48GX5M2B5600C40")).orElseThrow().specification();
        assertThat(kit.moduleCapacityBytes()).isEqualTo(24L << 30);
        assertThat(kit.moduleCount()).isEqualTo(2);
        assertThat(kit.isEcc()).isNull();
        var screen = (CatalogSpecification.Monitor) entries.findById(id("VG259QM")).orElseThrow().specification();
        assertThat(screen.nativeStandardRefreshHz()).isEqualByComparingTo("240");
        assertThat(screen.nativeOcRefreshHz()).isEqualByComparingTo("280");
        var gpu = (CatalogSpecification.Gpu) entries.findById(id("Red Devil Radeon RX 6900 XT 16GB")).orElseThrow().specification();
        assertThat(gpu.cardPowerW()).isNull();
        assertThat(gpu.psuRequirementW()).isEqualTo(900);
        assertThat(entries.findById(id("Ryzen 9 7900")).orElseThrow().product().partNumber()).isNull();
        mvc.perform(get("/api/catalog/products/{id}/cpu-support", id("MAG B550 TOMAHAWK"))
                        .param("cpuProductId", id("Ryzen 3 3100")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasRecordedEntries").value(true))
                .andExpect(jsonPath("$.profiles[0].source.sourceRevision").value(MotherboardCpuSeedLoader.EXPANSION_300));
    }

    private void baseline161() {
        for (var batch : CatalogSeedBatch.values()) {
            if (batch != CatalogSeedBatch.EXPAND_100 && batch != CatalogSeedBatch.EXPAND_300) products.seed(batch);
        }
        memory.seed(); boards.seed(); expansion.seed();
    }
    private CompatibilityDtos.Result checkKnown(String cpu, String board, String ram, int modules) {
        var profile = support.findByMotherboardId(board).getFirst().support();
        var row = profile.entries().stream().filter(e -> e.cpuProductId().equals(cpu) && e.supportStatus().name().equals("LISTED")
                && !e.biosRequirement().name().equals("UNKNOWN")).findFirst().orElseThrow();
        return compatibility.check(new CompatibilityDtos.Request(cpu, board, List.of(new CompatibilityDtos.RamInput(ram, modules)),
                profile.hardwareRevision(), row.cpuStepping(), row.minimumBiosVersion()));
    }
    private static String checkStatus(CompatibilityDtos.Result result, String code) {
        return result.checks().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow().status().name();
    }
    private void assertCounts300() {
        Map.of("catalog_product", 300, "catalog_reference_price", 300, "cpu_spec", 70, "motherboard_spec", 70,
                "ram_spec", 60, "gpu_spec", 80, "monitor_spec", 20, "cpu_memory_support", 70,
                "motherboard_cpu_support_profile", 70).forEach((table, n) -> assertThat(count(table)).as(table).isEqualTo(n));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT DISTINCT motherboard_product_id,cpu_product_id FROM motherboard_cpu_support) pairs", Integer.class)).isEqualTo(1638);
        assertThat(count("motherboard_cpu_support")).isEqualTo(1807);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM motherboard_cpu_support WHERE support_status = 'UNVERIFIED'", Integer.class)).isEqualTo(32);
        for (var input : boardLoader.load(MotherboardCpuSeedLoader.EXPANSION_300).items()) {
            var profile = input.support().resolve(this::productId);
            assertThat(support.findByRevision(productId(input.board().externalId()), profile.revisionKey()).orElseThrow().support()).isEqualTo(profile);
        }
    }
    private String id(String name) { return jdbc.queryForObject("SELECT id FROM catalog_product WHERE model_name = ?", String.class, name); }
    private String part(String number) { return jdbc.queryForObject("SELECT id FROM catalog_product WHERE type = 'RAM' AND part_number = ?", String.class, number); }
    private String productId(String externalId) { return jdbc.queryForObject("SELECT product_id FROM catalog_product_source WHERE source_name = 'BUILDCORES' AND external_id = ?", String.class, externalId); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private List<Map<String, Object>> rows(String table) {
        var records = jdbc.queryForList("SELECT * FROM " + table + (table.equals("motherboard_cpu_support") ? " ORDER BY 1,2,3,4" : " ORDER BY 1,2"));
        for (var row : records) row.replaceAll((key, value) -> value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value);
        return records;
    }
    private Map<String, List<Map<String, Object>>> snapshot() {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : TABLES) result.put(table, rows(table));
        return result;
    }
    private void reviewPrice(String productId) {
        jdbc.update("UPDATE catalog_product SET verification_status = 'CORE_VERIFIED' WHERE id = ?", productId);
        jdbc.update("""
                UPDATE catalog_reference_price SET amount_krw = ?, status = 'CONFIRMED', method = 'MEDIAN_DAILY_6M_V1',
                  period_start = DATE '2026-03-29', period_end = DATE '2026-09-28', observed_day_count = 184, sample_count = 368,
                  price_basis = 'Test fixture', evidence_ref = 'test-fixture://expand300/price', calculated_at = CURRENT_TIMESTAMP,
                  confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE product_id = ?
                """, new BigDecimal("123456.78"), productId);
    }
}
