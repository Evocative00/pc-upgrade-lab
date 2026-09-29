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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 전용 H2 DB에서 GPU 제원과 전원 커넥터의 실제 커밋·롤백을 확인한다. 테스트 트랜잭션은 사용하지 않는다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:catalog-gpu-entry-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogGpuEntryServiceTests {
    private static final long GIB = 1024L * 1024 * 1024;
    private static final List<String> TABLES = List.of("catalog_product_source", "gpu_power_connector",
            "gpu_spec", "cpu_spec", "motherboard_spec", "ram_spec", "catalog_reference_price", "catalog_product");

    @Autowired CatalogEntryService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void requireEmptyDedicatedDatabase() throws SQLException {
        assertDedicatedDatabase();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertAllTablesEmpty();
    }

    @AfterEach
    void cleanCommittedFixtures() throws SQLException {
        assertDedicatedDatabase();
        for (String table : TABLES) jdbc.update("DELETE FROM " + table);
    }

    @Test
    void commitsMixedConnectorsAndReloadsCanonicalImmutableSpecifications() {
        var sixPin = new CatalogSpecification.GpuPowerConnector("PCIE_6PIN", 1);
        var eightPin = new CatalogSpecification.GpuPowerConnector("PCIE_8PIN", 1);
        var callerConnectors = new ArrayList<>(List.of(eightPin, sixPin));
        CatalogSpecification.Gpu specification = completeGpu(callerConnectors);
        callerConnectors.clear();
        assertThat(specification.powerConnectors()).containsExactly(sixPin, eightPin);
        assertThatThrownBy(specification.powerConnectors()::clear).isInstanceOf(UnsupportedOperationException.class);

        String id = service.create(request(specification)).product().id();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        CatalogEntryView reloaded = service.findById(id).orElseThrow();
        CatalogSpecification.Gpu stored = (CatalogSpecification.Gpu) reloaded.specification();
        assertThat(stored).isEqualTo(specification);
        assertThat(stored.powerConnectorsKnown()).isTrue();
        assertThat(stored.powerConnectors()).containsExactly(sixPin, eightPin);
        assertThatThrownBy(stored.powerConnectors()::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(stored.vramBytes()).isEqualTo(12 * GIB);
        assertThat(stored.pcieConnectorLanes()).isEqualTo(16);
        assertThat(stored.pcieActiveLanes()).isEqualTo(8);
        assertThat(stored.cardPowerW()).isEqualByComparingTo("220.00");
        assertThat(stored.cardPowerBasis()).isEqualTo("POWER_CONSUMPTION");
        assertThat(stored.psuRequirementW()).isEqualTo(650);
        assertThat(stored.psuRequirementBasis()).isEqualTo("RECOMMENDED");
        assertThat(reloaded.product().type()).isEqualTo(PartType.GPU);
        assertThat(reloaded.product().verificationStatus()).isEqualTo(CatalogVerificationStatus.UNVERIFIED);
        assertThat(reloaded.product().active()).isFalse();
        assertThat(reloaded.product().referencePrice().status()).isEqualTo(CatalogPriceStatus.UNCONFIRMED);
        assertThat(reloaded.product().referencePrice().amountKrw()).isNull();
        assertGpuRowCounts(1, 2);
    }

    @Test
    void unspecifiedSixteenPinAndUnknownPowerOrPcieValuesRemainUnchanged() {
        var connector = new CatalogSpecification.GpuPowerConnector("PCIE_16PIN_UNSPECIFIED", 1);
        CatalogSpecification.Gpu specification = unknownGpu(true, List.of(connector));
        String id = service.create(request(specification)).product().id();

        CatalogSpecification.Gpu stored = storedGpu(id);
        assertThat(stored.powerConnectors()).containsExactly(connector);
        assertThat(stored.powerConnectorsKnown()).isTrue();
        assertThat(stored.pcieVersion()).isNull();
        assertThat(stored.pcieConnectorLanes()).isNull();
        assertThat(stored.pcieActiveLanes()).isNull();
        assertThat(stored.cardPowerW()).isNull();
        assertThat(stored.cardPowerBasis()).isNull();
        assertThat(stored.psuRequirementW()).isNull();
        assertThat(stored.psuRequirementBasis()).isNull();
        assertGpuRowCounts(1, 1);
    }

    @Test
    void unknownConnectorsAndConfirmedAbsenceRemainDistinctAfterCommit() {
        String unknownId = service.create(request(unknownGpu(false, List.of()))).product().id();
        String absentId = service.create(request(unknownGpu(true, List.of()))).product().id();

        CatalogSpecification.Gpu unknown = storedGpu(unknownId);
        CatalogSpecification.Gpu absent = storedGpu(absentId);
        assertThat(unknown.powerConnectorsKnown()).isFalse();
        assertThat(unknown.powerConnectors()).isEmpty();
        assertThat(absent.powerConnectorsKnown()).isTrue();
        assertThat(absent.powerConnectors()).isEmpty();
        assertGpuRowCounts(2, 0);
    }

    @Test
    void sourceInsertFailureRollsBackGpuConnectorsAndEveryOtherInsertedRow() {
        CatalogSpecification.Gpu specification = completeGpu(List.of(
                new CatalogSpecification.GpuPowerConnector("PCIE_6PIN", 1),
                new CatalogSpecification.GpuPowerConnector("PCIE_8PIN", 1)));
        // 출처 저장 전까지 실행된 제품·가격·GPU 제원·커넥터 INSERT가 함께 롤백되어야 한다.
        jdbc.execute("""
                ALTER TABLE catalog_product_source
                ADD CONSTRAINT ck_test_reject_gpu_source CHECK (1 = 0)
                """);
        try {
            Throwable failure = catchThrowable(() -> service.create(request(specification)));
            assertThat(failure).isNotNull();
            assertThat(NestedExceptionUtils.getMostSpecificCause(failure).getMessage())
                    .containsIgnoringCase("ck_test_reject_gpu_source");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertAllTablesEmpty();
        } finally {
            jdbc.execute("ALTER TABLE catalog_product_source DROP CONSTRAINT ck_test_reject_gpu_source");
        }
    }

    @Test
    void addingCpuSpecificationsToAGpuProductMakesTheEntryInvalid() {
        String id = service.create(request(unknownGpu(false, List.of()))).product().id();
        // FK만으로는 다른 종류의 상세 행을 막을 수 없어 직접 SQL로 불일치를 재현한다.
        jdbc.update("INSERT INTO cpu_spec (product_id) VALUES (?)", id);

        assertThatThrownBy(() -> service.findById(id)).isInstanceOf(IllegalStateException.class);
        assertThat(rowCount("gpu_spec")).isEqualTo(1);
        assertThat(rowCount("cpu_spec")).isEqualTo(1);
    }

    @Test
    void databaseRejectsAStandalonePowerNumberOrBasisEvenWhenJavaIsBypassed() {
        String id = service.create(request(completeGpu(List.of(
                new CatalogSpecification.GpuPowerConnector("PCIE_8PIN", 1))))).product().id();
        // SQL CHECK는 UNKNOWN도 허용하므로, NULL 한쪽만 들어갈 때 실제로 거절하는지 확인한다.
        for (String column : List.of("card_power_w", "card_power_basis",
                "psu_requirement_w", "psu_requirement_basis")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE gpu_spec SET " + column + " = NULL WHERE product_id = ?", id))
                    .as("Reject a half-known pair after clearing %s", column)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        CatalogSpecification.Gpu remaining = storedGpu(id);
        assertThat(remaining.cardPowerW()).isEqualByComparingTo("220.00");
        assertThat(remaining.psuRequirementW()).isEqualTo(650);
    }

    @Test
    void databaseRejectsDuplicateAndOrphanConnectorRows() {
        String id = service.create(request(completeGpu(List.of(
                new CatalogSpecification.GpuPowerConnector("PCIE_8PIN", 1))))).product().id();
        String insert = "INSERT INTO gpu_power_connector (product_id, connector_type, connector_count) VALUES (?, ?, ?)";
        assertThatThrownBy(() -> jdbc.update(insert, id, "PCIE_8PIN", 2))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "missing-gpu", "PCIE_8PIN", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, id, "PCIE_6PIN", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertGpuRowCounts(1, 1);
    }

    private CatalogSpecification.Gpu completeGpu(List<CatalogSpecification.GpuPowerConnector> connectors) {
        // 실제 판매 제품이 아닌 저장 검증용 제원이다.
        return new CatalogSpecification.Gpu("AMD", "Fixture GPU", 12 * GIB, "GDDR6", "4.0", 16, 8,
                new BigDecimal("249.00"), new BigDecimal("132.00"), new BigDecimal("41.00"),
                new BigDecimal("2.00"), new BigDecimal("220.00"), "POWER_CONSUMPTION",
                650, "RECOMMENDED", true, connectors);
    }

    private CatalogSpecification.Gpu unknownGpu(boolean connectorsKnown,
                                                 List<CatalogSpecification.GpuPowerConnector> connectors) {
        return new CatalogSpecification.Gpu(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, connectorsKnown, connectors);
    }

    private CatalogEntryCreateRequest request(CatalogSpecification.Gpu specification) {
        var product = new CatalogProductCreateRequest(PartType.GPU, "Test Manufacturer", "GPU entry fixture", null);
        var source = new CatalogSourceInput(CatalogSourceName.MANUFACTURER, null, null,
                "https://example.invalid/gpu/specifications", null, Instant.parse("2026-09-01T12:00:00Z"));
        return new CatalogEntryCreateRequest(product, specification, List.of(source));
    }

    private CatalogSpecification.Gpu storedGpu(String id) {
        return (CatalogSpecification.Gpu) service.findById(id).orElseThrow().specification();
    }

    private void assertGpuRowCounts(int products, int connectors) {
        for (String table : List.of("catalog_product", "catalog_reference_price", "gpu_spec", "catalog_product_source")) {
            assertThat(rowCount(table)).as("Rows in %s", table).isEqualTo(products);
        }
        assertThat(rowCount("gpu_power_connector")).isEqualTo(connectors);
        for (String table : List.of("cpu_spec", "motherboard_spec", "ram_spec")) {
            assertThat(rowCount(table)).as("Rows in %s", table).isZero();
        }
    }

    private int rowCount(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertAllTablesEmpty() {
        for (String table : TABLES) assertThat(rowCount(table)).as("Rows in %s", table).isZero();
    }

    private void assertDedicatedDatabase() throws SQLException {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL().split(";", 2)[0]).isEqualTo("jdbc:h2:mem:catalog-gpu-entry-test");
        }
    }
}
