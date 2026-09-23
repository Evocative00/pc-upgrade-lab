package com.pcupgradelab.scan;

import com.pcupgradelab.common.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ScanControllerTests {
    private ScanService service;
    private MockMvc mvc;
    @BeforeEach void setup() {
        service = new ScanService();
        mvc = MockMvcBuilders.standaloneSetup(new ScanController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test void requiresCustomCreationHeaderAndDisablesCaching() throws Exception {
        mvc.perform(post("/api/scan-sessions")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(post("/api/scan-sessions").header("X-PCUL-Client", "web"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.launchUri").isString()).andExpect(jsonPath("$.readToken").isString());
    }

    @Test void validatesNestedPartsAndAcceptsPartialResults() throws Exception {
        var scan = service.create();
        var auth = "Bearer " + scan.launchUri().split("token=")[1];
        var path = "/api/scan-sessions/" + scan.sessionId();
        mvc.perform(post(path + "/start").header("Authorization", auth)).andExpect(status().isNoContent());
        String input = """
            {"schemaVersion":1,"collectorVersion":"0.1.0","collectedAt":"2026-09-23T00:00:00Z",
             "parts":[{"type":"CPU","displayName":"Fixture CPU","rawName":"원문 이름","quantity":0,
                       "source":"AUTO","catalogProductId":null,"matchStatus":"UNMATCHED","specs":{}}],"warnings":[]}
            """;
        mvc.perform(post(path + "/result").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(post(path + "/result").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(input.replace("\"quantity\":0", "\"quantity\":1")))
                .andExpect(status().isNoContent());
        mvc.perform(get(path).header("X-Scan-Token", scan.readToken())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED_WITH_WARNINGS"))
                .andExpect(jsonPath("$.result.parts[0].rawName").value("원문 이름"));
    }
}
