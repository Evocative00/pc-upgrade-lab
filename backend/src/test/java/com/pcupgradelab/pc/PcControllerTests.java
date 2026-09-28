package com.pcupgradelab.pc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * PC 등록·조회·수정 API HTTP 계층 및 데이터베이스 연동 검증.
 * docs/week1-contract.md에 정의된 공통 규격 및 완료 기준을 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PcControllerTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static final String VALID_PC_JSON = """
            {
              "name": "테스트용 PC",
              "parts": [
                {
                  "type": "CPU",
                  "displayName": "Intel Core i7",
                  "rawName": "Intel(R) Core(TM) i7-14700",
                  "quantity": 1,
                  "source": "AUTO",
                  "catalogProductId": null,
                  "matchStatus": "UNMATCHED",
                  "specs": {
                    "cores": 20,
                    "logicalProcessors": 28
                  }
                },
                {
                  "type": "RAM",
                  "displayName": "DDR5 16GB #1",
                  "rawName": "Samsung DDR5",
                  "quantity": 1,
                  "source": "AUTO",
                  "catalogProductId": null,
                  "matchStatus": "UNMATCHED",
                  "specs": {
                    "capacityBytes": 17179869184,
                    "slot": "DIMM 1"
                  }
                },
                {
                  "type": "RAM",
                  "displayName": "DDR5 16GB #2",
                  "rawName": "Samsung DDR5",
                  "quantity": 1,
                  "source": "AUTO",
                  "catalogProductId": null,
                  "matchStatus": "UNMATCHED",
                  "specs": {
                    "capacityBytes": 17179869184,
                    "slot": "DIMM 2"
                  }
                },
                {
                  "type": "STORAGE",
                  "displayName": "NVMe SSD 1TB",
                  "rawName": "Samsung 980 PRO 1TB",
                  "quantity": 1,
                  "source": "AUTO",
                  "catalogProductId": null,
                  "matchStatus": "UNMATCHED",
                  "specs": {
                    "capacityBytes": 1000000000000
                  }
                }
              ]
            }
            """;

    @Test
    @DisplayName("PC 등록 성공 시 201 Created와 Location 헤더, PC 상세 DTO를 반환한다")
    void createPcSuccess() throws Exception {
        mvc.perform(post("/api/pcs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PC_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("^/api/pcs/\\d+$")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("테스트용 PC"))
                .andExpect(jsonPath("$.parts", hasSize(4)))
                .andExpect(jsonPath("$.parts[0].displayName").value("Intel Core i7"))
                .andExpect(jsonPath("$.parts[1].displayName").value("DDR5 16GB #1"))
                .andExpect(jsonPath("$.parts[2].displayName").value("DDR5 16GB #2"))
                .andExpect(jsonPath("$.parts[0].id").doesNotExist()) // 내부 DB ID 노출 안 됨 확인
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.updatedAt").isString());
    }

    @Test
    @DisplayName("PC 상세 조회 시 저장된 부품 목록과 정보를 반환한다")
    void getPcDetailSuccess() throws Exception {
        var createResult = mvc.perform(post("/api/pcs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PC_JSON))
                .andExpect(status().isCreated())
                .andReturn();

        String location = createResult.getResponse().getHeader("Location");
        assertThat(location).isNotNull();

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("테스트용 PC"))
                .andExpect(jsonPath("$.parts", hasSize(4)))
                .andExpect(jsonPath("$.parts[3].displayName").value("NVMe SSD 1TB"));
    }

    @Test
    @DisplayName("PC 목록 조회 시 요약 정보와 페이징 메타데이터를 반환한다")
    void getPcListSuccess() throws Exception {
        mvc.perform(post("/api/pcs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PC_JSON))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/pcs?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.totalPages").isNumber())
                .andExpect(jsonPath("$.items", isA(Iterable.class)))
                .andExpect(jsonPath("$.items[0].id").isNumber())
                .andExpect(jsonPath("$.items[0].name").isString())
                .andExpect(jsonPath("$.items[0].createdAt").isString())
                .andExpect(jsonPath("$.items[0].updatedAt").isString())
                .andExpect(jsonPath("$.items[0].parts").doesNotExist()); // 요약 정보이므로 parts 제외 확인
    }

    @Test
    @DisplayName("PC 수정 시 기존 PC ID를 유지하고 부품을 교체하며 이전 부품이 중복 누적되지 않는다")
    void updatePcSuccessAndReplacesPartsWithoutDuplication() throws Exception {
        var createResult = mvc.perform(post("/api/pcs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PC_JSON))
                .andExpect(status().isCreated())
                .andReturn();

        String location = createResult.getResponse().getHeader("Location");
        Long id = Long.valueOf(location.substring("/api/pcs/".length()));

        String updateJson = """
                {
                  "name": "수정된 PC 이름",
                  "parts": [
                    {
                      "type": "GPU",
                      "displayName": "NVIDIA GeForce RTX 4070",
                      "rawName": "RTX 4070",
                      "quantity": 1,
                      "source": "MANUAL",
                      "catalogProductId": null,
                      "matchStatus": "UNMATCHED",
                      "specs": {}
                    }
                  ]
                }
                """;

        mvc.perform(put("/api/pcs/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("수정된 PC 이름"))
                .andExpect(jsonPath("$.parts", hasSize(1)))
                .andExpect(jsonPath("$.parts[0].displayName").value("NVIDIA GeForce RTX 4070"));

        // DB에 실제로 이전 4개 부품이 삭제되고 새 1개 부품만 남아있는지 확인
        Integer partCount = jdbc.queryForObject("SELECT COUNT(*) FROM pc_part WHERE pc_id = ?", Integer.class, id);
        assertThat(partCount).isEqualTo(1);
    }

    @Test
    @DisplayName("빈 PC 이름, 빈 부품 목록, 잘못된 수량은 400 INVALID_INPUT으로 거절한다")
    void rejectsInvalidInputs() throws Exception {
        // 1. 빈 이름
        String blankNameJson = """
                {
                  "name": "   ",
                  "parts": [
                    {
                      "type": "CPU",
                      "displayName": "CPU",
                      "quantity": 1,
                      "source": "AUTO",
                      "matchStatus": "UNMATCHED",
                      "specs": {}
                    }
                  ]
                }
                """;
        mvc.perform(post("/api/pcs").contentType(MediaType.APPLICATION_JSON).content(blankNameJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists());

        // 2. 빈 부품 목록
        String emptyPartsJson = """
                {
                  "name": "정상 이름",
                  "parts": []
                }
                """;
        mvc.perform(post("/api/pcs").contentType(MediaType.APPLICATION_JSON).content(emptyPartsJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.errors[?(@.field == 'parts')]").exists());

        // 3. 잘못된 부품 수량 (0 이하)
        String zeroQuantityJson = """
                {
                  "name": "정상 이름",
                  "parts": [
                    {
                      "type": "CPU",
                      "displayName": "CPU",
                      "quantity": 0,
                      "source": "AUTO",
                      "matchStatus": "UNMATCHED",
                      "specs": {}
                    }
                  ]
                }
                """;
        mvc.perform(post("/api/pcs").contentType(MediaType.APPLICATION_JSON).content(zeroQuantityJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.errors[?(@.field == 'parts[0].quantity')]").exists());

        // 4. 잘못된 페이징 size (<1 또는 >100)
        mvc.perform(get("/api/pcs?page=0&size=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        mvc.perform(get("/api/pcs?page=0&size=101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("JPA 조회 위치의 상한을 넘으면 공통 400 응답을 반환하고 허용 범위는 정상 조회한다")
    void rejectsPageOffsetsBeyondJpaLimit() throws Exception {
        // 두 입력 모두 int지만 곱은 int 범위를 넘는다. int로 곱하면 음수로 돌아가는 경우도 검사한다.
        for (String query : new String[]{"page=21474837&size=100", "page=2147483647&size=100"}) {
            mvc.perform(get("/api/pcs?" + query))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.message").isString())
                    .andExpect(jsonPath("$.errors").isArray());
        }
        // 경계 바로 아래와 정확한 상한은 유효하다. 해당 위치에 PC가 없으면 빈 목록이다.
        for (String query : new String[]{"page=21474836&size=100", "page=2147483647&size=1"}) {
            mvc.perform(get("/api/pcs?" + query))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(0)));
        }
    }

    @Test
    @DisplayName("존재하지 않는 PC ID 조회 및 수정 시 404 PC_NOT_FOUND를 반환한다")
    void returns404WhenNotFound() throws Exception {
        mvc.perform(get("/api/pcs/99999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PC_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("PC를 찾을 수 없습니다."));

        mvc.perform(put("/api/pcs/99999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PC_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PC_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("PC를 찾을 수 없습니다."));
    }
}
