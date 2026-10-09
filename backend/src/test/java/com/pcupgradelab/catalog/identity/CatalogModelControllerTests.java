package com.pcupgradelab.catalog.identity;

import com.pcupgradelab.catalog.CatalogSourceInput;
import com.pcupgradelab.catalog.CatalogSourceName;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:catalog-model-controller-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "catalog.seed.enabled=false", "catalog.cpu-memory.seed.enabled=false", "catalog.motherboard-cpu.seed.enabled=false"
})
@ActiveProfiles({"local", "test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class CatalogModelControllerTests {
    @Autowired WebApplicationContext context;
    @Autowired CatalogIdentityService identity;
    private MockMvc mvc;

    @BeforeEach
    void setUp() { mvc = MockMvcBuilders.webAppContextSetup(context).build(); }

    @Test
    void exposesReviewModelsAndScopedEvidenceWithoutRawPayloadOrWrites() throws Exception {
        var source = new CatalogSourceInput(CatalogSourceName.MANUFACTURER, "fixture-model", null,
                "https://example.com/model", Map.of("rawOnly", "do not expose"), Instant.parse("2026-10-09T01:00:00Z"));
        var model = identity.registerModel(new CatalogIdentityRequests.ModelRegistration(UUID.randomUUID().toString(),
                CatalogModelKind.RAM_SPEC_GROUP.type(), "Fixture", "DDR5-5600 16 GiB range", CatalogModelKind.RAM_SPEC_GROUP,
                CatalogRole.INSTALLED_PC_REFERENCE, null, null,
                List.of(new CatalogIdentityRequests.ModelSource(source, "PN is unknown; only specification range")),
                List.of(new CatalogIdentityRequests.ModelAlias("Fixture DDR5 5600", 0))));
        mvc.perform(get("/api/catalog/models").param("type", "RAM").param("q", "Fixture DDR5 5600"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].kind").value("RAM_SPEC_GROUP"))
                .andExpect(jsonPath("$.items[0].verificationStatus").value("UNVERIFIED"));
        mvc.perform(get("/api/catalog/models/{id}", model.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[0].reviewScope").value("PN is unknown; only specification range"))
                .andExpect(jsonPath("$.sources[0].rawPayload").doesNotExist())
                .andExpect(jsonPath("$.aliases[0].evidence.rawPayload").doesNotExist())
                .andExpect(jsonPath("$.productCandidates").isEmpty());
        mvc.perform(post("/api/catalog/models").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(put("/api/catalog/models/{id}", model.id()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/api/catalog/models/{id}", model.id())).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void malformedSearchAndUnknownModelKeepTheSharedErrorContract() throws Exception {
        mvc.perform(get("/api/catalog/models").param("size", "101")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(get("/api/catalog/models/{id}", "unknown-model")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATALOG_MODEL_NOT_FOUND"));
    }
}
