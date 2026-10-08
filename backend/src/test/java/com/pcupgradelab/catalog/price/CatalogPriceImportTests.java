package com.pcupgradelab.catalog.price;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.pc.PartType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** 전용 H2에 서비스가 실제 커밋/롤백한다. 실제 MySQL 또는 사이트 가격 수집 검증은 아니다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:catalog-price-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogPriceImportTests {
    private static final Instant REVIEWED = Instant.parse("2026-01-01T01:00:00Z");
    private static final Instant OBSERVED = Instant.parse("2026-01-01T02:00:00Z");
    @Autowired CatalogEntryService entries;
    @Autowired CatalogProductService products;
    @Autowired CatalogQueryService query;
    @Autowired CatalogPriceImportService service;
    @Autowired CatalogPriceImportLoader loader;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void verifyDedicatedDatabase() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:catalog-price-test");
        }
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(count("catalog_product")).isZero();
    }

    @AfterEach
    void cleanup() {
        for (String table : List.of("catalog_price_observation", "catalog_price_mapping", "catalog_product_source",
                "cpu_spec", "ram_spec", "catalog_reference_price", "catalog_product")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void previewNeverWritesAndApplyIsIdempotentWithoutChangingReferencePriceOrVerification() {
        var identity = fixture(PartType.CPU, "A");
        var batch = batch(item(identity, "offer-a", 150000, OBSERVED));
        assertThat(service.preview(batch)).isEqualTo(new CatalogPriceImportService.Result(true, 1, 1, 1, 0));
        assertThat(count("catalog_price_mapping")).isZero();
        assertThat(count("catalog_price_observation")).isZero();
        assertThat(service.apply(batch)).isEqualTo(new CatalogPriceImportService.Result(false, 1, 1, 1, 0));
        assertThat(service.apply(batch)).isEqualTo(new CatalogPriceImportService.Result(false, 1, 0, 0, 1));
        assertThat(count("catalog_price_mapping")).isEqualTo(1);
        assertThat(count("catalog_price_observation")).isEqualTo(1);
        var product = query.search(PartType.CPU, "Model A", 0, 20).items().getFirst();
        assertThat(product.referencePrice().status()).isEqualTo(CatalogPriceStatus.UNCONFIRMED);
        assertThat(product.referencePrice().amountKrw()).isNull();
        assertThat(product.verificationStatus()).isEqualTo(CatalogVerificationStatus.UNVERIFIED);
        assertThat(product.active()).isFalse();
        assertThat(product.currentPrice().amountKrw()).isEqualTo(150000);
    }

    @Test
    void mismatchedReviewedSkuAndUnknownCatalogIdentityRefuseTheWholeBatch() {
        var identity = fixture(PartType.CPU, "A");
        var wrongModel = new CatalogPriceImportBatch.Product(CatalogSourceName.BUILDCORES, identity.externalId(),
                identity.manufacturer(), "Different model", identity.partNumber());
        var wrongSku = new CatalogPriceImportBatch.Product(CatalogSourceName.BUILDCORES, identity.externalId(),
                identity.manufacturer(), identity.modelName(), "Wrong SKU");
        var unknown = new CatalogPriceImportBatch.Product(CatalogSourceName.BUILDCORES, UUID.randomUUID().toString(),
                identity.manufacturer(), identity.modelName(), identity.partNumber());
        for (var invalid : List.of(wrongModel, wrongSku, unknown)) {
            assertThatThrownBy(() -> service.apply(batch(item(identity, "valid", 100, OBSERVED),
                    item(invalid, "invalid", 200, OBSERVED)))).isInstanceOf(IllegalArgumentException.class);
            assertThat(count("catalog_price_mapping")).isZero();
            assertThat(count("catalog_price_observation")).isZero();
        }
    }

    @Test
    void aProviderIdCannotMapToTwoProductsInOneFileOrAcrossImports() {
        var a = fixture(PartType.CPU, "A");
        var b = fixture(PartType.CPU, "B");
        assertThatThrownBy(() -> service.apply(batch(item(a, "shared", 100, OBSERVED),
                item(b, "shared", 200, OBSERVED)))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another product");
        assertThat(count("catalog_price_mapping")).isZero();
        service.apply(batch(item(a, "shared", 100, OBSERVED)));
        assertThatThrownBy(() -> service.apply(batch(item(b, "shared", 200, OBSERVED))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("catalog_price_mapping")).isEqualTo(1);
        assertThat(count("catalog_price_observation")).isEqualTo(1);
    }

    @Test
    void ramPricesMustCoverExactlyTheCatalogKitAndProductUnitIsRejected() {
        var memory = fixture(PartType.RAM, "Kit");
        for (var offer : List.of(offer("ram", CatalogPriceImportBatch.SaleUnit.PRODUCT, null),
                offer("ram", CatalogPriceImportBatch.SaleUnit.RAM_KIT, 1))) {
            assertThatThrownBy(() -> service.apply(batch(new CatalogPriceImportBatch.Item(memory, offer,
                    price(100, OBSERVED))))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("moduleCount");
        }
        var valid = new CatalogPriceImportBatch.Item(memory,
                offer("ram", CatalogPriceImportBatch.SaleUnit.RAM_KIT, 2), price(100, OBSERVED));
        service.apply(batch(valid));
        assertThat(query.search(PartType.RAM, "", 0, 20).items().getFirst().currentPrice().amountKrw()).isEqualTo(100);
    }

    @Test
    void observationsAppendAndEqualTimestampUsesDeterministicIdForListAndDetail() {
        var identity = fixture(PartType.CPU, "A");
        var missing = fixture(PartType.CPU, "NoPrice");
        service.apply(batch(item(identity, "older", 900, OBSERVED.minusSeconds(1))));
        service.apply(batch(item(identity, "first-at-time", 800, OBSERVED),
                item(identity, "second-at-time", 700, OBSERVED)));
        var list = query.search(PartType.CPU, "Model A", 0, 20).items().getFirst();
        var expected = new CatalogProductView.CurrentPrice(700, "DANAWA", "https://example.com/second-at-time", OBSERVED);
        assertThat(list.currentPrice()).isEqualTo(expected);
        assertThat(query.findById(list.id()).product().currentPrice()).isEqualTo(expected);
        assertThat(products.findById(list.id()).orElseThrow().currentPrice()).isEqualTo(expected);
        assertThat(query.search(PartType.CPU, missing.modelName(), 0, 20).items().getFirst().currentPrice()).isNull();
        assertThat(count("catalog_price_observation")).isEqualTo(3);
    }

    @Test
    void sameMappingAndTimeIsIdempotentButConflictingValuesRefuseAnEntireBatch() {
        var identity = fixture(PartType.CPU, "A");
        var original = item(identity, "same", 100, OBSERVED);
        assertThat(service.apply(batch(original, original))).isEqualTo(
                new CatalogPriceImportService.Result(false, 2, 1, 1, 1));
        assertThatThrownBy(() -> service.apply(batch(item(identity, "new", 200, OBSERVED),
                item(identity, "same", 101, OBSERVED)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("catalog_price_mapping")).isEqualTo(1);
        assertThat(count("catalog_price_observation")).isEqualTo(1);
    }

    @Test
    void finalDatabaseInsertFailureRollsBackMappingsAndAllObservations() {
        var identity = fixture(PartType.CPU, "A");
        jdbc.execute("ALTER TABLE catalog_price_observation ADD CONSTRAINT ck_test_reject_second_price CHECK (amount_krw <> 200)");
        try {
            assertThatThrownBy(() -> service.apply(batch(item(identity, "first", 100, OBSERVED),
                    item(identity, "second", 200, OBSERVED)))).isInstanceOf(RuntimeException.class);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(count("catalog_price_mapping")).isZero();
            assertThat(count("catalog_price_observation")).isZero();
        } finally {
            jdbc.execute("ALTER TABLE catalog_price_observation DROP CONSTRAINT ck_test_reject_second_price");
        }
    }

    @Test
    void integerNewStockAndTimestampContractIsStrict() {
        assertThatThrownBy(() -> price(0, OBSERVED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> price(-1, OBSERVED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> price(1000000000000L, OBSERVED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> price(100, Instant.now().plusSeconds(600))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogPriceImportBatch.Price(100L, OBSERVED,
                "https://example.com/evidence", "USED", true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogPriceImportBatch.Price(100L, OBSERVED,
                "https://example.com/evidence", "NEW", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogPriceImportBatch.Price(100L, OBSERVED,
                "https://user:password@example.com/evidence", "NEW", true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loaderRejectsMalformedDuplicateUnknownFieldsAndFractionalPrice() {
        String valid = """
                {"schemaVersion":1,"items":[{
                  "product":{"sourceName":"BUILDCORES","externalId":"123","manufacturer":"Maker","modelName":"Model A"},
                  "offer":{"sourceName":"DANAWA","externalId":"offer","sourceUrl":"https://example.com/offer",
                    "saleSku":"SKU-A","saleUnit":"PRODUCT","verifiedAt":"2026-01-01T01:00:00Z"},
                  "price":{"amountKrw":100,"observedAt":"2026-01-01T02:00:00Z",
                    "evidenceUrl":"https://example.com/offer","condition":"NEW","inStock":true}
                }]}
                """;
        assertThat(loader.read(valid.getBytes(StandardCharsets.UTF_8)).items()).hasSize(1);
        for (String invalid : List.of(valid.replace("\"amountKrw\":100", "\"amountKrw\":100.5"),
                valid.replace("\"amountKrw\":100", "\"amountKrw\":\"100\""),
                valid.replace("\"inStock\":true", "\"inStock\":\"true\""),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"shippingFee\":0"),
                valid + "{}", "[]")) {
            assertThatThrownBy(() -> loader.read(invalid.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void databaseConstraintsRejectWrongProductLinkAndNonPositivePrices() {
        var a = fixture(PartType.CPU, "A");
        var b = fixture(PartType.CPU, "B");
        service.apply(batch(item(a, "a", 100, OBSERVED)));
        Long mappingId = jdbc.queryForObject("SELECT id FROM catalog_price_mapping", Long.class);
        String aId = query.search(PartType.CPU, a.modelName(), 0, 20).items().getFirst().id();
        String bId = query.search(PartType.CPU, b.modelName(), 0, 20).items().getFirst().id();
        String sql = """
                INSERT INTO catalog_price_observation (mapping_id, product_id, amount_krw, observed_at, evidence_url)
                VALUES (?, ?, ?, ?, 'https://example.com/evidence')
                """;
        for (long invalid : new long[]{0, -1, 1000000000000L}) {
            assertThatThrownBy(() -> jdbc.update(sql, mappingId, aId, invalid,
                    java.sql.Timestamp.from(OBSERVED.plusSeconds(1)))).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> jdbc.update(sql, mappingId, bId, 100,
                java.sql.Timestamp.from(OBSERVED.plusSeconds(1)))).isInstanceOf(RuntimeException.class);
        assertThat(count("catalog_price_observation")).isEqualTo(1);
    }

    private CatalogPriceImportBatch.Product fixture(PartType type, String suffix) {
        String externalId = UUID.randomUUID().toString();
        CatalogSpecification specification = type == PartType.CPU
                ? new CatalogSpecification.Cpu(null, null, null, null, null, null, null, null)
                : new CatalogSpecification.Ram("DDR5", 16L * 1024 * 1024 * 1024, 2, 6000, "DIMM", null, null, null,
                        new BigDecimal("1.35"), null);
        entries.create(new CatalogEntryCreateRequest(new CatalogProductCreateRequest(type, "Maker", "Model " + suffix,
                "SKU-" + suffix), specification, List.of(new CatalogSourceInput(CatalogSourceName.BUILDCORES,
                externalId, "a".repeat(40), "https://example.com/source", Map.of("opendb_id", externalId), REVIEWED))));
        return new CatalogPriceImportBatch.Product(CatalogSourceName.BUILDCORES, externalId, "Maker", "Model " + suffix,
                "SKU-" + suffix);
    }

    private CatalogPriceImportBatch batch(CatalogPriceImportBatch.Item... items) {
        return new CatalogPriceImportBatch(1, List.of(items));
    }

    private CatalogPriceImportBatch.Item item(CatalogPriceImportBatch.Product identity, String externalId,
                                               long amount, Instant time) {
        return new CatalogPriceImportBatch.Item(identity,
                offer(externalId, CatalogPriceImportBatch.SaleUnit.PRODUCT, null), price(amount, time));
    }

    private CatalogPriceImportBatch.Offer offer(String externalId, CatalogPriceImportBatch.SaleUnit unit, Integer modules) {
        return new CatalogPriceImportBatch.Offer("DANAWA", externalId, "https://example.com/" + externalId,
                "Reviewed " + externalId, unit, modules, REVIEWED);
    }

    private CatalogPriceImportBatch.Price price(long amount, Instant time) {
        return new CatalogPriceImportBatch.Price(amount, time, "https://example.com/evidence", "NEW", true);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
