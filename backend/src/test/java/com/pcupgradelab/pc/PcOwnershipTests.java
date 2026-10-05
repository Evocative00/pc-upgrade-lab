package com.pcupgradelab.pc;

import com.pcupgradelab.auth.SessionCurrentUser;
import com.pcupgradelab.catalog.CatalogProductCreateRequest;
import com.pcupgradelab.catalog.CatalogProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 이슈 #18 완료 기준을 A/B 두 회원으로 확인한다.
 * A는 로그인 세션 속성, B는 개발용 헤더로 지정해 두 회원 지정 방식이 모두 동작하는지도 함께 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PcOwnershipTests {
    private static final long USER_A = 1001L;
    private static final long USER_B = 1002L;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired WebApplicationContext context;
    @Autowired CatalogProductService products;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;
    private MockHttpSession sessionA;

    @BeforeEach
    void setUp() {
        for (long userId : new long[]{USER_A, USER_B}) {
            jdbc.update("INSERT INTO users (id, name, created_at, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    userId, "PC 소유권 테스트 회원 " + userId);
        }
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        sessionA = new MockHttpSession();
        sessionA.setAttribute(SessionCurrentUser.SESSION_ATTRIBUTE, USER_A);
    }

    @Test
    @DisplayName("로그인하지 않으면 목록·상세·등록·수정·삭제 모두 401 UNAUTHORIZED")
    void requiresLogin() throws Exception {
        for (var request : new MockHttpServletRequestBuilder[]{
                get("/api/pcs"), get("/api/pcs/1"), delete("/api/pcs/1"),
                post("/api/pcs").contentType(MediaType.APPLICATION_JSON).content(pc("이름")),
                put("/api/pcs/1").contentType(MediaType.APPLICATION_JSON).content(pc("이름"))}) {
            mvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    @DisplayName("B가 A의 PC ID로 조회·수정·삭제하면 모두 404이고 A의 PC는 그대로 남는다")
    void otherUsersPcIsInvisible() throws Exception {
        long id = create(asA(), "A의 PC");

        mvc.perform(asB(get("/api/pcs/{id}", id))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PC_NOT_FOUND"));
        mvc.perform(asB(put("/api/pcs/{id}", id)).contentType(MediaType.APPLICATION_JSON).content(pc("B가 바꾼 이름")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PC_NOT_FOUND"));
        mvc.perform(asB(delete("/api/pcs/{id}", id))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PC_NOT_FOUND"));
        mvc.perform(asB(get("/api/pcs"))).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));

        mvc.perform(get("/api/pcs/{id}", id).session(sessionA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("A의 PC"));
        mvc.perform(get("/api/pcs").session(sessionA)).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    @DisplayName("같은 회원의 중복 이름은 공백·대소문자가 달라도 409, 다른 회원의 같은 이름은 허용")
    void duplicateNamesAreScopedToOneUser() throws Exception {
        create(asA(), "Gaming PC");

        mvc.perform(post("/api/pcs").session(sessionA).contentType(MediaType.APPLICATION_JSON).content(pc("  gaming pc ")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PC_NAME_DUPLICATE"))
                .andExpect(jsonPath("$.message").isString());

        create(asB(post("/api/pcs")), "Gaming PC");
    }

    @Test
    @DisplayName("수정 시 자기 PC는 중복 대상에서 제외하고, 다른 PC 이름으로 바꾸면 409")
    void updateExcludesItselfFromDuplicateCheck() throws Exception {
        long first = create(asA(), "첫 번째");
        create(asA(), "두 번째");

        mvc.perform(put("/api/pcs/{id}", first).session(sessionA).contentType(MediaType.APPLICATION_JSON).content(pc(" 첫 번째 ")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("첫 번째"));
        mvc.perform(put("/api/pcs/{id}", first).session(sessionA).contentType(MediaType.APPLICATION_JSON).content(pc("두 번째")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PC_NAME_DUPLICATE"));
        mvc.perform(get("/api/pcs/{id}", first).session(sessionA)).andExpect(jsonPath("$.name").value("첫 번째"));
    }

    @Test
    @DisplayName("이름 검사를 통과한 뒤 동시에 같은 이름이 저장돼도 DB 제약으로 막는다")
    void databaseConstraintBlocksDuplicateNames() {
        jdbc.update("""
                INSERT INTO pc_configuration (user_id, name, name_normalized, version, created_at, updated_at)
                VALUES (?, 'Race', 'race', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, USER_A);
        var duplicate = org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update("""
                INSERT INTO pc_configuration (user_id, name, name_normalized, version, created_at, updated_at)
                VALUES (?, 'RACE', 'race', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, USER_A));
        assertThat(duplicate).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("UK_PC_CONFIGURATION_USER_NAME");
    }

    @Test
    @DisplayName("삭제하면 204이고 목록·상세에서 다시 조회되지 않으며 부품 행도 함께 삭제된다")
    void deleteRemovesPcAndParts() throws Exception {
        long id = create(asA(), "삭제할 PC");

        mvc.perform(delete("/api/pcs/{id}", id).session(sessionA)).andExpect(status().isNoContent());

        mvc.perform(get("/api/pcs/{id}", id).session(sessionA)).andExpect(status().isNotFound());
        mvc.perform(get("/api/pcs").session(sessionA)).andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(delete("/api/pcs/{id}", id).session(sessionA)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PC_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pc_part WHERE pc_id = ?", Long.class, id)).isZero();
        // 삭제한 이름은 다시 사용할 수 있다.
        create(asA(), "삭제할 PC");
    }

    @Test
    @DisplayName("없는 부품 ID는 INVALID_PART_ID, 종류가 다른 부품은 PART_CATEGORY_MISMATCH로 400")
    void rejectsInvalidPartLinks() throws Exception {
        var gpuId = products.create(new CatalogProductCreateRequest(PartType.GPU, "Owner Test", "GPU 모델", null)).id();

        mvc.perform(post("/api/pcs").session(sessionA).contentType(MediaType.APPLICATION_JSON)
                        .content(pcWithLink("없는 부품", "RAM", "missing-product")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PART_ID"));
        mvc.perform(post("/api/pcs").session(sessionA).contentType(MediaType.APPLICATION_JSON)
                        .content(pcWithLink("종류 불일치", "RAM", gpuId)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PART_CATEGORY_MISMATCH"));
        mvc.perform(get("/api/pcs").session(sessionA)).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    @DisplayName("카탈로그에 없는 수집 결과는 미연결 부품으로 원문과 함께 보존한다")
    void keepsUnmatchedPartsWithRawName() throws Exception {
        long id = create(asA(), "미연결 부품 PC");
        mvc.perform(get("/api/pcs/{id}", id).session(sessionA))
                .andExpect(jsonPath("$.parts[0].matchStatus").value("UNMATCHED"))
                .andExpect(jsonPath("$.parts[0].catalogProductId").doesNotExist())
                .andExpect(jsonPath("$.parts[0].rawName").value("Unknown Vendor RAM"));
    }

    @Test
    @DisplayName("기존 local-dev 데이터(user_id NULL)는 어느 회원에게도 보이지 않는다")
    void legacyRowsAreNotAssignedToAnyone() throws Exception {
        jdbc.update("""
                INSERT INTO pc_configuration (owner_key, user_id, name, name_normalized, version, created_at, updated_at)
                VALUES ('local-dev', NULL, '예전 PC', '예전 pc', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        long legacyId = jdbc.queryForObject("SELECT id FROM pc_configuration WHERE owner_key = 'local-dev'", Long.class);

        mvc.perform(get("/api/pcs").session(sessionA)).andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get("/api/pcs/{id}", legacyId).session(sessionA)).andExpect(status().isNotFound());
        // 같은 이름도 회원 PC로 새로 저장할 수 있다.
        create(asA(), "예전 PC");
    }

    private long create(MockHttpServletRequestBuilder request, String name) throws Exception {
        var result = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(pc(name)))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsByteArray()).get("id").longValue();
    }

    private MockHttpServletRequestBuilder asA() {
        return post("/api/pcs").session(sessionA);
    }

    private static MockHttpServletRequestBuilder asB(MockHttpServletRequestBuilder request) {
        return request.header(SessionCurrentUser.DEV_HEADER, String.valueOf(USER_B));
    }

    private static String pc(String name) {
        return """
                {"name": %s, "parts": [{"type": "RAM", "displayName": "DDR4 8GB", "rawName": "Unknown Vendor RAM",
                  "quantity": 1, "source": "AUTO", "catalogProductId": null, "matchStatus": "UNMATCHED",
                  "specs": {"capacityBytes": 8589934592}}]}
                """.formatted(quote(name));
    }

    private static String pcWithLink(String name, String type, String productId) {
        return """
                {"name": %s, "parts": [{"type": "%s", "displayName": "연결 부품", "rawName": null,
                  "quantity": 1, "source": "MANUAL", "catalogProductId": %s, "matchStatus": "MATCHED", "specs": {}}]}
                """.formatted(quote(name), type, quote(productId));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
