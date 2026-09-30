package com.pcupgradelab.catalog;

import com.pcupgradelab.catalog.seed.CatalogSeedBatch;
import com.pcupgradelab.catalog.seed.CatalogSeedService;
import com.pcupgradelab.pc.PartType;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 18종 seed와 HTTP 응답을 함께 검사한다. 각 테스트의 DB 변경은 롤백된다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:catalog-api-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "catalog.seed.enabled=false"
})
@ActiveProfiles({"local", "test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class CatalogControllerTests {
    private static final String URL = "/api/catalog/products";
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired WebApplicationContext context;
    @Autowired CatalogSeedService seeds;
    @Autowired CatalogProductService products;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        // local 프로필을 함께 사용해도 개인 MySQL에는 접속하지 않는지 먼저 확인한다.
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:catalog-api-test");
        }
        for (CatalogSeedBatch batch : CatalogSeedBatch.values()) seeds.seed(batch);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void listsAllEighteenWithExplicitUnknownPricesAndAttribution() throws Exception {
        var result = mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(18)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(18))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.attributions[0].name").value("BuildCores OpenDB"))
                .andExpect(jsonPath("$.attributions[0].license").value("ODC-By-1.0"))
                .andReturn();
        var items = body(result).get("items");
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            assertThat(item.get("id").stringValue()).isNotBlank();
            assertThat(item.get("verificationStatus").stringValue()).isEqualTo("UNVERIFIED");
            assertThat(item.get("active").booleanValue()).isFalse();
            assertThat(item.has("specification")).isFalse();
            assertThat(item.has("sources")).isFalse();
            var price = item.get("referencePrice");
            assertThat(price.get("status").stringValue()).isEqualTo("UNCONFIRMED");
            assertNullField(price, "amountKrw");
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM catalog_product WHERE is_active = FALSE AND verification_status = 'UNVERIFIED'
                """, Integer.class)).isEqualTo(18);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM catalog_reference_price WHERE amount_krw IS NULL AND status = 'UNCONFIRMED'
                """, Integer.class)).isEqualTo(18);
    }

    @Test
    void filtersEveryPartTypeAndReturnsAnEmptyPageForUnseededTypes() throws Exception {
        var expected = Map.of(PartType.CPU, 4, PartType.MOTHERBOARD, 2, PartType.RAM, 3,
                PartType.GPU, 6, PartType.MONITOR, 3);
        for (PartType type : PartType.values()) {
            int count = expected.getOrDefault(type, 0);
            var result = mvc.perform(get(URL).param("type", type.name()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(count)))
                    .andExpect(jsonPath("$.totalElements").value(count))
                    .andExpect(jsonPath("$.totalPages").value(count == 0 ? 0 : 1))
                    .andReturn();
            var items = body(result).get("items");
            for (int i = 0; i < items.size(); i++) {
                assertThat(items.get(i).get("type").stringValue()).isEqualTo(type.name());
            }
        }
    }

    @Test
    void searchesManufacturerModelAndPartNumberIgnoringCaseAndTrimmingEdges() throws Exception {
        assertSearch("CPU", "  aMd  ", 4);
        assertSearch("CPU", "rYzEn 5", 3);
        assertSearch("CPU", "AMD Ryzen 5 5600", 1);
        assertSearch("RAM", "kf432c16bbk2/16", 1);
        assertSearch("GPU", "RTX 3060", 1); // partNumber가 null이어도 모델명으로 검색된다.
        assertSearch("GPU", "Ryzen", 0);
        assertSearch("MONITOR", "   ", 3);
        assertSearch("MONITOR", "no-such-model", 0);
    }

    @Test
    void treatsLikeWildcardsEscapeCharactersAndSqlTextAsLiteralSearches() throws Exception {
        for (String q : List.of("%", "_", "!", "' OR 1=1 --")) assertSearch("CPU", q, 0);
        // 기본 정보만으로 검색하는 계층이므로 이 검색용 fixture에는 상세 제원을 만들지 않는다.
        products.create(new CatalogProductCreateRequest(PartType.CPU,
                "Search Fixture", "Literal 100%_! value", null));
        assertSearch("CPU", "%_!", 1);
        assertSearch("CPU", "100%_!", 1);
        assertSearch("CPU", "100__!", 0);
    }

    @Test
    void pagesHaveStableOrderNoDuplicatesAndAnEmptyTail() throws Exception {
        var all = body(mvc.perform(get(URL).param("type", "GPU"))
                .andExpect(status().isOk()).andReturn());
        var pagedIds = new ArrayList<String>();
        for (int page = 0; page < 3; page++) {
            var part = mvc.perform(get(URL).param("type", "GPU")
                            .param("page", String.valueOf(page)).param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(page))
                    .andExpect(jsonPath("$.size").value(2))
                    .andExpect(jsonPath("$.totalElements").value(6))
                    .andExpect(jsonPath("$.totalPages").value(3))
                    .andExpect(jsonPath("$.items", hasSize(2))).andReturn();
            pagedIds.addAll(ids(body(part)));
        }
        assertThat(pagedIds).containsExactlyElementsOf(ids(all)).doesNotHaveDuplicates();
        mvc.perform(get(URL).param("type", "GPU").param("page", "3").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void everyListedProductHasADetailWithSpecificationsAndSourcesButNoRawPayload() throws Exception {
        var list = body(mvc.perform(get(URL)).andExpect(status().isOk()).andReturn());
        for (String id : ids(list)) {
            var detail = body(mvc.perform(get(URL + "/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.product.id").value(id))
                    .andExpect(jsonPath("$.specification").isMap())
                    .andExpect(jsonPath("$.attributions[0].licenseUrl")
                            .value("https://opendatacommons.org/licenses/by/1-0/"))
                    .andReturn());
            assertThat(detail.get("specification").size()).isPositive();
            var sources = detail.get("sources");
            assertThat(sources.size()).isGreaterThanOrEqualTo(2);
            for (int i = 0; i < sources.size(); i++) {
                var source = sources.get(i);
                assertThat(source.get("sourceUrl").stringValue()).startsWith("https://");
                assertThat(source.get("retrievedAt").stringValue()).isNotBlank();
                assertThat(source.has("rawPayload")).isFalse();
                assertThat(source.has("id")).isFalse();
            }
        }
    }

    @Test
    void ramDetailKeepsPerModuleCapacityAndKitCountSeparate() throws Exception {
        mvc.perform(get(URL + "/{id}", findId("RAM", "KF432C16BBK2/32")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specification.moduleCapacityBytes").value(17179869184L))
                .andExpect(jsonPath("$.specification.moduleCount").value(2));
    }

    @Test
    void gpuDetailKeepsLargeVramValuesAndPowerConnectorArray() throws Exception {
        mvc.perform(get(URL + "/{id}", findId("GPU", "RTX 3060")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specification.vramBytes").value(12884901888L))
                .andExpect(jsonPath("$.specification.powerConnectorsKnown").value(true))
                .andExpect(jsonPath("$.specification.powerConnectors[0].connectorType").value("PCIE_8PIN"))
                .andExpect(jsonPath("$.specification.powerConnectors[0].connectorCount").value(1));
    }

    @Test
    void monitorDetailSeparatesStandardAndOverclockRefreshAndPreservesUnknowns() throws Exception {
        var result = mvc.perform(get(URL + "/{id}", findId("MONITOR", "27GP850-B")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specification.nativeStandardRefreshHz").value(165.0))
                .andExpect(jsonPath("$.specification.hasRefreshOverclock").value(true))
                .andExpect(jsonPath("$.specification.nativeOcRefreshHz").value(180.0))
                .andReturn();
        assertNullField(body(result).get("specification"), "activePowerW");
        var unknownOc = body(mvc.perform(get(URL + "/{id}", findId("MONITOR", "24GN650-B")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specification.screenSizeInches").value(23.8))
                .andReturn()).get("specification");
        assertNullField(unknownOc, "hasRefreshOverclock");
        assertNullField(unknownOc, "nativeOcRefreshHz");
    }

    @Test
    void invalidInputsReturnTheCommon400ResponseAndUnknownIdReturns404() throws Exception {
        var invalid = List.of(new String[]{"page", "-1"}, new String[]{"size", "0"},
                new String[]{"size", "101"}, new String[]{"page", "2147483647"},
                new String[]{"page", "abc"}, new String[]{"size", "abc"},
                new String[]{"type", "NOT_A_PART"}, new String[]{"q", "a".repeat(101)});
        for (var pair : invalid) {
            mvc.perform(get(URL).param(pair[0], pair[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        mvc.perform(get(URL + "/{id}", "a".repeat(129)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(get(URL + "/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATALOG_PRODUCT_NOT_FOUND"));
    }

    @Test
    void exposesNoCreateUpdateOrDeleteRoute() throws Exception {
        String id = findId("CPU", "Ryzen 5 5600");
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(put(URL + "/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete(URL + "/{id}", id)).andExpect(status().isMethodNotAllowed());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product", Integer.class)).isEqualTo(18);
    }

    private void assertSearch(String type, String q, int count) throws Exception {
        mvc.perform(get(URL).param("type", type).param("q", q))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(count)))
                .andExpect(jsonPath("$.totalElements").value(count));
    }

    private String findId(String type, String q) throws Exception {
        var result = mvc.perform(get(URL).param("type", type).param("q", q))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1))).andReturn();
        return body(result).get("items").get(0).get("id").stringValue();
    }

    private JsonNode body(MvcResult result) {
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private static List<String> ids(JsonNode page) {
        var ids = new ArrayList<String>();
        var items = page.get("items");
        for (int i = 0; i < items.size(); i++) ids.add(items.get(i).get("id").stringValue());
        return ids;
    }

    private static void assertNullField(JsonNode object, String field) {
        assertThat(object.has(field)).as("%s must be present", field).isTrue();
        assertThat(object.get(field).isNull()).as("%s must be JSON null", field).isTrue();
    }
}
