package com.pcupgradelab.pc;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.catalog.identity.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pc-model-link-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class PcModelLinkTests {
    @Autowired WebApplicationContext context;
    @Autowired CatalogIdentityService identity;
    @Autowired CatalogProductService products;
    @Autowired CatalogProductRepository productRepository;
    @Autowired CatalogProductSourceRepository productSources;
    @Autowired JdbcTemplate jdbc;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).defaultRequest(get("/").header("X-Dev-User-Id", "1")).build();
        jdbc.update("INSERT INTO users (id, name, created_at, updated_at) VALUES (1, 'Model link test', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    @Test
    void modelOnlyRecognitionIsStoredWithoutInventingAProductOrChangingObservedDeviceValues() throws Exception {
        var model = model(CatalogModelKind.CPU_MODEL, "CPU model");
        var part = part(PartType.CPU, null, model.id(), CatalogRecognitionLevel.MODEL);
        var result = mvc.perform(post("/api/pcs").contentType(MediaType.APPLICATION_JSON).content(request("Model PC", part)))
                .andExpect(status().isCreated()).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(body.get("parts")).isEqualTo(mapper.readTree(mapper.writeValueAsString(List.of(part))));
        long id = body.get("id").longValue();
        mvc.perform(get("/api/pcs/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.parts[0].catalogModelId").value(model.id()))
                .andExpect(jsonPath("$.parts[0].recognitionLevel").value("MODEL"))
                .andExpect(jsonPath("$.parts[0].matchStatus").value("UNMATCHED"))
                .andExpect(jsonPath("$.parts[0].catalogProductId").isEmpty());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product", Long.class)).isZero();
    }

    @Test
    void unknownWrongTypeAndSpecificationGroupClaimsRejectTheEntireCreate() throws Exception {
        var cpu = model(CatalogModelKind.CPU_MODEL, "CPU model");
        var group = model(CatalogModelKind.RAM_SPEC_GROUP, "RAM specification range");
        rejectCreate(part(PartType.CPU, null, "missing-model", CatalogRecognitionLevel.MODEL), "INVALID_MODEL_ID");
        rejectCreate(part(PartType.CPU, null, group.id(), CatalogRecognitionLevel.SPEC_GROUP), "PART_CATEGORY_MISMATCH");
        rejectCreate(part(PartType.CPU, null, cpu.id(), CatalogRecognitionLevel.SPEC_GROUP), "MODEL_RECOGNITION_MISMATCH");
        rejectCreate(part(PartType.RAM, null, group.id(), CatalogRecognitionLevel.MODEL), "MODEL_RECOGNITION_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pc_configuration", Long.class)).isZero();
    }

    @Test
    void inconsistentModelAndProductUpdatePreservesTheExistingPc() throws Exception {
        var cpu = model(CatalogModelKind.CPU_MODEL, "CPU model");
        var other = model(CatalogModelKind.CPU_MODEL, "Different CPU model");
        String product = physicalProduct(cpu.id());
        var first = part(PartType.CPU, product, cpu.id(), CatalogRecognitionLevel.MODEL);
        var result = mvc.perform(post("/api/pcs").contentType(MediaType.APPLICATION_JSON).content(request("Original PC", first)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode before = mapper.readTree(result.getResponse().getContentAsByteArray());
        long id = before.get("id").longValue();
        mvc.perform(put("/api/pcs/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(request("Must stay original", part(PartType.CPU, product, other.id(), CatalogRecognitionLevel.MODEL))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MODEL_PRODUCT_MISMATCH"));
        var after = mvc.perform(get("/api/pcs/{id}", id)).andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(after.getResponse().getContentAsByteArray())).isEqualTo(before);
    }

    @Test
    void physicalVariantClaimsRequireExplicitProductClassificationWhileLegacyLinksRemainValid() throws Exception {
        String legacy = products.create(new CatalogProductCreateRequest(PartType.CPU, "Fixture", "Legacy CPU", null)).id();
        rejectCreate(part(PartType.CPU, legacy, null, CatalogRecognitionLevel.PHYSICAL_VARIANT), "PHYSICAL_VARIANT_UNVERIFIED");
        mvc.perform(post("/api/pcs").contentType(MediaType.APPLICATION_JSON)
                        .content(request("Legacy preserved", new PartInput(PartType.CPU, "CPU", "raw", 1, InputSource.MANUAL,
                                legacy, MatchStatus.MATCHED, Map.of()))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.parts[0].recognitionLevel").isEmpty())
                .andExpect(jsonPath("$.parts[0].catalogModelId").isEmpty());
        var model = model(CatalogModelKind.CPU_MODEL, "Explicit CPU model");
        String exact = physicalProduct(model.id());
        mvc.perform(post("/api/pcs").contentType(MediaType.APPLICATION_JSON)
                        .content(request("Reviewed variant", part(PartType.CPU, exact, model.id(), CatalogRecognitionLevel.PHYSICAL_VARIANT))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.parts[0].recognitionLevel").value("PHYSICAL_VARIANT"));
    }

    @Test
    void recognitionLevelAndModelPresenceMustAgreeBeforeAnyPcIsWritten() throws Exception {
        var model = model(CatalogModelKind.CPU_MODEL, "CPU model");
        rejectCreate(part(PartType.CPU, null, model.id(), null), "INVALID_INPUT");
        rejectCreate(part(PartType.CPU, null, null, CatalogRecognitionLevel.MODEL), "INVALID_INPUT");
        rejectCreate(part(PartType.CPU, null, model.id(), CatalogRecognitionLevel.PHYSICAL_VARIANT), "INVALID_INPUT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pc_configuration", Long.class)).isZero();
    }

    private void rejectCreate(PartInput part, String code) throws Exception {
        mvc.perform(post("/api/pcs").contentType(MediaType.APPLICATION_JSON).content(request("Rejected PC", part)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(code));
    }

    private CatalogIdentityDtos.Model model(CatalogModelKind kind, String name) {
        var source = new CatalogSourceInput(CatalogSourceName.MANUFACTURER, null, null, "https://example.com/model",
                Map.of("record_kind", "TEST_MODEL_EXTRACT"), Instant.parse("2026-10-09T01:00:00Z"));
        return identity.registerModel(new CatalogIdentityRequests.ModelRegistration(UUID.randomUUID().toString(), kind.type(),
                "Fixture", name, kind, CatalogRole.INSTALLED_PC_REFERENCE, null, null,
                List.of(new CatalogIdentityRequests.ModelSource(source, "Fixture model identity only")), List.of()));
    }

    private String physicalProduct(String modelId) {
        String id = products.create(new CatalogProductCreateRequest(PartType.CPU, "Fixture", "Reviewed CPU", "FIXTURE-SKU")).id();
        var source = productSources.saveAndFlush(new CatalogProductSource(productRepository.findById(id).orElseThrow(),
                new CatalogSourceInput(CatalogSourceName.MANUFACTURER, null, null, "https://example.com/product",
                        Map.of("record_kind", "TEST_PRODUCT_EXTRACT"), Instant.parse("2026-10-09T01:00:00Z"))));
        identity.bindProduct(new CatalogIdentityRequests.ProductBinding(id, UUID.randomUUID().toString(), modelId,
                CatalogIdentityKind.PHYSICAL_VARIANT, CatalogRole.BOTH, source.getId(), "Fixture physical model identity"));
        return id;
    }

    private PartInput part(PartType type, String product, String model, CatalogRecognitionLevel level) {
        return new PartInput(type, "Observed " + type, "  raw Windows model  ", 1, InputSource.AUTO, product,
                product == null ? MatchStatus.UNMATCHED : MatchStatus.MATCHED, Map.of("unmodifiedReading", "unknown"), model, level);
    }

    private String request(String name, PartInput part) { return mapper.writeValueAsString(new PcDtos.Request(name, List.of(part))); }
}
