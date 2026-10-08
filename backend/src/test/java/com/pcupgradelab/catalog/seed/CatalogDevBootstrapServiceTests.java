package com.pcupgradelab.catalog.seed;

import com.pcupgradelab.catalog.CatalogProductSourceRepository;
import com.pcupgradelab.catalog.CatalogQueryService;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.memory.CpuMemorySeedLoader;
import com.pcupgradelab.catalog.price.CatalogPriceImportBatch;
import com.pcupgradelab.catalog.price.CatalogPriceImportService;
import com.pcupgradelab.catalog.price.CatalogPriceSnapshotLoader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 전용 H2에만 실제 커밋·롤백한다. 팀원의 MySQL 초기화 또는 외부 가격 수집 검증은 아니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:catalog-dev-bootstrap-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "catalog.seed.enabled=false", "catalog.cpu-memory.seed.enabled=false",
        "catalog.motherboard-cpu.seed.enabled=false", "catalog.price-import.enabled=false"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogDevBootstrapServiceTests {
    private static final List<String> TABLES = List.of("catalog_price_observation", "catalog_price_mapping",
            "pc_part", "pc_configuration", "motherboard_cpu_support", "motherboard_cpu_support_profile",
            "cpu_memory_type_support", "cpu_memory_support", "catalog_product_source", "gpu_power_connector",
            "monitor_spec", "gpu_spec", "cpu_spec", "motherboard_spec", "ram_spec", "catalog_reference_price",
            "catalog_product", "social_accounts", "users");
    @Autowired CatalogDevBootstrapService bootstrap;
    @Autowired CatalogSeedService products;
    @Autowired CatalogPriceSnapshotLoader snapshots;
    @Autowired CatalogPriceImportService prices;
    @Autowired CatalogProductSourceRepository sources;
    @Autowired CatalogQueryService catalog;
    @Autowired CpuMemorySeedLoader memoryLoader;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void requireDedicatedEmptyH2WithoutAnOuterTestTransaction() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:catalog-dev-bootstrap-test");
        }
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        for (String table : TABLES) assertThat(count(table)).as(table).isZero();
    }

    @AfterEach
    void removeOnlyDedicatedH2Fixtures() {
        for (String table : TABLES) jdbc.update("DELETE FROM " + table);
    }

    @Test
    void bootstrapsAnEmptyCatalogAndReplaysWithoutChangingNewerPricesReviewedDataOrSavedPc() {
        var snapshot = snapshots.load();
        var created = bootstrap.apply(snapshot);
        assertThat(created.createdProducts()).isEqualTo(300);
        assertThat(created.prices()).isEqualTo(new CatalogPriceImportService.Result(false, 72, 72, 72, 0));
        assertCounts300And72();

        var sample = snapshot.items().getFirst();
        String productId = productId(sample.product().externalId());
        preserveExistingUserPcAndReviewedProduct(productId);
        var newer = new CatalogPriceImportBatch.Price(sample.price().amountKrw() + 17,
                sample.price().observedAt().plusSeconds(1), sample.price().evidenceUrl(), "NEW", true);
        prices.apply(new CatalogPriceImportBatch(1, List.of(new CatalogPriceImportBatch.Item(sample.product(), sample.offer(), newer))));
        var before = databaseSnapshot();

        var repeated = bootstrap.apply(snapshot);
        assertThat(repeated.createdProducts()).isZero();
        assertThat(repeated.prices()).isEqualTo(new CatalogPriceImportService.Result(false, 72, 0, 0, 72));
        assertThat(prices.preview(snapshot)).isEqualTo(new CatalogPriceImportService.Result(true, 72, 0, 0, 72));
        assertThat(databaseSnapshot()).isEqualTo(before);
        var currentPrice = catalog.findById(productId).product().currentPrice();
        assertThat(currentPrice.amountKrw()).isEqualTo(newer.amountKrw());
        assertThat(currentPrice.observedAt()).isEqualTo(newer.observedAt());
        assertThat(count("catalog_price_observation")).isEqualTo(73);
    }

    @Test
    void completesAnExistingInitialBatchWithoutDuplicatingItsProducts() {
        products.seed(CatalogSeedBatch.INITIAL);
        var result = bootstrap.apply(snapshots.load());
        assertThat(result.createdProducts()).isEqualTo(291);
        assertThat(result.prices().createdObservations()).isEqualTo(72);
        assertCounts300And72();
    }

    @Test
    void invalidFinalPriceRollsBackAllNewProductsSupportProfilesAndPrices() {
        var snapshot = snapshots.load();
        var items = new ArrayList<>(snapshot.items());
        var last = items.getLast();
        var unknown = new CatalogPriceImportBatch.Product(CatalogSourceName.BUILDCORES, "missing-reviewed-product",
                last.product().manufacturer(), last.product().modelName(), last.product().partNumber());
        items.set(items.size() - 1, new CatalogPriceImportBatch.Item(unknown, last.offer(), last.price()));
        assertThatThrownBy(() -> bootstrap.apply(new CatalogPriceImportBatch(1, items)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown catalog source identity");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        for (String table : TABLES) assertThat(count(table)).as(table).isZero();
    }

    @Test
    void anExisting300ProductCatalogWithMissingLatestSupportIsRejectedWithoutRepair() {
        bootstrap.apply(snapshots.load());
        var newerCpu = memoryLoader.load(CpuMemorySeedLoader.EXPANSION_300).getFirst();
        String productId = productId(newerCpu.externalId());
        jdbc.update("DELETE FROM cpu_memory_type_support WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM cpu_memory_support WHERE product_id = ?", productId);
        var before = databaseSnapshot();
        assertThatThrownBy(() -> bootstrap.apply(snapshots.load())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approved memory support is missing");
        assertThat(databaseSnapshot()).isEqualTo(before);
        assertThat(count("catalog_product")).isEqualTo(300);
        assertThat(count("cpu_memory_support")).isEqualTo(69);
    }

    @Test
    void thePriceOnlyOperationRefusesAMissingCatalogWithoutSeedingIt() {
        assertThatThrownBy(() -> prices.preview(snapshots.load())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown catalog source identity");
        assertThatThrownBy(() -> prices.apply(snapshots.load())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown catalog source identity");
        for (String table : TABLES) assertThat(count(table)).as(table).isZero();
    }

    private void assertCounts300And72() {
        assertThat(count("catalog_product")).isEqualTo(300);
        assertThat(count("catalog_reference_price")).isEqualTo(300);
        assertThat(count("cpu_memory_support")).isEqualTo(70);
        assertThat(count("motherboard_cpu_support_profile")).isEqualTo(70);
        assertThat(count("motherboard_cpu_support")).isEqualTo(1807);
        assertThat(count("catalog_price_mapping")).isEqualTo(72);
        assertThat(count("catalog_price_observation")).isEqualTo(72);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT product_id) FROM catalog_price_observation", Integer.class)).isEqualTo(72);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product p WHERE NOT EXISTS "
                + "(SELECT 1 FROM catalog_price_observation o WHERE o.product_id = p.id)", Integer.class)).isEqualTo(228);
    }

    private String productId(String externalId) {
        return sources.findBySourceNameAndExternalId(CatalogSourceName.BUILDCORES, externalId).orElseThrow().getProduct().getId();
    }

    private void preserveExistingUserPcAndReviewedProduct(String productId) {
        jdbc.update("UPDATE catalog_product SET verification_status = 'CORE_VERIFIED', is_active = TRUE WHERE id = ?", productId);
        jdbc.update("""
                UPDATE catalog_reference_price SET amount_krw = ?, status = 'CONFIRMED', method = 'MEDIAN_DAILY_6M_V1',
                  period_start = DATE '2026-03-29', period_end = DATE '2026-09-28', observed_day_count = 184, sample_count = 368,
                  price_basis = 'Bootstrap test', evidence_ref = 'test-fixture://bootstrap/price', calculated_at = CURRENT_TIMESTAMP,
                  confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE product_id = ?
                """, new BigDecimal("123456.78"), productId);
        jdbc.update("INSERT INTO users (id, name, created_at, updated_at) VALUES (1, 'Existing fixture user', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        jdbc.update("""
                INSERT INTO pc_configuration (id, name, name_normalized, user_id, created_at, updated_at)
                VALUES (1, 'Existing fixture PC', 'existing fixture pc', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        jdbc.update("""
                INSERT INTO pc_part (pc_id, type, display_name, raw_name, quantity, source, catalog_product_id, match_status, specs)
                VALUES (1, 'CPU', 'User CPU', 'Collected raw CPU', 1, 'AUTO', ?, 'MATCHED', '{}')
                """, productId);
    }

    private Map<String, List<String>> databaseSnapshot() {
        var snapshot = new LinkedHashMap<String, List<String>>();
        for (String table : TABLES) {
            var rows = jdbc.queryForList("SELECT * FROM " + table).stream().map(row -> row.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).map(entry -> entry.getKey() + "=" + value(entry.getValue()))
                    .collect(Collectors.joining(";"))).sorted().toList();
            snapshot.put(table, rows);
        }
        return snapshot;
    }

    private String value(Object value) {
        return value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : String.valueOf(value);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
