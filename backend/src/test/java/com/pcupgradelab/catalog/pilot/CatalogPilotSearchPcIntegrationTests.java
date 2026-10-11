package com.pcupgradelab.catalog.pilot;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.catalog.identity.*;
import com.pcupgradelab.catalog.price.CatalogPriceImportService;
import com.pcupgradelab.catalog.seed.CatalogSeedBatch;
import com.pcupgradelab.catalog.seed.CatalogSeedLoader;
import com.pcupgradelab.pc.*;
import com.pcupgradelab.scan.ScanDtos;
import com.pcupgradelab.scan.ScanService;
import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The approved fourteen identities in isolated H2; every fixture and PC write rolls back. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:catalog-pilot-search-pc-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "catalog.seed.enabled=false"
})
@ActiveProfiles({"local", "test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class CatalogPilotSearchPcIntegrationTests {
    private static final String PRODUCT_URL = "/api/catalog/products";
    private static final String MODEL_URL = "/api/catalog/models";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Map<Integer, String> productIds = new LinkedHashMap<>();
    private final Map<String, String> modelIds = new LinkedHashMap<>();
    @Autowired WebApplicationContext context;
    @Autowired CatalogEntryService entries;
    @Autowired CatalogSeedLoader seeds;
    @Autowired CatalogIdentityService identity;
    @Autowired CatalogProductRepository products;
    @Autowired CatalogProductSourceRepository sources;
    @Autowired CatalogPriceImportService prices;
    @Autowired ScanService scans;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    private CatalogPilotImportLoader.Plan plan;
    private MockMvc mvc;

    @BeforeEach
    void createReviewedSearchFixtureWithoutCallingAnyLocalDatabaseImport() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:catalog-pilot-search-pc-test");
        }
        assertThat(count("catalog_product")).isZero();
        assertThat(count("pc_configuration")).isZero();
        plan = new CatalogPilotImportLoader().load(Path.of("..").toAbsolutePath().normalize());
        for (var model : plan.models()) modelIds.put(model.canonicalId(), identity.registerModel(model).id());
        var originalEntries = new ArrayList<CatalogEntryCreateRequest>();
        for (var batch : CatalogSeedBatch.values()) originalEntries.addAll(seeds.load(batch));
        for (var part : plan.parts()) {
            CatalogEntryCreateRequest fixture;
            if (part.sourceIdentity().sourceName() == CatalogSourceName.BUILDCORES) {
                fixture = originalEntries.stream().filter(request -> request.sources().stream().anyMatch(source ->
                        source.sourceName() == part.sourceIdentity().sourceName()
                                && part.sourceIdentity().externalId().equals(source.externalId()))).findFirst().orElseThrow();
                assertThat(fixture.product()).isEqualTo(part.product());
                assertThat(fixture.specification()).isEqualTo(part.specification());
            } else {
                fixture = new CatalogEntryCreateRequest(part.product(), part.specification(), List.of(part.manufacturerEvidence()));
            }
            String productId = entries.create(fixture).product().id();
            productIds.put(part.selectionNumber(), productId);
            var evidence = sources.findBySourceNameAndExternalId(part.manufacturerEvidence().sourceName(),
                    part.manufacturerEvidence().externalId()).orElseGet(() -> sources.saveAndFlush(
                    new CatalogProductSource(products.getReferenceById(productId), part.manufacturerEvidence())));
            identity.bindProduct(new CatalogIdentityRequests.ProductBinding(productId, part.proposedCanonicalId(),
                    modelIds.get(part.modelCanonicalId()), part.identityKind(), part.role(), evidence.getId(), part.bindingReviewScope()));
        }
        prices.apply(plan.prices());
        assertThat(count("catalog_product")).isEqualTo(14);
        assertThat(count("catalog_model")).isEqualTo(13);
        assertThat(count("catalog_price_observation")).isEqualTo(8);
        jdbc.update("INSERT INTO users (id, name, created_at, updated_at) VALUES (1, 'Pilot search PC test', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        mvc = MockMvcBuilders.webAppContextSetup(context).defaultRequest(get("/").header("X-Dev-User-Id", "1")).build();
    }

    @Test
    void everyApprovedProductCanBeFoundByItsExactNameAndKnownPartNumberWithIdentityAndEightPrices() throws Exception {
        var approvedPrices = plan.prices().items().stream().collect(Collectors.toMap(
                item -> item.product().sourceName() + "/" + item.product().externalId(), item -> item.price().amountKrw()));
        for (var part : plan.parts()) {
            String productId = productIds.get(part.selectionNumber());
            var result = search(PRODUCT_URL, part.product().type(), "  " + part.product().modelName().toLowerCase(java.util.Locale.ROOT) + "  ");
            assertThat(ids(result)).as("selection %s exact model name", part.selectionNumber()).containsExactly(productId);
            if (part.product().partNumber() != null) {
                assertThat(ids(search(PRODUCT_URL, part.product().type(), part.product().partNumber())))
                        .as("selection %s exact PN", part.selectionNumber()).containsExactly(productId);
            }
            var detail = body(mvc.perform(get(PRODUCT_URL + "/{id}", productId)).andExpect(status().isOk()).andReturn());
            var product = detail.get("product");
            assertThat(product.get("canonicalId").stringValue()).isEqualTo(part.proposedCanonicalId());
            assertThat(product.get("modelId").stringValue()).isEqualTo(modelIds.get(part.modelCanonicalId()));
            assertThat(product.get("identityKind").stringValue()).isEqualTo(part.identityKind().name());
            assertThat(product.get("role").stringValue()).isEqualTo(part.role().name());
            assertThat(product.get("verificationStatus").stringValue()).isEqualTo("UNVERIFIED");
            assertThat(product.get("active").booleanValue()).isFalse();
            assertThat(detail.get("specification")).isEqualTo(mapper.readTree(mapper.writeValueAsString(part.specification())));
            var amount = approvedPrices.get(part.sourceIdentity().sourceName() + "/" + part.sourceIdentity().externalId());
            if (amount == null) assertThat(product.get("currentPrice").isNull()).isTrue();
            else assertThat(product.get("currentPrice").get("amountKrw").longValue()).isEqualTo(amount);
            assertThat(result.get("items").get(0).get("currentPrice")).isEqualTo(product.get("currentPrice"));
        }
    }

    @Test
    void productPaginationAndModelCandidatesKeepFourteenProductsAndThirteenModelsSeparate() throws Exception {
        var paged = new ArrayList<String>();
        for (int page = 0; page < 3; page++) {
            var result = body(mvc.perform(get(PRODUCT_URL).param("page", Integer.toString(page)).param("size", "5"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(page == 2 ? 4 : 5)))
                    .andExpect(jsonPath("$.totalElements").value(14)).andReturn());
            paged.addAll(ids(result));
        }
        assertThat(paged).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(productIds.values());
        mvc.perform(get(MODEL_URL).param("size", "100")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(13))).andExpect(jsonPath("$.totalElements").value(13));
        for (var model : plan.models()) {
            String modelId = modelIds.get(model.canonicalId());
            assertThat(ids(search(MODEL_URL, model.type(), model.manufacturer() + " " + model.modelName()))).containsExactly(modelId);
            var detail = body(mvc.perform(get(MODEL_URL + "/{id}", modelId)).andExpect(status().isOk()).andReturn());
            var expected = plan.parts().stream().filter(part -> part.modelCanonicalId().equals(model.canonicalId()))
                    .map(part -> productIds.get(part.selectionNumber())).toList();
            var candidates = new ArrayList<String>();
            detail.get("productCandidates").forEach(candidate -> candidates.add(candidate.get("id").stringValue()));
            assertThat(candidates).containsExactlyInAnyOrderElementsOf(expected);
            if (model.kind() == CatalogModelKind.RAM_SPEC_GROUP) assertThat(candidates).hasSize(2);
        }
    }

    @Test
    void manualIdLinksForAllFourteenProductsRoundTripWithoutChangingDeviceValuesOrCatalog() throws Exception {
        var before = productIds.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                item -> entries.findById(item.getValue()).orElseThrow()));
        var input = plan.parts().stream().map(part -> linked(part, productIds.get(part.selectionNumber()),
                modelIds.get(part.modelCanonicalId()), part.identityKind() == CatalogIdentityKind.MODEL_REFERENCE
                        ? CatalogRecognitionLevel.MODEL : CatalogRecognitionLevel.PHYSICAL_VARIANT)).toList();
        var created = create("All fourteen manually linked", input);
        long pcId = created.get("id").longValue();
        var read = body(mvc.perform(get("/api/pcs/{id}", pcId)).andExpect(status().isOk()).andReturn());
        assertThat(read.get("parts")).isEqualTo(mapper.readTree(mapper.writeValueAsString(input)));
        assertThat(count("pc_part")).isEqualTo(14);
        for (var ram : read.get("parts")) if (ram.get("type").stringValue().equals("RAM")) {
            assertThat(ram.get("quantity").intValue()).isEqualTo(2);
            assertThat(ram.get("specs").get("capacityBytes").longValue()).isEqualTo(17179869184L);
        }
        before.forEach((number, entry) -> {
            var reloaded = entries.findById(productIds.get(number)).orElseThrow();
            // JSON integer values can reload as a different Java Number class; compare persisted JSON facts.
            assertThat(mapper.readTree(mapper.writeValueAsString(reloaded)))
                    .isEqualTo(mapper.readTree(mapper.writeValueAsString(entry)));
        });
    }

    @Test
    void modelOnlyGpuAndRamRecognitionPreservesUnmatchedProductStateForEveryReviewedModel() throws Exception {
        var input = plan.parts().stream().map(part -> linked(part, null, modelIds.get(part.modelCanonicalId()),
                part.product().type() == PartType.RAM ? CatalogRecognitionLevel.SPEC_GROUP : CatalogRecognitionLevel.MODEL)).toList();
        var created = create("Models without exact retail products", input);
        var read = body(mvc.perform(get("/api/pcs/{id}", created.get("id").longValue())).andExpect(status().isOk()).andReturn());
        assertThat(read.get("parts")).isEqualTo(mapper.readTree(mapper.writeValueAsString(input)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pc_part WHERE catalog_product_id IS NOT NULL OR match_status <> 'UNMATCHED'", Integer.class)).isZero();
        assertThat(count("catalog_product")).isEqualTo(14);
        for (var part : read.get("parts")) if (part.get("type").stringValue().equals("GPU")) {
            assertThat(part.get("recognitionLevel").stringValue()).isEqualTo("MODEL");
            assertThat(part.get("catalogProductId").isNull()).isTrue();
        }
    }

    @Test
    void exactSsdSellerNumbersAndGenericGpuNamesReturnCandidatesWithoutInferringAnExactMatch() throws Exception {
        assertThat(ids(search(PRODUCT_URL, PartType.STORAGE, "MZ-77E1T0BW"))).containsExactly(productIds.get(13));
        assertThat(ids(search(PRODUCT_URL, PartType.STORAGE, "MZ-V9P1T0BW"))).containsExactly(productIds.get(14));
        // A partial device PN can find a candidate, but no PC link is created by a search.
        assertThat(ids(search(PRODUCT_URL, PartType.STORAGE, "MZ-V9P1T0"))).containsExactly(productIds.get(14));
        assertThat(ids(search(PRODUCT_URL, PartType.STORAGE, "MZ-V9P1T0CW"))).isEmpty();
        assertThat(ids(search(PRODUCT_URL, PartType.GPU, "GeForce RTX 5060"))).containsExactly(productIds.get(11));
        assertThat(ids(search(PRODUCT_URL, PartType.GPU, "NVIDIA GeForce RTX 5060"))).isEmpty();
        assertThat(ids(search(MODEL_URL, PartType.GPU, "NVIDIA GeForce RTX 5060")))
                .containsExactly(modelIds.get(plan.parts().get(10).modelCanonicalId()));
        assertThat(ids(search(PRODUCT_URL, PartType.GPU, "AMD Radeon RX 9060 XT"))).isEmpty();
        assertThat(ids(search(MODEL_URL, PartType.GPU, "AMD Radeon RX 9060 XT")))
                .containsExactly(modelIds.get(plan.parts().get(11).modelCanonicalId()));
        assertThat(ids(search(PRODUCT_URL, PartType.STORAGE, "Samsung SSD 990 PRO 1TB"))).isEmpty();
        assertThat(ids(search(MODEL_URL, PartType.STORAGE, "Samsung SSD 990 PRO 1TB"))).isEmpty();
        assertThat(ids(search(MODEL_URL, PartType.CPU, "AMD Ryzen 5 9600X 6-Core Processor"))).isEmpty();
        assertThat(ids(search(MODEL_URL, PartType.CPU, "Intel(R) Core(TM) Ultra 5 245K"))).isEmpty();
        assertThat(ids(search(PRODUCT_URL, PartType.CPU, "BX80768245K"))).isEmpty();
        assertThat(ids(search(PRODUCT_URL, PartType.CPU, "BX80768250K"))).isEmpty();
        assertThat(count("pc_configuration")).isZero();
        assertThat(count("catalog_price_observation")).isEqualTo(8);
    }

    @Test
    void windowsNamesRemainUnmatchedDuringScanEvenWhenReviewedCatalogCandidatesExist() {
        var observed = List.of(
                observed(PartType.CPU, "AMD Ryzen 5 9600X 6-Core Processor", Map.of()),
                observed(PartType.CPU, "Intel(R) Core(TM) Ultra 5 245K", Map.of()),
                observed(PartType.GPU, "NVIDIA GeForce RTX 5060", Map.of()),
                observed(PartType.GPU, "AMD Radeon RX 9060 XT", Map.of()),
                observed(PartType.RAM, "Unknown DDR5 module", Map.of("capacityBytes", 17179869184L)),
                observed(PartType.STORAGE, "Samsung SSD 870 EVO 1TB", Map.of("capacityBytes", 1000000000000L)),
                observed(PartType.STORAGE, "Samsung SSD 990 PRO 1TB", Map.of("capacityBytes", 1000000000000L)),
                observed(PartType.STORAGE, "Unknown device", Map.of()));
        var created = scans.create();
        String writeToken = created.launchUri().substring(created.launchUri().indexOf("&token=") + 7);
        scans.start(created.sessionId(), writeToken);
        scans.complete(created.sessionId(), writeToken, new ScanDtos.Result(1, "pilot-test", Instant.now(), observed, List.of()));
        var result = scans.read(created.sessionId(), created.readToken()).result();
        assertThat(result.parts()).isEqualTo(observed);
        assertThat(result.parts()).allSatisfy(part -> {
            assertThat(part.matchStatus()).isEqualTo(MatchStatus.UNMATCHED);
            assertThat(part.catalogProductId()).isNull();
            assertThat(part.catalogModelId()).isNull();
            assertThat(part.recognitionLevel()).isNull();
        });
        assertThat(count("pc_configuration")).isZero();
        assertThat(count("pc_part")).isZero();
    }

    @Test
    void wrongCategoryModelOrExactVariantClaimsRejectTheWholePcInsteadOfInventingAConnection() throws Exception {
        var gpu = plan.parts().get(10);
        var board = plan.parts().get(4);
        var ram = plan.parts().get(8);
        reject(linked(gpu, productIds.get(13), null, null), "PART_CATEGORY_MISMATCH");
        reject(linked(gpu, productIds.get(11), modelIds.get(plan.parts().get(11).modelCanonicalId()), CatalogRecognitionLevel.MODEL), "MODEL_PRODUCT_MISMATCH");
        reject(linked(board, productIds.get(5), modelIds.get(board.modelCanonicalId()), CatalogRecognitionLevel.PHYSICAL_VARIANT), "PHYSICAL_VARIANT_UNVERIFIED");
        reject(linked(ram, null, modelIds.get(ram.modelCanonicalId()), CatalogRecognitionLevel.MODEL), "MODEL_RECOGNITION_MISMATCH");
        assertThat(count("pc_configuration")).isZero();
        assertThat(count("pc_part")).isZero();
    }

    private JsonNode search(String url, PartType type, String q) throws Exception {
        return body(mvc.perform(get(url).param("type", type.name()).param("q", q).param("size", "100"))
                .andExpect(status().isOk()).andReturn());
    }

    private PartInput linked(CatalogPilotImportLoader.Part part, String productId, String modelId, CatalogRecognitionLevel level) {
        var specs = new LinkedHashMap<String, Object>();
        specs.put("unconfirmedReading", null);
        if (part.specification() instanceof CatalogSpecification.Ram ram) specs.put("capacityBytes", ram.moduleCapacityBytes());
        if (part.specification() instanceof CatalogSpecification.Storage storage) specs.put("capacityBytes", storage.capacityBytes());
        int quantity = part.product().type() == PartType.RAM ? 2 : 1;
        return new PartInput(part.product().type(), "Observed " + part.selectionNumber(), "  unmodified hardware reading  ", quantity,
                InputSource.MANUAL, productId, productId == null ? MatchStatus.UNMATCHED : MatchStatus.MATCHED, specs, modelId, level);
    }

    private PartInput observed(PartType type, String name, Map<String, Object> specs) {
        return new PartInput(type, name, name, 1, InputSource.AUTO, null, MatchStatus.UNMATCHED, specs);
    }

    private JsonNode create(String name, List<PartInput> parts) throws Exception {
        var created = body(mvc.perform(post("/api/pcs").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new PcDtos.Request(name, parts)))).andExpect(status().isCreated()).andReturn());
        assertThat(created.get("parts")).isEqualTo(mapper.readTree(mapper.writeValueAsString(parts)));
        // Reload through SQL rather than returning the newly saved PC from the persistence cache.
        entityManager.flush();
        entityManager.clear();
        return created;
    }

    private void reject(PartInput part, String code) throws Exception {
        mvc.perform(post("/api/pcs").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new PcDtos.Request("Rejected pilot connection", List.of(part)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(code));
    }

    private JsonNode body(MvcResult result) { return mapper.readTree(result.getResponse().getContentAsByteArray()); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private List<String> ids(JsonNode page) {
        var result = new ArrayList<String>();
        page.get("items").forEach(item -> result.add(item.get("id").stringValue()));
        return result;
    }
}
