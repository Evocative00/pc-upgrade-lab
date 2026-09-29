package com.pcupgradelab.catalog;

import com.pcupgradelab.pc.PartType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 외부 사양에서 모르는 값이나 별도 의미를 가진 값을 임의로 채우지 않는지 검사한다. */
class CatalogMonitorSpecificationTests {
    @Test
    void decimalScreenSizeAndFractionalRefreshSurviveWithoutRoundingOrInventingOverclock() {
        var specification = new CatalogSpecification.Monitor(new BigDecimal("23.8"), 1920, 1080,
                " ips ", new BigDecimal("59.9400"), null, null, null, null, null);
        assertThat(specification.type()).isEqualTo(PartType.MONITOR);
        assertThat(specification.screenSizeInches()).isEqualTo(new BigDecimal("23.80"));
        assertThat(specification.nativeStandardRefreshHz()).isEqualTo(new BigDecimal("59.940"));
        assertThat(specification.panelType()).isEqualTo("IPS");
        assertThat(specification.hasRefreshOverclock()).isNull();
        assertThat(specification.nativeOcRefreshHz()).isNull();
        for (String rate : List.of("0", "-1", "59.9401", "10000")) {
            assertThatThrownBy(() -> refresh(new BigDecimal(rate), null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String size : List.of("0", "-1", "23.801", "1000")) {
            assertThatThrownBy(() -> new CatalogSpecification.Monitor(new BigDecimal(size),
                    null, null, null, null, null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void resolutionRequiresBothDimensionsButDoesNotAssumeLandscapeOrientation() {
        var portrait = resolution(1080, 1920);
        assertThat(portrait.nativeWidthPx()).isEqualTo(1080);
        assertThat(portrait.nativeHeightPx()).isEqualTo(1920);
        var unknown = resolution(null, null);
        assertThat(unknown.nativeWidthPx()).isNull();
        assertThat(unknown.nativeHeightPx()).isNull();
        for (Runnable invalid : List.<Runnable>of(
                () -> resolution(1920, null), () -> resolution(null, 1080),
                () -> resolution(0, 1080), () -> resolution(1920, -1))) {
            assertThatThrownBy(invalid::run).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void overclockRequiresConfirmedSupportAndDoesNotFillInAnUnknownStandardRate() {
        var onlyOverclockKnown = refresh(null, true, new BigDecimal("180"));
        assertThat(onlyOverclockKnown.nativeStandardRefreshHz()).isNull();
        assertThat(onlyOverclockKnown.nativeOcRefreshHz()).isEqualTo(new BigDecimal("180.000"));
        for (Boolean support : Arrays.asList(null, false, true)) {
            var partial = refresh(new BigDecimal("165"), support, null);
            assertThat(partial.hasRefreshOverclock()).isEqualTo(support);
            assertThat(partial.nativeOcRefreshHz()).isNull();
        }
        for (Runnable invalid : List.<Runnable>of(
                () -> refresh(new BigDecimal("165"), null, new BigDecimal("180")),
                () -> refresh(new BigDecimal("165"), false, new BigDecimal("180")),
                () -> refresh(new BigDecimal("165"), true, new BigDecimal("165")),
                () -> refresh(new BigDecimal("165"), true, new BigDecimal("144")),
                () -> refresh(null, true, BigDecimal.ZERO),
                () -> refresh(null, true, new BigDecimal("180.0001")))) {
            assertThatThrownBy(invalid::run).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void activePowerNeedsBothItsMeaningAndMeasurementConditions() {
        var known = power(new BigDecimal("32.000"), " manufacturer_typical ", " SDR, brightness 50% ");
        assertThat(known.activePowerW()).isEqualTo(new BigDecimal("32.00"));
        assertThat(known.activePowerBasis()).isEqualTo("MANUFACTURER_TYPICAL");
        assertThat(known.activePowerConditions()).isEqualTo("SDR, brightness 50%");
        var unknown = power(null, null, null);
        assertThat(unknown.activePowerW()).isNull();
        assertThat(unknown.activePowerBasis()).isNull();
        assertThat(unknown.activePowerConditions()).isNull();
        // 숫자/기준/조건 중 일부만 있는 모든 조합을 거절한다.
        for (int mask = 1; mask < 7; mask++) {
            BigDecimal watts = (mask & 1) != 0 ? new BigDecimal("32") : null;
            String basis = (mask & 2) != 0 ? "MANUFACTURER_TYPICAL" : null;
            String conditions = (mask & 4) != 0 ? "SDR, brightness 50%" : null;
            assertThatThrownBy(() -> power(watts, basis, conditions))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String conditions : List.of(" ", "x".repeat(1001))) {
            assertThatThrownBy(() -> power(new BigDecimal("32"), "MANUFACTURER_TYPICAL", conditions))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void maximumStandbyAndAdapterRatingsCannotBeUsedAsActivePower() {
        for (String basis : List.of("MAXIMUM", "STANDBY", "ADAPTER_RATING")) {
            assertThatThrownBy(() -> power(new BigDecimal("40"), basis, "Fixture conditions"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var measured = power(new BigDecimal("30.25"), "MEASURED_ACTIVE", "Fixture: SDR, 60 Hz, 150 cd/m2");
        assertThat(measured.activePowerBasis()).isEqualTo("MEASURED_ACTIVE");
        for (String watts : List.of("0", "-1", "30.251", "1000000")) {
            assertThatThrownBy(() -> power(new BigDecimal(watts), "MEASURED_ACTIVE", "Fixture conditions"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void marketingPanelLabelsAreNotSilentlyReinterpretedAndProductTypeMustMatch() {
        for (String panel : List.of("Nano IPS", "Mini LED", "UNKNOWN")) {
            assertThatThrownBy(() -> new CatalogSpecification.Monitor(null, null, null,
                    panel, null, null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var unknown = resolution(null, null);
        assertThat(unknown.panelType()).isNull();
        var otherProduct = new CatalogProduct(PartType.GPU, "Fixture", "Wrong type", null);
        assertThatThrownBy(() -> new MonitorSpec(otherProduct, unknown))
                .isInstanceOf(IllegalArgumentException.class);
        var monitorProduct = new CatalogProduct(PartType.MONITOR, "Fixture", "Monitor", null);
        assertThatThrownBy(() -> new MonitorSpec(monitorProduct, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private CatalogSpecification.Monitor resolution(Integer width, Integer height) {
        return new CatalogSpecification.Monitor(null, width, height, null, null, null, null, null, null, null);
    }

    private CatalogSpecification.Monitor refresh(BigDecimal standard, Boolean support, BigDecimal overclock) {
        return new CatalogSpecification.Monitor(null, 2560, 1440, null,
                standard, support, overclock, null, null, null);
    }

    private CatalogSpecification.Monitor power(BigDecimal watts, String basis, String conditions) {
        return new CatalogSpecification.Monitor(null, null, null, null, null, null, null, watts, basis, conditions);
    }
}
