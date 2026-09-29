package com.pcupgradelab.catalog;

import com.pcupgradelab.pc.PartType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 다른 스키마 테스트의 pc-test와 분리된 H2 DB·Spring 컨텍스트를 사용한다.
 * 테스트에 @Transactional을 붙이지 않아 서비스의 실제 커밋·롤백 후 새 조회로 검사한다.
 * 각 테스트 뒤에는 이 전용 DB에 만든 가격과 제품을 삭제한다. 실제 MySQL 검증은 별도다.
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:catalog-service-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogProductServiceTests {
    @Autowired CatalogProductService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void requireAnEmptyDedicatedDatabaseWithoutATestTransaction() throws SQLException {
        assertDedicatedDatabase();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(rowCount("catalog_product")).isZero();
        assertThat(rowCount("catalog_reference_price")).isZero();
    }

    @AfterEach
    void removeCommittedFixturesFromTheDedicatedDatabase() throws SQLException {
        // 설정 실수로 공용 테스트 DB에 연결되면 삭제하지 않고 실패시킨다.
        assertDedicatedDatabase();
        jdbc.update("DELETE FROM catalog_reference_price");
        jdbc.update("DELETE FROM catalog_product");
    }

    @Test
    void createsAndCommitsNormalizedProductWithAnUnconfirmedPrice() {
        CatalogProductView created = service.create(new CatalogProductCreateRequest(
                PartType.CPU, "  Test Manufacturer  ", "  Test CPU Model  ", "  TEST-SKU-001  "));

        assertThat(UUID.fromString(created.id()).toString()).isEqualTo(created.id());
        assertThat(created.verificationStatus()).isEqualTo(CatalogVerificationStatus.UNVERIFIED);
        assertThat(created.active()).isFalse();
        assertThat(created.referencePrice().status()).isEqualTo(CatalogPriceStatus.UNCONFIRMED);
        assertThat(created.referencePrice().amountKrw()).isNull();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        // create의 트랜잭션이 끝난 뒤 DB와 서비스에서 다시 조회한다.
        assertThat(rowCount("catalog_product")).isEqualTo(1);
        assertThat(rowCount("catalog_reference_price")).isEqualTo(1);
        CatalogProductView reloaded = service.findById(created.id()).orElseThrow();
        assertThat(reloaded.id()).isEqualTo(created.id());
        assertThat(reloaded.type()).isEqualTo(PartType.CPU);
        assertThat(reloaded.manufacturer()).isEqualTo("Test Manufacturer");
        assertThat(reloaded.modelName()).isEqualTo("Test CPU Model");
        assertThat(reloaded.partNumber()).isEqualTo("TEST-SKU-001");
        assertThat(reloaded.verificationStatus()).isEqualTo(CatalogVerificationStatus.UNVERIFIED);
        assertThat(reloaded.active()).isFalse();
        assertThat(reloaded.referencePrice().status()).isEqualTo(CatalogPriceStatus.UNCONFIRMED);
        assertThat(reloaded.referencePrice().amountKrw()).isNull();
        assertThat(reloaded.createdAt()).isNotNull();
        assertThat(reloaded.updatedAt()).isNotNull();
        assertThat(reloaded.referencePrice().updatedAt()).isNotNull();
    }

    @Test
    void invalidRequiredFieldsAndExcessiveLengthsLeaveNoRows() {
        List<Runnable> invalidCalls = List.of(
                () -> service.create(null),
                () -> service.create(new CatalogProductCreateRequest(null, "Maker", "Model", null)),
                () -> service.create(new CatalogProductCreateRequest(PartType.CPU, null, "Model", null)),
                () -> service.create(new CatalogProductCreateRequest(PartType.CPU, " \t ", "Model", null)),
                () -> service.create(new CatalogProductCreateRequest(PartType.CPU, "Maker", null, null)),
                () -> service.create(new CatalogProductCreateRequest(PartType.CPU, "Maker", " \t ", null)),
                () -> service.create(new CatalogProductCreateRequest(PartType.CPU, "M".repeat(101), "Model", null)),
                () -> service.create(new CatalogProductCreateRequest(PartType.CPU, "Maker", "M".repeat(256), null)),
                () -> service.create(new CatalogProductCreateRequest(PartType.CPU, "Maker", "Model", "P".repeat(129))));

        for (Runnable invalidCall : invalidCalls) {
            assertThatThrownBy(invalidCall::run).isInstanceOf(IllegalArgumentException.class);
            assertThat(rowCount("catalog_product")).isZero();
            assertThat(rowCount("catalog_reference_price")).isZero();
        }
    }

    @Test
    void unknownProductReturnsAnEmptyResult() {
        assertThat(service.findById(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void productWithoutItsPriceIsReportedAsAnIntegrityFailure() {
        CatalogProductView created = service.create(validRequest());
        // 저장 계층 밖에서 가격 행만 제거해, 데이터 불일치를 의도적으로 만든다.
        assertThat(jdbc.update(
                "DELETE FROM catalog_reference_price WHERE product_id = ?", created.id())).isEqualTo(1);

        assertThatThrownBy(() -> service.findById(created.id())).isInstanceOf(IllegalStateException.class);
        assertThat(rowCount("catalog_product")).isEqualTo(1);
        assertThat(rowCount("catalog_reference_price")).isZero();
    }

    @Test
    void failedPriceInsertRollsBackTheAlreadyFlushedProduct() {
        assertThat(rowCount("catalog_product")).isZero();
        assertThat(rowCount("catalog_reference_price")).isZero();
        // 전용 H2 DB의 빈 가격 표에만 적용한다. 제품 INSERT 이후 가격 INSERT를 실제 DB에서 거절시킨다.
        jdbc.execute("""
                ALTER TABLE catalog_reference_price
                ADD CONSTRAINT ck_test_reject_catalog_price CHECK (1 = 0)
                """);
        try {
            Throwable failure = catchThrowable(() -> service.create(validRequest()));
            assertThat(failure).isNotNull();
            assertThat(NestedExceptionUtils.getMostSpecificCause(failure).getMessage())
                    .containsIgnoringCase("ck_test_reject_catalog_price");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(rowCount("catalog_product")).isZero();
            assertThat(rowCount("catalog_reference_price")).isZero();
        } finally {
            jdbc.execute("ALTER TABLE catalog_reference_price DROP CONSTRAINT ck_test_reject_catalog_price");
        }
    }

    private CatalogProductCreateRequest validRequest() {
        return new CatalogProductCreateRequest(PartType.CPU, "Test Manufacturer", "Transaction fixture CPU", null);
    }

    private int rowCount(String table) {
        // 호출 위치에서 지정한 두 테스트 대상 테이블명만 사용한다.
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertDedicatedDatabase() throws SQLException {
        try (var connection = jdbc.getDataSource().getConnection()) {
            String databaseUrl = connection.getMetaData().getURL().split(";", 2)[0];
            assertThat(databaseUrl).isEqualTo("jdbc:h2:mem:catalog-service-test");
        }
    }
}
