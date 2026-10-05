package com.pcupgradelab.pc;

import com.pcupgradelab.catalog.CatalogProductCreateRequest;
import com.pcupgradelab.catalog.CatalogProductService;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 각 HTTP 요청을 독립 트랜잭션으로 실행해 커밋된 저장 결과와 실패한 수정의 원본 보존을 확인한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pc-catalog-link-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "catalog.seed.enabled=false"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PcCatalogLinkTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Map<PartType, String> productIds = new EnumMap<>(PartType.class);
    private final List<Long> pcIds = new ArrayList<>();
    private static final long USER_ID = 1L;

    @Autowired WebApplicationContext context;
    @Autowired CatalogProductService products;
    @Autowired PcConfigurationRepository pcs;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:pc-catalog-link-test");
        }
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .defaultRequest(get("/").header("X-Dev-User-Id", String.valueOf(USER_ID))).build();
        for (var type : List.of(PartType.CPU, PartType.MOTHERBOARD, PartType.RAM, PartType.GPU, PartType.MONITOR)) {
            // ID와 종류 검사에 필요한 기본 정보만 생성한다. 제품과 미확정 가격 행은 함께 저장된다.
            productIds.put(type, products.create(new CatalogProductCreateRequest(
                    type, "PC Link Test", "카탈로그 모델 " + type, null)).id());
        }
    }

    @AfterEach
    void removeOnlyTestRecords() {
        for (Long id : pcIds) {
            jdbc.update("DELETE FROM pc_part WHERE pc_id = ?", id);
            jdbc.update("DELETE FROM pc_configuration WHERE id = ?", id);
        }
        for (String id : productIds.values()) {
            jdbc.update("DELETE FROM catalog_reference_price WHERE product_id = ?", id);
            jdbc.update("DELETE FROM catalog_product WHERE id = ?", id);
        }
    }

    @Test
    void savesAllFiveTypesAndRepeatedRamLinksWithoutReplacingDeviceValues() throws Exception {
        var catalogBefore = jdbc.queryForList("SELECT * FROM catalog_product ORDER BY id");
        var pricesBefore = jdbc.queryForList("SELECT * FROM catalog_reference_price ORDER BY product_id");
        var parts = List.of(linked(PartType.CPU, "CPU 1"), linked(PartType.MOTHERBOARD, "Board 1"),
                linked(PartType.RAM, "DIMM 1"), linked(PartType.RAM, "DIMM 2"),
                linked(PartType.GPU, "GPU 1"), linked(PartType.MONITOR, "Display 1"), manualStorage());
        long id = create(parts);

        assertParts(read(id), parts);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pc_part WHERE pc_id = ?", Long.class, id))
                .isEqualTo(7L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pc_part WHERE pc_id = ? AND catalog_product_id = ?
                """, Long.class, id, productIds.get(PartType.RAM))).isEqualTo(2L);
        assertThat(jdbc.queryForList("SELECT * FROM catalog_product ORDER BY id")).isEqualTo(catalogBefore);
        assertThat(jdbc.queryForList("SELECT * FROM catalog_reference_price ORDER BY product_id")).isEqualTo(pricesBefore);
    }

    @Test
    void unknownProductRejectsTheWholeCreateRequest() throws Exception {
        var bad = withLink(linked(PartType.GPU, "GPU 1"), "missing-catalog-product", MatchStatus.MATCHED);
        mvc.perform(post("/api/pcs").contentType(MediaType.APPLICATION_JSON)
                        .content(request("저장되면 안 되는 PC", List.of(linked(PartType.RAM, "DIMM 1"), bad))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PART_ID"));
        assertNoPcRows();
    }

    @Test
    void wrongProductTypeRejectsTheWholeCreateRequest() throws Exception {
        var bad = withLink(linked(PartType.RAM, "DIMM 1"), productIds.get(PartType.GPU), MatchStatus.MATCHED);
        mvc.perform(post("/api/pcs").contentType(MediaType.APPLICATION_JSON)
                        .content(request("저장되면 안 되는 PC", List.of(bad))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PART_CATEGORY_MISMATCH"));
        assertNoPcRows();
    }

    @Test
    void inconsistentLinkFieldsAreStillRejectedByRequestValidation() throws Exception {
        var ram = linked(PartType.RAM, "DIMM 1");
        for (var invalid : List.of(withLink(ram, null, MatchStatus.MATCHED),
                withLink(ram, " ", MatchStatus.MATCHED),
                withLink(ram, ram.catalogProductId(), MatchStatus.UNMATCHED))) {
            mvc.perform(post("/api/pcs").contentType(MediaType.APPLICATION_JSON)
                            .content(request("잘못된 연결 상태", List.of(invalid))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        assertNoPcRows();
    }

    @Test
    void rejectedUpdatesPreserveCommittedNamePartsIdsAndTimestamps() throws Exception {
        long id = create(List.of(linked(PartType.RAM, "DIMM 1"), manualStorage()));
        var before = read(id);
        var rowIds = jdbc.queryForList("SELECT id FROM pc_part WHERE pc_id = ? ORDER BY id", Long.class, id);
        var ram = linked(PartType.RAM, "DIMM 1");
        for (var invalid : List.of(withLink(ram, "missing-catalog-product", MatchStatus.MATCHED),
                withLink(ram, productIds.get(PartType.GPU), MatchStatus.MATCHED))) {
            mvc.perform(put("/api/pcs/{id}", id).contentType(MediaType.APPLICATION_JSON)
                            .content(request("바뀌면 안 되는 이름", List.of(linked(PartType.CPU, "CPU 1"), invalid))))
                    .andExpect(status().isBadRequest());
            assertThat(read(id)).isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT id FROM pc_part WHERE pc_id = ? ORDER BY id", Long.class, id))
                    .isEqualTo(rowIds);
        }
    }

    @Test
    void updatingAndUnlinkingKeepThePcIdAndDoNotAccumulatePartRows() throws Exception {
        var ram = linked(PartType.RAM, "DIMM 1");
        var gpu = linked(PartType.GPU, "GPU 1");
        long id = create(List.of(ram, linked(PartType.RAM, "DIMM 2"), gpu));
        var replacement = List.of(new PartInput(ram.type(), ram.displayName(), ram.rawName(), 2,
                ram.source(), ram.catalogProductId(), ram.matchStatus(), ram.specs()),
                withLink(gpu, null, MatchStatus.UNMATCHED));
        var result = mvc.perform(put("/api/pcs/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(request("수정된 연결 PC", replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("수정된 연결 PC")).andReturn();
        assertParts(body(result), replacement);
        assertParts(read(id), replacement);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pc_part WHERE pc_id = ?", Long.class, id))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pc_part WHERE pc_id = ? AND catalog_product_id IS NULL AND match_status = 'UNMATCHED'
                """, Long.class, id)).isEqualTo(1L);
    }

    @Test
    void anOldPlaceholderLinkCanBeReadAndRepairedByUnlinking() throws Exception {
        var legacy = withLink(linked(PartType.RAM, "DIMM 1"), "old-mock-id", MatchStatus.MATCHED);
        // 이전 단계에서 임시 ID로 저장한 PC를 재현한다. 신규 API 요청은 이런 ID를 허용하지 않는다.
        long id = pcs.saveAndFlush(new PcConfiguration(USER_ID, "기존 PC", List.of(legacy))).getId();
        pcIds.add(id);
        assertParts(read(id), List.of(legacy));
        var fixed = List.of(withLink(legacy, null, MatchStatus.UNMATCHED));
        mvc.perform(put("/api/pcs/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(request("복구한 PC", fixed)))
                .andExpect(status().isOk());
        assertParts(read(id), fixed);
    }

    private long create(List<PartInput> parts) throws Exception {
        var result = mvc.perform(post("/api/pcs").contentType(MediaType.APPLICATION_JSON)
                        .content(request("카탈로그 연결 저장 검증", parts)))
                .andExpect(status().isCreated()).andReturn();
        long id = body(result).get("id").longValue();
        pcIds.add(id);
        assertParts(body(result), parts);
        return id;
    }

    private JsonNode read(long id) throws Exception {
        return body(mvc.perform(get("/api/pcs/{id}", id)).andExpect(status().isOk()).andReturn());
    }

    private String request(String name, List<PartInput> parts) {
        return mapper.writeValueAsString(new PcDtos.Request(name, parts));
    }

    private JsonNode body(MvcResult result) {
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private void assertParts(JsonNode actual, List<PartInput> expected) {
        // JSON 왕복 후 비교해 JDBC/Jackson의 정수 구현 타입 차이가 검사 결과를 바꾸지 않게 한다.
        JsonNode expectedJson = mapper.readTree(mapper.writeValueAsString(expected));
        assertThat(actual.get("parts")).isEqualTo(expectedJson);
    }

    private void assertNoPcRows() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pc_configuration", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pc_part", Long.class)).isZero();
    }

    private PartInput linked(PartType type, String slot) {
        var specs = new LinkedHashMap<String, Object>();
        specs.put("slot", slot);
        specs.put("unconfirmedReading", null);
        if (type == PartType.RAM) specs.put("capacityBytes", 8589934592L);
        if (type == PartType.GPU) specs.put("vramBytes", 12884901888L);
        return new PartInput(type, "사용자 표시 " + type, "  수집 원문: " + type + "  ", 1,
                InputSource.AUTO, productIds.get(type), MatchStatus.MATCHED, specs);
    }

    private static PartInput withLink(PartInput part, String id, MatchStatus status) {
        return new PartInput(part.type(), part.displayName(), part.rawName(), part.quantity(),
                part.source(), id, status, part.specs());
    }

    private static PartInput manualStorage() {
        return new PartInput(PartType.STORAGE, "직접 입력한 SSD", null, 1, InputSource.MANUAL,
                null, MatchStatus.UNMATCHED, Map.of("capacityBytes", 1000000000000L));
    }
}
