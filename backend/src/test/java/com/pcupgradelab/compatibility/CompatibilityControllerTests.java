package com.pcupgradelab.compatibility;

import com.pcupgradelab.catalog.memory.CpuMemorySeedService;
import com.pcupgradelab.catalog.seed.CatalogSeedBatch;
import com.pcupgradelab.catalog.seed.CatalogSeedService;
import com.pcupgradelab.catalog.support.MotherboardCpuSeedService;
import com.pcupgradelab.pc.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 61종·CPU 메모리 12개·보드 지원 71행을 사용해 HTTP와 조회 전후 DB 불변성을 검사한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:compatibility-api-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "catalog.seed.enabled=false", "catalog.cpu-memory.seed.enabled=false", "catalog.motherboard-cpu.seed.enabled=false"
})
@ActiveProfiles({"local", "test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class CompatibilityControllerTests {
    private static final String URL = "/api/compatibility/check";
    private static final List<String> TABLES = List.of("pc_configuration", "pc_part", "catalog_product",
            "catalog_reference_price", "catalog_product_source", "cpu_spec", "motherboard_spec", "ram_spec",
            "gpu_spec", "gpu_power_connector", "monitor_spec", "cpu_memory_support", "cpu_memory_type_support",
            "motherboard_cpu_support_profile", "motherboard_cpu_support");
    private final JsonMapper mapper = JsonMapper.builder().build();
    @Autowired WebApplicationContext context;
    @Autowired CatalogSeedService seeds;
    @Autowired CpuMemorySeedService memorySeeds;
    @Autowired MotherboardCpuSeedService supportSeeds;
    @Autowired PcService pcs;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void prepareCatalogOnlyInDedicatedH2() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:compatibility-api-test");
        }
        for (var batch : CatalogSeedBatch.values()) seeds.seed(batch);
        memorySeeds.seed();
        supportSeeds.seed();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void checksTemporaryCandidateWithoutChangingStoredPcReviewedProductsConfirmedPricesOrSupportRows() throws Exception {
        String cpu = id("Core i5-12400F"), board = id("PRO B760M-A WIFI DDR4");
        var original = pcs.create(new PcDtos.Request("원본 PC", List.of(new PartInput(PartType.CPU,
                "자동 수집 CPU", "Intel original raw name", 1, InputSource.AUTO, cpu, MatchStatus.MATCHED,
                Map.of("cores", 6)))));
        jdbc.update("UPDATE catalog_product SET verification_status = 'CORE_VERIFIED', is_active = TRUE WHERE id = ?", cpu);
        jdbc.update("""
                UPDATE catalog_reference_price SET amount_krw = ?, status = 'CONFIRMED', method = 'MEDIAN_DAILY_6M_V1',
                    period_start = DATE '2026-03-29', period_end = DATE '2026-09-28', observed_day_count = 184, sample_count = 368,
                    price_basis = 'Test fixture', evidence_ref = 'test-fixture://compatibility/price',
                    calculated_at = CURRENT_TIMESTAMP, confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE product_id = ?
                """, new BigDecimal("123456.78"), cpu);
        var before = snapshot();
        var request = new CompatibilityDtos.Request(cpu, board, List.of(new CompatibilityDtos.RamInput(
                id("FURY Beast DDR4-3200 CL16 32GB (2x16GB)"), 2)), null, "H0", "7D99v10");
        var result = mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("CPU_MOTHERBOARD_RAM_V1"))
                .andExpect(jsonPath("$.status").value("COMPATIBLE")).andExpect(jsonPath("$.fullPcCompatibilityChecked").value(false))
                .andExpect(jsonPath("$.memory.installedModuleCount").value(2))
                .andExpect(jsonPath("$.memory.totalCapacityBytes").value(34359738368L))
                .andExpect(jsonPath("$.attributions[0].license").value("ODC-By-1.0")).andReturn();
        assertThat(check(body(result), "CPU_MANUFACTURER_SUPPORT").get("evidence").get(0).get("sourceName").stringValue())
                .isEqualTo("MANUFACTURER");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("rawPayload", "evidenceSourceId", "amountKrw");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(pcs.findById(original.id())).isEqualTo(original);
        assertCounts();
    }

    @Test
    void missingSteppingAndBiosReturnBothConditionalCandidatesAndExactSelectionUsesTheRightOne() throws Exception {
        var request = new CompatibilityDtos.Request(id("Ryzen 5 5600X"), id("B550-A PRO"),
                List.of(new CompatibilityDtos.RamInput(id("FURY Beast DDR4-3200 CL16 16GB (2x8GB)"), 2)), null, null, null);
        var response = mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NEEDS_CHECK"))
                .andExpect(jsonPath("$.cpuSupportCandidates.length()").value(2))
                .andExpect(jsonPath("$.cpuSupportCandidates[0].cpuStepping").value("B0"))
                .andExpect(jsonPath("$.cpuSupportCandidates[0].minimumBiosVersion").value("7C56vA4"))
                .andExpect(jsonPath("$.cpuSupportCandidates[1].cpuStepping").value("B2"))
                .andExpect(jsonPath("$.cpuSupportCandidates[1].minimumBiosVersion").value("7C56vA7")).andReturn();
        assertThat(check(body(response), "CPU_MANUFACTURER_SUPPORT").get("status").stringValue()).isEqualTo("NEEDS_CHECK");
        var selected = new CompatibilityDtos.Request(request.cpuProductId(), request.motherboardProductId(),
                request.ram(), null, "B2", "7C56vA7");
        var selectedResponse = mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(selected)))
                .andExpect(status().isOk()).andReturn();
        assertThat(check(body(selectedResponse), "CPU_BIOS").get("status").stringValue()).isEqualTo("COMPATIBLE");
    }

    @Test
    void missingSupportAndUnrecordedRevisionDoNotBecomeUnsupported() throws Exception {
        String cpu = id("Core i5-12400F"), board = id("B760M DS3H DDR4");
        var request = new CompatibilityDtos.Request(cpu, board, List.of(new CompatibilityDtos.RamInput(
                id("FURY Beast DDR4-3200 CL16 32GB (2x16GB)"), 2)), "2.0", "H0", "F1");
        var result = mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NEEDS_CHECK")).andReturn();
        assertThat(check(body(result), "CPU_MANUFACTURER_SUPPORT").get("status").stringValue()).isEqualTo("NEEDS_CHECK");
        jdbc.update("DELETE FROM motherboard_cpu_support WHERE motherboard_product_id = ? AND cpu_product_id = ?", board, cpu);
        request = new CompatibilityDtos.Request(cpu, board, request.ram(), "1.0", "H0", "F1");
        result = mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NEEDS_CHECK"))
                .andExpect(jsonPath("$.cpuSupportCandidates.length()").value(0)).andReturn();
        assertThat(check(body(result), "CPU_MANUFACTURER_SUPPORT").get("message").stringValue()).contains("미지원으로 판단하지 않습니다");
    }

    @Test
    void socketAndDdrContradictionsOverrideUnconfirmedConditionsAndRequestsAreRepeatable() throws Exception {
        var socketRequest = new CompatibilityDtos.Request(id("Core i5-12400F"), id("B550-A PRO"),
                List.of(new CompatibilityDtos.RamInput(id("FURY Beast DDR4-3200 CL16 16GB (2x8GB)"), 2)), null, null, null);
        String json = mapper.writeValueAsString(socketRequest);
        var first = mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INCOMPATIBLE")).andReturn();
        var repeated = mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(repeated)).isEqualTo(body(first));
        assertThat(check(body(first), "CPU_SOCKET").get("status").stringValue()).isEqualTo("INCOMPATIBLE");
        var ddrRequest = new CompatibilityDtos.Request(id("Core i5-12400F"), id("PRO B760M-A WIFI DDR4"),
                List.of(new CompatibilityDtos.RamInput(id("FURY Beast DDR5-6000 CL36 32GB (2x16GB)"), 2)), null, "H0", "7D99v10");
        var ddr = mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(ddrRequest)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INCOMPATIBLE")).andReturn();
        assertThat(check(body(ddr), "CPU_RAM_TYPE_0").get("status").stringValue()).isEqualTo("COMPATIBLE");
        assertThat(check(body(ddr), "MOTHERBOARD_RAM_TYPE_0").get("status").stringValue()).isEqualTo("INCOMPATIBLE");
    }

    @Test
    void nullCatalogLinksAreUnknownButExplicitBadIdsWrongTypesAndInvalidShapesAreClientErrors() throws Exception {
        var before = snapshot();
        var empty = new CompatibilityDtos.Request(null, null, List.of(), null, null, null);
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(empty)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NEEDS_CHECK"))
                .andExpect(jsonPath("$.memory.totalCapacityBytes").value(nullValue()));
        var missing = new CompatibilityDtos.Request("missing-catalog-id", id("B550-A PRO"), List.of(), null, null, null);
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(missing)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CATALOG_PRODUCT_NOT_FOUND"));
        var wrongType = new CompatibilityDtos.Request(id("B550-A PRO"), id("B550-A PRO"), List.of(), null, null, null);
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(wrongType)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_COMPATIBILITY_INPUT"));
        for (String payload : List.of("{}", "{\"ram\":[null]}", "{\"ram\":[{\"quantity\":0}]}",
                "{\"ram\":[{\"quantity\":65}]}", "{\"ram\":[{}]}", "{\"ram\":[],\"currentBiosVersion\":\"" + "x".repeat(65) + "\"}")) {
            mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        var excessive = new ArrayList<CompatibilityDtos.RamInput>();
        for (int i = 0; i < 65; i++) excessive.add(new CompatibilityDtos.RamInput(null, 1));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                        new CompatibilityDtos.Request(null, null, excessive, null, null, null))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        assertThat(snapshot()).isEqualTo(before);
    }

    private String id(String model) {
        return jdbc.queryForObject("SELECT id FROM catalog_product WHERE model_name = ?", String.class, model);
    }
    private JsonNode body(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString()); }
    private static JsonNode check(JsonNode result, String code) {
        for (var item : result.get("checks")) if (item.get("code").stringValue().equals(code)) return item;
        throw new AssertionError("Missing check: " + code);
    }
    private List<List<Map<String, Object>>> snapshot() {
        return TABLES.stream().map(table -> {
            String order = table.equals("motherboard_cpu_support") ? " ORDER BY 1, 2, 3, 4" : " ORDER BY 1, 2";
            var rows = jdbc.queryForList("SELECT * FROM " + table + order);
            for (var row : rows) row.replaceAll((key, value) -> value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value);
            return rows;
        }).toList();
    }
    private void assertCounts() {
        for (var expected : Map.of("catalog_product", 61, "catalog_reference_price", 61, "catalog_product_source", 165,
                "cpu_memory_support", 12, "cpu_memory_type_support", 16, "motherboard_cpu_support_profile", 16, "motherboard_cpu_support", 71).entrySet()) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + expected.getKey(), Integer.class)).as(expected.getKey())
                    .isEqualTo(expected.getValue());
        }
    }
}
