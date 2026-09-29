package com.pcupgradelab.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 출처 연결과 원본 보존을 Flyway로 생성한 H2 테이블에서 확인한다. 각 테스트는 롤백한다.
 * URL 형식·JSON 객체 여부·원본 ID 일치 등 적재 서비스의 검증은 여기서 대신하지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CatalogProductSourceSchemaTests {
    @Autowired JdbcTemplate jdbc;

    @Test
    void productKeepsMultipleSourcesAndPreservesNestedRawPayload() {
        String productId = insertProduct();
        String externalId = "bc-cpu-" + UUID.randomUUID();
        String payload = rawPayload(externalId);
        insertBuildCores(productId, externalId, "fixture-revision-1", payload);
        insertUrlOnlySource(productId, "MANUFACTURER", null,
                "https://example.invalid/manufacturer/cpu-specifications");

        assertThat(jdbc.queryForList("""
                SELECT source_name FROM catalog_product_source WHERE product_id = ? ORDER BY source_name
                """, String.class, productId)).containsExactly("BUILDCORES", "MANUFACTURER");
        var sourceIds = jdbc.queryForList(
                "SELECT id FROM catalog_product_source WHERE product_id = ?", Long.class, productId);
        assertThat(sourceIds).hasSize(2).doesNotHaveDuplicates().allSatisfy(id -> assertThat(id).isPositive());

        String storedPayload = jdbc.queryForObject("""
                SELECT raw_payload FROM catalog_product_source
                WHERE product_id = ? AND source_name = 'BUILDCORES'
                """, (resultSet, rowNum) -> resultSet.getString("raw_payload"), productId);
        // H2는 JSON 객체 멤버 순서를 보존한다. 공백 없는 원본으로 배열·중첩·JSON null까지 비교한다.
        // 문자열로 이중 인코딩했다면 이 비교가 실패한다.
        assertThat(storedPayload).isEqualTo(payload);
        String manufacturerPayload = jdbc.queryForObject("""
        SELECT raw_payload FROM catalog_product_source
        WHERE product_id = ? AND source_name = 'MANUFACTURER'
        """, (resultSet, rowNum) -> resultSet.getString("raw_payload"), productId);

        assertThat(manufacturerPayload).isNull();
    }

    @Test
    void urlOnlySourcesAllowMultipleRowsWithoutAnExternalIdOrRevision() {
        String productId = insertProduct();
        insertUrlOnlySource(productId, "MANUFACTURER", null,
                "https://example.invalid/manufacturer/specifications");
        insertUrlOnlySource(productId, "MANUFACTURER", null,
                "https://example.invalid/manufacturer/support");
        insertUrlOnlySource(productId, "MANUAL", null,
                "https://example.invalid/manual/verification-record");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM catalog_product_source
                WHERE product_id = ? AND source_name = 'MANUFACTURER' AND external_id IS NULL
                """, Integer.class, productId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM catalog_product_source
                WHERE product_id = ? AND external_id IS NULL AND source_revision IS NULL AND raw_payload IS NULL
                """, Integer.class, productId)).isEqualTo(3);
    }

    @Test
    void externalIdentityCannotBeDuplicatedByChangingProductOrRevision() {
        String firstProductId = insertProduct();
        String secondProductId = insertProduct();
        String externalId = "bc-cpu-" + UUID.randomUUID();
        String payload = rawPayload(externalId);
        insertBuildCores(firstProductId, externalId, "fixture-revision-1", payload);

        assertThatThrownBy(() -> insertBuildCores(
                secondProductId, externalId, "fixture-revision-1", payload))
                .as("One source identity cannot be linked to a different product")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertBuildCores(
                firstProductId, externalId, "fixture-revision-2", payload))
                .as("A new source revision does not create a new external identity")
                .isInstanceOf(DataIntegrityViolationException.class);

        // 외부 ID 문자열이 같더라도 다른 출처의 ID라면 별개로 취급한다.
        insertUrlOnlySource(firstProductId, "MANUFACTURER", externalId,
                "https://example.invalid/manufacturer/same-id-in-another-source");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM catalog_product_source WHERE external_id = ?
                """, Integer.class, externalId)).isEqualTo(2);
    }

    @Test
    void buildCoresRequiresExternalIdRevisionAndRawPayload() {
        String productId = insertProduct();
        String externalId = "bc-cpu-" + UUID.randomUUID();
        insertBuildCores(productId, externalId, "fixture-revision-1", rawPayload(externalId));

        // 컬럼명은 이 테스트의 고정 목록만 사용한다.
        for (String column : List.of("external_id", "source_revision", "raw_payload")) {
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE catalog_product_source SET " + column + " = NULL WHERE product_id = ?", productId))
                    .as("BUILDCORES requires %s", column)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void sourceCannotReferToAnUnknownProduct() {
        String missingProductId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> insertUrlOnlySource(missingProductId, "MANUFACTURER", null,
                "https://example.invalid/manufacturer/unlinked-product"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void blankIdentifiersUnsupportedSourcesAndMissingRequiredValuesAreRejected() {
        String productId = insertProduct();
        String externalId = "bc-cpu-" + UUID.randomUUID();
        insertBuildCores(productId, externalId, "fixture-revision-1", rawPayload(externalId));

        for (String column : List.of("source_name", "external_id", "source_revision", "source_url")) {
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE catalog_product_source SET " + column + " = '   ' WHERE product_id = ?", productId))
                    .as("Blank text is not a valid %s", column)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE catalog_product_source SET source_name = 'UNSUPPORTED' WHERE product_id = ?
                """, productId)).isInstanceOf(DataIntegrityViolationException.class);
        for (String column : List.of("source_url", "retrieved_at")) {
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE catalog_product_source SET " + column + " = NULL WHERE product_id = ?", productId))
                    .as("Source records require %s", column)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private String insertProduct() {
        String productId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO catalog_product
                    (id, type, manufacturer, model_name, created_at, updated_at)
                VALUES (?, 'CPU', 'Test Manufacturer', 'Source schema fixture', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, productId);
        return productId;
    }

    private void insertBuildCores(String productId, String externalId, String revision, String payload) {
        // H2에서는 VARCHAR를 JSON 객체로 넣을 때 FORMAT JSON이 필요하다.
        // https://h2database.com/html/datatypes.html#json_type
        jdbc.update("""
                INSERT INTO catalog_product_source
                    (product_id, source_name, external_id, source_revision, source_url, raw_payload, retrieved_at)
                VALUES (?, 'BUILDCORES', ?, ?, ?, ? FORMAT JSON, CURRENT_TIMESTAMP)
                """, productId, externalId, revision,
                "https://example.invalid/buildcores/cpu-fixture.json", payload);
    }

    private void insertUrlOnlySource(String productId, String sourceName, String externalId, String url) {
        jdbc.update("""
                INSERT INTO catalog_product_source
                    (product_id, source_name, external_id, source_url, retrieved_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, productId, sourceName, externalId, url);
    }

    private String rawPayload(String externalId) {
        // externalId는 이 테스트에서 생성한 ASCII 식별자다. 실제 적재에서는 JSON 라이브러리를 사용한다.
        return "{\"opendb_id\":\"" + externalId + "\",\"name\":\"Source fixture CPU\","
                + "\"memory_types\":[\"DDR4\",\"DDR5\"],"
                + "\"specs\":{\"core_count\":8,\"integrated_graphics\":null},"
                + "\"aliases\":[\"cpu-a\",\"cpu-b\"]}";
    }
}
