package com.pcupgradelab.catalog.seed;

import com.pcupgradelab.catalog.CatalogEntryCreateRequest;
import com.pcupgradelab.catalog.CatalogEntryService;
import com.pcupgradelab.catalog.CatalogEntryView;
import com.pcupgradelab.catalog.CatalogPriceStatus;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.CatalogSpecification.Motherboard;
import com.pcupgradelab.catalog.CatalogSpecification.Ram;
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

import static com.pcupgradelab.catalog.seed.CatalogSeedBatch.BOARD_RAM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 테스트 트랜잭션 없이 기존 50종 보존과 메인보드·RAM 11종의 실제 커밋·롤백을 검사한다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:catalog-board-ram-seed-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogBoardRamSeedServiceTests {
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
    void commitsSixBoardsAndFiveSingleModulesPreservingTheExistingFifty() {
        assertThat(CatalogSeedBatch.fromName("week2-board-ram")).isEqualTo(BOARD_RAM);
        assertThat(BOARD_RAM.supports(PartType.MOTHERBOARD)).isTrue();
        assertThat(BOARD_RAM.supports(PartType.RAM)).isTrue();
        assertThat(BOARD_RAM.supports(PartType.CPU)).isFalse();
        var existing = seedExistingSnapshot();
        var existingRows = TABLES.stream().map(this::snapshotRows).toList();
        var expected = loader.load(BOARD_RAM);
        assertThat(expected.stream().map(request -> request.product().modelName()).toList()).containsExactly(
                "B550M Pro4", "PRIME B550M-A (WI-FI)", "B650M Pro RS", "B650M DS3H (rev. 1.0)",
                "B760M Pro RS", "B760M DS3H DDR4", "M378A1K43CB2-CRC DDR4-2400 8GB (1x8GB)",
                "M378A2K43CB1-CTD DDR4-2666 16GB (1x16GB)", "HMA81GU6MFR8N-UH DDR4-2400 8GB (1x8GB)",
                "HMAA2GU6CJR8N-XN DDR4-3200 16GB (1x16GB)", "M323R1GB4PB0-CWM DDR5-5600 8GB (1x8GB)");
        var result = seedService.seed(BOARD_RAM);
        assertThat(result.created()).isEqualTo(11);
        assertThat(result.skipped()).isZero();
        assertThat(result.items()).hasSize(11).allSatisfy(item -> assertThat(item.created()).isTrue());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        for (int i = 0; i < expected.size(); i++) {
            var request = expected.get(i);
            var stored = entries.findById(result.items().get(i).productId()).orElseThrow();
            assertThat(UUID.fromString(stored.product().id()).toString()).isEqualTo(stored.product().id());
            assertThat(stored.product().type()).isEqualTo(request.product().type());
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
        assertManufacturerFactsAndUnknowns(expected);
        assertExistingPreserved(existing);
        // API 뷰뿐 아니라 기존 출처 payload와 생성 시각 등 모든 기존 DB 행을 보존한다.
        for (int i = 0; i < TABLES.size(); i++) {
            assertThat(snapshotRows(TABLES.get(i))).containsAll(existingRows.get(i));
        }
        assertTableCounts(6, 5);
    }

    @Test
    void repeatedSeedKeepsIdsEveryRowReviewedStatesAndConfirmedPrices() {
        var existing = seedExistingSnapshot();
        var first = seedService.seed(BOARD_RAM);
        for (String id : List.of(first.items().getFirst().productId(), first.items().getLast().productId())) {
            markReviewedWithConfirmedPrice(id);
        }
        var before = TABLES.stream().map(this::snapshotRows).toList();
        var repeated = seedService.seed(BOARD_RAM);
        assertThat(repeated.created()).isZero();
        assertThat(repeated.skipped()).isEqualTo(11);
        assertThat(repeated.items()).hasSize(11).allSatisfy(item -> assertThat(item.created()).isFalse());
        assertThat(repeated.items().stream().map(item -> item.productId()).toList())
                .containsExactlyElementsOf(first.items().stream().map(item -> item.productId()).toList());
        for (int i = 0; i < TABLES.size(); i++) {
            assertThat(snapshotRows(TABLES.get(i))).as("Unchanged rows in %s", TABLES.get(i)).isEqualTo(before.get(i));
        }
        for (String id : List.of(first.items().getFirst().productId(), first.items().getLast().productId())) {
            assertReviewedWithConfirmedPrice(id);
        }
        assertExistingPreserved(existing);
        assertTableCounts(6, 5);
    }

    @Test
    void lastDdr5SourceFailureRollsBackAllElevenAdditionsAndKeepsTheExistingRows() {
        var existing = seedExistingSnapshot();
        var before = TABLES.stream().map(this::snapshotRows).toList();
        String externalId = lastRamRequest().sources().stream()
                .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES)
                .findFirst().orElseThrow().externalId();
        assertThat(UUID.fromString(externalId).toString()).isEqualTo(externalId);
        jdbc.execute("""
                ALTER TABLE catalog_product_source ADD CONSTRAINT ck_test_reject_last_board_ram_source
                CHECK (source_name <> 'BUILDCORES' OR external_id IS NULL OR external_id <> '%s')
                """.formatted(externalId));
        try {
            Throwable failure = catchThrowable(() -> seedService.seed(BOARD_RAM));
            assertThat(failure).isNotNull();
            assertThat(NestedExceptionUtils.getMostSpecificCause(failure).getMessage())
                    .containsIgnoringCase("ck_test_reject_last_board_ram_source");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            for (int i = 0; i < TABLES.size(); i++) assertThat(snapshotRows(TABLES.get(i))).isEqualTo(before.get(i));
            assertExistingPreserved(existing);
            assertTableCounts(0, 0);
        } finally {
            jdbc.execute("ALTER TABLE catalog_product_source DROP CONSTRAINT ck_test_reject_last_board_ram_source");
        }
    }

    @Test
    void conflictingExistingDdr5IsPreservedAndTheEarlierTenProductsAreRolledBack() {
        var existing = seedExistingSnapshot();
        String id = entries.create(lastRamRequest()).product().id();
        // 전용 테스트 DB에서만 5600과 다른 유효한 속도를 만들어 등록 충돌을 검사한다.
        assertThat(jdbc.update("UPDATE ram_spec SET data_rate_mts = 5200 WHERE product_id = ?", id)).isEqualTo(1);
        var before = entries.findById(id).orElseThrow();
        var rowsBefore = TABLES.stream().map(this::snapshotRows).toList();
        assertThat(((Ram) before.specification()).dataRateMts()).isEqualTo(5200);
        assertThatThrownBy(() -> seedService.seed(BOARD_RAM)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Catalog seed conflict").hasMessageContaining("M323R1GB4PB0-CWM");
        assertThat(entries.findById(id).orElseThrow()).isEqualTo(before);
        for (int i = 0; i < TABLES.size(); i++) assertThat(snapshotRows(TABLES.get(i))).isEqualTo(rowsBefore.get(i));
        assertExistingPreserved(existing);
        assertTableCounts(0, 1);
    }

    @Test
    void startupDoesNotInsertABatchAndOnlyTheExactApprovedNameIsAccepted() {
        assertThat(context.getBeansOfType(CatalogSeedRunner.class)).isEmpty();
        assertAllTablesEmpty();
        assertThat(loader.load()).hasSize(9);
        for (String invalid : new String[]{null, "", "week2-Board-ram", " week2-board-ram",
                "../week2-board-ram", "week2-board-ram/", "week2-all"}) {
            assertThatThrownBy(() -> CatalogSeedBatch.fromName(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> seedService.seed(null)).isInstanceOf(IllegalArgumentException.class);
        assertAllTablesEmpty();
    }

    private void assertManufacturerFactsAndUnknowns(List<CatalogEntryCreateRequest> requests) {
        assertThat(requests.stream().map(request -> request.specification()).toList()).containsExactly(
                new Motherboard("AM4", "AMD B550", "MICRO_ATX", "DDR4", "DIMM", 4, 137438953472L, null),
                new Motherboard("AM4", "AMD B550", "MICRO_ATX", "DDR4", "DIMM", 4, 137438953472L, null),
                new Motherboard("AM5", "AMD B650", "MICRO_ATX", "DDR5", "DIMM", 4, 274877906944L, true),
                new Motherboard("AM5", "AMD B650", "MICRO_ATX", "DDR5", "DIMM", 4, 274877906944L, false),
                new Motherboard("LGA1700", "Intel B760", "MICRO_ATX", "DDR5", "DIMM", 4, 274877906944L, false),
                new Motherboard("LGA1700", "Intel B760", "MICRO_ATX", "DDR4", "DIMM", 4, 137438953472L, false),
                new Ram("DDR4", 8589934592L, 1, 2400, "DIMM", 288, false, "UNBUFFERED", decimal("1.2"), null),
                new Ram("DDR4", 17179869184L, 1, 2666, "DIMM", 288, false, "UNBUFFERED", decimal("1.2"), null),
                new Ram("DDR4", 8589934592L, 1, 2400, "DIMM", 288, false, "UNBUFFERED", decimal("1.2"), null),
                new Ram("DDR4", 17179869184L, 1, 3200, "DIMM", 288, false, "UNBUFFERED", decimal("1.2"), null),
                new Ram("DDR5", 8589934592L, 1, 5600, "DIMM", 288, false, "UNBUFFERED", null, null));
        assertThat(requests.get(3).product().partNumber()).isEqualTo("B650M DS3H (rev. 1.0)");
        assertThat(requests.get(5).product().modelName()).isEqualTo("B760M DS3H DDR4");
        for (var request : requests) {
            var manufacturer = request.sources().stream()
                    .filter(source -> source.sourceName() == CatalogSourceName.MANUFACTURER).findFirst().orElseThrow();
            assertThat(manufacturer.rawPayload().get("record_kind")).isEqualTo("CURATED_SPEC_EXTRACT");
            assertThat(manufacturer.rawPayload().get("notes")).isInstanceOf(List.class);
            if (request.product().type() == PartType.RAM) {
                var normalized = (Map<?, ?>) manufacturer.rawPayload().get("normalized_specification");
                assertThat(normalized.containsKey("heightMm")).isTrue();
                assertThat(normalized.get("heightMm")).isNull();
            }
        }
        var b650Raw = upstream(requests.get(3));
        assertThat(((Number) ((Map<?, ?>) b650Raw.get("memory")).get("max")).intValue()).isEqualTo(192);
        assertThat(upstream(requests.getFirst()).get("ecc_support")).isEqualTo(false);
        assertThat(((Number) upstream(requests.get(7)).get("height")).intValue()).isZero();
        assertThat(upstream(requests.getLast()).get("profile_support")).isEqualTo(List.of("EXPO"));
        assertThat(((Number) upstream(requests.getLast()).get("cas_latency")).intValue()).isEqualTo(36);
        assertThat(requests.getLast().product().modelName()).doesNotContain("EXPO", "CL36");
        var oem = requests.getLast().sources().stream()
                .filter(source -> source.sourceName() == CatalogSourceName.MANUFACTURER).findFirst().orElseThrow();
        assertThat(oem.rawPayload().get("document_author")).isEqualTo("Dell");
        assertThat(oem.sourceUrl()).startsWith("https://www.dell.com/");
    }

    private Map<String, Object> upstream(CatalogEntryCreateRequest request) {
        return request.sources().stream().filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES)
                .findFirst().orElseThrow().rawPayload();
    }

    private CatalogEntryCreateRequest lastRamRequest() {
        var last = loader.load(BOARD_RAM).getLast();
        assertThat(last.product().type()).isEqualTo(PartType.RAM);
        assertThat(last.product().partNumber()).isEqualTo("M323R1GB4PB0-CWM");
        return last;
    }

    private List<CatalogEntryView> seedExistingSnapshot() {
        var existing = Stream.of(CatalogSeedBatch.INITIAL, CatalogSeedBatch.GPU, CatalogSeedBatch.MONITOR,
                        CatalogSeedBatch.INTEL, CatalogSeedBatch.RAM, CatalogSeedBatch.AMD, CatalogSeedBatch.GPU_EXPANSION)
                .flatMap(batch -> seedService.seed(batch).items().stream())
                .map(item -> entries.findById(item.productId()).orElseThrow()).toList();
        for (int index : new int[]{0, 6, 9, 41}) markReviewedWithConfirmedPrice(existing.get(index).product().id());
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
                    evidence_ref = 'test-fixture://catalog-board-ram-seed/confirmed-price', calculated_at = CURRENT_TIMESTAMP,
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
        assertThat(existing).hasSize(50);
        for (var before : existing) assertThat(entries.findById(before.product().id()).orElseThrow()).isEqualTo(before);
        for (int index : new int[]{0, 6, 9, 41}) assertReviewedWithConfirmedPrice(existing.get(index).product().id());
    }

    private void assertTableCounts(int addedBoards, int addedRam) {
        int totalAdded = addedBoards + addedRam;
        assertThat(rowCount("catalog_product")).isEqualTo(50 + totalAdded);
        assertThat(rowCount("catalog_reference_price")).isEqualTo(50 + totalAdded);
        assertThat(rowCount("catalog_product_source")).isEqualTo(115 + 2 * totalAdded);
        assertThat(rowCount("cpu_spec")).isEqualTo(12);
        assertThat(rowCount("motherboard_spec")).isEqualTo(10 + addedBoards);
        assertThat(rowCount("ram_spec")).isEqualTo(11 + addedRam);
        assertThat(rowCount("gpu_spec")).isEqualTo(14);
        assertThat(rowCount("gpu_power_connector")).isEqualTo(14);
        assertThat(rowCount("monitor_spec")).isEqualTo(3);
        Integer connectorTotal = jdbc.queryForObject("SELECT SUM(connector_count) FROM gpu_power_connector", Integer.class);
        assertThat(connectorTotal).isEqualTo(18);
    }

    private int rowCount(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private List<Map<String, Object>> snapshotRows(String table) {
        var rows = jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1");
        for (var row : rows) row.replaceAll((key, value) -> value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value);
        return rows;
    }

    private void assertAllTablesEmpty() {
        for (String table : TABLES) assertThat(rowCount(table)).as("Rows in %s", table).isZero();
    }

    private void assertDedicatedDatabase() throws SQLException {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL().split(";", 2)[0])
                    .isEqualTo("jdbc:h2:mem:catalog-board-ram-seed-test");
        }
    }

    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
}
