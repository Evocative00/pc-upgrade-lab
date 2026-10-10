package com.pcupgradelab.catalog.price;

import com.pcupgradelab.catalog.CatalogProduct;
import com.pcupgradelab.catalog.CatalogProductRepository;
import com.pcupgradelab.catalog.CatalogProductSource;
import com.pcupgradelab.catalog.CatalogProductSourceRepository;
import com.pcupgradelab.catalog.CatalogSourceInput;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.CatalogSpecification;
import com.pcupgradelab.catalog.MotherboardSpec;
import com.pcupgradelab.catalog.MotherboardSpecRepository;
import com.pcupgradelab.catalog.identity.CanonicalCatalogIds;
import com.pcupgradelab.catalog.identity.CatalogIdentityKind;
import com.pcupgradelab.catalog.identity.CatalogIdentityRequests;
import com.pcupgradelab.catalog.identity.CatalogIdentityService;
import com.pcupgradelab.catalog.identity.CatalogModelKind;
import com.pcupgradelab.catalog.identity.CatalogRole;
import com.pcupgradelab.pc.PartType;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Dedicated H2 only: actual product classification, registered evidence and atomic price writes. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reviewed-motherboard-price;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReviewedMotherboardPriceImportTests {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Instant OBSERVED = Instant.parse("2026-01-01T02:00:00Z");
    private static final String OFFER_URL = "https://prod.danawa.com/info/?pcode=74255378";
    @Autowired CatalogPriceImportService service;
    @Autowired CatalogProductRepository products;
    @Autowired CatalogProductSourceRepository sources;
    @Autowired MotherboardSpecRepository motherboards;
    @Autowired CatalogIdentityService identities;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void requireDedicatedH2() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:reviewed-motherboard-price");
        }
        assertThat(count("catalog_product")).isZero();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("UPDATE catalog_product SET canonical_id=NULL, model_id=NULL, identity_kind='LEGACY_UNCLASSIFIED',"
                + "role='UNASSIGNED', identity_evidence_source_id=NULL, identity_review_scope=NULL, identity_reviewed_at=NULL");
        for (String table : List.of("catalog_price_observation", "catalog_price_mapping", "catalog_product_source",
                "motherboard_spec", "catalog_product", "catalog_model_alias", "catalog_model_source", "catalog_model"))
            jdbc.update("DELETE FROM " + table);
    }

    @Test
    void separateReviewedBoardPreviewsWithoutWritingAndAppliesOnceWithoutPromotion() {
        var fixture = fixture(PartType.MOTHERBOARD, CatalogIdentityKind.PHYSICAL_VARIANT,
                CatalogRole.PURCHASE_CANDIDATE, null, true);
        var batch = batch(item(fixture.identity()));
        var before = jdbc.queryForMap("SELECT * FROM catalog_product WHERE id=?", fixture.productId());
        assertThat(service.preview(batch)).isEqualTo(new CatalogPriceImportService.Result(true, 1, 1, 1, 0));
        assertNoPrices();
        assertThat(service.apply(batch)).isEqualTo(new CatalogPriceImportService.Result(false, 1, 1, 1, 0));
        assertThat(service.apply(batch)).isEqualTo(new CatalogPriceImportService.Result(false, 1, 0, 0, 1));
        assertThat(jdbc.queryForMap("SELECT * FROM catalog_product WHERE id=?", fixture.productId())).isEqualTo(before);
        assertThat(count("catalog_price_mapping")).isEqualTo(1);
        assertThat(count("catalog_price_observation")).isEqualTo(1);
    }

    @Test
    void claimedBoardConfigurationCannotPriceCpuRamGpuOrInstalledModelReference() {
        for (PartType type : List.of(PartType.CPU, PartType.RAM, PartType.GPU)) {
            var fixture = fixture(type, CatalogIdentityKind.PHYSICAL_VARIANT, CatalogRole.PURCHASE_CANDIDATE, null, true);
            assertThatThrownBy(() -> service.preview(batch(item(fixture.identity()))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("distinct PN-less motherboard");
            assertThatThrownBy(() -> service.apply(batch(item(fixture.identity()))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertNoPrices();
        }
        var model = fixture(PartType.MOTHERBOARD, CatalogIdentityKind.MODEL_REFERENCE,
                CatalogRole.INSTALLED_PC_REFERENCE, null, true);
        assertThatThrownBy(() -> service.apply(batch(item(model.identity()))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("distinct PN-less motherboard");
        assertNoPrices();
    }

    @Test
    void knownPartNumberCannotBeHiddenAndPurchaseRoleCannotBeInvented() {
        var known = fixture(PartType.MOTHERBOARD, CatalogIdentityKind.PHYSICAL_VARIANT,
                CatalogRole.PURCHASE_CANDIDATE, "KNOWN-EXACT-PN", true);
        assertThatThrownBy(() -> service.apply(batch(item(known.identity())))).isInstanceOf(IllegalArgumentException.class);
        var installed = fixture(PartType.MOTHERBOARD, CatalogIdentityKind.PHYSICAL_VARIANT,
                CatalogRole.INSTALLED_PC_REFERENCE, null, true);
        assertThatThrownBy(() -> service.apply(batch(item(installed.identity())))).isInstanceOf(IllegalArgumentException.class);
        var both = fixture(PartType.MOTHERBOARD, CatalogIdentityKind.PHYSICAL_VARIANT, CatalogRole.BOTH, null, true);
        assertThatThrownBy(() -> service.preview(batch(item(both.identity()))))
                .isInstanceOf(IllegalArgumentException.class); // claimed PURCHASE_CANDIDATE must match the actual BOTH role.
        var wrongCanonical = fixture(PartType.MOTHERBOARD, CatalogIdentityKind.PHYSICAL_VARIANT,
                CatalogRole.PURCHASE_CANDIDATE, null, true);
        jdbc.update("UPDATE catalog_product SET canonical_id=? WHERE id=?", UUID.randomUUID().toString(), wrongCanonical.productId());
        assertThatThrownBy(() -> service.preview(batch(item(wrongCanonical.identity()))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("distinct PN-less motherboard");
        assertNoPrices();
    }

    @Test
    void missingOrDifferentRegisteredEvidenceRefusesThePrice() {
        var missing = fixture(PartType.MOTHERBOARD, CatalogIdentityKind.PHYSICAL_VARIANT,
                CatalogRole.PURCHASE_CANDIDATE, null, false);
        assertThatThrownBy(() -> service.apply(batch(item(missing.identity()))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("registered source evidence");
        var valid = fixture(PartType.MOTHERBOARD, CatalogIdentityKind.PHYSICAL_VARIANT,
                CatalogRole.PURCHASE_CANDIDATE, null, true);
        for (var change : List.of(new Change("wifiIncluded", false), new Change("domesticDistributor", "Different distributor"),
                new Change("revisionScope", "Different reviewed revision"), new Change("reviewId", "other-review"))) {
            var changed = configuration(change);
            var identity = new CatalogPriceImportBatch.Product(CatalogSourceName.MANUFACTURER,
                    valid.identity().externalId(), "ASUS", changed.manufacturerModelName(), null, changed);
            assertThatThrownBy(() -> service.apply(batch(item(identity))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("registered source evidence");
        }
        var extra = sources.saveAndFlush(new CatalogProductSource(products.findById(valid.productId()).orElseThrow(),
                new CatalogSourceInput(CatalogSourceName.MANUFACTURER, "other-evidence:" + UUID.randomUUID(), "fixture",
                        "https://www.asus.com/kr/spec/other", Map.of(), OBSERVED)));
        jdbc.update("UPDATE catalog_product SET identity_evidence_source_id=? WHERE id=?", extra.getId(), valid.productId());
        assertThatThrownBy(() -> service.apply(batch(item(valid.identity()))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("distinct PN-less motherboard");
        assertNoPrices();
    }

    @Test
    void ddrSocketAndBoardSizeMustMatchActualStoredSpecification() {
        var valid = fixture(PartType.MOTHERBOARD, CatalogIdentityKind.PHYSICAL_VARIANT,
                CatalogRole.PURCHASE_CANDIDATE, null, true);
        for (var change : List.of(new Change("memoryType", "DDR4"), new Change("socketCode", "LGA1700"),
                new Change("formFactor", "MICRO_ATX"))) {
            var changed = configuration(change);
            var identity = new CatalogPriceImportBatch.Product(CatalogSourceName.MANUFACTURER,
                    valid.identity().externalId(), "ASUS", changed.manufacturerModelName(), null, changed);
            assertThatThrownBy(() -> service.apply(batch(item(identity))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stored motherboard specification");
        }
        assertNoPrices();
    }

    @Test
    void partialSaleSkuWrongProviderIdentityDifferentEvidenceAndEarlyObservationAreRejected() {
        var valid = fixture(PartType.MOTHERBOARD, CatalogIdentityKind.PHYSICAL_VARIANT,
                CatalogRole.PURCHASE_CANDIDATE, null, true);
        var original = item(valid.identity());
        var offer = original.offer();
        for (var invalid : List.of(
                new CatalogPriceImportBatch.Item(valid.identity(), new CatalogPriceImportBatch.Offer("DANAWA", "74255378",
                        OFFER_URL, "TUF GAMING B860-PLUS WIFI", offer.saleUnit(), null, offer.verifiedAt()), original.price()),
                new CatalogPriceImportBatch.Item(valid.identity(), new CatalogPriceImportBatch.Offer("DANAWA", "999",
                        OFFER_URL, offer.saleSku(), offer.saleUnit(), null, offer.verifiedAt()), original.price()),
                new CatalogPriceImportBatch.Item(valid.identity(), offer, new CatalogPriceImportBatch.Price(275350L,
                        OBSERVED, "https://prod.danawa.com/info/?pcode=999", "NEW", true)),
                new CatalogPriceImportBatch.Item(valid.identity(), new CatalogPriceImportBatch.Offer("DANAWA", "74255378",
                        OFFER_URL, offer.saleSku(), offer.saleUnit(), null, Instant.parse("2026-01-01T00:00:00Z")), original.price()),
                new CatalogPriceImportBatch.Item(valid.identity(), offer, new CatalogPriceImportBatch.Price(275350L,
                        Instant.parse("2026-01-01T00:00:00Z"), OFFER_URL, "NEW", true)))) {
            assertThatThrownBy(() -> service.apply(batch(invalid))).isInstanceOf(IllegalArgumentException.class);
            assertNoPrices();
        }
    }

    @Test
    void invalidLaterRowLeavesTheValidEarlierBoardPriceAndEveryProductUntouched() {
        var board = fixture(PartType.MOTHERBOARD, CatalogIdentityKind.PHYSICAL_VARIANT,
                CatalogRole.PURCHASE_CANDIDATE, null, true);
        var model = fixture(PartType.MOTHERBOARD, CatalogIdentityKind.MODEL_REFERENCE,
                CatalogRole.INSTALLED_PC_REFERENCE, null, true);
        var before = jdbc.queryForList("SELECT * FROM catalog_product ORDER BY id");
        assertThatThrownBy(() -> service.apply(batch(item(board.identity()), item(model.identity()))))
                .isInstanceOf(IllegalArgumentException.class);
        assertNoPrices();
        assertThat(jdbc.queryForList("SELECT * FROM catalog_product ORDER BY id")).isEqualTo(before);
    }

    private record Fixture(String productId, CatalogPriceImportBatch.Product identity) { }
    private record Change(String field, Object value) { }

    @SuppressWarnings("unchecked")
    private Fixture fixture(PartType type, CatalogIdentityKind kind, CatalogRole role, String actualPn, boolean includeEvidence) {
        var configuration = ReviewedMotherboardSaleConfigurationTests.configuration();
        String externalId = "asus:retail:test:" + UUID.randomUUID();
        var product = products.saveAndFlush(new CatalogProduct(type, "ASUS", configuration.manufacturerModelName(), actualPn));
        if (type == PartType.MOTHERBOARD) motherboards.saveAndFlush(new MotherboardSpec(product,
                new CatalogSpecification.Motherboard("LGA1851", "B860", "ATX", "DDR5", "DIMM", 4, 256L << 30, null)));
        Map<String, Object> payload = includeEvidence
                ? Map.of("reviewedSaleConfiguration", MAPPER.convertValue(configuration, Map.class)) : Map.of();
        var source = sources.saveAndFlush(new CatalogProductSource(product, new CatalogSourceInput(
                CatalogSourceName.MANUFACTURER, externalId, "approved-fixture", "https://www.asus.com/kr/spec/test",
                payload, configuration.verifiedAt())));
        String modelId = null;
        if (kind == CatalogIdentityKind.MODEL_REFERENCE) {
            var modelSource = new CatalogSourceInput(CatalogSourceName.MANUFACTURER, "model-fixture:" + UUID.randomUUID(),
                    "fixture", "https://www.asus.com/kr/spec/model", Map.of(), configuration.verifiedAt());
            modelId = identities.registerModel(new CatalogIdentityRequests.ModelRegistration(UUID.randomUUID().toString(),
                    PartType.MOTHERBOARD, "ASUS", configuration.manufacturerModelName(), CatalogModelKind.BOARD_MODEL,
                    CatalogRole.INSTALLED_PC_REFERENCE, null, null,
                    List.of(new CatalogIdentityRequests.ModelSource(modelSource, "H2 installed model fixture")), List.of())).id();
        }
        identities.bindProduct(new CatalogIdentityRequests.ProductBinding(product.getId(),
                CanonicalCatalogIds.productForReviewedSource(CatalogSourceName.MANUFACTURER, externalId), modelId,
                kind, role, source.getId(), "H2 reviewed product evidence fixture"));
        return new Fixture(product.getId(), new CatalogPriceImportBatch.Product(CatalogSourceName.MANUFACTURER,
                externalId, "ASUS", configuration.manufacturerModelName(), null, configuration));
    }

    private ReviewedMotherboardSaleConfiguration configuration(Change change) {
        ObjectNode node = MAPPER.valueToTree(ReviewedMotherboardSaleConfigurationTests.configuration());
        node.set(change.field(), MAPPER.valueToTree(change.value()));
        return MAPPER.treeToValue(node, ReviewedMotherboardSaleConfiguration.class);
    }

    private CatalogPriceImportBatch.Item item(CatalogPriceImportBatch.Product identity) {
        return new CatalogPriceImportBatch.Item(identity, new CatalogPriceImportBatch.Offer("DANAWA", "74255378", OFFER_URL,
                identity.reviewedSaleConfiguration().saleSku(), CatalogPriceImportBatch.SaleUnit.PRODUCT, null,
                identity.reviewedSaleConfiguration().verifiedAt()),
                new CatalogPriceImportBatch.Price(275350L, OBSERVED, OFFER_URL, "NEW", true));
    }

    private CatalogPriceImportBatch batch(CatalogPriceImportBatch.Item... items) {
        return new CatalogPriceImportBatch(1, List.of(items));
    }

    private void assertNoPrices() {
        assertThat(count("catalog_price_mapping")).isZero();
        assertThat(count("catalog_price_observation")).isZero();
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
}
