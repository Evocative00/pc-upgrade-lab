package com.pcupgradelab.catalog.seed;

import com.pcupgradelab.catalog.CatalogEntryCreateRequest;
import com.pcupgradelab.catalog.CatalogEntryService;
import com.pcupgradelab.catalog.CatalogPriceStatus;
import com.pcupgradelab.catalog.CatalogSourceName;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 전용 H2 DB에서 시드 전체의 실제 커밋·재실행·롤백을 검사한다. 테스트 트랜잭션은 사용하지 않는다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:catalog-seed-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogSeedServiceTests {
    private static final List<String> TABLES = List.of("catalog_product_source", "cpu_spec",
            "motherboard_spec", "ram_spec", "catalog_reference_price", "catalog_product");

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
    void cleanCommittedSeedFixtures() throws SQLException {
        assertDedicatedDatabase();
        for (String table : TABLES) jdbc.update("DELETE FROM " + table);
    }

    @Test
    void commitsNineProductsWithSpecificationsSourcesAndUnconfirmedPrices() {
        List<CatalogEntryCreateRequest> expected = loader.load();
        assertThat(expected).hasSize(9);
        var result = seedService.seed();
        assertThat(result.created()).isEqualTo(9);
        assertThat(result.skipped()).isZero();
        assertThat(result.items()).hasSize(9).allSatisfy(item -> assertThat(item.created()).isTrue());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        for (var item : result.items()) {
            var request = expected.stream().filter(value -> value.product().modelName().equals(item.modelName()))
                    .findFirst().orElseThrow();
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
            assertThat(stored.sources()).hasSize(request.sources().size()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(stored.sources().stream().map(source -> source.sourceName()).toList())
                    .contains(CatalogSourceName.BUILDCORES, CatalogSourceName.MANUFACTURER);
            for (var source : request.sources()) {
                assertThat(stored.sources()).anySatisfy(actual -> {
                    assertThat(actual.sourceName()).isEqualTo(source.sourceName());
                    assertThat(actual.externalId()).isEqualTo(source.externalId());
                    assertThat(actual.sourceRevision()).isEqualTo(source.sourceRevision());
                    assertThat(actual.sourceUrl()).isEqualTo(source.sourceUrl());
                });
            }
        }
        assertSeedTableCounts(expected);
    }

    @Test
    void repeatedSeedSkipsAllProductsWithoutChangingTheirIdsOrRowCounts() {
        var first = seedService.seed();
        var repeated = seedService.seed();
        assertThat(repeated.created()).isZero();
        assertThat(repeated.skipped()).isEqualTo(9);
        assertThat(repeated.items()).hasSize(9).allSatisfy(item -> assertThat(item.created()).isFalse());
        List<String> firstIds = first.items().stream().map(item -> item.productId()).toList();
        List<String> repeatedIds = repeated.items().stream().map(item -> item.productId()).toList();
        assertThat(repeatedIds).containsExactlyInAnyOrderElementsOf(firstIds);
        assertSeedTableCounts(loader.load());
    }

    @Test
    void repeatedSeedPreservesConfirmedPriceAndReviewedProductState() {
        String productId = seedService.seed().items().getFirst().productId();
        jdbc.update("""
                UPDATE catalog_product SET is_active = TRUE, verification_status = 'CORE_VERIFIED',
                    updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, productId);
        jdbc.update("""
                UPDATE catalog_reference_price SET amount_krw = ?, status = 'CONFIRMED',
                    method = 'MEDIAN_DAILY_6M_V1', period_start = DATE '2026-03-29', period_end = DATE '2026-09-28',
                    observed_day_count = 184, sample_count = 368, price_basis = 'Test fixture: daily KRW observations',
                    evidence_ref = 'test-fixture://catalog-seed/confirmed-price', calculated_at = CURRENT_TIMESTAMP,
                    confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE product_id = ?
                """, new BigDecimal("98765.43"), productId);
        var priceBefore = jdbc.queryForMap("SELECT * FROM catalog_reference_price WHERE product_id = ?", productId);
        var productBefore = jdbc.queryForMap("SELECT * FROM catalog_product WHERE id = ?", productId);

        var result = seedService.seed();
        assertThat(result.created()).isZero();
        assertThat(result.skipped()).isEqualTo(9);
        var stored = entries.findById(productId).orElseThrow().product();
        assertThat(stored.active()).isTrue();
        assertThat(stored.verificationStatus()).isEqualTo(CatalogVerificationStatus.CORE_VERIFIED);
        assertThat(stored.referencePrice().status()).isEqualTo(CatalogPriceStatus.CONFIRMED);
        assertThat(stored.referencePrice().amountKrw()).isEqualByComparingTo("98765.43");
        var priceAfter = jdbc.queryForMap("SELECT * FROM catalog_reference_price WHERE product_id = ?", productId);
        var productAfter = jdbc.queryForMap("SELECT * FROM catalog_product WHERE id = ?", productId);
        assertThat(priceAfter).isEqualTo(priceBefore);
        assertThat(productAfter).isEqualTo(productBefore);
    }

    @Test
    void lastRamSourceFailureRollsBackEveryPreviouslyInsertedSeedRow() {
        var last = loader.load().getLast();
        assertThat(last.product().type()).isEqualTo(PartType.RAM);
        String externalId = last.sources().stream()
                .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES)
                .findFirst().orElseThrow().externalId();
        // DDL에는 검증한 UUID만 넣는다. 마지막 RAM 이전의 제품·가격·제원·출처 INSERT는 허용한다.
        assertThat(UUID.fromString(externalId).toString()).isEqualTo(externalId);
        jdbc.execute("""
                ALTER TABLE catalog_product_source ADD CONSTRAINT ck_test_reject_last_seed_source
                CHECK (source_name <> 'BUILDCORES' OR external_id IS NULL OR external_id <> '%s')
                """.formatted(externalId));
        try {
            Throwable failure = catchThrowable(seedService::seed);
            assertThat(failure).isNotNull();
            assertThat(NestedExceptionUtils.getMostSpecificCause(failure).getMessage())
                    .containsIgnoringCase("ck_test_reject_last_seed_source");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertAllTablesEmpty();
        } finally {
            jdbc.execute("ALTER TABLE catalog_product_source DROP CONSTRAINT ck_test_reject_last_seed_source");
        }
    }

    @Test
    void conflictingExistingModelIsPreservedAndEarlierNewSeedRowsAreRolledBack() {
        CatalogEntryCreateRequest last = loader.load().getLast();
        assertThat(last.product().type()).isEqualTo(PartType.RAM);
        String existingId = entries.create(last).product().id();
        String changedModel = "Changed existing seed product";
        jdbc.update("UPDATE catalog_product SET model_name = ? WHERE id = ?", changedModel, existingId);

        assertThatThrownBy(seedService::seed).isInstanceOf(IllegalStateException.class);
        List<String> remainingIds = jdbc.queryForList("SELECT id FROM catalog_product", String.class);
        assertThat(remainingIds).containsExactly(existingId);
        var remaining = entries.findById(existingId).orElseThrow();
        assertThat(remaining.product().modelName()).isEqualTo(changedModel);
        assertThat(remaining.specification()).isEqualTo(last.specification());
        assertThat(remaining.sources()).hasSize(last.sources().size());
        assertThat(rowCount("catalog_reference_price")).isEqualTo(1);
        assertThat(rowCount("ram_spec")).isEqualTo(1);
        assertThat(rowCount("catalog_product_source")).isEqualTo(last.sources().size());
        assertThat(rowCount("cpu_spec")).isZero();
        assertThat(rowCount("motherboard_spec")).isZero();
    }

    @Test
    void runnerIsDisabledByDefaultAndStartupDoesNotInsertProducts() {
        assertThat(context.getBeansOfType(CatalogSeedRunner.class)).isEmpty();
        assertAllTablesEmpty();
    }

    private void assertSeedTableCounts(List<CatalogEntryCreateRequest> expected) {
        assertThat(rowCount("catalog_product")).isEqualTo(9);
        assertThat(rowCount("catalog_reference_price")).isEqualTo(9);
        assertThat(rowCount("cpu_spec")).isEqualTo(4);
        assertThat(rowCount("motherboard_spec")).isEqualTo(2);
        assertThat(rowCount("ram_spec")).isEqualTo(3);
        int expectedSourceCount = expected.stream().mapToInt(entry -> entry.sources().size()).sum();
        assertThat(expectedSourceCount).isEqualTo(21);
        assertThat(rowCount("catalog_product_source")).isEqualTo(expectedSourceCount);
    }

    private int rowCount(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertAllTablesEmpty() {
        for (String table : TABLES) assertThat(rowCount(table)).as("Rows in %s", table).isZero();
    }

    private void assertDedicatedDatabase() throws SQLException {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL().split(";", 2)[0]).isEqualTo("jdbc:h2:mem:catalog-seed-test");
        }
    }
}
