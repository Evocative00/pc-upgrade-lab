package com.pcupgradelab.catalog;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogPsuCaseSpecificationTests {
    @Test
    void unknownCableInventoryAndConfirmedAbsenceAreDifferentFacts() {
        assertThat(psu(false, List.of()).powerConnectorsKnown()).isFalse();
        assertThat(psu(true, List.of()).powerConnectorsKnown()).isTrue();
        var cable = connector("PCIE_6_2PIN", 4, 2, null);
        assertThatThrownBy(() -> psu(false, List.of(cable))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> psu(true, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> psu(true, Arrays.asList(cable, null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void daisyChainPlugsAreNotAdditionalIndependentCablesAndUnknownLimitsRemainNull() {
        var cable = connector("PCIE_6_2PIN", 4, 2, null);
        assertThat(cable.connectorCount()).isEqualTo(4);
        assertThat(cable.independentCableCount()).isEqualTo(2);
        assertThat(cable.maxPowerW()).isNull();
        var unknown = connector("PCIE_12V_2X6", 1, null, null);
        assertThat(unknown.independentCableCount()).isNull();
        assertThat(unknown.maxPowerW()).isNull();
        for (Integer invalid : List.of(0, -1, 5, 32768)) {
            assertThatThrownBy(() -> connector("PCIE_6_2PIN", 4, invalid, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> connector("PCIE_8PIN", 0, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> connector("PCIE_8PIN", 1, null, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cableVariantsRemainSeparateAndCallersCannotChangeInventories() {
        var unspecified = connector("PCIE_16PIN_UNSPECIFIED", 1, 1, null);
        var hpwr = connector("PCIE_12VHPWR", 1, 1, 600);
        var twoBySix = connector("PCIE_12V_2X6", 1, 1, 600);
        var inventory = new ArrayList<>(List.of(unspecified, hpwr, twoBySix));
        var power = psu(true, inventory);
        inventory.clear();
        assertThat(power.powerConnectors()).hasSize(3).contains(unspecified, hpwr, twoBySix);
        assertThatThrownBy(power.powerConnectors()::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> psu(true, List.of(hpwr, connector(" pcie_12vhpwr ", 2, 2, 600))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> connector("12VHPWR", 1, 1, 600)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unconfirmedFormFactorListsCannotBeUsedAsCompleteSupportedLists() {
        var unknown = enclosure(false, List.of(), false, List.of(), null, null, false, false);
        var empty = enclosure(true, List.of(), true, List.of(), null, null, false, false);
        assertThat(unknown.motherboardFormFactorsKnown()).isFalse();
        assertThat(empty.motherboardFormFactorsKnown()).isTrue();
        assertThatThrownBy(() -> enclosure(false, List.of("ATX"), true, List.of("ATX"), null, null, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enclosure(true, List.of("ATX"), false, List.of("SFX"), null, null, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enclosure(true, null, true, List.of("ATX"), null, null, false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void caseSupportedListsNormalizeSpellingWithoutInferringSizeOrAdapterSupport() {
        var boards = new ArrayList<>(List.of("mini-itx", "Micro ATX", "ATX"));
        var enclosure = enclosure(true, boards, true, List.of("sfx-l"), "340", "130", false, false);
        boards.clear();
        assertThat(enclosure.motherboardFormFactors()).containsExactly("ATX", "MICRO_ATX", "MINI_ITX");
        assertThat(enclosure.psuFormFactors()).containsExactly("SFX_L");
        assertThatThrownBy(enclosure.motherboardFormFactors()::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> enclosure(true, List.of("Micro ATX", "MICRO_ATX"), true,
                List.of("ATX"), null, null, false, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enclosure(true, List.of(""), true, List.of("ATX"), null, null, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enclosure(true, List.of("ATX"), true, List.of("TFX"), null, null, false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clearanceConditionsRemainAvailableAndCannotBecomeUnconditionalWithoutALimit() {
        var enclosure = enclosure(true, List.of("ATX"), true, List.of("ATX"), "340", "180", false, false);
        assertThat(enclosure.gpuClearanceUnconditional()).isFalse();
        assertThat(enclosure.gpuClearanceConditions()).isEqualTo("전면 라디에이터 없이 340 mm");
        assertThat(enclosure.psuClearanceConditions()).isEqualTo("드라이브 케이지 위치를 확인");
        assertThatThrownBy(() -> enclosure(true, List.of("ATX"), true, List.of("ATX"), null, "180", true, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enclosure(true, List.of("ATX"), true, List.of("ATX"), "340", null, false, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishedNumbersCannotBeNegativeZeroOrRoundedToFitSchema() {
        for (Integer invalid : List.of(0, -1)) {
            assertThatThrownBy(() -> new CatalogSpecification.Psu(invalid, "ATX", null, null, null, null, false, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String invalid : List.of("0", "-1", "140.001", "1000000")) {
            assertThatThrownBy(() -> new CatalogSpecification.Psu(750, "ATX", new BigDecimal(invalid),
                    null, null, "3.1", false, List.of())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> enclosure(true, List.of("ATX"), true, List.of("ATX"),
                    invalid, null, false, false)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new CatalogSpecification.Psu(750, "ATX", null, null, null, "ATX 3.1", false, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private CatalogSpecification.Psu psu(boolean known, List<CatalogSpecification.PsuPowerConnector> cables) {
        return new CatalogSpecification.Psu(750, "ATX", new BigDecimal("140"), new BigDecimal("150"),
                new BigDecimal("86"), "3.1", known, cables);
    }

    private CatalogSpecification.PsuPowerConnector connector(String type, Integer plugs, Integer cables, Integer watts) {
        return new CatalogSpecification.PsuPowerConnector(type, plugs, cables, watts);
    }

    private CatalogSpecification.Case enclosure(boolean boardsKnown, List<String> boards, boolean psusKnown,
            List<String> psus, String gpuLength, String psuLength, boolean gpuUnconditional, boolean psuUnconditional) {
        return new CatalogSpecification.Case(boardsKnown, boards, psusKnown, psus,
                psuLength == null ? null : new BigDecimal(psuLength), gpuLength == null ? null : new BigDecimal(gpuLength),
                null, null, null, gpuLength == null ? null : "전면 라디에이터 없이 340 mm",
                psuLength == null ? null : "드라이브 케이지 위치를 확인", gpuUnconditional, psuUnconditional);
    }
}
