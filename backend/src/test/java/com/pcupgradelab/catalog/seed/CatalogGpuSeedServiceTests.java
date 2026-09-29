package com.pcupgradelab.catalog.seed;

import com.pcupgradelab.catalog.CatalogEntryCreateRequest;
import com.pcupgradelab.catalog.CatalogEntryService;
import com.pcupgradelab.catalog.CatalogEntryView;
import com.pcupgradelab.catalog.CatalogPriceStatus;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.CatalogSpecification.Gpu;
import com.pcupgradelab.catalog.CatalogSpecification.GpuPowerConnector;
import com.pcupgradelab.catalog.CatalogVerificationStatus;
import com.pcupgradelab.pc.PartType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static com.pcupgradelab.catalog.seed.CatalogSeedBatch.GPU;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 전용 DB에서 두 시드 묶음의 실제 커밋과 GPU 묶음 전체의 롤백을 검사한다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:catalog-gpu-seed-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogGpuSeedServiceTests {
    private static final List<String> TABLES = List.of("catalog_product_source", "gpu_power_connector", "gpu_spec",
            "cpu_spec", "motherboard_spec", "ram_spec", "catalog_reference_price", "catalog_product");

    @Autowired CatalogSeedLoader loader;
    @Autowired CatalogSeedService seedService;
    @Autowired CatalogEntryService entries;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;

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
    void addsSixGpusWithoutChangingTheInitialNineProducts() {
        var initial = seedInitialSnapshot();
        List<CatalogEntryCreateRequest> expected = loader.load(GPU);
        assertThat(expected).hasSize(6);
        assertThat(expected.stream().map(request -> ((Gpu) request.specification()).chipset()).toList())
                .containsExactly("GeForce RTX 3060", "GeForce RTX 4060", "GeForce RTX 4070 SUPER",
                        "Radeon RX 6600", "Radeon RX 7800 XT", "Arc B580");
        var result = seedService.seed(GPU);
        assertThat(result.created()).isEqualTo(6);
        assertThat(result.skipped()).isZero();
        assertThat(result.items()).hasSize(6).allSatisfy(item -> assertThat(item.created()).isTrue());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        for (int i = 0; i < result.items().size(); i++) {
            var item = result.items().get(i);
            var request = expected.get(i);
            var stored = entries.findById(item.productId()).orElseThrow();
            assertThat(UUID.fromString(item.productId()).toString()).isEqualTo(item.productId());
            assertThat(stored.product().type()).isEqualTo(PartType.GPU);
            assertThat(stored.product().manufacturer()).isEqualTo(request.product().manufacturer());
            assertThat(stored.product().modelName()).isEqualTo(request.product().modelName());
            assertThat(stored.product().partNumber()).isEqualTo(request.product().partNumber());
            assertThat(stored.product().verificationStatus()).isEqualTo(CatalogVerificationStatus.UNVERIFIED);
            assertThat(stored.product().active()).isFalse();
            assertThat(stored.product().referencePrice().status()).isEqualTo(CatalogPriceStatus.UNCONFIRMED);
            assertThat(stored.product().referencePrice().amountKrw()).isNull();
            assertThat(stored.specification()).isEqualTo(request.specification());
            assertThat(stored.sources()).hasSize(2);
            assertThat(stored.sources().stream().map(source -> source.sourceName()).toList())
                    .containsExactlyInAnyOrder(CatalogSourceName.BUILDCORES, CatalogSourceName.MANUFACTURER);
            for (var source : request.sources()) {
                assertThat(stored.sources()).anySatisfy(actual -> {
                    assertThat(actual.sourceName()).isEqualTo(source.sourceName());
                    assertThat(actual.externalId()).isEqualTo(source.externalId());
                    assertThat(actual.sourceRevision()).isEqualTo(source.sourceRevision());
                    assertThat(actual.sourceUrl()).isEqualTo(source.sourceUrl());
                });
            }
        }
        assertSelectedManufacturerFacts(expected);
        assertInitialPreserved(initial);
        assertTableCounts(6, 12, 6);
        Integer connectorTotal = jdbc.queryForObject("SELECT SUM(connector_count) FROM gpu_power_connector", Integer.class);
        assertThat(connectorTotal).isEqualTo(7);
    }

    @Test
    void repeatedGpuSeedSkipsSixAndKeepsEveryIdAndRowCount() {
        var initial = seedInitialSnapshot();
        var first = seedService.seed(GPU);
        var repeated = seedService.seed(GPU);
        assertThat(repeated.created()).isZero();
        assertThat(repeated.skipped()).isEqualTo(6);
        assertThat(repeated.items()).hasSize(6).allSatisfy(item -> assertThat(item.created()).isFalse());
        assertThat(repeated.items().stream().map(item -> item.productId()).toList())
                .containsExactlyElementsOf(first.items().stream().map(item -> item.productId()).toList());
        assertInitialPreserved(initial);
        assertTableCounts(6, 12, 6);
    }

    @Test
    void repeatedGpuSeedPreservesConfirmedPriceAndReviewedProductState() {
        var initial = seedInitialSnapshot();
        String productId = seedService.seed(GPU).items().getFirst().productId();
        jdbc.update("""
                UPDATE catalog_product SET is_active = TRUE, verification_status = 'CORE_VERIFIED',
                    updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, productId);
        jdbc.update("""
                UPDATE catalog_reference_price SET amount_krw = ?, status = 'CONFIRMED',
                    method = 'MEDIAN_DAILY_6M_V1', period_start = DATE '2026-03-29', period_end = DATE '2026-09-28',
                    observed_day_count = 184, sample_count = 368, price_basis = 'Test fixture: daily KRW observations',
                    evidence_ref = 'test-fixture://catalog-gpu-seed/confirmed-price', calculated_at = CURRENT_TIMESTAMP,
                    confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE product_id = ?
                """, new BigDecimal("98765.43"), productId);
        var priceBefore = jdbc.queryForMap("SELECT * FROM catalog_reference_price WHERE product_id = ?", productId);
        var productBefore = jdbc.queryForMap("SELECT * FROM catalog_product WHERE id = ?", productId);

        var result = seedService.seed(GPU);
        assertThat(result.created()).isZero();
        assertThat(result.skipped()).isEqualTo(6);
        var stored = entries.findById(productId).orElseThrow().product();
        assertThat(stored.active()).isTrue();
        assertThat(stored.verificationStatus()).isEqualTo(CatalogVerificationStatus.CORE_VERIFIED);
        assertThat(stored.referencePrice().status()).isEqualTo(CatalogPriceStatus.CONFIRMED);
        assertThat(stored.referencePrice().amountKrw()).isEqualByComparingTo("98765.43");
        var priceAfter = jdbc.queryForMap("SELECT * FROM catalog_reference_price WHERE product_id = ?", productId);
        var productAfter = jdbc.queryForMap("SELECT * FROM catalog_product WHERE id = ?", productId);
        assertThat(priceAfter).isEqualTo(priceBefore);
        assertThat(productAfter).isEqualTo(productBefore);
        assertInitialPreserved(initial);
        assertTableCounts(6, 12, 6);
    }

    @Test
    void lastGpuSourceFailureRollsBackAllNewGpusAndPreservesTheInitialBatch() {
        var initial = seedInitialSnapshot();
        var last = lastB580Request();
        String externalId = last.sources().stream()
                .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES)
                .findFirst().orElseThrow().externalId();
        // 검증한 UUID만 DDL에 넣고 마지막 GPU 출처 외의 모든 INSERT는 허용한다.
        assertThat(UUID.fromString(externalId).toString()).isEqualTo(externalId);
        jdbc.execute("""
                ALTER TABLE catalog_product_source ADD CONSTRAINT ck_test_reject_last_gpu_source
                CHECK (source_name <> 'BUILDCORES' OR external_id IS NULL OR external_id <> '%s')
                """.formatted(externalId));
        try {
            Throwable failure = catchThrowable(() -> seedService.seed(GPU));
            assertThat(failure).isNotNull();
            assertThat(NestedExceptionUtils.getMostSpecificCause(failure).getMessage())
                    .containsIgnoringCase("ck_test_reject_last_gpu_source");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertInitialPreserved(initial);
            assertTableCounts(0, 0, 0);
        } finally {
            jdbc.execute("ALTER TABLE catalog_product_source DROP CONSTRAINT ck_test_reject_last_gpu_source");
        }
    }

    @Test
    void existingConnectorConflictPreservesTheExistingGpuAndRollsBackFiveNewGpus() {
        var initial = seedInitialSnapshot();
        String existingId = entries.create(lastB580Request()).product().id();
        int changed = jdbc.update("UPDATE gpu_power_connector SET connector_count = 2 WHERE product_id = ?", existingId);
        assertThat(changed).isEqualTo(1);
        var before = entries.findById(existingId).orElseThrow();
        assertThat(((Gpu) before.specification()).powerConnectors()).containsExactly(new GpuPowerConnector("PCIE_8PIN", 2));

        assertThatThrownBy(() -> seedService.seed(GPU)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Catalog seed conflict").hasMessageContaining("Intel Arc B580");
        var after = entries.findById(existingId).orElseThrow();
        assertThat(after).isEqualTo(before);
        List<String> gpuIds = jdbc.queryForList("SELECT product_id FROM gpu_spec", String.class);
        assertThat(gpuIds).containsExactly(existingId);
        assertInitialPreserved(initial);
        assertTableCounts(1, 2, 1);
    }

    @Test
    void runnerIsDisabledByDefaultAndStartupDoesNotInsertEitherBatch() {
        assertThat(context.getBeansOfType(CatalogSeedRunner.class)).isEmpty();
        assertAllTablesEmpty();
    }

    private void assertSelectedManufacturerFacts(List<CatalogEntryCreateRequest> requests) {
        Gpu rtx4060 = (Gpu) requests.get(1).specification();
        Gpu rtx4070 = (Gpu) requests.get(2).specification();
        Gpu rx6600 = (Gpu) requests.get(3).specification();
        Gpu rx7800 = (Gpu) requests.get(4).specification();
        Gpu b580 = (Gpu) requests.get(5).specification();
        for (Gpu unknownPower : List.of(rtx4060, b580)) {
            assertThat(unknownPower.cardPowerW()).isNull();
            assertThat(unknownPower.cardPowerBasis()).isNull();
            assertThat(unknownPower.pcieActiveLanes()).isEqualTo(8);
        }
        for (var request : requests) {
            Gpu specification = (Gpu) request.specification();
            assertThat(specification.pcieConnectorLanes()).isNull();
            assertThat(specification.powerConnectorsKnown()).isTrue();
            if (!request.product().manufacturer().equals("Sapphire")) {
                assertThat(specification.heightMm()).isNull();
                assertThat(specification.thicknessMm()).isNull();
            }
        }
        assertThat(rtx4070.powerConnectors()).containsExactly(new GpuPowerConnector("PCIE_16PIN_UNSPECIFIED", 1));
        assertThat(rx6600.heightMm()).isEqualByComparingTo("120.05");
        assertThat(rx6600.thicknessMm()).isEqualByComparingTo("40.05");
        assertThat(rx7800.cardPowerW()).isEqualByComparingTo("266");
        assertThat(rx7800.cardPowerBasis()).isEqualTo("TOTAL_BOARD_POWER");
        assertThat(rx7800.slotWidth()).isEqualByComparingTo("2.5");
        assertThat(rx7800.heightMm()).isEqualByComparingTo("128.75");
        assertThat(rx7800.thicknessMm()).isEqualByComparingTo("52.57");
        assertThat(rx7800.powerConnectors()).containsExactly(new GpuPowerConnector("PCIE_8PIN", 2));
    }

    private CatalogEntryCreateRequest lastB580Request() {
        var last = loader.load(GPU).getLast();
        assertThat(last.product().type()).isEqualTo(PartType.GPU);
        assertThat(last.product().modelName()).isEqualTo("Intel Arc B580 Challenger 12GB OC");
        return last;
    }

    private List<CatalogEntryView> seedInitialSnapshot() {
        var result = seedService.seed();
        assertThat(result.created()).isEqualTo(9);
        assertThat(result.skipped()).isZero();
        return result.items().stream().map(item -> entries.findById(item.productId()).orElseThrow()).toList();
    }

    private void assertInitialPreserved(List<CatalogEntryView> initial) {
        for (var before : initial) {
            var after = entries.findById(before.product().id()).orElseThrow();
            assertThat(after).isEqualTo(before);
        }
    }

    private void assertTableCounts(int gpuCount, int gpuSourceCount, int connectorRows) {
        assertThat(rowCount("catalog_product")).isEqualTo(9 + gpuCount);
        assertThat(rowCount("catalog_reference_price")).isEqualTo(9 + gpuCount);
        assertThat(rowCount("catalog_product_source")).isEqualTo(21 + gpuSourceCount);
        assertThat(rowCount("cpu_spec")).isEqualTo(4);
        assertThat(rowCount("motherboard_spec")).isEqualTo(2);
        assertThat(rowCount("ram_spec")).isEqualTo(3);
        assertThat(rowCount("gpu_spec")).isEqualTo(gpuCount);
        assertThat(rowCount("gpu_power_connector")).isEqualTo(connectorRows);
    }

    private int rowCount(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertAllTablesEmpty() {
        for (String table : TABLES) assertThat(rowCount(table)).as("Rows in %s", table).isZero();
    }

    private void assertDedicatedDatabase() throws SQLException {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL().split(";", 2)[0]).isEqualTo("jdbc:h2:mem:catalog-gpu-seed-test");
        }
    }
}
