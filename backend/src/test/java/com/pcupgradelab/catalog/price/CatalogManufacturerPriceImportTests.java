package com.pcupgradelab.catalog.price;

import com.pcupgradelab.catalog.CatalogEntryCreateRequest;
import com.pcupgradelab.catalog.CatalogEntryService;
import com.pcupgradelab.catalog.CatalogPriceStatus;
import com.pcupgradelab.catalog.CatalogProductCreateRequest;
import com.pcupgradelab.catalog.CatalogQueryService;
import com.pcupgradelab.catalog.CatalogSourceInput;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.CatalogSpecification;
import com.pcupgradelab.catalog.CatalogVerificationStatus;
import com.pcupgradelab.pc.PartType;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Dedicated H2 checks; does not connect to MySQL or fetch seller pages. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:manufacturer-price-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogManufacturerPriceImportTests {
    private static final String PN = "MZ-77E1T0BW";
    private static final Instant REVIEWED = Instant.parse("2026-01-01T01:00:00Z");
    private static final Instant OBSERVED = Instant.parse("2026-01-01T02:00:00Z");
    @Autowired CatalogEntryService entries;
    @Autowired CatalogQueryService query;
    @Autowired CatalogPriceImportService service;
    @Autowired CatalogPriceImportLoader loader;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void verifyDedicatedH2() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:manufacturer-price-test");
        }
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(count("catalog_product")).isZero();
    }

    @AfterEach
    void cleanup() {
        for (String table : List.of("catalog_price_observation", "catalog_price_mapping", "catalog_product_source",
                "storage_spec", "catalog_reference_price", "catalog_product")) jdbc.update("DELETE FROM " + table);
    }

    @Test
    void exactManufacturerSsdPreviewNeverWritesAndApplyIsIdempotentWithoutPromotingProduct() {
        var identity = fixture(PN, "870 EVO 1TB");
        var prices = batch(item(identity, "1000000066"));
        assertThat(service.preview(prices)).isEqualTo(new CatalogPriceImportService.Result(true, 1, 1, 1, 0));
        assertThat(count("catalog_price_mapping")).isZero();
        assertThat(count("catalog_price_observation")).isZero();
        assertThat(service.apply(prices)).isEqualTo(new CatalogPriceImportService.Result(false, 1, 1, 1, 0));
        assertThat(service.apply(prices)).isEqualTo(new CatalogPriceImportService.Result(false, 1, 0, 0, 1));
        assertThat(count("catalog_price_mapping")).isEqualTo(1);
        assertThat(count("catalog_price_observation")).isEqualTo(1);
        var product = query.search(PartType.STORAGE, "870 EVO 1TB", 0, 20).items().getFirst();
        assertThat(product.currentPrice().sourceName()).isEqualTo("SAMSUNG_CNH");
        assertThat(product.currentPrice().amountKrw()).isEqualTo(459000);
        assertThat(product.currentPrice().observedAt()).isEqualTo(OBSERVED);
        assertThat(product.verificationStatus()).isEqualTo(CatalogVerificationStatus.UNVERIFIED);
        assertThat(product.active()).isFalse();
        assertThat(product.referencePrice().status()).isEqualTo(CatalogPriceStatus.UNCONFIRMED);
        assertThat(product.referencePrice().amountKrw()).isNull();
    }

    @Test
    void wrongPartNumberOrUnknownManufacturerSourceRefusesEntireBatchBeforeWriting() {
        var identity = fixture(PN, "870 EVO 1TB");
        var wrongPart = new CatalogPriceImportBatch.Product(CatalogSourceName.MANUFACTURER, identity.externalId(),
                identity.manufacturer(), identity.modelName(), "MZ-77E1T0B");
        var unknownSource = new CatalogPriceImportBatch.Product(CatalogSourceName.MANUFACTURER, "samsung:unknown",
                identity.manufacturer(), identity.modelName(), identity.partNumber());
        var wrongModel = new CatalogPriceImportBatch.Product(CatalogSourceName.MANUFACTURER, identity.externalId(),
                identity.manufacturer(), "Different model", identity.partNumber());
        for (var invalid : List.of(wrongPart, unknownSource, wrongModel)) {
            var prices = batch(item(identity, "valid"), item(invalid, "invalid"));
            assertThatThrownBy(() -> service.preview(prices)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.apply(prices)).isInstanceOf(IllegalArgumentException.class);
            assertThat(count("catalog_price_mapping")).isZero();
            assertThat(count("catalog_price_observation")).isZero();
        }
    }

    @Test
    void manufacturerSupportPreservesProviderCollisionAndSingleProductUnitGuards() {
        var first = fixture(PN, "870 EVO 1TB");
        var second = fixture("MZ-V9P1T0BW", "990 PRO 1TB");
        service.apply(batch(item(first, "shared-goods")));
        assertThatThrownBy(() -> service.apply(batch(item(second, "shared-goods"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another product");
        var invalidUnit = new CatalogPriceImportBatch.Item(second, new CatalogPriceImportBatch.Offer("SAMSUNG_CNH",
                "other", "https://example.com/other", second.partNumber(), CatalogPriceImportBatch.SaleUnit.RAM_KIT, 2, REVIEWED),
                new CatalogPriceImportBatch.Price(399000L, OBSERVED, "https://example.com/other", "NEW", true));
        assertThatThrownBy(() -> service.apply(batch(invalidUnit))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one PRODUCT");
        assertThat(count("catalog_price_mapping")).isEqualTo(1);
        assertThat(count("catalog_price_observation")).isEqualTo(1);
    }

    @Test
    void manufacturerRequiresExactPartNumberAndApprovedEightRowsPreserveOriginalObservations() throws Exception {
        for (String partNumber : new String[]{null, "", " "}) {
            assertThatThrownBy(() -> new CatalogPriceImportBatch.Product(CatalogSourceName.MANUFACTURER,
                    "samsung:" + PN, "Samsung", "870 EVO 1TB", partNumber)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new CatalogPriceImportBatch.Product(CatalogSourceName.MANUAL, "manual", "Samsung",
                "870 EVO 1TB", PN)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new CatalogPriceImportBatch.Product(CatalogSourceName.BUILDCORES, "legacy", "Maker", "Model", null)
                .partNumber()).isNull();

        Path folder = Path.of("../data/catalog-review");
        var approved = loader.load(folder.resolve("pilot-approved-prices-2026-10-10.json"));
        var original = loader.load(folder.resolve("pilot-existing-prices-2026-10-10.json"));
        assertThat(approved.items()).hasSize(8);
        assertThat(approved.items().subList(0, 6)).containsExactlyElementsOf(original.items());
        var mapper = JsonMapper.builder().build();
        var review = mapper.readTree(folder.resolve("pilot-storage-price-review-2026-10-10.json").toFile());
        var pilot = mapper.readTree(folder.resolve("pilot-import-preview-2026-10-10.json").toFile());
        for (int index = 0; index < 2; index++) {
            var quote = review.path("observations").get(index);
            var item = approved.items().get(index + 6);
            var product = pilot.path("products").get(index + 12).path("product");
            assertThat(item.product().sourceName()).isEqualTo(CatalogSourceName.MANUFACTURER);
            assertThat(item.product().externalId()).isEqualTo("samsung:" + quote.path("partNumber").stringValue());
            assertThat(item.product().manufacturer()).isEqualTo(product.path("manufacturer").stringValue());
            assertThat(item.product().modelName()).isEqualTo(product.path("modelName").stringValue());
            assertThat(item.product().partNumber()).isEqualTo(product.path("partNumber").stringValue());
            assertThat(item.offer().sourceName()).isEqualTo("SAMSUNG_CNH");
            assertThat(item.offer().externalId()).isEqualTo(quote.path("sellerProductCode").stringValue());
            assertThat(item.offer().saleSku()).isEqualTo(item.product().partNumber());
            assertThat(item.offer().saleUnit()).isEqualTo(CatalogPriceImportBatch.SaleUnit.PRODUCT);
            assertThat(item.offer().moduleCount()).isNull();
            assertThat(item.offer().verifiedAt()).isEqualTo(Instant.parse(quote.path("observedAt").stringValue()));
            assertThat(item.offer().sourceUrl()).isEqualTo(quote.path("evidenceUrl").stringValue());
            assertThat(item.price().amountKrw()).isEqualTo(quote.path("amountKrw").longValue());
            assertThat(item.price().observedAt()).isEqualTo(Instant.parse(quote.path("observedAt").stringValue()));
            assertThat(item.price().evidenceUrl()).isEqualTo(quote.path("evidenceUrl").stringValue());
            assertThat(item.price().condition()).isEqualTo("NEW");
            assertThat(item.price().inStock()).isTrue();
        }
        ObjectNode missingPart = mapper.valueToTree(approved);
        ((ObjectNode) missingPart.path("items").get(6).path("product")).remove("partNumber");
        assertThatThrownBy(() -> loader.read(mapper.writeValueAsBytes(missingPart))).isInstanceOf(IllegalArgumentException.class);
    }

    private CatalogPriceImportBatch.Product fixture(String partNumber, String model) {
        String externalId = "samsung:" + partNumber;
        boolean nvme = partNumber.equals("MZ-V9P1T0BW");
        var spec = new CatalogSpecification.Storage("SSD", 1000000000000L, new BigDecimal("1000"), "DECIMAL_GB",
                nvme ? "M2" : "TWO_POINT_FIVE_INCH", nvme ? "PCIE" : "SATA", nvme ? "NVME" : "ATA",
                nvme ? "4.0" : null, nvme ? 4 : null, nvme ? "2.0" : null, nvme ? null : "3.0",
                nvme ? "M" : null, nvme ? "2280" : null, null, null, null, null, null);
        entries.create(new CatalogEntryCreateRequest(new CatalogProductCreateRequest(PartType.STORAGE, "Samsung", model,
                partNumber), spec, List.of(new CatalogSourceInput(CatalogSourceName.MANUFACTURER, externalId,
                "manufacturer-test", "https://example.com/manufacturer/" + partNumber, Map.of("partNumber", partNumber), REVIEWED))));
        return new CatalogPriceImportBatch.Product(CatalogSourceName.MANUFACTURER, externalId, "Samsung", model, partNumber);
    }

    private CatalogPriceImportBatch batch(CatalogPriceImportBatch.Item... items) { return new CatalogPriceImportBatch(1, List.of(items)); }
    private CatalogPriceImportBatch.Item item(CatalogPriceImportBatch.Product product, String goodsId) {
        String url = "https://example.com/goods?goodsNo=" + goodsId;
        return new CatalogPriceImportBatch.Item(product, new CatalogPriceImportBatch.Offer("SAMSUNG_CNH", goodsId,
                url, product.partNumber(), CatalogPriceImportBatch.SaleUnit.PRODUCT, null, REVIEWED),
                new CatalogPriceImportBatch.Price(459000L, OBSERVED, url, "NEW", true));
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
}
