package com.pcupgradelab.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flyway 상세 제원 테이블의 미확인 값·단위·관계 제약을 H2에서 확인한다.
 * 각 테스트는 롤백한다. 실제 MySQL 검증과 서비스의 제품 종류 검증은 별도다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CatalogSpecificationSchemaTests {
    private static final long GIB = 1024L * 1024 * 1024;
    private static final Map<String, String> SPEC_TYPES = Map.of(
            "cpu_spec", "CPU", "motherboard_spec", "MOTHERBOARD", "ram_spec", "RAM");

    @Autowired JdbcTemplate jdbc;

    @Test
    void unknownSpecificationsRemainNullInsteadOfZeroOrFalse() {
        for (String table : SPEC_TYPES.keySet()) {
            String productId = insertSpecification(table);
            var row = jdbc.queryForMap("SELECT * FROM " + table + " WHERE product_id = ?", productId);

            for (var field : row.entrySet()) {
                if (!field.getKey().equalsIgnoreCase("product_id")) {
                    assertThat(field.getValue())
                            .as("Unknown %s.%s must remain NULL", table, field.getKey())
                            .isNull();
                }
            }
        }
    }

    @Test
    void ramKitStoresCapacityPerModuleAndPreservesValuesBeyondIntegerRange() {
        long moduleCapacity = 24 * GIB;
        String kitId = insertSpecification("ram_spec");
        String singleModuleId = insertSpecification("ram_spec");
        jdbc.update("""
                UPDATE ram_spec SET module_capacity_bytes = ?, module_count = 2,
                    voltage_v = ?, height_mm = ? WHERE product_id = ?
                """, moduleCapacity, new BigDecimal("1.350"), new BigDecimal("32.50"), kitId);
        jdbc.update("""
                UPDATE ram_spec SET module_capacity_bytes = ?, module_count = 1 WHERE product_id = ?
                """, moduleCapacity, singleModuleId);

        Long kitModuleCapacity = jdbc.queryForObject(
                "SELECT module_capacity_bytes FROM ram_spec WHERE product_id = ?", Long.class, kitId);
        Integer kitModuleCount = jdbc.queryForObject(
                "SELECT module_count FROM ram_spec WHERE product_id = ?", Integer.class, kitId);
        assertThat(kitModuleCapacity).isEqualTo(25_769_803_776L);
        assertThat(kitModuleCount).isEqualTo(2);
        assertThat(kitModuleCapacity * kitModuleCount).isEqualTo(48 * GIB);
        assertThat(jdbc.queryForObject(
                "SELECT module_capacity_bytes FROM ram_spec WHERE product_id = ?", Long.class, singleModuleId))
                .isEqualTo(moduleCapacity);
        assertThat(jdbc.queryForObject(
                "SELECT module_count FROM ram_spec WHERE product_id = ?", Integer.class, singleModuleId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT voltage_v FROM ram_spec WHERE product_id = ?", BigDecimal.class, kitId))
                .isEqualTo(new BigDecimal("1.350"));
        assertThat(jdbc.queryForObject(
                "SELECT height_mm FROM ram_spec WHERE product_id = ?", BigDecimal.class, kitId))
                .isEqualTo(new BigDecimal("32.50"));

        String motherboardId = insertSpecification("motherboard_spec");
        jdbc.update("UPDATE motherboard_spec SET max_memory_bytes = ? WHERE product_id = ?",
                256 * GIB, motherboardId);
        assertThat(jdbc.queryForObject(
                "SELECT max_memory_bytes FROM motherboard_spec WHERE product_id = ?", Long.class, motherboardId))
                .isEqualTo(274_877_906_944L);
    }

    @Test
    void knownNumericSpecificationsMustBePositive() {
        var columnsByTable = Map.of(
                "cpu_spec", List.of("core_count", "thread_count", "base_clock_mhz", "boost_clock_mhz", "tdp_w"),
                "motherboard_spec", List.of("memory_slot_count", "max_memory_bytes"),
                "ram_spec", List.of("module_capacity_bytes", "module_count", "data_rate_mts",
                        "pin_count", "voltage_v", "height_mm"));

        // 테이블명과 컬럼명은 이 테스트 안의 고정 목록만 사용한다.
        for (var table : columnsByTable.entrySet()) {
            String productId = insertSpecification(table.getKey());
            for (String column : table.getValue()) {
                for (int invalidValue : List.of(0, -1)) {
                    assertThatThrownBy(() -> jdbc.update(
                            "UPDATE " + table.getKey() + " SET " + column + " = ? WHERE product_id = ?",
                            invalidValue, productId))
                            .as("Reject %s.%s = %s", table.getKey(), column, invalidValue)
                            .isInstanceOf(DataIntegrityViolationException.class);
                }
            }
        }
    }

    @Test
    void unknownEccAndExplicitNonEccRemainDifferentValues() {
        for (var ecc : Map.of("motherboard_spec", "supports_ecc", "ram_spec", "is_ecc").entrySet()) {
            String productId = insertSpecification(ecc.getKey());
            String select = "SELECT " + ecc.getValue() + " FROM " + ecc.getKey() + " WHERE product_id = ?";
            assertThat(jdbc.queryForObject(select, Boolean.class, productId)).isNull();

            jdbc.update("UPDATE " + ecc.getKey() + " SET " + ecc.getValue() + " = ? WHERE product_id = ?",
                    false, productId);
            assertThat(jdbc.queryForObject(select, Boolean.class, productId)).isFalse();

            jdbc.update("UPDATE " + ecc.getKey() + " SET " + ecc.getValue() + " = ? WHERE product_id = ?",
                    true, productId);
            assertThat(jdbc.queryForObject(select, Boolean.class, productId)).isTrue();
        }
    }

    @Test
    void specificationsRequireExistingProductsAndPermitOnlyOneRowPerProduct() {
        for (String table : SPEC_TYPES.keySet()) {
            String missingProductId = UUID.randomUUID().toString();
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO " + table + " (product_id) VALUES (?)", missingProductId))
                    .as("%s must reference an existing product", table)
                    .isInstanceOf(DataIntegrityViolationException.class);

            String productId = insertSpecification(table);
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO " + table + " (product_id) VALUES (?)", productId))
                    .as("%s is one-to-one with its product", table)
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE product_id = ?", Integer.class, productId))
                    .isEqualTo(1);
        }
    }

    @Test
    void rejectsUnsupportedBufferTypeAndContradictoryIntegratedGraphics() {
        String ramId = insertSpecification("ram_spec");
        for (String bufferType : List.of("UNBUFFERED", "REGISTERED", "LOAD_REDUCED")) {
            jdbc.update("UPDATE ram_spec SET buffer_type = ? WHERE product_id = ?", bufferType, ramId);
            assertThat(jdbc.queryForObject(
                    "SELECT buffer_type FROM ram_spec WHERE product_id = ?", String.class, ramId))
                    .isEqualTo(bufferType);
        }
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ram_spec SET buffer_type = 'UNKNOWN' WHERE product_id = ?", ramId))
                .isInstanceOf(DataIntegrityViolationException.class);

        String cpuId = insertSpecification("cpu_spec");
        // 내장 그래픽 여부가 미확인이어도 모델 문자열만 먼저 수집하는 것은 허용한다.
        jdbc.update("UPDATE cpu_spec SET integrated_graphics_model = ? WHERE product_id = ?",
                "Test integrated graphics", cpuId);
        assertThat(jdbc.queryForObject(
                "SELECT has_integrated_graphics FROM cpu_spec WHERE product_id = ?", Boolean.class, cpuId))
                .isNull();
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE cpu_spec SET has_integrated_graphics = FALSE WHERE product_id = ?", cpuId))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                UPDATE cpu_spec SET integrated_graphics_model = NULL, has_integrated_graphics = FALSE
                WHERE product_id = ?
                """, cpuId);
        assertThat(jdbc.queryForObject(
                "SELECT has_integrated_graphics FROM cpu_spec WHERE product_id = ?", Boolean.class, cpuId))
                .isFalse();
    }

    @Test
    void blankSpecificationStringsCannotStandInForUnknownValues() {
        var columnsByTable = Map.of(
                "cpu_spec", List.of("socket_code", "integrated_graphics_model"),
                "motherboard_spec", List.of("socket_code", "chipset", "form_factor", "memory_type", "memory_form_factor"),
                "ram_spec", List.of("memory_type", "module_form_factor", "buffer_type"));

        for (var table : columnsByTable.entrySet()) {
            String productId = insertSpecification(table.getKey());
            for (String column : table.getValue()) {
                assertThatThrownBy(() -> jdbc.update(
                        "UPDATE " + table.getKey() + " SET " + column + " = '   ' WHERE product_id = ?", productId))
                        .as("Unknown %s.%s must use NULL instead of blank text", table.getKey(), column)
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
        }
    }

    private String insertSpecification(String table) {
        String productId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO catalog_product
                    (id, type, manufacturer, model_name, created_at, updated_at)
                VALUES (?, ?, 'Test Manufacturer', 'Specification schema fixture', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, productId, SPEC_TYPES.get(table));
        jdbc.update("INSERT INTO " + table + " (product_id) VALUES (?)", productId);
        return productId;
    }
}
