package com.pcupgradelab.catalog.seed;

import com.pcupgradelab.catalog.CatalogEntryCreateRequest;
import com.pcupgradelab.catalog.CatalogEntryService;
import com.pcupgradelab.catalog.CatalogEntryView;
import com.pcupgradelab.catalog.CatalogPriceStatus;
import com.pcupgradelab.catalog.CatalogSourceName;
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

import static com.pcupgradelab.catalog.seed.CatalogSeedBatch.RAM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 테스트 자체의 트랜잭션 없이 기존 26종 보존과 RAM 8종의 실제 커밋·롤백을 검사한다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:catalog-ram-seed-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogRamSeedServiceTests {
    private static final List<String> TABLES = List.of("catalog_product_source", "gpu_power_connector",
            "monitor_spec", "gpu_spec", "cpu_spec", "motherboard_spec", "ram_spec",
            "catalog_reference_price", "catalog_product");
    private static final List<String> PART_NUMBERS = List.of("KF432C16BB/8", "KF432C16BB/16",
            "KF436C18BB/16", "KF436C18BBK2/32", "KF552C40BB-16", "KF560C36BBE-16",
            "KF560C36BBE-32", "KF560C36BBEK2-64");

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
    void addsEightRamsWithPerModuleCapacitiesPreservingTheExistingTwentySix() {
        assertThat(CatalogSeedBatch.fromName("week2-ram")).isEqualTo(RAM);
        var existing = seedExistingSnapshot();
        var expected = loader.load(RAM);
        assertThat(expected.stream().map(request -> request.product().partNumber()).toList())
                .containsExactlyElementsOf(PART_NUMBERS);
        var result = seedService.seed(RAM);
        assertThat(result.created()).isEqualTo(8);
        assertThat(result.skipped()).isZero();
        assertThat(result.items()).hasSize(8).allSatisfy(item -> assertThat(item.created()).isTrue());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        for (int i = 0; i < expected.size(); i++) {
            var request = expected.get(i);
            var stored = entries.findById(result.items().get(i).productId()).orElseThrow();
            assertThat(UUID.fromString(stored.product().id()).toString()).isEqualTo(stored.product().id());
            assertThat(stored.product().type()).isEqualTo(PartType.RAM);
            assertThat(stored.product().manufacturer()).isEqualTo("Kingston");
            assertThat(stored.product().modelName()).isEqualTo(request.product().modelName());
            assertThat(stored.product().partNumber()).isEqualTo(request.product().partNumber());
            assertThat(stored.product().verificationStatus()).isEqualTo(CatalogVerificationStatus.UNVERIFIED);
            assertThat(stored.product().active()).isFalse();
            assertThat(stored.product().referencePrice().status()).isEqualTo(CatalogPriceStatus.UNCONFIRMED);
            assertThat(stored.product().referencePrice().amountKrw()).isNull();
            assertThat(stored.specification()).isEqualTo(request.specification());
            assertThat(stored.sources()).hasSize(3);
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
        assertSelectedManufacturerFacts(expected);
        assertExistingPreserved(existing);
        assertTableCounts(8);
    }

    @Test
    void repeatedSeedPreservesIdsEveryRowReviewedStatesAndConfirmedPrices() {
        var existing = seedExistingSnapshot();
        var first = seedService.seed(RAM);
        for (String id : List.of(first.items().getFirst().productId(), first.items().getLast().productId())) {
            jdbc.update("""
                    UPDATE catalog_product SET is_active = TRUE, verification_status = 'CORE_VERIFIED',
                        updated_at = CURRENT_TIMESTAMP WHERE id = ?
                    """, id);
            jdbc.update("""
                    UPDATE catalog_reference_price SET amount_krw = ?, status = 'CONFIRMED',
                        method = 'MEDIAN_DAILY_6M_V1', period_start = DATE '2026-03-29', period_end = DATE '2026-09-28',
                        observed_day_count = 184, sample_count = 368, price_basis = 'Test fixture: daily KRW observations',
                        evidence_ref = 'test-fixture://catalog-ram-seed/confirmed-price', calculated_at = CURRENT_TIMESTAMP,
                        confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE product_id = ?
                    """, new BigDecimal("98765.43"), id);
        }
        var before = TABLES.stream().map(this::snapshotRows).toList();
        var repeated = seedService.seed(RAM);
        assertThat(repeated.created()).isZero();
        assertThat(repeated.skipped()).isEqualTo(8);
        assertThat(repeated.items()).hasSize(8).allSatisfy(item -> assertThat(item.created()).isFalse());
        assertThat(repeated.items().stream().map(item -> item.productId()).toList())
                .containsExactlyElementsOf(first.items().stream().map(item -> item.productId()).toList());
        for (int i = 0; i < TABLES.size(); i++) {
            assertThat(snapshotRows(TABLES.get(i))).as("Unchanged rows in %s", TABLES.get(i)).isEqualTo(before.get(i));
        }
        for (String id : List.of(first.items().getFirst().productId(), first.items().getLast().productId())) {
            var product = entries.findById(id).orElseThrow().product();
            assertThat(product.active()).isTrue();
            assertThat(product.verificationStatus()).isEqualTo(CatalogVerificationStatus.CORE_VERIFIED);
            assertThat(product.referencePrice().status()).isEqualTo(CatalogPriceStatus.CONFIRMED);
            assertThat(product.referencePrice().amountKrw()).isEqualByComparingTo("98765.43");
        }
        assertExistingPreserved(existing);
        assertTableCounts(8);
    }

    @Test
    void lastRamSourceFailureRollsBackAllEightAdditionsAndKeepsTheExistingBatches() {
        var existing = seedExistingSnapshot();
        String externalId = lastKitRequest().sources().stream()
                .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES)
                .findFirst().orElseThrow().externalId();
        assertThat(UUID.fromString(externalId).toString()).isEqualTo(externalId);
        jdbc.execute("""
                ALTER TABLE catalog_product_source ADD CONSTRAINT ck_test_reject_last_ram_expand_source
                CHECK (source_name <> 'BUILDCORES' OR external_id IS NULL OR external_id <> '%s')
                """.formatted(externalId));
        try {
            Throwable failure = catchThrowable(() -> seedService.seed(RAM));
            assertThat(failure).isNotNull();
            assertThat(NestedExceptionUtils.getMostSpecificCause(failure).getMessage())
                    .containsIgnoringCase("ck_test_reject_last_ram_expand_source");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertExistingPreserved(existing);
            assertTableCounts(0);
        } finally {
            jdbc.execute("ALTER TABLE catalog_product_source DROP CONSTRAINT ck_test_reject_last_ram_expand_source");
        }
    }

    @Test
    void existingRamCapacityConflictIsPreservedAndEarlierSevenNewProductsAreRolledBack() {
        var existing = seedExistingSnapshot();
        String id = entries.create(lastKitRequest()).product().id();
        // 테스트 DB에서만 충돌을 만든다. 이 제품의 실제 모듈당 용량은 32 GiB다.
        assertThat(jdbc.update("UPDATE ram_spec SET module_capacity_bytes = 17179869184 WHERE product_id = ?", id))
                .isEqualTo(1);
        var before = entries.findById(id).orElseThrow();
        assertThat(((Ram) before.specification()).moduleCapacityBytes()).isEqualTo(17179869184L);
        assertThatThrownBy(() -> seedService.seed(RAM)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Catalog seed conflict").hasMessageContaining("64GB (2x32GB)");
        assertThat(entries.findById(id).orElseThrow()).isEqualTo(before);
        assertExistingPreserved(existing);
        assertTableCounts(1);
    }

    @Test
    void startupDoesNotInsertAnyBatchAndOnlyTheExactApprovedNameIsAccepted() {
        assertThat(context.getBeansOfType(CatalogSeedRunner.class)).isEmpty();
        assertAllTablesEmpty();
        for (String invalid : new String[]{null, "", "week2-Ram", " week2-ram", "../week2-ram", "week2-all"}) {
            assertThatThrownBy(() -> CatalogSeedBatch.fromName(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> seedService.seed(null)).isInstanceOf(IllegalArgumentException.class);
        assertAllTablesEmpty();
    }

    private void assertSelectedManufacturerFacts(List<CatalogEntryCreateRequest> requests) {
        assertThat(requests.stream().map(request -> request.specification()).toList()).containsExactly(
                ram("DDR4", 8589934592L, 1, 3200, "1.35", "34"),
                ram("DDR4", 17179869184L, 1, 3200, "1.35", "34"),
                ram("DDR4", 17179869184L, 1, 3600, "1.35", "34"),
                ram("DDR4", 17179869184L, 2, 3600, "1.35", "34"),
                ram("DDR5", 17179869184L, 1, 5200, "1.25", "34.9"),
                ram("DDR5", 17179869184L, 1, 6000, "1.35", "34.9"),
                ram("DDR5", 34359738368L, 1, 6000, "1.35", "34.9"),
                ram("DDR5", 34359738368L, 2, 6000, "1.35", "34.9"));
        var original = requests.get(4).sources().stream()
                .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES).findFirst().orElseThrow();
        assertThat(new BigDecimal(original.rawPayload().get("voltage").toString())).isEqualByComparingTo("1.35");
        for (var request : requests) {
            var manufacturer = request.sources().stream()
                    .filter(source -> source.sourceName() == CatalogSourceName.MANUFACTURER
                            && source.sourceUrl().endsWith(".pdf")).findFirst().orElseThrow();
            boolean ddr4 = ((Ram) request.specification()).memoryType().equals("DDR4");
            assertThat(manufacturer.rawPayload().get("profile_basis")).isEqualTo(ddr4
                    || request.product().partNumber().equals("KF552C40BB-16")
                    ? "XMP_PROFILE_1" : "EXPO_PROFILE_0_AND_XMP_PROFILE_1");
            assertThat(((Number) manufacturer.rawPayload().get("default_jedec_data_rate_mts")).intValue())
                    .isEqualTo(ddr4 ? 2400 : 4800);
            assertThat(manufacturer.rawPayload().get("profile_speed_is_not_guaranteed_in_all_systems")).isEqualTo(true);
        }
        var discontinued = requests.get(5).sources().stream()
                .filter(source -> source.sourceName() == CatalogSourceName.MANUFACTURER
                        && !source.sourceUrl().endsWith(".pdf")).findFirst().orElseThrow();
        assertThat(discontinued.sourceUrl()).contains("/discontinuedmodels?partId=KF560C36BBE-16");
    }

    private static Ram ram(String type, long bytes, int count, int rate, String voltage, String height) {
        return new Ram(type, bytes, count, rate, "DIMM", 288, false, "UNBUFFERED",
                new BigDecimal(voltage), new BigDecimal(height));
    }

    private CatalogEntryCreateRequest lastKitRequest() {
        var last = loader.load(RAM).getLast();
        assertThat(last.product().type()).isEqualTo(PartType.RAM);
        assertThat(last.product().partNumber()).isEqualTo("KF560C36BBEK2-64");
        return last;
    }

    private List<CatalogEntryView> seedExistingSnapshot() {
        return Stream.of(CatalogSeedBatch.INITIAL, CatalogSeedBatch.GPU, CatalogSeedBatch.MONITOR, CatalogSeedBatch.INTEL)
                .flatMap(batch -> seedService.seed(batch).items().stream())
                .map(item -> entries.findById(item.productId()).orElseThrow()).toList();
    }

    private void assertExistingPreserved(List<CatalogEntryView> existing) {
        assertThat(existing).hasSize(26);
        for (var before : existing) assertThat(entries.findById(before.product().id()).orElseThrow()).isEqualTo(before);
    }

    private void assertTableCounts(int newRamCount) {
        assertThat(rowCount("catalog_product")).isEqualTo(26 + newRamCount);
        assertThat(rowCount("catalog_reference_price")).isEqualTo(26 + newRamCount);
        assertThat(rowCount("catalog_product_source")).isEqualTo(59 + 3 * newRamCount);
        assertThat(rowCount("cpu_spec")).isEqualTo(8);
        assertThat(rowCount("motherboard_spec")).isEqualTo(6);
        assertThat(rowCount("ram_spec")).isEqualTo(3 + newRamCount);
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
                    .isEqualTo("jdbc:h2:mem:catalog-ram-seed-test");
        }
    }
}
