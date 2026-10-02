package com.pcupgradelab.catalog.seed;

import com.pcupgradelab.catalog.CatalogEntryCreateRequest;
import com.pcupgradelab.catalog.CatalogEntryService;
import com.pcupgradelab.catalog.CatalogEntryView;
import com.pcupgradelab.catalog.CatalogPriceStatus;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.CatalogSpecification.Cpu;
import com.pcupgradelab.catalog.CatalogSpecification.Motherboard;
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

import static com.pcupgradelab.catalog.seed.CatalogSeedBatch.INTEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 테스트 자체의 트랜잭션 없이 기존 18종 보존과 추가 8종의 실제 커밋·롤백을 검사한다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:catalog-intel-seed-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogIntelSeedServiceTests {
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
    void addsFourCpusAndFourBoardsPreservingTheExistingEighteen() {
        assertThat(CatalogSeedBatch.fromName("week2-intel")).isEqualTo(INTEL);
        var existing = seedExistingSnapshot();
        var expected = loader.load(INTEL);
        assertThat(expected.stream().map(request -> request.product().modelName()).toList()).containsExactly(
                "Core i3-12100F", "Core i5-12400F", "Core i5-13600KF", "Core i7-14700F",
                "PRO B760M-A WIFI DDR4", "PRO B760M-A WIFI", "PRIME B760M-A D4", "PRIME B760M-A");
        var result = seedService.seed(INTEL);
        assertThat(result.created()).isEqualTo(8);
        assertThat(result.skipped()).isZero();
        assertThat(result.items()).hasSize(8).allSatisfy(item -> assertThat(item.created()).isTrue());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        for (int i = 0; i < expected.size(); i++) {
            var request = expected.get(i);
            var item = result.items().get(i);
            var stored = entries.findById(item.productId()).orElseThrow();
            assertThat(UUID.fromString(item.productId()).toString()).isEqualTo(item.productId());
            assertThat(stored.product().type()).isEqualTo(request.product().type());
            assertThat(stored.product().manufacturer()).isEqualTo(request.product().manufacturer());
            assertThat(stored.product().modelName()).isEqualTo(request.product().modelName());
            assertThat(stored.product().partNumber()).isEqualTo(request.product().partNumber());
            assertThat(stored.product().verificationStatus()).isEqualTo(CatalogVerificationStatus.UNVERIFIED);
            assertThat(stored.product().active()).isFalse();
            assertThat(stored.product().referencePrice().status()).isEqualTo(CatalogPriceStatus.UNCONFIRMED);
            assertThat(stored.product().referencePrice().amountKrw()).isNull();
            assertThat(stored.specification()).isEqualTo(request.specification());
            assertThat(stored.sources()).hasSize(request.sources().size());
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
        assertManufacturerCorrections(expected);
        assertExistingPreserved(existing);
        assertTableCounts(4, 4);
    }

    @Test
    void repeatedSeedPreservesIdsReviewedStatesConfirmedPricesAndAllRows() {
        var existing = seedExistingSnapshot();
        var first = seedService.seed(INTEL);
        for (String id : List.of(first.items().getFirst().productId(), first.items().getLast().productId())) {
            jdbc.update("""
                    UPDATE catalog_product SET is_active = TRUE, verification_status = 'CORE_VERIFIED',
                        updated_at = CURRENT_TIMESTAMP WHERE id = ?
                    """, id);
            jdbc.update("""
                    UPDATE catalog_reference_price SET amount_krw = ?, status = 'CONFIRMED',
                        method = 'MEDIAN_DAILY_6M_V1', period_start = DATE '2026-03-29', period_end = DATE '2026-09-28',
                        observed_day_count = 184, sample_count = 368, price_basis = 'Test fixture: daily KRW observations',
                        evidence_ref = 'test-fixture://catalog-intel-seed/confirmed-price', calculated_at = CURRENT_TIMESTAMP,
                        confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE product_id = ?
                    """, new BigDecimal("98765.43"), id);
        }
        var before = TABLES.stream().map(this::snapshotRows).toList();
        var repeated = seedService.seed(INTEL);
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
            var product = entries.findById(id).orElseThrow().product();
            assertThat(product.active()).isTrue();
            assertThat(product.verificationStatus()).isEqualTo(CatalogVerificationStatus.CORE_VERIFIED);
            assertThat(product.referencePrice().status()).isEqualTo(CatalogPriceStatus.CONFIRMED);
            assertThat(product.referencePrice().amountKrw()).isEqualByComparingTo("98765.43");
        }
        assertExistingPreserved(existing);
        assertTableCounts(4, 4);
    }

    @Test
    void lastBoardSourceFailureRollsBackAllEightAdditionsAndKeepsTheExistingBatches() {
        var existing = seedExistingSnapshot();
        String externalId = lastBoardRequest().sources().stream()
                .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES)
                .findFirst().orElseThrow().externalId();
        assertThat(UUID.fromString(externalId).toString()).isEqualTo(externalId);
        jdbc.execute("""
                ALTER TABLE catalog_product_source ADD CONSTRAINT ck_test_reject_last_intel_source
                CHECK (source_name <> 'BUILDCORES' OR external_id IS NULL OR external_id <> '%s')
                """.formatted(externalId));
        try {
            Throwable failure = catchThrowable(() -> seedService.seed(INTEL));
            assertThat(failure).isNotNull();
            assertThat(NestedExceptionUtils.getMostSpecificCause(failure).getMessage())
                    .containsIgnoringCase("ck_test_reject_last_intel_source");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertExistingPreserved(existing);
            assertTableCounts(0, 0);
        } finally {
            jdbc.execute("ALTER TABLE catalog_product_source DROP CONSTRAINT ck_test_reject_last_intel_source");
        }
    }

    @Test
    void conflictingExistingBoardIsPreservedAndEarlierSevenNewProductsAreRolledBack() {
        var existing = seedExistingSnapshot();
        String id = entries.create(lastBoardRequest()).product().id();
        // 테스트 DB에만 충돌을 만든다. 이 묶음의 제조사 공표 최대 용량은 256 GiB다.
        assertThat(jdbc.update("UPDATE motherboard_spec SET max_memory_bytes = 137438953472 WHERE product_id = ?", id))
                .isEqualTo(1);
        var before = entries.findById(id).orElseThrow();
        assertThat(((Motherboard) before.specification()).maxMemoryBytes()).isEqualTo(137438953472L);
        assertThatThrownBy(() -> seedService.seed(INTEL)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Catalog seed conflict").hasMessageContaining("PRIME B760M-A");
        assertThat(entries.findById(id).orElseThrow()).isEqualTo(before);
        assertExistingPreserved(existing);
        assertTableCounts(0, 1);
    }

    @Test
    void startupDoesNotInsertAnyBatchAndOnlyAnExactApprovedBatchNameIsAccepted() {
        assertThat(context.getBeansOfType(CatalogSeedRunner.class)).isEmpty();
        assertAllTablesEmpty();
        for (String invalid : new String[]{null, "", "week2-Intel", " week2-intel", "../week2-intel", "week2-all"}) {
            assertThatThrownBy(() -> CatalogSeedBatch.fromName(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> seedService.seed(null)).isInstanceOf(IllegalArgumentException.class);
        assertAllTablesEmpty();
    }

    private void assertManufacturerCorrections(List<CatalogEntryCreateRequest> requests) {
        assertThat(requests.subList(0, 4).stream().map(request -> request.specification()).toList()).containsExactly(
                new Cpu("LGA1700", 4, 8, 3300, 4300, null, false, null,
                        new BigDecimal("58"), new BigDecimal("89"), 4, 0, 3300, null, 4300, null),
                new Cpu("LGA1700", 6, 12, 2500, 4400, null, false, null,
                        new BigDecimal("65"), new BigDecimal("117"), 6, 0, 2500, null, 4400, null),
                new Cpu("LGA1700", 14, 20, null, 5100, null, false, null,
                        new BigDecimal("125"), new BigDecimal("181"), 6, 8, 3500, 2600, 5100, 3900),
                new Cpu("LGA1700", 20, 28, null, 5400, null, false, null,
                        new BigDecimal("65"), new BigDecimal("219"), 8, 12, 2100, 1500, 5300, 4200));
        assertThat(requests.subList(0, 4).stream().map(request -> request.product().partNumber()).toList())
                .containsExactly("BX8071512100F", "BX8071512400F", "BX8071513600KF", "BX8071514700F");
        var rawCpu = requests.get(3).sources().stream()
                .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES).findFirst().orElseThrow();
        var clocks = (Map<?, ?>) rawCpu.rawPayload().get("clocks");
        var performance = (Map<?, ?>) clocks.get("performance");
        assertThat(new BigDecimal(performance.get("boost").toString())).isEqualByComparingTo("5.4");
        assertThat(requests.subList(4, 8).stream().map(request -> request.specification()).toList()).containsExactly(
                new Motherboard("LGA1700", "B760", "MICRO_ATX", "DDR4", "DIMM", 4, 137438953472L, false),
                new Motherboard("LGA1700", "B760", "MICRO_ATX", "DDR5", "DIMM", 4, 274877906944L, false),
                new Motherboard("LGA1700", "B760", "MICRO_ATX", "DDR4", "DIMM", 4, 137438953472L, false),
                new Motherboard("LGA1700", "B760", "MICRO_ATX", "DDR5", "DIMM", 4, 274877906944L, false));
        var rawBoard = lastBoardRequest().sources().stream()
                .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES).findFirst().orElseThrow();
        var memory = (Map<?, ?>) rawBoard.rawPayload().get("memory");
        assertThat(((Number) memory.get("max")).intValue()).isEqualTo(128);
    }

    private CatalogEntryCreateRequest lastBoardRequest() {
        var last = loader.load(INTEL).getLast();
        assertThat(last.product().type()).isEqualTo(PartType.MOTHERBOARD);
        assertThat(last.product().modelName()).isEqualTo("PRIME B760M-A");
        return last;
    }

    private List<CatalogEntryView> seedExistingSnapshot() {
        return Stream.of(CatalogSeedBatch.INITIAL, CatalogSeedBatch.GPU, CatalogSeedBatch.MONITOR)
                .flatMap(batch -> seedService.seed(batch).items().stream())
                .map(item -> entries.findById(item.productId()).orElseThrow()).toList();
    }

    private void assertExistingPreserved(List<CatalogEntryView> existing) {
        assertThat(existing).hasSize(18);
        for (var before : existing) {
            assertThat(entries.findById(before.product().id()).orElseThrow()).isEqualTo(before);
        }
    }

    private void assertTableCounts(int newCpuCount, int newBoardCount) {
        assertThat(rowCount("catalog_product")).isEqualTo(18 + newCpuCount + newBoardCount);
        assertThat(rowCount("catalog_reference_price")).isEqualTo(18 + newCpuCount + newBoardCount);
        assertThat(rowCount("catalog_product_source")).isEqualTo(39 + 3 * newCpuCount + 2 * newBoardCount);
        assertThat(rowCount("cpu_spec")).isEqualTo(4 + newCpuCount);
        assertThat(rowCount("motherboard_spec")).isEqualTo(2 + newBoardCount);
        assertThat(rowCount("ram_spec")).isEqualTo(3);
        assertThat(rowCount("gpu_spec")).isEqualTo(6);
        assertThat(rowCount("gpu_power_connector")).isEqualTo(6);
        assertThat(rowCount("monitor_spec")).isEqualTo(3);
    }

    private int rowCount(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private List<Map<String, Object>> snapshotRows(String table) {
        var rows = jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1");
        // H2 JSON의 JDBC byte[]는 객체 참조 대신 내용으로 비교한다.
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
                    .isEqualTo("jdbc:h2:mem:catalog-intel-seed-test");
        }
    }
}
