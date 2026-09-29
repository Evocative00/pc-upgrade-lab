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
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 전용 H2 DB에서 모니터의 실제 커밋·다음 트랜잭션 조회·롤백을 확인한다. 테스트 트랜잭션은 쓰지 않는다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:catalog-monitor-entry-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "catalog.seed.enabled=false"
})
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogMonitorEntryServiceTests {
    private static final List<String> TABLES = List.of("catalog_product_source", "gpu_power_connector",
            "monitor_spec", "gpu_spec", "cpu_spec", "motherboard_spec", "ram_spec",
            "catalog_reference_price", "catalog_product");

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
    void commitsAndReloadsMonitorWithSeparateRefreshRatesAndUnconfirmedPrice() {
        CatalogSpecification.Monitor specification = completeMonitor();
        String id = service.create(request(specification)).product().id();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        CatalogEntryView reloaded = service.findById(id).orElseThrow();
        var stored = (CatalogSpecification.Monitor) reloaded.specification();
        assertThat(stored).isEqualTo(specification);
        assertThat(stored.screenSizeInches()).isEqualTo(new BigDecimal("23.80"));
        assertThat(stored.nativeStandardRefreshHz()).isEqualTo(new BigDecimal("59.940"));
        assertThat(stored.nativeOcRefreshHz()).isEqualTo(new BigDecimal("75.000"));
        assertThat(stored.hasRefreshOverclock()).isTrue();
        assertThat(stored.activePowerW()).isEqualTo(new BigDecimal("30.25"));
        assertThat(stored.activePowerBasis()).isEqualTo("MEASURED_ACTIVE");
        assertThat(stored.activePowerConditions()).isEqualTo("Fixture: SDR, 60 Hz, 150 cd/m2");
        assertThat(reloaded.product().type()).isEqualTo(PartType.MONITOR);
        assertThat(reloaded.product().verificationStatus()).isEqualTo(CatalogVerificationStatus.UNVERIFIED);
        assertThat(reloaded.product().active()).isFalse();
        assertThat(reloaded.product().referencePrice().amountKrw()).isNull();
        assertThat(reloaded.product().referencePrice().status()).isEqualTo(CatalogPriceStatus.UNCONFIRMED);
        assertThat(reloaded.sources()).hasSize(1);
        assertMonitorRowCounts(1);
    }

    @Test
    void unknownUnsupportedAndSupportedWithUnknownMaximumRemainDistinctAfterCommit() {
        for (Boolean support : Arrays.asList(null, false, true)) {
            CatalogSpecification.Monitor specification = unknownMonitor(support);
            String id = service.create(request(specification)).product().id();
            CatalogSpecification.Monitor stored = storedMonitor(id);
            assertThat(stored).isEqualTo(specification);
            assertThat(stored.hasRefreshOverclock()).isEqualTo(support);
            assertThat(stored.nativeOcRefreshHz()).isNull();
            assertThat(stored.nativeStandardRefreshHz()).isNull();
            assertThat(stored.activePowerW()).isNull();
            assertThat(stored.activePowerBasis()).isNull();
            assertThat(stored.activePowerConditions()).isNull();
        }
        assertMonitorRowCounts(3);
    }

    @Test
    void lastSourceFailureRollsBackProductPriceMonitorAndEarlierSource() {
        CatalogEntryCreateRequest good = request(completeMonitor());
        var rejectedSource = new CatalogSourceInput(CatalogSourceName.MANUAL, null, null,
                "https://example.invalid/monitor/fail", null, Instant.parse("2026-09-01T12:00:00Z"));
        var input = new CatalogEntryCreateRequest(good.product(), good.specification(),
                List.of(good.sources().getFirst(), rejectedSource));
        jdbc.execute("""
                ALTER TABLE catalog_product_source ADD CONSTRAINT ck_test_reject_monitor_source
                CHECK (source_url <> 'https://example.invalid/monitor/fail')
                """);
        try {
            Throwable failure = catchThrowable(() -> service.create(input));
            assertThat(failure).isNotNull();
            assertThat(NestedExceptionUtils.getMostSpecificCause(failure).getMessage())
                    .containsIgnoringCase("ck_test_reject_monitor_source");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertAllTablesEmpty();
        } finally {
            jdbc.execute("ALTER TABLE catalog_product_source DROP CONSTRAINT ck_test_reject_monitor_source");
        }
    }

    @Test
    void extraSpecificationsAreRejectedForBothMonitorAndExistingGpuProducts() {
        String monitorId = service.create(request(unknownMonitor(null))).product().id();
        jdbc.update("INSERT INTO cpu_spec (product_id) VALUES (?)", monitorId);
        assertThatThrownBy(() -> service.findById(monitorId)).isInstanceOf(IllegalStateException.class);

        var gpu = new CatalogSpecification.Gpu(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, false, List.of());
        String gpuId = service.create(request(gpu)).product().id();
        jdbc.update("INSERT INTO monitor_spec (product_id) VALUES (?)", gpuId);
        assertThatThrownBy(() -> service.findById(gpuId)).isInstanceOf(IllegalStateException.class);
        assertThat(rowCount("monitor_spec")).isEqualTo(2);
        assertThat(rowCount("gpu_spec")).isEqualTo(1);
        assertThat(rowCount("cpu_spec")).isEqualTo(1);
    }

    @Test
    void aSingleMonitorSpecificationStillRequiresAMonitorProduct() {
        String id = service.create(request(unknownMonitor(null))).product().id();
        jdbc.update("UPDATE catalog_product SET type = 'GPU' WHERE id = ?", id);
        assertThatThrownBy(() -> service.findById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("types do not match");
    }

    @Test
    void databaseRequiresCompletePowerEvidenceAndResolutionEvenWhenJavaIsBypassed() {
        String id = service.create(request(completeMonitor())).product().id();
        for (String column : List.of("native_width_px", "native_height_px", "active_power_w",
                "active_power_basis", "active_power_conditions")) {
            assertRejectedUpdate(id, column + " = NULL");
        }
        for (String assignment : List.of("active_power_basis = 'MAXIMUM'",
                "active_power_basis = 'STANDBY'", "active_power_basis = 'ADAPTER_RATING'",
                "active_power_conditions = ''", "active_power_conditions = ' '", "panel_type = 'MINI_LED'")) {
            assertRejectedUpdate(id, assignment);
        }
        assertThat(storedMonitor(id)).isEqualTo(completeMonitor());

        // 모두 NULL이면 유효한 미확인 상태이며, 값 하나만 비운 상태와 구분한다.
        jdbc.update("""
                UPDATE monitor_spec SET native_width_px = NULL, native_height_px = NULL,
                    active_power_w = NULL, active_power_basis = NULL, active_power_conditions = NULL
                WHERE product_id = ?
                """, id);
        CatalogSpecification.Monitor stored = storedMonitor(id);
        assertThat(stored.nativeWidthPx()).isNull();
        assertThat(stored.nativeHeightPx()).isNull();
        assertThat(stored.activePowerW()).isNull();
    }

    @Test
    void databaseRejectsOverclockWithoutKnownSupportAndInvertedRefreshRates() {
        String id = service.create(request(completeMonitor())).product().id();
        for (String assignment : List.of("has_refresh_overclock = NULL", "has_refresh_overclock = FALSE",
                "native_oc_refresh_hz = 59.940", "native_oc_refresh_hz = 50")) {
            assertRejectedUpdate(id, assignment);
        }
        assertThat(storedMonitor(id)).isEqualTo(completeMonitor());
        jdbc.update("UPDATE monitor_spec SET native_oc_refresh_hz = NULL, has_refresh_overclock = NULL WHERE product_id = ?", id);
        assertThat(storedMonitor(id).hasRefreshOverclock()).isNull();
        assertThat(storedMonitor(id).nativeStandardRefreshHz()).isEqualTo(new BigDecimal("59.940"));
    }

    @Test
    void databaseRejectsOrphanDuplicateAndNonPositiveSpecifications() {
        String id = service.create(request(completeMonitor())).product().id();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO monitor_spec (product_id) VALUES (?)", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO monitor_spec (product_id) VALUES (?)", "missing-monitor"))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (String column : List.of("screen_size_inches", "native_width_px", "native_height_px",
                "native_standard_refresh_hz", "native_oc_refresh_hz", "active_power_w")) {
            assertRejectedUpdate(id, column + " = 0");
        }
        assertThat(storedMonitor(id)).isEqualTo(completeMonitor());
        assertMonitorRowCounts(1);
    }

    private CatalogSpecification.Monitor completeMonitor() {
        // 실제 판매 제품이 아닌 저장 검증용 제원이다. 이 값은 초기 제품 데이터에 사용하지 않는다.
        return new CatalogSpecification.Monitor(new BigDecimal("23.80"), 1920, 1080, "IPS",
                new BigDecimal("59.940"), true, new BigDecimal("75.000"), new BigDecimal("30.25"),
                "MEASURED_ACTIVE", "Fixture: SDR, 60 Hz, 150 cd/m2");
    }

    private CatalogSpecification.Monitor unknownMonitor(Boolean overclockSupport) {
        return new CatalogSpecification.Monitor(null, null, null, null, null, overclockSupport, null, null, null, null);
    }

    private CatalogEntryCreateRequest request(CatalogSpecification specification) {
        var product = new CatalogProductCreateRequest(specification.type(), "Test Manufacturer", "Monitor test fixture", null);
        var source = new CatalogSourceInput(CatalogSourceName.MANUFACTURER, null, null,
                "https://example.invalid/monitor/specifications", null, Instant.parse("2026-09-01T12:00:00Z"));
        return new CatalogEntryCreateRequest(product, specification, List.of(source));
    }

    private CatalogSpecification.Monitor storedMonitor(String id) {
        return (CatalogSpecification.Monitor) service.findById(id).orElseThrow().specification();
    }

    private void assertRejectedUpdate(String id, String assignment) {
        assertThatThrownBy(() -> jdbc.update("UPDATE monitor_spec SET " + assignment + " WHERE product_id = ?", id))
                .as("Reject inconsistent monitor specification: %s", assignment)
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertMonitorRowCounts(int products) {
        for (String table : List.of("catalog_product", "catalog_reference_price", "monitor_spec", "catalog_product_source")) {
            assertThat(rowCount(table)).as("Rows in %s", table).isEqualTo(products);
        }
        for (String table : List.of("cpu_spec", "motherboard_spec", "ram_spec", "gpu_spec", "gpu_power_connector")) {
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
            assertThat(connection.getMetaData().getURL().split(";", 2)[0])
                    .isEqualTo("jdbc:h2:mem:catalog-monitor-entry-test");
        }
    }
}
