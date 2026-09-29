package com.pcupgradelab.catalog.seed;

import com.pcupgradelab.catalog.CatalogEntryCreateRequest;
import com.pcupgradelab.catalog.CatalogEntryService;
import com.pcupgradelab.catalog.CatalogEntryView;
import com.pcupgradelab.catalog.CatalogPriceStatus;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.CatalogSpecification.Monitor;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.pcupgradelab.catalog.seed.CatalogSeedBatch.GPU;
import static com.pcupgradelab.catalog.seed.CatalogSeedBatch.MONITOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 기존 15종을 보존하면서 모니터 묶음의 실제 커밋·재실행·전체 롤백을 검사한다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:catalog-monitor-seed-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogMonitorSeedServiceTests {
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
    void addsThreeMonitorsWithoutChangingTheExistingFifteenProducts() {
        assertThat(CatalogSeedBatch.fromName("week2-monitor")).isEqualTo(MONITOR);
        var existing = seedExistingSnapshot();
        List<CatalogEntryCreateRequest> expected = loader.load(MONITOR);
        assertThat(expected).hasSize(3);
        assertThat(expected.stream().map(request -> request.product().modelName()).toList())
                .containsExactly("24GN650-B", "27GP850-B", "27UL500-W");
        var result = seedService.seed(MONITOR);
        assertThat(result.created()).isEqualTo(3);
        assertThat(result.skipped()).isZero();
        assertThat(result.items()).hasSize(3).allSatisfy(item -> assertThat(item.created()).isTrue());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        for (int i = 0; i < result.items().size(); i++) {
            var item = result.items().get(i);
            var request = expected.get(i);
            var stored = entries.findById(item.productId()).orElseThrow();
            assertThat(UUID.fromString(item.productId()).toString()).isEqualTo(item.productId());
            assertThat(stored.product().type()).isEqualTo(PartType.MONITOR);
            assertThat(stored.product().manufacturer()).isEqualTo("LG");
            assertThat(stored.product().modelName()).isEqualTo(request.product().modelName());
            assertThat(stored.product().partNumber()).isEqualTo(request.product().modelName());
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
                    assertThat(actual.retrievedAt()).isEqualTo(source.retrievedAt());
                });
            }
        }
        assertSelectedManufacturerFacts(expected);
        assertExistingPreserved(existing);
        assertTableCounts(3);
    }

    @Test
    void repeatedMonitorSeedSkipsThreeAndKeepsEveryIdAndRowCount() {
        var existing = seedExistingSnapshot();
        var first = seedService.seed(MONITOR);
        var repeated = seedService.seed(MONITOR);
        assertThat(repeated.created()).isZero();
        assertThat(repeated.skipped()).isEqualTo(3);
        assertThat(repeated.items()).hasSize(3).allSatisfy(item -> assertThat(item.created()).isFalse());
        assertThat(repeated.items().stream().map(item -> item.productId()).toList())
                .containsExactlyElementsOf(first.items().stream().map(item -> item.productId()).toList());
        assertExistingPreserved(existing);
        assertTableCounts(3);
    }

    @Test
    void repeatedMonitorSeedPreservesConfirmedPriceAndReviewedProductState() {
        var existing = seedExistingSnapshot();
        String productId = seedService.seed(MONITOR).items().getFirst().productId();
        jdbc.update("""
                UPDATE catalog_product SET is_active = TRUE, verification_status = 'CORE_VERIFIED',
                    updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, productId);
        jdbc.update("""
                UPDATE catalog_reference_price SET amount_krw = ?, status = 'CONFIRMED',
                    method = 'MEDIAN_DAILY_6M_V1', period_start = DATE '2026-03-29', period_end = DATE '2026-09-28',
                    observed_day_count = 184, sample_count = 368, price_basis = 'Test fixture: daily KRW observations',
                    evidence_ref = 'test-fixture://catalog-monitor-seed/confirmed-price', calculated_at = CURRENT_TIMESTAMP,
                    confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE product_id = ?
                """, new BigDecimal("98765.43"), productId);
        var priceBefore = jdbc.queryForMap("SELECT * FROM catalog_reference_price WHERE product_id = ?", productId);
        var productBefore = jdbc.queryForMap("SELECT * FROM catalog_product WHERE id = ?", productId);

        var result = seedService.seed(MONITOR);
        assertThat(result.created()).isZero();
        assertThat(result.skipped()).isEqualTo(3);
        var stored = entries.findById(productId).orElseThrow().product();
        assertThat(stored.active()).isTrue();
        assertThat(stored.verificationStatus()).isEqualTo(CatalogVerificationStatus.CORE_VERIFIED);
        assertThat(stored.referencePrice().status()).isEqualTo(CatalogPriceStatus.CONFIRMED);
        assertThat(stored.referencePrice().amountKrw()).isEqualByComparingTo("98765.43");
        var priceAfter = jdbc.queryForMap("SELECT * FROM catalog_reference_price WHERE product_id = ?", productId);
        var productAfter = jdbc.queryForMap("SELECT * FROM catalog_product WHERE id = ?", productId);
        assertThat(priceAfter).isEqualTo(priceBefore);
        assertThat(productAfter).isEqualTo(productBefore);
        assertExistingPreserved(existing);
        assertTableCounts(3);
    }

    @Test
    void lastMonitorSourceFailureRollsBackAllNewMonitorsAndPreservesTheExistingBatches() {
        var existing = seedExistingSnapshot();
        var last = last4kRequest();
        String externalId = last.sources().stream()
                .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES)
                .findFirst().orElseThrow().externalId();
        // 검증한 UUID만 DDL에 넣고 마지막 모니터 출처 외의 모든 INSERT는 허용한다.
        assertThat(UUID.fromString(externalId).toString()).isEqualTo(externalId);
        jdbc.execute("""
                ALTER TABLE catalog_product_source ADD CONSTRAINT ck_test_reject_last_monitor_source
                CHECK (source_name <> 'BUILDCORES' OR external_id IS NULL OR external_id <> '%s')
                """.formatted(externalId));
        try {
            Throwable failure = catchThrowable(() -> seedService.seed(MONITOR));
            assertThat(failure).isNotNull();
            assertThat(NestedExceptionUtils.getMostSpecificCause(failure).getMessage())
                    .containsIgnoringCase("ck_test_reject_last_monitor_source");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertExistingPreserved(existing);
            assertTableCounts(0);
        } finally {
            jdbc.execute("ALTER TABLE catalog_product_source DROP CONSTRAINT ck_test_reject_last_monitor_source");
        }
    }

    @Test
    void existingRefreshConflictPreservesTheExistingMonitorAndRollsBackTwoNewMonitors() {
        var existing = seedExistingSnapshot();
        String existingId = entries.create(last4kRequest()).product().id();
        // 테스트 DB에서만 제원 충돌을 만든다. 실제 제품의 공표 값은 60 Hz다.
        int changed = jdbc.update("UPDATE monitor_spec SET native_standard_refresh_hz = 75 WHERE product_id = ?", existingId);
        assertThat(changed).isEqualTo(1);
        var before = entries.findById(existingId).orElseThrow();
        assertThat(((Monitor) before.specification()).nativeStandardRefreshHz()).isEqualByComparingTo("75");

        assertThatThrownBy(() -> seedService.seed(MONITOR)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Catalog seed conflict").hasMessageContaining("27UL500-W");
        var after = entries.findById(existingId).orElseThrow();
        assertThat(after).isEqualTo(before);
        List<String> monitorIds = jdbc.queryForList("SELECT product_id FROM monitor_spec", String.class);
        assertThat(monitorIds).containsExactly(existingId);
        assertExistingPreserved(existing);
        assertTableCounts(1);
    }

    @Test
    void runnerIsDisabledByDefaultAndStartupDoesNotInsertAnyBatch() {
        assertThat(context.getBeansOfType(CatalogSeedRunner.class)).isEmpty();
        assertAllTablesEmpty();
    }

    private void assertSelectedManufacturerFacts(List<CatalogEntryCreateRequest> requests) {
        assertThat(requests.stream().map(request -> request.specification()).toList()).containsExactly(
                new Monitor(new BigDecimal("23.8"), 1920, 1080, "IPS", new BigDecimal("144"),
                        null, null, null, null, null),
                new Monitor(new BigDecimal("27"), 2560, 1440, "IPS", new BigDecimal("165"),
                        true, new BigDecimal("180"), null, null, null),
                new Monitor(new BigDecimal("27"), 3840, 2160, "IPS", new BigDecimal("60"),
                        null, null, null, null, null));
        var fhdSource = requests.getFirst().sources().stream()
                .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES).findFirst().orElseThrow();
        Number originalDiagonal = (Number) fhdSource.rawPayload().get("screen_size");
        assertThat(originalDiagonal.intValue()).isEqualTo(24);

        // 제조사 typical 값은 원문 근거에만 보존하고, 측정 조건을 모르는 계산용 값은 채우지 않는다.
        List<Integer> publishedTypicalWatts = List.of(32, 48, 36);
        for (int i = 0; i < requests.size(); i++) {
            var manufacturer = requests.get(i).sources().stream()
                    .filter(source -> source.sourceName() == CatalogSourceName.MANUFACTURER).findFirst().orElseThrow();
            Map<?, ?> power = (Map<?, ?>) manufacturer.rawPayload().get("published_power");
            Number typical = (Number) power.get("manufacturer_typical_w");
            assertThat(typical.intValue()).isEqualTo(publishedTypicalWatts.get(i));
            assertThat(power.get("measurement_conditions")).isNull();
        }
    }

    private CatalogEntryCreateRequest last4kRequest() {
        var last = loader.load(MONITOR).getLast();
        assertThat(last.product().type()).isEqualTo(PartType.MONITOR);
        assertThat(last.product().modelName()).isEqualTo("27UL500-W");
        return last;
    }

    private List<CatalogEntryView> seedExistingSnapshot() {
        var initial = seedService.seed();
        var gpus = seedService.seed(GPU);
        assertThat(initial.created()).isEqualTo(9);
        assertThat(initial.skipped()).isZero();
        assertThat(gpus.created()).isEqualTo(6);
        assertThat(gpus.skipped()).isZero();
        return Stream.concat(initial.items().stream(), gpus.items().stream())
                .map(item -> entries.findById(item.productId()).orElseThrow()).toList();
    }

    private void assertExistingPreserved(List<CatalogEntryView> existing) {
        assertThat(existing).hasSize(15);
        for (var before : existing) {
            var after = entries.findById(before.product().id()).orElseThrow();
            assertThat(after).isEqualTo(before);
        }
    }

    private void assertTableCounts(int monitorCount) {
        assertThat(rowCount("catalog_product")).isEqualTo(15 + monitorCount);
        assertThat(rowCount("catalog_reference_price")).isEqualTo(15 + monitorCount);
        assertThat(rowCount("catalog_product_source")).isEqualTo(33 + 2 * monitorCount);
        assertThat(rowCount("cpu_spec")).isEqualTo(4);
        assertThat(rowCount("motherboard_spec")).isEqualTo(2);
        assertThat(rowCount("ram_spec")).isEqualTo(3);
        assertThat(rowCount("gpu_spec")).isEqualTo(6);
        assertThat(rowCount("gpu_power_connector")).isEqualTo(6);
        assertThat(rowCount("monitor_spec")).isEqualTo(monitorCount);
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
                    .isEqualTo("jdbc:h2:mem:catalog-monitor-seed-test");
        }
    }
}
