package com.pcupgradelab.catalog;

import com.pcupgradelab.pc.PartType;
import com.pcupgradelab.catalog.powercase.PowerCaseCatalogPreviewLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 전용 H2에서 파워·케이스의 실제 커밋·출처 실패 롤백을 확인한다. 실제 MySQL을 사용하지 않는다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:catalog-psu-case-entry-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogPsuCaseEntryServiceTests {
    private static final List<String> TABLES = List.of("catalog_product_source", "psu_power_connector",
            "case_motherboard_form_factor", "case_psu_form_factor", "psu_spec", "case_spec",
            "catalog_reference_price", "catalog_product");
    @Autowired CatalogEntryService service;
    @Autowired CatalogQueryService query;
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
    void commitsAndReloadsPsuPlugAndCableCountsWithoutInventingLimitsOrChangingProductState() {
        var spec = psu(true, List.of(new CatalogSpecification.PsuPowerConnector("PCIE_6_2PIN", 4, 2, null),
                new CatalogSpecification.PsuPowerConnector("PCIE_12V_2X6", 1, 1, 600)));
        String id = service.create(request(spec)).product().id();
        var detail = query.findById(id);
        assertThat(detail.specification()).isEqualTo(spec);
        var stored = (CatalogSpecification.Psu) detail.specification();
        assertThat(stored.powerConnectors()).hasSize(2);
        assertThat(stored.powerConnectors().stream().filter(p -> p.connectorType().equals("PCIE_6_2PIN")).findFirst().orElseThrow())
                .isEqualTo(new CatalogSpecification.PsuPowerConnector("PCIE_6_2PIN", 4, 2, null));
        assertThat(detail.product().type()).isEqualTo(PartType.PSU);
        assertThat(detail.product().verificationStatus()).isEqualTo(CatalogVerificationStatus.UNVERIFIED);
        assertThat(detail.product().active()).isFalse();
        assertThat(detail.product().referencePrice().amountKrw()).isNull();
        assertThat(count("psu_power_connector")).isEqualTo(2);
        assertThat(count("case_spec")).isZero();
    }

    @Test
    void caseSupportListsAndConditionalClearanceSurviveStorageAndAreImmutable() {
        var spec = enclosure(true, List.of("ATX", "MICRO_ATX", "MINI_ITX"), true, List.of("ATX"));
        String id = service.create(request(spec)).product().id();
        var stored = (CatalogSpecification.Case) query.findById(id).specification();
        assertThat(stored).isEqualTo(spec);
        assertThat(stored.gpuClearanceUnconditional()).isFalse();
        assertThat(stored.psuClearanceUnconditional()).isFalse();
        assertThat(stored.maxGpuHeightMm()).isNull();
        assertThat(stored.maxGpuThicknessMm()).isNull();
        assertThat(stored.gpuClearanceConditions()).isEqualTo("전면 라디에이터 없을 때 340 mm");
        assertThatThrownBy(stored.motherboardFormFactors()::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(count("case_motherboard_form_factor")).isEqualTo(3);
        assertThat(count("case_psu_form_factor")).isEqualTo(1);
    }

    @Test
    void knownEmptyAndUnknownEmptyInventoriesRemainDifferentAfterCommit() {
        String unknownPsu = service.create(request(psu(false, List.of()))).product().id();
        String absentPsu = service.create(request(psu(true, List.of()))).product().id();
        String unknownCase = service.create(request(enclosure(false, List.of(), false, List.of()))).product().id();
        String absentCase = service.create(request(enclosure(true, List.of(), true, List.of()))).product().id();
        assertThat(((CatalogSpecification.Psu) query.findById(unknownPsu).specification()).powerConnectorsKnown()).isFalse();
        assertThat(((CatalogSpecification.Psu) query.findById(absentPsu).specification()).powerConnectorsKnown()).isTrue();
        assertThat(((CatalogSpecification.Case) query.findById(unknownCase).specification()).motherboardFormFactorsKnown()).isFalse();
        assertThat(((CatalogSpecification.Case) query.findById(absentCase).specification()).motherboardFormFactorsKnown()).isTrue();
        assertThat(count("psu_power_connector")).isZero();
        assertThat(count("case_motherboard_form_factor")).isZero();
    }

    @Test
    void aSourceInsertFailureRollsBackBothKindsAndTheirCollections() {
        jdbc.execute("ALTER TABLE catalog_product_source ADD CONSTRAINT ck_test_reject_psu_case_source CHECK (1 = 0)");
        try {
            for (var spec : List.of(psu(true, List.of(new CatalogSpecification.PsuPowerConnector("PCIE_6_2PIN", 4, 2, null))),
                    enclosure(true, List.of("ATX"), true, List.of("ATX")))) {
                Throwable failure = catchThrowable(() -> service.create(request(spec)));
                assertThat(failure).isNotNull();
                assertThat(NestedExceptionUtils.getMostSpecificCause(failure).getMessage()).containsIgnoringCase("ck_test_reject_psu_case_source");
                assertAllTablesEmpty();
            }
        } finally {
            jdbc.execute("ALTER TABLE catalog_product_source DROP CONSTRAINT ck_test_reject_psu_case_source");
        }
    }

    @Test
    void databaseRejectsDuplicateOrphanInvalidConnectorRowsAndContradictoryCableCount() {
        String id = service.create(request(psu(true, List.of(new CatalogSpecification.PsuPowerConnector("PCIE_6_2PIN", 4, 2, null)))))
                .product().id();
        String insert = "INSERT INTO psu_power_connector(product_id, connector_type, connector_count) VALUES (?, ?, ?)";
        assertThatThrownBy(() -> jdbc.update(insert, id, "PCIE_6_2PIN", 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "missing", "PCIE_8PIN", 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, id, "EPS_8PIN", 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, id, "PCIE_8PIN", 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE psu_power_connector SET independent_cable_count = 5 WHERE product_id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("psu_power_connector")).isEqualTo(1);
    }

    @Test
    void databaseRejectsCaseOrphansDuplicateFormsAndUnconditionalClearanceWithoutDimensions() {
        String id = service.create(request(enclosure(true, List.of("ATX"), true, List.of("ATX")))).product().id();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO case_motherboard_form_factor(product_id,form_factor) VALUES (?, 'ATX')", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO case_psu_form_factor(product_id,form_factor) VALUES ('missing','ATX')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE case_spec SET max_gpu_length_mm = NULL, gpu_clearance_unconditional = TRUE WHERE product_id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE case_spec SET max_psu_length_mm = NULL, psu_clearance_unconditional = TRUE WHERE product_id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE case_spec SET max_gpu_length_mm = 0 WHERE product_id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anotherKindOfSpecificationCannotBeAttachedSilently() {
        String id = service.create(request(psu(false, List.of()))).product().id();
        jdbc.update("INSERT INTO case_spec(product_id) VALUES (?)", id);
        assertThatThrownBy(() -> service.findById(id)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CatalogEntryCreateRequest(new CatalogProductCreateRequest(PartType.CASE, "Fixture", "Wrong kind", null),
                psu(false, List.of()), request(psu(false, List.of())).sources())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actualSevenReviewedProductsCanBeStoredAndQueriedOnlyInDedicatedH2WithoutAnyPricesOrIdentityBindings() {
        var plan = new PowerCaseCatalogPreviewLoader().load(Path.of("..").toAbsolutePath().normalize());
        for (var registration : plan.registrations()) {
            String id = service.create(registration.entry()).product().id();
            var reloaded = query.findById(id);
            assertThat(reloaded.specification()).isEqualTo(registration.entry().specification());
            assertThat(reloaded.product().active()).isFalse();
            assertThat(reloaded.product().verificationStatus()).isEqualTo(CatalogVerificationStatus.UNVERIFIED);
            assertThat(reloaded.product().referencePrice().amountKrw()).isNull();
        }
        for (var table : plan.report().proposedRegistrationRows().entrySet()) {
            assertThat(count(table.getKey())).as("Actual H2 rows in %s", table.getKey()).isEqualTo(table.getValue());
        }
        assertThat(count("catalog_price_mapping")).isZero();
        assertThat(count("catalog_price_observation")).isZero();
    }

    private CatalogSpecification.Psu psu(boolean known, List<CatalogSpecification.PsuPowerConnector> plugs) {
        return new CatalogSpecification.Psu(750, "ATX", new BigDecimal("140"), new BigDecimal("150"), new BigDecimal("86"), "3.1", known, plugs);
    }

    private CatalogSpecification.Case enclosure(boolean boardsKnown, List<String> boards, boolean psusKnown, List<String> psus) {
        return new CatalogSpecification.Case(boardsKnown, boards, psusKnown, psus, new BigDecimal("180"),
                new BigDecimal("340"), null, null, 7, "전면 라디에이터 없을 때 340 mm", "드라이브 케이지 위치 확인", false, false);
    }

    private CatalogEntryCreateRequest request(CatalogSpecification spec) {
        return new CatalogEntryCreateRequest(new CatalogProductCreateRequest(spec.type(), "Fixture", "PSU/CASE fixture", null), spec,
                List.of(new CatalogSourceInput(CatalogSourceName.MANUFACTURER, null, null,
                        "https://example.invalid/psu-case/specification", null, Instant.parse("2026-10-11T00:00:00Z"))));
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private void assertAllTablesEmpty() { for (String table : TABLES) assertThat(count(table)).as(table).isZero(); }
    private void assertDedicatedDatabase() throws SQLException {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL().split(";", 2)[0]).isEqualTo("jdbc:h2:mem:catalog-psu-case-entry-test");
        }
    }
}
