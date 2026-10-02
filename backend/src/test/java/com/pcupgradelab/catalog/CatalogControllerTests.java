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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 기존 18종과 Intel·RAM·AMD·GPU 확장 26/34/42/50종의 HTTP 응답을 검사한다. 각 테스트의 DB 변경은 롤백된다. */
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
    @Autowired CatalogEntryService entries;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        // local 프로필을 함께 사용해도 개인 MySQL에는 접속하지 않는지 먼저 확인한다.
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:catalog-api-test");
        }
        // 기존 18종 회귀 검사의 fixture를 고정하고, 추가 묶음은 해당 테스트에서 실행한다.
        for (CatalogSeedBatch batch : List.of(CatalogSeedBatch.INITIAL,
                CatalogSeedBatch.GPU, CatalogSeedBatch.MONITOR)) seeds.seed(batch);
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
    void cpuDetailKeepsLegacyFieldsAndExposesHybridPowerAndClocks() throws Exception {
        var legacy = body(mvc.perform(get(URL + "/{id}", findId("CPU", "Ryzen 5 3600")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specification.tdpW").value(65.0))
                .andExpect(jsonPath("$.specification.baseClockMhz").value(3600))
                .andReturn()).get("specification");
        for (String field : List.of("processorBasePowerW", "maximumTurboPowerW", "performanceCoreCount",
                "efficientCoreCount", "performanceCoreBaseClockMhz", "efficientCoreBaseClockMhz",
                "performanceCoreBoostClockMhz", "efficientCoreBoostClockMhz")) {
            assertNullField(legacy, field);
        }
        var specification = new CatalogSpecification.Cpu("LGA1700", 14, 20, null, 5100, null, false, null,
                new BigDecimal("125"), new BigDecimal("181"), 6, 8, 3500, 2600, 5100, 3900);
        var created = entries.create(new CatalogEntryCreateRequest(
                new CatalogProductCreateRequest(PartType.CPU, "Test Manufacturer", "Hybrid CPU fixture", null),
                specification, List.of(new CatalogSourceInput(CatalogSourceName.MANUFACTURER, null, null,
                "https://example.com/cpu-fixture", null, Instant.parse("2026-09-30T00:00:00Z")))));
        var detail = body(mvc.perform(get(URL + "/{id}", created.product().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specification.coreCount").value(14))
                .andExpect(jsonPath("$.specification.threadCount").value(20))
                .andExpect(jsonPath("$.specification.processorBasePowerW").value(125.0))
                .andExpect(jsonPath("$.specification.maximumTurboPowerW").value(181.0))
                .andExpect(jsonPath("$.specification.performanceCoreCount").value(6))
                .andExpect(jsonPath("$.specification.efficientCoreCount").value(8))
                .andExpect(jsonPath("$.specification.performanceCoreBaseClockMhz").value(3500))
                .andExpect(jsonPath("$.specification.efficientCoreBaseClockMhz").value(2600))
                .andExpect(jsonPath("$.specification.performanceCoreBoostClockMhz").value(5100))
                .andExpect(jsonPath("$.specification.efficientCoreBoostClockMhz").value(3900))
                .andExpect(jsonPath("$.specification.boostClockMhz").value(5100))
                .andReturn());
        assertNullField(detail.get("specification"), "tdpW");
        assertNullField(detail.get("specification"), "baseClockMhz");
        assertNullField(detail.get("product").get("referencePrice"), "amountKrw");
    }

    @Test
    void intelExpansionPagesTwentySixAndDistinguishesCpuAndDdrVariants() throws Exception {
        var seeded = seeds.seed(CatalogSeedBatch.INTEL);
        assertThat(seeded.created()).isEqualTo(8);
        var first = body(mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.totalElements").value(26))
                .andExpect(jsonPath("$.totalPages").value(2)).andReturn());
        var second = body(mvc.perform(get(URL).param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(6)))
                .andExpect(jsonPath("$.totalElements").value(26)).andReturn());
        var all = body(mvc.perform(get(URL).param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(26))).andReturn());
        var combined = new ArrayList<>(ids(first));
        combined.addAll(ids(second));
        assertThat(combined).containsExactlyElementsOf(ids(all)).doesNotHaveDuplicates();
        assertSearch("CPU", "Intel", 4);
        assertSearch("CPU", "BX8071513600KF", 1);
        assertSearch("CPU", "BX8071513100F", 0); // 원본의 다른 모델 품번을 검색 별칭으로 채택하지 않는다.
        assertSearch("MOTHERBOARD", "B760", 4);
        mvc.perform(get(URL).param("type", "CPU"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(8));
        mvc.perform(get(URL).param("type", "MOTHERBOARD"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(6));

        var hybrid = body(mvc.perform(get(URL + "/{id}", findId("CPU", "Core i5-13600KF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.partNumber").value("BX8071513600KF"))
                .andExpect(jsonPath("$.specification.coreCount").value(14))
                .andExpect(jsonPath("$.specification.performanceCoreCount").value(6))
                .andExpect(jsonPath("$.specification.efficientCoreCount").value(8))
                .andExpect(jsonPath("$.specification.processorBasePowerW").value(125.0))
                .andExpect(jsonPath("$.specification.maximumTurboPowerW").value(181.0))
                .andExpect(jsonPath("$.specification.hasIntegratedGraphics").value(false))
                .andExpect(jsonPath("$.sources", hasSize(3))).andReturn());
        assertNullField(hybrid.get("specification"), "baseClockMhz");
        assertNullField(hybrid.get("specification"), "tdpW");
        assertNullField(hybrid.get("product").get("referencePrice"), "amountKrw");
        mvc.perform(get(URL + "/{id}", findId("CPU", "Core i7-14700F")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specification.boostClockMhz").value(5400))
                .andExpect(jsonPath("$.specification.performanceCoreBoostClockMhz").value(5300));
        var nonHybrid = body(mvc.perform(get(URL + "/{id}", findId("CPU", "Core i3-12100F")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specification.efficientCoreCount").value(0)).andReturn());
        assertNullField(nonHybrid.get("specification"), "efficientCoreBaseClockMhz");

        // 모델명 부분 검색으로 두 DDR 변형이 검색되어도 각각 다른 ID와 제원을 유지한다.
        for (String manufacturer : List.of("MSI", "ASUS")) {
            var variants = body(mvc.perform(get(URL).param("type", "MOTHERBOARD")
                            .param("q", manufacturer + " " + (manufacturer.equals("MSI")
                                    ? "PRO B760M-A WIFI" : "PRIME B760M-A")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(2))).andReturn());
            assertThat(ids(variants)).doesNotHaveDuplicates();
            var memoryTypes = new ArrayList<String>();
            for (String id : ids(variants)) {
                var detail = body(mvc.perform(get(URL + "/{id}", id))
                        .andExpect(status().isOk()).andReturn());
                var spec = detail.get("specification");
                String memoryType = spec.get("memoryType").stringValue();
                memoryTypes.add(memoryType);
                assertThat(spec.get("maxMemoryBytes").longValue())
                        .isEqualTo(memoryType.equals("DDR4") ? 137438953472L : 274877906944L);
                assertNullField(detail.get("product"), "partNumber");
            }
            assertThat(memoryTypes).containsExactlyInAnyOrder("DDR4", "DDR5");
        }
    }

    @Test
    void ramExpansionPagesThirtyFourAndKeepsSingleModulesSeparateFromKits() throws Exception {
        seeds.seed(CatalogSeedBatch.INTEL);
        assertThat(seeds.seed(CatalogSeedBatch.RAM).created()).isEqualTo(8);
        var first = body(mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.totalElements").value(34))
                .andExpect(jsonPath("$.totalPages").value(2)).andReturn());
        var second = body(mvc.perform(get(URL).param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(14)))
                .andExpect(jsonPath("$.totalElements").value(34)).andReturn());
        var all = body(mvc.perform(get(URL).param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(34))).andReturn());
        var combined = new ArrayList<>(ids(first));
        combined.addAll(ids(second));
        assertThat(combined).containsExactlyElementsOf(ids(all)).doesNotHaveDuplicates();
        assertSearch("RAM", "Kingston", 11);
        assertSearch("RAM", "KF432C16BB/16", 1);
        assertSearch("RAM", "KF560C36BBE2-16", 0); // 후속 대체 모델을 원래 SKU의 별칭으로 쓰지 않는다.
        for (var count : Map.of(PartType.CPU, 8, PartType.MOTHERBOARD, 6,
                PartType.RAM, 11, PartType.GPU, 6, PartType.MONITOR, 3).entrySet()) {
            mvc.perform(get(URL).param("type", count.getKey().name()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(count.getValue()));
        }
        String singleId = findId("RAM", "KF432C16BB/16");
        String existingKitId = findId("RAM", "KF432C16BBK2/32");
        assertThat(singleId).isNotEqualTo(existingKitId);
        for (var product : Map.of(singleId, 1, existingKitId, 2).entrySet()) {
            mvc.perform(get(URL + "/{id}", product.getKey()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.specification.moduleCapacityBytes").value(17179869184L))
                    .andExpect(jsonPath("$.specification.moduleCount").value(product.getValue()));
        }
        String single32Id = findId("RAM", "KF560C36BBE-32");
        String kit64Id = findId("RAM", "KF560C36BBEK2-64");
        assertThat(single32Id).isNotEqualTo(kit64Id);
        for (var product : Map.of(single32Id, 1, kit64Id, 2).entrySet()) {
            mvc.perform(get(URL + "/{id}", product.getKey()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.specification.moduleCapacityBytes").value(34359738368L))
                    .andExpect(jsonPath("$.specification.moduleCount").value(product.getValue()));
        }
        var profile = body(mvc.perform(get(URL + "/{id}", findId("RAM", "KF552C40BB-16")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specification.dataRateMts").value(5200))
                .andExpect(jsonPath("$.specification.voltageV").value(1.25))
                .andExpect(jsonPath("$.specification.heightMm").value(34.9))
                .andExpect(jsonPath("$.specification.isEcc").value(false))
                .andExpect(jsonPath("$.specification.bufferType").value("UNBUFFERED"))
                .andExpect(jsonPath("$.product.verificationStatus").value("UNVERIFIED"))
                .andExpect(jsonPath("$.product.active").value(false))
                .andExpect(jsonPath("$.product.referencePrice.status").value("UNCONFIRMED"))
                .andExpect(jsonPath("$.sources", hasSize(3))).andReturn());
        assertNullField(profile.get("product").get("referencePrice"), "amountKrw");
    }

    @Test
    void amdExpansionPagesFortyTwoAndKeepsCpuModelsAndBoardMemoryLimitsDistinct() throws Exception {
        seeds.seed(CatalogSeedBatch.INTEL);
        seeds.seed(CatalogSeedBatch.RAM);
        String existing5600Id = findId("CPU", "100-100000927BOX");
        String existingHybridId = findId("CPU", "Core i5-13600KF");
        var existingHybrid = body(mvc.perform(get(URL + "/{id}", existingHybridId))
                .andExpect(status().isOk()).andReturn());
        assertThat(seeds.seed(CatalogSeedBatch.AMD).created()).isEqualTo(8);

        var first = body(mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.totalElements").value(42))
                .andExpect(jsonPath("$.totalPages").value(3)).andReturn());
        var second = body(mvc.perform(get(URL).param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.totalElements").value(42)).andReturn());
        var third = body(mvc.perform(get(URL).param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(42)).andReturn());
        var all = body(mvc.perform(get(URL).param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(42))).andReturn());
        var combined = new ArrayList<>(ids(first));
        combined.addAll(ids(second));
        combined.addAll(ids(third));
        assertThat(combined).containsExactlyElementsOf(ids(all)).doesNotHaveDuplicates();
        for (var count : Map.of(PartType.CPU, 12, PartType.MOTHERBOARD, 10,
                PartType.RAM, 11, PartType.GPU, 6, PartType.MONITOR, 3).entrySet()) {
            mvc.perform(get(URL).param("type", count.getKey().name()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(count.getValue()));
        }
        assertSearch("CPU", "AMD", 8);
        assertSearch("CPU", "Ryzen 5 5600", 2);
        assertSearch("CPU", "Ryzen 7 5700X", 2);
        assertSearch("CPU", "AMD Ryzen 5 5700X3D", 0); // 원본의 잘못된 제품군 별칭을 사용하지 않는다.
        assertSearch("CPU", "100-000000910", 0); // 트레이 포장을 별도 CPU 모델로 추가하지 않는다.
        assertSearch("MOTHERBOARD", "B550-A PRO (CEC)", 0);
        assertSearch("MOTHERBOARD", "MSI PRO A620M-E", 1);
        assertThat(findId("CPU", "100-100000927BOX")).isEqualTo(existing5600Id);
        assertThat(findId("CPU", "100-100000065BOX")).isNotEqualTo(existing5600Id);
        assertThat(body(mvc.perform(get(URL + "/{id}", existingHybridId))
                .andExpect(status().isOk()).andReturn())).isEqualTo(existingHybrid);

        var x3d = body(mvc.perform(get(URL + "/{id}", findId("CPU", "100-100001503WOF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.modelName").value("Ryzen 7 5700X3D"))
                .andExpect(jsonPath("$.product.verificationStatus").value("UNVERIFIED"))
                .andExpect(jsonPath("$.product.active").value(false))
                .andExpect(jsonPath("$.product.referencePrice.status").value("UNCONFIRMED"))
                .andExpect(jsonPath("$.specification.socketCode").value("AM4"))
                .andExpect(jsonPath("$.specification.coreCount").value(8))
                .andExpect(jsonPath("$.specification.threadCount").value(16))
                .andExpect(jsonPath("$.specification.baseClockMhz").value(3000))
                .andExpect(jsonPath("$.specification.boostClockMhz").value(4100))
                .andExpect(jsonPath("$.specification.tdpW").value(105.0))
                .andExpect(jsonPath("$.specification.hasIntegratedGraphics").value(false))
                .andExpect(jsonPath("$.sources", hasSize(2))).andReturn());
        for (String field : List.of("integratedGraphicsModel", "processorBasePowerW", "maximumTurboPowerW",
                "performanceCoreCount", "efficientCoreCount", "performanceCoreBaseClockMhz",
                "efficientCoreBaseClockMhz", "performanceCoreBoostClockMhz", "efficientCoreBoostClockMhz")) {
            assertNullField(x3d.get("specification"), field);
        }
        assertNullField(x3d.get("product").get("referencePrice"), "amountKrw");
        for (int i = 0; i < x3d.get("sources").size(); i++) {
            assertThat(x3d.get("sources").get(i).has("rawPayload")).isFalse();
        }
        mvc.perform(get(URL + "/{id}", findId("CPU", "100-100000910WOF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specification.socketCode").value("AM5"))
                .andExpect(jsonPath("$.specification.tdpW").value(120.0))
                .andExpect(jsonPath("$.specification.hasIntegratedGraphics").value(true))
                .andExpect(jsonPath("$.specification.integratedGraphicsModel").value("AMD Radeon Graphics"));
        mvc.perform(get(URL + "/{id}", findId("MOTHERBOARD", "B450M PRO-VDH MAX")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specification.socketCode").value("AM4"))
                .andExpect(jsonPath("$.specification.memoryType").value("DDR4"))
                .andExpect(jsonPath("$.specification.memorySlotCount").value(4))
                .andExpect(jsonPath("$.specification.maxMemoryBytes").value(137438953472L))
                .andExpect(jsonPath("$.specification.supportsEcc").value(false));
        mvc.perform(get(URL + "/{id}", findId("MOTHERBOARD", "PRO A620M-E")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specification.socketCode").value("AM5"))
                .andExpect(jsonPath("$.specification.memoryType").value("DDR5"))
                .andExpect(jsonPath("$.specification.memorySlotCount").value(2))
                .andExpect(jsonPath("$.specification.maxMemoryBytes").value(137438953472L));
        mvc.perform(get(URL + "/{id}", findId("MOTHERBOARD", "MAG B650 TOMAHAWK WIFI")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specification.socketCode").value("AM5"))
                .andExpect(jsonPath("$.specification.memoryType").value("DDR5"))
                .andExpect(jsonPath("$.specification.memorySlotCount").value(4))
                .andExpect(jsonPath("$.specification.maxMemoryBytes").value(274877906944L));
    }

    @Test
    void gpuExpansionPagesFiftyAndSeparatesCardPowerPsuRequirementsAndUnconfirmedConnectorSubtypes() throws Exception {
        seeds.seed(CatalogSeedBatch.INTEL);
        seeds.seed(CatalogSeedBatch.RAM);
        seeds.seed(CatalogSeedBatch.AMD);
        var existingDetails = new ArrayList<JsonNode>();
        var existingPage = body(mvc.perform(get(URL).param("type", "GPU"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(6))).andReturn());
        for (String id : ids(existingPage)) {
            existingDetails.add(body(mvc.perform(get(URL + "/{id}", id))
                    .andExpect(status().isOk()).andReturn()));
        }
        var seeded = seeds.seed(CatalogSeedBatch.GPU_EXPANSION);
        assertThat(seeded.created()).isEqualTo(8);
        assertThat(seeded.skipped()).isZero();
        var combined = new ArrayList<String>();
        for (int page = 0; page < 3; page++) {
            var response = body(mvc.perform(get(URL).param("page", Integer.toString(page)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(page < 2 ? 20 : 10)))
                    .andExpect(jsonPath("$.totalElements").value(50))
                    .andExpect(jsonPath("$.totalPages").value(3)).andReturn());
            combined.addAll(ids(response));
        }
        var all = body(mvc.perform(get(URL).param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(50))).andReturn());
        assertThat(combined).containsExactlyElementsOf(ids(all)).doesNotHaveDuplicates();
        mvc.perform(get(URL).param("page", "3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(50));
        for (var count : Map.of(PartType.CPU, 12, PartType.MOTHERBOARD, 10,
                PartType.RAM, 11, PartType.GPU, 14, PartType.MONITOR, 3).entrySet()) {
            mvc.perform(get(URL).param("type", count.getKey().name()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(count.getValue()));
        }
        assertSearch("GPU", "MSI", 7);
        assertSearch("GPU", "Sapphire", 5);
        assertSearch("GPU", "ASRock", 2);
        assertSearch("GPU", "GTX 1660 SUPER", 1);
        assertSearch("GPU", "RTX 2060 VENTUS 6G", 1);
        assertSearch("GPU", "RTX 2060 VENTUS GP", 0);
        assertSearch("GPU", "RTX 3070 VENTUS 2X 8G OC LHR", 1);
        assertSearch("GPU", "WHITE", 0); // 다른 WHITE 모델을 원본의 잘못된 별칭으로 합치지 않는다.
        assertSearch("GPU", "RX 9060 XT 8GB", 0);
        assertSearch("GPU", "A750 CLD 8GO90-GA3HZZ-00UANF", 0);
        for (var old : existingDetails) {
            assertThat(body(mvc.perform(get(URL + "/{id}", old.get("product").get("id").stringValue()))
                    .andExpect(status().isOk()).andReturn())).isEqualTo(old);
        }
        for (var item : seeded.items()) {
            var detail = body(mvc.perform(get(URL + "/{id}", item.productId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.product.type").value("GPU"))
                    .andExpect(jsonPath("$.product.verificationStatus").value("UNVERIFIED"))
                    .andExpect(jsonPath("$.product.active").value(false))
                    .andExpect(jsonPath("$.product.referencePrice.status").value("UNCONFIRMED"))
                    .andExpect(jsonPath("$.specification.powerConnectorsKnown").value(true))
                    .andExpect(jsonPath("$.sources", hasSize(2)))
                    .andExpect(jsonPath("$.attributions[0].license").value("ODC-By-1.0")).andReturn());
            assertNullField(detail.get("product").get("referencePrice"), "amountKrw");
            assertNullField(detail.get("specification"), "pcieConnectorLanes");
            for (int i = 0; i < detail.get("sources").size(); i++) {
                assertThat(detail.get("sources").get(i).has("rawPayload")).isFalse();
            }
        }
        var rtx5070 = body(mvc.perform(get(URL + "/{id}", findId("GPU", "G5070-12V2C")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.modelName").value("GeForce RTX 5070 12G VENTUS 2X OC"))
                .andExpect(jsonPath("$.specification.vramBytes").value(12884901888L))
                .andExpect(jsonPath("$.specification.memoryType").value("GDDR7"))
                .andExpect(jsonPath("$.specification.pcieVersion").value("5.0"))
                .andExpect(jsonPath("$.specification.cardPowerW").value(250.0))
                .andExpect(jsonPath("$.specification.cardPowerBasis").value("POWER_CONSUMPTION"))
                .andExpect(jsonPath("$.specification.psuRequirementW").value(650))
                .andExpect(jsonPath("$.specification.psuRequirementBasis").value("RECOMMENDED"))
                .andExpect(jsonPath("$.specification.powerConnectors[0].connectorType").value("PCIE_16PIN_UNSPECIFIED"))
                .andExpect(jsonPath("$.specification.powerConnectors[0].connectorCount").value(1)).andReturn());
        for (String field : List.of("heightMm", "thicknessMm", "slotWidth", "pcieActiveLanes")) {
            assertNullField(rtx5070.get("specification"), field);
        }
        mvc.perform(get(URL + "/{id}", findId("GPU", "11324-01-20G")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specification.pcieVersion").value("4.0"))
                .andExpect(jsonPath("$.specification.pcieActiveLanes").value(8))
                .andExpect(jsonPath("$.specification.slotWidth").value(2.0))
                .andExpect(jsonPath("$.specification.cardPowerW").value(185.0))
                .andExpect(jsonPath("$.specification.cardPowerBasis").value("TOTAL_BOARD_POWER"))
                .andExpect(jsonPath("$.specification.psuRequirementW").value(550))
                .andExpect(jsonPath("$.specification.psuRequirementBasis").value("MINIMUM"))
                .andExpect(jsonPath("$.specification.powerConnectors[0].connectorType").value("PCIE_8PIN"))
                .andExpect(jsonPath("$.specification.powerConnectors[0].connectorCount").value(1));
        mvc.perform(get(URL + "/{id}", findId("GPU", "11350-03-20G")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specification.vramBytes").value(17179869184L))
                .andExpect(jsonPath("$.specification.cardPowerW").value(170.0))
                .andExpect(jsonPath("$.specification.cardPowerBasis").value("TYPICAL_BOARD_POWER"))
                .andExpect(jsonPath("$.specification.psuRequirementW").value(450))
                .andExpect(jsonPath("$.specification.psuRequirementBasis").value("MINIMUM"));
        mvc.perform(get(URL + "/{id}", findId("GPU", "11349-03-20G")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specification.cardPowerW").value(220.0))
                .andExpect(jsonPath("$.specification.cardPowerBasis").value("TYPICAL_BOARD_POWER"))
                .andExpect(jsonPath("$.specification.powerConnectors[0].connectorCount").value(2));
        var a750 = body(mvc.perform(get(URL + "/{id}", findId("GPU", "A750 CLD 8GO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.partNumber").value("A750 CLD 8GO"))
                .andExpect(jsonPath("$.specification.chipVendor").value("INTEL"))
                .andExpect(jsonPath("$.specification.slotWidth").value(2.4))
                .andExpect(jsonPath("$.specification.psuRequirementW").value(650))
                .andExpect(jsonPath("$.specification.psuRequirementBasis").value("RECOMMENDED"))
                .andExpect(jsonPath("$.specification.powerConnectors[0].connectorCount").value(2)).andReturn());
        assertNullField(a750.get("specification"), "cardPowerW");
        assertNullField(a750.get("specification"), "cardPowerBasis");
        assertNullField(a750.get("specification"), "heightMm");
        assertNullField(a750.get("specification"), "thicknessMm");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product_source", Integer.class)).isEqualTo(115);
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
