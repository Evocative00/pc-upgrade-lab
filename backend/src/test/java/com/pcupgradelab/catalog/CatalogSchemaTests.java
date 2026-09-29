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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flyway가 만든 실제 테이블의 가격 자료 무결성을 H2에서 확인한다.
 * 각 테스트의 삽입·수정은 롤백하며, 이 검사가 실제 MySQL 검증을 대신하지는 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CatalogSchemaTests {
    @Autowired JdbcTemplate jdbc;

    @Test
    void newProductStartsInactiveAndPriceRemainsUnknownInsteadOfZero() {
        String productId = insertProduct();
        jdbc.update("""
                INSERT INTO catalog_reference_price (product_id, updated_at)
                VALUES (?, CURRENT_TIMESTAMP)
                """, productId);

        var product = jdbc.queryForMap("SELECT * FROM catalog_product WHERE id = ?", productId);
        assertThat(product.get("verification_status")).isEqualTo("UNVERIFIED");
        assertThat(jdbc.queryForObject(
                "SELECT is_active FROM catalog_product WHERE id = ?", Boolean.class, productId)).isFalse();

        var price = jdbc.queryForMap(
                "SELECT * FROM catalog_reference_price WHERE product_id = ?", productId);
        assertThat(price.get("status")).isEqualTo("UNCONFIRMED");
        assertThat(price.get("amount_krw")).isNull();
        assertThat(price.get("confirmed_at")).isNull();
    }

    @Test
    void confirmedPricePreservesDecimalAmountAndItsSupportingEvidence() {
        String productId = insertConfirmedPrice();

        var price = jdbc.queryForMap(
                "SELECT * FROM catalog_reference_price WHERE product_id = ?", productId);
        assertThat(price.get("status")).isEqualTo("CONFIRMED");
        assertThat(price.get("amount_krw")).isEqualTo(new BigDecimal("123456.78"));
        assertThat(price.get("observed_day_count")).isEqualTo(184);
        assertThat(price.get("sample_count")).isEqualTo(368);
        assertThat(price.get("price_basis")).isEqualTo("Test fixture: daily observations, KRW, shipping included");
        assertThat(price.get("evidence_ref")).isEqualTo("test-fixture://catalog-price/six-months");
        assertThat(price.get("calculated_at")).isNotNull();
        assertThat(price.get("confirmed_at")).isNotNull();
    }

    @Test
    void confirmedPriceRejectsMissingZeroOrNegativeAmount() {
        String productId = insertConfirmedPrice();

        for (BigDecimal amount : new BigDecimal[]{null, BigDecimal.ZERO, new BigDecimal("-0.01")}) {
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE catalog_reference_price SET amount_krw = ? WHERE product_id = ?", amount, productId))
                    .as("CONFIRMED amount must be positive: %s", amount)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        assertThat(jdbc.queryForObject(
                "SELECT amount_krw FROM catalog_reference_price WHERE product_id = ?", BigDecimal.class, productId))
                .isEqualTo(new BigDecimal("123456.78"));
    }

    @Test
    void unconfirmedAndInsufficientHistoryCannotRetainAConfirmedAmount() {
        String productId = insertConfirmedPrice();

        for (String status : List.of("UNCONFIRMED", "INSUFFICIENT_HISTORY")) {
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE catalog_reference_price SET status = ?, confirmed_at = NULL
                    WHERE product_id = ?
                    """, status, productId))
                    .as("An unconfirmed amount must remain NULL: %s", status)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void confirmedPriceRejectsMissingSupportingEvidence() {
        String productId = insertConfirmedPrice();

        // 컬럼명은 이 테스트의 고정 목록만 사용한다. 근거 하나가 빠져도 확정값으로 남기지 않는다.
        for (String column : List.of("method", "period_start", "period_end", "observed_day_count",
                "sample_count", "price_basis", "evidence_ref", "calculated_at", "confirmed_at")) {
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE catalog_reference_price SET " + column + " = NULL WHERE product_id = ?", productId))
                    .as("CONFIRMED requires supporting field %s", column)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        for (String column : List.of("method", "price_basis", "evidence_ref")) {
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE catalog_reference_price SET " + column + " = '   ' WHERE product_id = ?", productId))
                    .as("Blank text is not supporting evidence: %s", column)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void observationCountsMustFitTheDateRangeAndAvailableSamples() {
        String productId = insertConfirmedPrice();

        for (String invalidChange : List.of(
                "observed_day_count = 185", // 양 끝 날짜를 포함해도 184일인 기간에 185일을 관측할 수 없다.
                "sample_count = 183",       // 184일의 관측에는 최소 184개 표본이 필요하다.
                "period_end = DATE '2026-03-28'")) {
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE catalog_reference_price SET " + invalidChange + " WHERE product_id = ?", productId))
                    .as("Reject inconsistent observation evidence: %s", invalidChange)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void referencePriceCannotExistWithoutItsCatalogProduct() {
        String missingProductId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO catalog_reference_price (product_id, updated_at)
                VALUES (?, CURRENT_TIMESTAMP)
                """, missingProductId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private String insertProduct() {
        String productId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO catalog_product
                    (id, type, manufacturer, model_name, created_at, updated_at)
                VALUES (?, 'CPU', 'Test Manufacturer', 'Catalog schema fixture', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, productId);
        return productId;
    }

    private String insertConfirmedPrice() {
        String productId = insertProduct();
        jdbc.update("""
                INSERT INTO catalog_reference_price
                    (product_id, amount_krw, status, method, period_start, period_end,
                     observed_day_count, sample_count, price_basis, evidence_ref,
                     calculated_at, confirmed_at, updated_at)
                VALUES (?, ?, 'CONFIRMED', 'MEDIAN_DAILY_6M_V1', DATE '2026-03-29', DATE '2026-09-28',
                        184, 368, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, productId, new BigDecimal("123456.78"),
                "Test fixture: daily observations, KRW, shipping included",
                "test-fixture://catalog-price/six-months");
        return productId;
    }
}
