package com.pcupgradelab.catalog;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 외부 자료를 정규화할 때 미확인 값과 모순된 값을 구분하는 경계를 검사한다. */
class CatalogGpuSpecificationTests {
    @Test
    void activeLanesDoNotInventPhysicalConnectorSizeAndMustFitWhenBothAreKnown() {
        CatalogSpecification.Gpu gpu = gpu(null, 8, null, null, null, null, false, List.of());
        assertThat(gpu.pcieConnectorLanes()).isNull();
        assertThat(gpu.pcieActiveLanes()).isEqualTo(8);
        assertThatThrownBy(() -> gpu(8, 16, null, null, null, null, false, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gpu(3, 2, null, null, null, null, false, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPowerNumberRequiresItsMeaningAndPsuRequirementDoesNotFillInCardPower() {
        CatalogSpecification.Gpu gpu = gpu(null, null, null, null, 700, "minimum", false, List.of());
        assertThat(gpu.cardPowerW()).isNull();
        assertThat(gpu.cardPowerBasis()).isNull();
        assertThat(gpu.psuRequirementW()).isEqualTo(700);
        assertThat(gpu.psuRequirementBasis()).isEqualTo("MINIMUM");

        for (Runnable invalid : List.<Runnable>of(
                () -> gpu(null, null, new BigDecimal("170"), null, null, null, false, List.of()),
                () -> gpu(null, null, null, "BOARD_POWER", null, null, false, List.of()),
                () -> gpu(null, null, null, null, 650, null, false, List.of()),
                () -> gpu(null, null, null, null, null, "RECOMMENDED", false, List.of()),
                () -> gpu(null, null, null, null, 650, "ACTUAL_DRAW", false, List.of()))) {
            assertThatThrownBy(invalid::run).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void unknownConnectorsCannotCarryKnownRowsAndDuplicateTypesAreNotSilentlyAdded() {
        var eightPin = new CatalogSpecification.GpuPowerConnector("PCIE_8PIN", 1);
        var sameType = new CatalogSpecification.GpuPowerConnector(" pcie_8pin ", 2);
        for (Runnable invalid : List.<Runnable>of(
                () -> gpu(null, null, null, null, null, null, false, List.of(eightPin)),
                () -> gpu(null, null, null, null, null, null, true, List.of(eightPin, sameType)),
                () -> gpu(null, null, null, null, null, null, false, null),
                () -> gpu(null, null, null, null, null, null, true, Arrays.asList(eightPin, null)))) {
            assertThatThrownBy(invalid::run).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void connectorCountsRequireARecognizedTypeAndPositiveSmallInteger() {
        for (Integer count : Arrays.asList(null, 0, -1, 32768)) {
            assertThatThrownBy(() -> new CatalogSpecification.GpuPowerConnector("PCIE_8PIN", count))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String type : Arrays.asList(null, "", "8PIN", "EPS_8PIN")) {
            assertThatThrownBy(() -> new CatalogSpecification.GpuPowerConnector(type, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void differentSixteenPinSpecificationsRemainDistinct() {
        for (String type : List.of("PCIE_16PIN_UNSPECIFIED", "PCIE_12VHPWR", "PCIE_12V_2X6")) {
            var connector = new CatalogSpecification.GpuPowerConnector(type, 1);
            var gpu = gpu(null, null, null, null, null, null, true, List.of(connector));
            assertThat(gpu.powerConnectors()).containsExactly(connector);
            assertThat(gpu.powerConnectors().getFirst().connectorType()).isEqualTo(type);
        }
    }

    @Test
    void publishedPowerIsNeverRoundedToFitTheDatabase() {
        var accepted = gpu(null, null, new BigDecimal("170.000"), "POWER_CONSUMPTION",
                null, null, false, List.of());
        assertThat(accepted.cardPowerW()).isEqualTo(new BigDecimal("170.00"));
        for (BigDecimal power : List.of(BigDecimal.ZERO, new BigDecimal("-1"),
                new BigDecimal("170.001"), new BigDecimal("1000000"))) {
            assertThatThrownBy(() -> gpu(null, null, power, "POWER_CONSUMPTION",
                    null, null, false, List.of())).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private CatalogSpecification.Gpu gpu(Integer connectorLanes, Integer activeLanes,
            BigDecimal cardPower, String cardPowerBasis, Integer psuWatts, String psuBasis,
            boolean connectorsKnown, List<CatalogSpecification.GpuPowerConnector> connectors) {
        return new CatalogSpecification.Gpu("NVIDIA", "Test GPU", 12L * 1024 * 1024 * 1024,
                "GDDR6", "4.0", connectorLanes, activeLanes, null, null, null, null,
                cardPower, cardPowerBasis, psuWatts, psuBasis, connectorsKnown, connectors);
    }
}
