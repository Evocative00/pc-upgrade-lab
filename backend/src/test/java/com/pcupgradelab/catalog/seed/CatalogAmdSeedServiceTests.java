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

import static com.pcupgradelab.catalog.seed.CatalogSeedBatch.AMD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 테스트 자체의 트랜잭션 없이 기존 34종 보존과 AMD 8종의 실제 커밋·롤백을 검사한다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:catalog-amd-seed-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogAmdSeedServiceTests {
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
    void addsFourAmdCpusAndFourBoardsPreservingTheExistingThirtyFour() {
        assertThat(CatalogSeedBatch.fromName("week2-amd")).isEqualTo(AMD);
        var existing = seedExistingSnapshot();
        var expected = loader.load(AMD);
        assertThat(expected.stream().map(request -> request.product().modelName()).toList()).containsExactly(
                "Ryzen 5 5600X", "Ryzen 7 5700X", "Ryzen 7 5700X3D", "Ryzen 7 7800X3D",
                "B450M PRO-VDH MAX", "B550-A PRO", "PRO A620M-E", "MAG B650 TOMAHAWK WIFI");
        var result = seedService.seed(AMD);
        assertThat(result.created()).isEqualTo(8);
        assertThat(result.skipped()).isZero();
        assertThat(result.items()).hasSize(8).allSatisfy(item -> assertThat(item.created()).isTrue());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        for (int i = 0; i < expected.size(); i++) {
            var request = expected.get(i);
            var stored = entries.findById(result.items().get(i).productId()).orElseThrow();
            assertThat(UUID.fromString(stored.product().id()).toString()).isEqualTo(stored.product().id());
            assertThat(stored.product().type()).isEqualTo(i < 4 ? PartType.CPU : PartType.MOTHERBOARD);
            assertThat(stored.product().manufacturer()).isEqualTo(i < 4 ? "AMD" : "MSI");
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
        assertTableCounts(4, 4);
    }

    @Test
    void repeatedSeedPreservesIdsEveryRowReviewedStatesAndConfirmedPrices() {
        var existing = seedExistingSnapshot();
        var first = seedService.seed(AMD);
        for (String id : List.of(first.items().getFirst().productId(), first.items().getLast().productId())) {
            markReviewedWithConfirmedPrice(id);
        }
        var before = TABLES.stream().map(this::snapshotRows).toList();
        var repeated = seedService.seed(AMD);
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
                ALTER TABLE catalog_product_source ADD CONSTRAINT ck_test_reject_last_amd_source
                CHECK (source_name <> 'BUILDCORES' OR external_id IS NULL OR external_id <> '%s')
                """.formatted(externalId));
        try {
            Throwable failure = catchThrowable(() -> seedService.seed(AMD));
            assertThat(failure).isNotNull();
            assertThat(NestedExceptionUtils.getMostSpecificCause(failure).getMessage())
                    .containsIgnoringCase("ck_test_reject_last_amd_source");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertExistingPreserved(existing);
            assertTableCounts(0, 0);
        } finally {
            jdbc.execute("ALTER TABLE catalog_product_source DROP CONSTRAINT ck_test_reject_last_amd_source");
        }
    }

    @Test
    void conflictingExistingBoardIsPreservedAndEarlierSevenNewProductsAreRolledBack() {
        var existing = seedExistingSnapshot();
        String id = entries.create(lastBoardRequest()).product().id();
        // 테스트 DB에만 충돌을 만든다. 이 보드의 제조사 공표 최대 용량은 256 GiB다.
        assertThat(jdbc.update("UPDATE motherboard_spec SET max_memory_bytes = 137438953472 WHERE product_id = ?", id))
                .isEqualTo(1);
        var before = entries.findById(id).orElseThrow();
        assertThat(((Motherboard) before.specification()).maxMemoryBytes()).isEqualTo(137438953472L);
        assertThatThrownBy(() -> seedService.seed(AMD)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Catalog seed conflict").hasMessageContaining("MAG B650 TOMAHAWK WIFI");
        assertThat(entries.findById(id).orElseThrow()).isEqualTo(before);
        assertExistingPreserved(existing);
        assertTableCounts(0, 1);
    }

    @Test
    void startupDoesNotInsertAnyBatchAndOnlyTheExactApprovedNameIsAccepted() {
        assertThat(context.getBeansOfType(CatalogSeedRunner.class)).isEmpty();
        assertAllTablesEmpty();
        assertThat(loader.load()).hasSize(9); // 기존 기본 묶음은 자동으로 확장하지 않는다.
        for (String invalid : new String[]{null, "", "week2-Amd", " week2-amd", "../week2-amd", "week2-all"}) {
            assertThatThrownBy(() -> CatalogSeedBatch.fromName(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> seedService.seed(null)).isInstanceOf(IllegalArgumentException.class);
        assertAllTablesEmpty();
    }

    private void assertManufacturerFactsAndCorrections(List<CatalogEntryCreateRequest> requests) {
        assertThat(requests.subList(0, 4).stream().map(request -> request.specification()).toList()).containsExactly(
                new Cpu("AM4", 6, 12, 3700, 4600, new BigDecimal("65"), false, null),
                new Cpu("AM4", 8, 16, 3400, 4600, new BigDecimal("65"), false, null),
                new Cpu("AM4", 8, 16, 3000, 4100, new BigDecimal("105"), false, null),
                new Cpu("AM5", 8, 16, 4200, 5000, new BigDecimal("120"), true, "AMD Radeon Graphics"));
        assertThat(requests.subList(0, 4).stream().map(request -> request.product().partNumber()).toList())
                .containsExactly("100-100000065BOX", "100-100000926WOF", "100-100001503WOF", "100-100000910WOF");
        for (var request : requests.subList(0, 4)) {
            var raw = request.sources().stream()
                    .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES).findFirst().orElseThrow();
            var cores = (Map<?, ?>) raw.rawPayload().get("cores");
            assertThat(((Number) cores.get("performance")).intValue()).isZero();
            assertThat(((Number) cores.get("efficiency")).intValue()).isZero();
            var manufacturer = request.sources().stream()
                    .filter(source -> source.sourceName() == CatalogSourceName.MANUFACTURER).findFirst().orElseThrow();
            var normalized = (Map<?, ?>) manufacturer.rawPayload().get("normalized_specification");
            for (String field : List.of("processorBasePowerW", "maximumTurboPowerW", "performanceCoreCount",
                    "efficientCoreCount", "performanceCoreBaseClockMhz", "efficientCoreBaseClockMhz",
                    "performanceCoreBoostClockMhz", "efficientCoreBoostClockMhz")) {
                assertThat(normalized.containsKey(field)).as("Present field %s", field).isTrue();
                assertThat(normalized.get(field)).as("AMD field %s", field).isNull();
            }
        }
        assertThat(requests.subList(4, 8).stream().map(request -> request.specification()).toList()).containsExactly(
                new Motherboard("AM4", "B450", "MICRO_ATX", "DDR4", "DIMM", 4, 137438953472L, false),
                new Motherboard("AM4", "B550", "ATX", "DDR4", "DIMM", 4, 137438953472L, false),
                new Motherboard("AM5", "A620", "MICRO_ATX", "DDR5", "DIMM", 2, 137438953472L, false),
                new Motherboard("AM5", "B650", "ATX", "DDR5", "DIMM", 4, 274877906944L, false));
        var b450Raw = requests.get(4).sources().stream()
                .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES).findFirst().orElseThrow();
        assertThat(((Number) ((Map<?, ?>) b450Raw.rawPayload().get("memory")).get("max")).intValue())
                .isEqualTo(64);
        var b450Manufacturer = requests.get(4).sources().stream()
                .filter(source -> source.sourceName() == CatalogSourceName.MANUFACTURER).findFirst().orElseThrow();
        var condition = (Map<?, ?>) b450Manufacturer.rawPayload().get("published_memory_capacity_condition");
        assertThat(((Number) condition.get("max_memory_bytes")).longValue()).isEqualTo(137438953472L);
        assertThat(condition.get("required_bios_family")).isEqualTo("ComboPI");
        assertThat(condition.get("minimum_bios_base_version")).isEqualTo("1.0.0.3");
        assertThat(condition.get("later_versions_allowed")).isEqualTo(true);
    }

    private CatalogEntryCreateRequest lastBoardRequest() {
        var last = loader.load(AMD).getLast();
        assertThat(last.product().type()).isEqualTo(PartType.MOTHERBOARD);
        assertThat(last.product().modelName()).isEqualTo("MAG B650 TOMAHAWK WIFI");
        return last;
    }

    private List<CatalogEntryView> seedExistingSnapshot() {
        var existing = Stream.of(CatalogSeedBatch.INITIAL, CatalogSeedBatch.GPU, CatalogSeedBatch.MONITOR,
                        CatalogSeedBatch.INTEL, CatalogSeedBatch.RAM)
                .flatMap(batch -> seedService.seed(batch).items().stream())
                .map(item -> entries.findById(item.productId()).orElseThrow()).toList();
        // 이전 CPU와 RAM의 검증·가격 상태까지 추가·롤백·재실행 모두에서 보존한다.
        markReviewedWithConfirmedPrice(existing.getFirst().product().id());
        markReviewedWithConfirmedPrice(existing.getLast().product().id());
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
                    evidence_ref = 'test-fixture://catalog-amd-seed/confirmed-price', calculated_at = CURRENT_TIMESTAMP,
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
        assertThat(existing).hasSize(34);
        for (var before : existing) assertThat(entries.findById(before.product().id()).orElseThrow()).isEqualTo(before);
        assertReviewedWithConfirmedPrice(existing.getFirst().product().id());
        assertReviewedWithConfirmedPrice(existing.getLast().product().id());
    }

    private void assertTableCounts(int newCpuCount, int newBoardCount) {
        assertThat(rowCount("catalog_product")).isEqualTo(34 + newCpuCount + newBoardCount);
        assertThat(rowCount("catalog_reference_price")).isEqualTo(34 + newCpuCount + newBoardCount);
        assertThat(rowCount("catalog_product_source")).isEqualTo(83 + 2 * newCpuCount + 2 * newBoardCount);
        assertThat(rowCount("cpu_spec")).isEqualTo(8 + newCpuCount);
        assertThat(rowCount("motherboard_spec")).isEqualTo(6 + newBoardCount);
        assertThat(rowCount("ram_spec")).isEqualTo(11);
        assertThat(rowCount("gpu_spec")).isEqualTo(6);
        assertThat(rowCount("gpu_power_connector")).isEqualTo(6);
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
                    .isEqualTo("jdbc:h2:mem:catalog-amd-seed-test");
        }
    }
}
