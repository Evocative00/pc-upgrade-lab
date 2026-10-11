package com.pcupgradelab.catalog.price;

import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.identity.CatalogIdentityKind;
import com.pcupgradelab.catalog.identity.CatalogRole;
import com.pcupgradelab.pc.PartType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewedMotherboardSaleConfigurationTests {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void completeSeparateMotherboardConfigurationRoundTripsWithoutInventingPartNumber() {
        var configuration = configuration();
        var product = new CatalogPriceImportBatch.Product(CatalogSourceName.MANUFACTURER,
                "asus:test:retail:intek", "ASUS", configuration.manufacturerModelName(), null, configuration);
        var roundTrip = mapper.readValue(mapper.writeValueAsString(product), CatalogPriceImportBatch.Product.class);
        assertThat(roundTrip).isEqualTo(product);
        assertThat(roundTrip.partNumber()).isNull();
        assertThat(configuration.saleSku()).isEqualTo("TUF GAMING B860-PLUS WIFI / 인텍앤컴퍼니 / DOMESTIC_RETAIL_SINGLE_BOARD");
    }

    @Test
    void legacyFiveArgumentAndMissingOptionalJsonKeepOriginalContract() {
        assertThat(new CatalogPriceImportBatch.Product(CatalogSourceName.BUILDCORES, "legacy", "Maker", "Model", null)
                .reviewedSaleConfiguration()).isNull();
        var old = mapper.readValue("{\"sourceName\":\"MANUFACTURER\",\"externalId\":\"samsung:MZ-77E1T0BW\","
                + "\"manufacturer\":\"Samsung\",\"modelName\":\"870 EVO 1TB\",\"partNumber\":\"MZ-77E1T0BW\"}",
                CatalogPriceImportBatch.Product.class);
        assertThat(old).isEqualTo(new CatalogPriceImportBatch.Product(CatalogSourceName.MANUFACTURER,
                "samsung:MZ-77E1T0BW", "Samsung", "870 EVO 1TB", "MZ-77E1T0BW"));
        assertThatThrownBy(() -> new CatalogPriceImportBatch.Product(CatalogSourceName.MANUFACTURER,
                "manufacturer:model", "Maker", "Model", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configurationDoesNotOverrideKnownPnBuildcoresOrDifferentModel() {
        var configuration = configuration();
        assertThatThrownBy(() -> new CatalogPriceImportBatch.Product(CatalogSourceName.MANUFACTURER,
                "maker:retail", "ASUS", configuration.manufacturerModelName(), "KNOWN-PN", configuration))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogPriceImportBatch.Product(CatalogSourceName.BUILDCORES,
                "legacy", "ASUS", configuration.manufacturerModelName(), null, configuration))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogPriceImportBatch.Product(CatalogSourceName.MANUFACTURER,
                "maker:retail", "ASUS", "TUF GAMING B860M-PLUS WIFI", null, configuration))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyCompleteReviewedSingleBoardPurchaseConfigurationsAreAccepted() {
        for (var mutation : List.of(
                new Mutation("partType", "CPU"), new Mutation("partType", "RAM"), new Mutation("partType", "GPU"),
                new Mutation("identityKind", "MODEL_REFERENCE"), new Mutation("identityKind", "LEGACY_UNCLASSIFIED"),
                new Mutation("role", "INSTALLED_PC_REFERENCE"), new Mutation("role", "UNASSIGNED"),
                new Mutation("packageKind", "BUNDLE"), new Mutation("unitCount", 2),
                new Mutation("domesticDistributor", "unknown"), new Mutation("revisionScope", " "),
                new Mutation("socketCode", "LGA 1851"), new Mutation("memoryType", "ddr5"),
                new Mutation("evidenceUrls", List.of("http://example.com/evidence")),
                new Mutation("evidenceUrls", List.of("https://user:secret@example.com/evidence")),
                new Mutation("evidenceUrls", List.of()),
                new Mutation("verifiedAt", Instant.now().plusSeconds(30).toString()))) {
            ObjectNode invalid = mapper.valueToTree(configuration());
            invalid.set(mutation.field(), mapper.valueToTree(mutation.value()));
            assertThatThrownBy(() -> mapper.treeToValue(invalid, ReviewedMotherboardSaleConfiguration.class))
                    .as(mutation.field() + "=" + mutation.value()).isInstanceOf(RuntimeException.class);
        }
        ObjectNode missingWifi = mapper.valueToTree(configuration());
        missingWifi.remove("wifiIncluded");
        assertThatThrownBy(() -> mapper.treeToValue(missingWifi, ReviewedMotherboardSaleConfiguration.class))
                .isInstanceOf(RuntimeException.class);
        ObjectNode both = mapper.valueToTree(configuration());
        both.put("role", "BOTH");
        assertThat(mapper.treeToValue(both, ReviewedMotherboardSaleConfiguration.class).role()).isEqualTo(CatalogRole.BOTH);
    }

    private record Mutation(String field, Object value) { }

    static ReviewedMotherboardSaleConfiguration configuration() {
        return new ReviewedMotherboardSaleConfiguration("review-board-fixture", PartType.MOTHERBOARD,
                CatalogIdentityKind.PHYSICAL_VARIANT, CatalogRole.PURCHASE_CANDIDATE, "TUF GAMING B860-PLUS WIFI",
                "인텍앤컴퍼니", ReviewedMotherboardSaleConfiguration.SINGLE_BOARD, 1,
                "LGA1851", "DDR5", "ATX", true, "Exact reviewed retail model; no retail revision inferred",
                List.of("https://www.asus.com/kr/motherboards-components/motherboards/tuf-gaming/tuf-gaming-b860-plus-wifi/techspec/",
                        "https://prod.danawa.com/info/?pcode=74255378"), Instant.parse("2026-01-01T01:00:00Z"));
    }
}
