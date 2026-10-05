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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.pcupgradelab.catalog.seed.CatalogSeedBatch.GPU_EXPANSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 테스트 자체의 트랜잭션 없이 기존 42종 보존과 GPU 8종의 실제 커밋·롤백을 검사한다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:catalog-gpu-expand-seed-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogGpuExpansionSeedServiceTests {
    private static final List<String> TABLES = List.of("catalog_product_source", "gpu_power_connector",
            "monitor_spec", "gpu_spec", "cpu_spec", "motherboard_spec", "ram_spec",
            "catalog_reference_price", "catalog_product");

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
    void addsEightExactCardsAndTheirCorrectedSpecificationsPreservingTheExistingFortyTwo() {
        assertThat(CatalogSeedBatch.fromName("week2-gpu-expand")).isEqualTo(GPU_EXPANSION);
        var existing = seedExistingSnapshot();
        var expected = loader.load(GPU_EXPANSION);
        assertThat(expected.stream().map(request -> request.product().modelName()).toList()).containsExactly(
                "GeForce GTX 1660 SUPER VENTUS XS OC", "GeForce RTX 2060 VENTUS 6G",
                "GeForce RTX 3070 VENTUS 2X 8G OC LHR", "GeForce RTX 5070 12G VENTUS 2X OC",
                "PULSE Radeon RX 7600 8GB", "PULSE Radeon RX 9060 XT OC 16GB",
                "PULSE Radeon RX 9070 16GB", "Intel Arc A750 Challenger D 8GB OC");
        var result = seedService.seed(GPU_EXPANSION);
        assertThat(result.created()).isEqualTo(8);
        assertThat(result.skipped()).isZero();
        assertThat(result.items()).hasSize(8).allSatisfy(item -> assertThat(item.created()).isTrue());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        for (int i = 0; i < expected.size(); i++) {
            var request = expected.get(i);
            var stored = entries.findById(result.items().get(i).productId()).orElseThrow();
            assertThat(UUID.fromString(stored.product().id()).toString()).isEqualTo(stored.product().id());
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
            for (var source : request.sources()) {
                assertThat(stored.sources()).anySatisfy(actual -> {
                    assertThat(actual.sourceName()).isEqualTo(source.sourceName());
                    assertThat(actual.externalId()).isEqualTo(source.externalId());
                    assertThat(actual.sourceRevision()).isEqualTo(source.sourceRevision());
                    assertThat(actual.sourceUrl()).isEqualTo(source.sourceUrl());
                    assertThat(actual.retrievedAt()).isEqualTo(source.retrievedAt());
                });
            }
        }
        assertManufacturerFactsAndCorrections(expected);
        assertExistingPreserved(existing);
        assertTableCounts(8);
        Integer connectorTotal = jdbc.queryForObject("SELECT SUM(connector_count) FROM gpu_power_connector", Integer.class);
        assertThat(connectorTotal).isEqualTo(18); // 기존 7개 + 추가 카드의 11개 보조전원 단자
    }

    @Test
    void repeatedSeedPreservesIdsEveryRowReviewedStatesAndConfirmedPrices() {
        var existing = seedExistingSnapshot();
        var first = seedService.seed(GPU_EXPANSION);
        for (String id : List.of(first.items().getFirst().productId(), first.items().getLast().productId())) {
            markReviewedWithConfirmedPrice(id);
        }
        var before = TABLES.stream().map(this::snapshotRows).toList();
        var repeated = seedService.seed(GPU_EXPANSION);
        assertThat(repeated.created()).isZero();
        assertThat(repeated.skipped()).isEqualTo(8);
        assertThat(repeated.items()).hasSize(8).allSatisfy(item -> assertThat(item.created()).isFalse());
        assertThat(repeated.items().stream().map(item -> item.productId()).toList())
                .containsExactlyElementsOf(first.items().stream().map(item -> item.productId()).toList());
        for (int i = 0; i < TABLES.size(); i++) {
            assertThat(snapshotRows(TABLES.get(i)))
                    .as("Unchanged rows in %s", TABLES.get(i)).isEqualTo(before.get(i));
        }
        for (String id : List.of(first.items().getFirst().productId(), first.items().getLast().productId())) {
            assertReviewedWithConfirmedPrice(id);
        }
        assertExistingPreserved(existing);
        assertTableCounts(8);
    }

    @Test
    void lastCardSourceFailureRollsBackAllEightAdditionsAndKeepsTheExistingBatches() {
        var existing = seedExistingSnapshot();
        String externalId = lastCardRequest().sources().stream()
                .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES)
                .findFirst().orElseThrow().externalId();
        assertThat(UUID.fromString(externalId).toString()).isEqualTo(externalId);
        jdbc.execute("""
                ALTER TABLE catalog_product_source ADD CONSTRAINT ck_test_reject_last_gpu_expand_source
                CHECK (source_name <> 'BUILDCORES' OR external_id IS NULL OR external_id <> '%s')
                """.formatted(externalId));
        try {
            Throwable failure = catchThrowable(() -> seedService.seed(GPU_EXPANSION));
            assertThat(failure).isNotNull();
            assertThat(NestedExceptionUtils.getMostSpecificCause(failure).getMessage())
                    .containsIgnoringCase("ck_test_reject_last_gpu_expand_source");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertExistingPreserved(existing);
            assertTableCounts(0);
        } finally {
            jdbc.execute("ALTER TABLE catalog_product_source DROP CONSTRAINT ck_test_reject_last_gpu_expand_source");
        }
    }

    @Test
    void conflictingExistingCardIsPreservedAndEarlierSevenNewCardsAreRolledBack() {
        var existing = seedExistingSnapshot();
        String id = entries.create(lastCardRequest()).product().id();
        // 전용 테스트 DB에만 실제 2개와 다른 보조전원 개수를 만들어 충돌시킨다.
        assertThat(jdbc.update("UPDATE gpu_power_connector SET connector_count = 1 WHERE product_id = ?", id))
                .isEqualTo(1);
        var before = entries.findById(id).orElseThrow();
        assertThat(((Gpu) before.specification()).powerConnectors())
                .containsExactly(new GpuPowerConnector("PCIE_8PIN", 1));
        assertThatThrownBy(() -> seedService.seed(GPU_EXPANSION)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Catalog seed conflict").hasMessageContaining("Intel Arc A750 Challenger D");
        assertThat(entries.findById(id).orElseThrow()).isEqualTo(before);
        assertExistingPreserved(existing);
        assertTableCounts(1);
    }

    @Test
    void startupDoesNotInsertAnyBatchAndOnlyTheExactApprovedNameIsAccepted() {
        assertThat(context.getBeansOfType(CatalogSeedRunner.class)).isEmpty();
        assertAllTablesEmpty();
        assertThat(loader.load()).hasSize(9);
        for (String invalid : new String[]{null, "", "week2-gpu-Expand", " week2-gpu-expand",
                "../week2-gpu-expand", "week2-gpu-expand/", "week2-all"}) {
            assertThatThrownBy(() -> CatalogSeedBatch.fromName(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> seedService.seed(null)).isInstanceOf(IllegalArgumentException.class);
        assertAllTablesEmpty();
    }

    private void assertManufacturerFactsAndCorrections(List<CatalogEntryCreateRequest> requests) {
        assertThat(requests.stream().map(request -> request.specification()).toList()).containsExactly(
                new Gpu("NVIDIA", "GeForce GTX 1660 SUPER", 6442450944L, "GDDR6", "3.0", null, 16,
                        decimal("204"), null, null, null, decimal("125"), "POWER_CONSUMPTION", 450,
                        "RECOMMENDED", true, List.of(new GpuPowerConnector("PCIE_8PIN", 1))),
                new Gpu("NVIDIA", "GeForce RTX 2060", 6442450944L, "GDDR6", "3.0", null, 16,
                        decimal("226"), null, null, null, decimal("160"), "POWER_CONSUMPTION", 500,
                        "RECOMMENDED", true, List.of(new GpuPowerConnector("PCIE_8PIN", 1))),
                new Gpu("NVIDIA", "GeForce RTX 3070", 8589934592L, "GDDR6", "4.0", null, null,
                        decimal("232"), null, null, null, decimal("220"), "POWER_CONSUMPTION", 650,
                        "RECOMMENDED", true, List.of(new GpuPowerConnector("PCIE_8PIN", 2))),
                new Gpu("NVIDIA", "GeForce RTX 5070", 12884901888L, "GDDR7", "5.0", null, null,
                        decimal("236"), null, null, null, decimal("250"), "POWER_CONSUMPTION", 650,
                        "RECOMMENDED", true, List.of(new GpuPowerConnector("PCIE_16PIN_UNSPECIFIED", 1))),
                new Gpu("AMD", "Radeon RX 7600", 8589934592L, "GDDR6", "4.0", null, 8,
                        decimal("240"), decimal("107.1"), decimal("44.07"), decimal("2"), decimal("185"),
                        "TOTAL_BOARD_POWER", 550, "MINIMUM", true, List.of(new GpuPowerConnector("PCIE_8PIN", 1))),
                new Gpu("AMD", "Radeon RX 9060 XT", 17179869184L, "GDDR6", "5.0", null, 16,
                        decimal("240"), decimal("111.25"), decimal("46.08"), decimal("2.3"), decimal("170"),
                        "TYPICAL_BOARD_POWER", 450, "MINIMUM", true, List.of(new GpuPowerConnector("PCIE_8PIN", 1))),
                new Gpu("AMD", "Radeon RX 9070", 17179869184L, "GDDR6", "5.0", null, 16,
                        decimal("280"), decimal("120.25"), decimal("51.5"), decimal("2.5"), decimal("220"),
                        "TYPICAL_BOARD_POWER", 650, "MINIMUM", true, List.of(new GpuPowerConnector("PCIE_8PIN", 2))),
                new Gpu("INTEL", "Arc A750", 8589934592L, "GDDR6", "4.0", null, 16,
                        decimal("271"), null, null, decimal("2.4"), null, null, 650,
                        "RECOMMENDED", true, List.of(new GpuPowerConnector("PCIE_8PIN", 2))));
        assertThat(requests.stream().map(request -> request.product().partNumber()).toList()).containsExactly(
                null, null, null, "G5070-12V2C", "11324-01-20G", "11350-03-20G", "11349-03-20G", "A750 CLD 8GO");
        for (var request : requests) {
            var manufacturer = request.sources().stream()
                    .filter(source -> source.sourceName() == CatalogSourceName.MANUFACTURER).findFirst().orElseThrow();
            var normalized = (Map<?, ?>) manufacturer.rawPayload().get("normalized_specification");
            assertThat(normalized.containsKey("pcieConnectorLanes")).isTrue();
            assertThat(normalized.get("pcieConnectorLanes")).isNull();
        }
        var rx7600Raw = requests.get(4).sources().stream()
                .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES).findFirst().orElseThrow();
        assertThat(((Number) ((Map<?, ?>) rx7600Raw.rawPayload().get("power_connectors")).get("pcie_8_pin")).intValue())
                .isZero();
        assertThat(((Number) rx7600Raw.rawPayload().get("total_slot_width")).intValue()).isEqualTo(3);
        var rtx5070Raw = requests.get(3).sources().stream()
                .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES).findFirst().orElseThrow();
        assertThat(((Number) ((Map<?, ?>) rtx5070Raw.rawPayload().get("power_connectors")).get("pcie_12V_2x6")).intValue())
                .isEqualTo(1); // 원본은 유지하지만 제조사에서 종류를 확인한 것처럼 정규화하지 않는다.
        var a750Raw = requests.getLast().sources().stream()
                .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES).findFirst().orElseThrow();
        assertThat(((Number) a750Raw.rawPayload().get("tdp")).intValue()).isEqualTo(225);
        assertThat(((Number) a750Raw.rawPayload().get("total_slot_width")).intValue()).isEqualTo(3);
    }

    private CatalogEntryCreateRequest lastCardRequest() {
        var last = loader.load(GPU_EXPANSION).getLast();
        assertThat(last.product().type()).isEqualTo(PartType.GPU);
        assertThat(last.product().modelName()).isEqualTo("Intel Arc A750 Challenger D 8GB OC");
        return last;
    }

    private List<CatalogEntryView> seedExistingSnapshot() {
        var existing = Stream.of(CatalogSeedBatch.INITIAL, CatalogSeedBatch.GPU, CatalogSeedBatch.MONITOR,
                        CatalogSeedBatch.INTEL, CatalogSeedBatch.RAM, CatalogSeedBatch.AMD)
                .flatMap(batch -> seedService.seed(batch).items().stream())
                .map(item -> entries.findById(item.productId()).orElseThrow()).toList();
        // 기존 CPU, GPU, 보드의 검증·가격 상태도 추가·롤백·재실행에서 보존해야 한다.
        for (int index : new int[]{0, 9, 41}) markReviewedWithConfirmedPrice(existing.get(index).product().id());
        return existing.stream().map(entry -> entries.findById(entry.product().id()).orElseThrow()).toList();
    }

    private void markReviewedWithConfirmedPrice(String id) {
        assertThat(jdbc.update("""
                UPDATE catalog_product SET is_active = TRUE, verification_status = 'CORE_VERIFIED',
                    updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, id)).isEqualTo(1);
        assertThat(jdbc.update("""
                UPDATE catalog_reference_price SET amount_krw = ?, status = 'CONFIRMED',
                    method = 'MEDIAN_DAILY_6M_V1', period_start = DATE '2026-03-29', period_end = DATE '2026-09-28',
                    observed_day_count = 184, sample_count = 368, price_basis = 'Test fixture: daily KRW observations',
                    evidence_ref = 'test-fixture://catalog-gpu-expand-seed/confirmed-price', calculated_at = CURRENT_TIMESTAMP,
                    confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE product_id = ?
                """, new BigDecimal("98765.43"), id)).isEqualTo(1);
    }

    private void assertReviewedWithConfirmedPrice(String id) {
        var product = entries.findById(id).orElseThrow().product();
        assertThat(product.active()).isTrue();
        assertThat(product.verificationStatus()).isEqualTo(CatalogVerificationStatus.CORE_VERIFIED);
        assertThat(product.referencePrice().status()).isEqualTo(CatalogPriceStatus.CONFIRMED);
        assertThat(product.referencePrice().amountKrw()).isEqualByComparingTo("98765.43");
    }

    private void assertExistingPreserved(List<CatalogEntryView> existing) {
        assertThat(existing).hasSize(42);
        for (var before : existing) assertThat(entries.findById(before.product().id()).orElseThrow()).isEqualTo(before);
        for (int index : new int[]{0, 9, 41}) assertReviewedWithConfirmedPrice(existing.get(index).product().id());
    }

    private void assertTableCounts(int newGpuCount) {
        assertThat(rowCount("catalog_product")).isEqualTo(42 + newGpuCount);
        assertThat(rowCount("catalog_reference_price")).isEqualTo(42 + newGpuCount);
        assertThat(rowCount("catalog_product_source")).isEqualTo(99 + 2 * newGpuCount);
        assertThat(rowCount("cpu_spec")).isEqualTo(12);
        assertThat(rowCount("motherboard_spec")).isEqualTo(10);
        assertThat(rowCount("ram_spec")).isEqualTo(11);
        assertThat(rowCount("gpu_spec")).isEqualTo(6 + newGpuCount);
        assertThat(rowCount("gpu_power_connector")).isEqualTo(6 + newGpuCount);
        assertThat(rowCount("monitor_spec")).isEqualTo(3);
    }

    private int rowCount(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private List<Map<String, Object>> snapshotRows(String table) {
        var rows = jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1");
        for (var row : rows) {
            row.replaceAll((key, value) -> value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value);
        }
        return rows;
    }

    private void assertAllTablesEmpty() {
        for (String table : TABLES) assertThat(rowCount(table)).as("Rows in %s", table).isZero();
    }

    private void assertDedicatedDatabase() throws SQLException {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL().split(";", 2)[0])
                    .isEqualTo("jdbc:h2:mem:catalog-gpu-expand-seed-test");
        }
    }

    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
}
