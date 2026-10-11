package com.pcupgradelab.compatibility;

import com.pcupgradelab.catalog.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static com.pcupgradelab.compatibility.CompatibilityDtos.Status.*;
import static org.assertj.core.api.Assertions.assertThat;

class HardwareCompatibilityRulesTests {
    private static final CatalogDtos.Source SOURCE = new CatalogDtos.Source(CatalogSourceName.MANUFACTURER,
            "fixture", null, "https://manufacturer.example/hardware", Instant.EPOCH);
    private final Map<String, CatalogDtos.Detail> details = new HashMap<>(Map.of(
            "board", detail("board", new CatalogSpecification.Motherboard("AM4", "B550", "ATX", "DDR4", "DIMM", 4, null, false)),
            "gpu", detail("gpu", gpu("300", "MINIMUM", List.of(new CatalogSpecification.GpuPowerConnector("PCIE_8PIN", 1)))),
            "psu", detail("psu", psu(750, List.of(new CatalogSpecification.PsuPowerConnector("PCIE_8PIN", 1, 1, 225)))),
            "case", detail("case", enclosure("350", true, true))));

    @Test
    void verifiedPublishedLimitsPassTheirDeclaredScopeAndRetainEvidenceWithoutFullPcClaim() {
        var result = run("gpu", "psu", "case", null);
        assertThat(result.scope()).isEqualTo(HardwareCompatibilityRules.SCOPE);
        assertThat(result.fullPcCompatibilityChecked()).isFalse();
        assertThat(result.status()).isEqualTo(COMPATIBLE);
        assertThat(result.checks()).allSatisfy(check -> {
            assertThat(check.status()).isEqualTo(COMPATIBLE);
            assertThat(check.evidence()).containsExactly(SOURCE);
        });
        assertThat(result.components()).extracting(CompatibilityDtos.Component::field)
                .containsExactly("gpuProductId", "psuProductId", "caseProductId");
        assertThat(check(result, "GPU_PSU_WATTAGE").message()).contains("전체 PC 전력 여유는 별도");
    }

    @Test
    void unlinkedPresentComponentNeedsCheckingWhileAbsentComponentsDoNotCreateChecks() {
        var result = run(null, null, null, List.of("GPU"));
        assertThat(result.checks()).extracting(CompatibilityDtos.Check::code).containsExactly("CATALOG_LINK_gpuProductId");
        assertThat(result.status()).isEqualTo(NEEDS_CHECK);
        assertThat(check(result, "CATALOG_LINK_gpuProductId").affectedFields()).containsExactly("gpuProductId");
        assertThat(run("gpu", null, null, null).checks()).extracting(CompatibilityDtos.Check::code).containsExactly("GPU_PAIR_SELECTION");
    }

    @Test
    void mechanicalBoundariesUseExactDecimalComparisonAndNeverRoundAnOversizedCardIntoAFit() {
        details.put("case", detail("case", enclosure("300", true, true)));
        assertThat(check(run("gpu", null, "case", null), "GPU_CASE_LENGTH").status()).isEqualTo(COMPATIBLE);
        details.put("gpu", detail("gpu", gpu("300.01", "MINIMUM", List.of())));
        var result = run("gpu", null, "case", null);
        assertThat(check(result, "GPU_CASE_LENGTH").status()).isEqualTo(INCOMPATIBLE);
        assertThat(result.status()).isEqualTo(INCOMPATIBLE);
    }

    @Test
    void conditionalSpacesCannotProveFitOrFailureAndUnknownDimensionsCannotBecomeUnlimited() {
        details.put("case", detail("case", enclosure("250", false, false)));
        var conditional = run("gpu", "psu", "case", null);
        assertThat(check(conditional, "GPU_CASE_LENGTH").status()).isEqualTo(NEEDS_CHECK);
        assertThat(check(conditional, "PSU_CASE_LENGTH").status()).isEqualTo(NEEDS_CHECK);
        details.put("case", detail("case", new CatalogSpecification.Case(false, List.of(), false, List.of(),
                null, null, null, null, null, null, null, false, false)));
        var unknown = run("gpu", "psu", "case", null);
        for (String code : List.of("GPU_CASE_LENGTH", "GPU_CASE_HEIGHT", "GPU_CASE_THICKNESS", "GPU_CASE_SLOTS", "PSU_CASE_FORM", "PSU_CASE_LENGTH"))
            assertThat(check(unknown, code).status()).as(code).isEqualTo(NEEDS_CHECK);
    }

    @Test
    void completeMountingListsCanProveMismatchButEatxLabelAloneDoesNotProveDimensions() {
        details.put("case", detail("case", new CatalogSpecification.Case(true, List.of("MINI_ITX"), true, List.of("SFX"),
                decimal("180"), decimal("350"), decimal("150"), decimal("60"), 7, null, null, true, true)));
        var mismatch = run(null, "psu", "case", null);
        assertThat(check(mismatch, "MOTHERBOARD_CASE_FORM").status()).isEqualTo(INCOMPATIBLE);
        assertThat(check(mismatch, "PSU_CASE_FORM").status()).isEqualTo(INCOMPATIBLE);
        details.put("board", detail("board", new CatalogSpecification.Motherboard("AM4", "B550", "E_ATX", "DDR4", "DIMM", 4, null, false)));
        details.put("case", detail("case", new CatalogSpecification.Case(true, List.of("E_ATX"), true, List.of("ATX"),
                decimal("180"), null, null, null, 7, null, null, false, true)));
        assertThat(check(run(null, "psu", "case", null), "MOTHERBOARD_CASE_FORM").status()).isEqualTo(NEEDS_CHECK);
    }

    @Test
    void confirmedMinimumOutputShortageFailsButRecommendationShortageOnlyRequestsReview() {
        details.put("psu", detail("psu", psu(600, List.of(new CatalogSpecification.PsuPowerConnector("PCIE_8PIN", 1, 1, 225)))));
        assertThat(check(run("gpu", "psu", null, null), "GPU_PSU_WATTAGE").status()).isEqualTo(INCOMPATIBLE);
        details.put("gpu", detail("gpu", gpu("300", "RECOMMENDED", List.of(new CatalogSpecification.GpuPowerConnector("PCIE_8PIN", 1)))));
        assertThat(check(run("gpu", "psu", null, null), "GPU_PSU_WATTAGE").status()).isEqualTo(NEEDS_CHECK);
        details.put("psu", detail("psu", psu(650, List.of(new CatalogSpecification.PsuPowerConnector("PCIE_8PIN", 1, 1, 225)))));
        assertThat(check(run("gpu", "psu", null, null), "GPU_PSU_WATTAGE").status()).isEqualTo(COMPATIBLE);
    }

    @Test
    void sixPlusTwoPlugsAreAllocatedOnceAcrossSixAndEightPinNeeds() {
        details.put("gpu", detail("gpu", gpu("300", "MINIMUM", List.of(
                new CatalogSpecification.GpuPowerConnector("PCIE_8PIN", 1), new CatalogSpecification.GpuPowerConnector("PCIE_6PIN", 1)))));
        details.put("psu", detail("psu", psu(750, List.of(new CatalogSpecification.PsuPowerConnector("PCIE_6_2PIN", 1, 1, 225)))));
        assertThat(check(run("gpu", "psu", null, null), "GPU_PSU_CONNECTORS").status()).isEqualTo(INCOMPATIBLE);
        details.put("psu", detail("psu", psu(750, List.of(new CatalogSpecification.PsuPowerConnector("PCIE_6_2PIN", 2, 2, 150)))));
        assertThat(check(run("gpu", "psu", null, null), "GPU_PSU_CONNECTORS").status()).isEqualTo(COMPATIBLE);
    }

    @Test
    void plugCountDoesNotStandInForIndependentCableCountOrUnknownCablePower() {
        details.put("gpu", detail("gpu", gpu("300", "MINIMUM", List.of(new CatalogSpecification.GpuPowerConnector("PCIE_8PIN", 2)))));
        for (Integer independent : new Integer[]{1, null}) {
            details.put("psu", detail("psu", psu(750, List.of(new CatalogSpecification.PsuPowerConnector("PCIE_6_2PIN", 2, independent, 150)))));
            assertThat(check(run("gpu", "psu", null, null), "GPU_PSU_CONNECTORS").status()).isEqualTo(NEEDS_CHECK);
        }
        details.put("psu", detail("psu", psu(750, List.of(new CatalogSpecification.PsuPowerConnector("PCIE_6_2PIN", 2, 2, null)))));
        var unknownPower = run("gpu", "psu", null, null);
        assertThat(check(unknownPower, "GPU_PSU_CONNECTORS").status()).isEqualTo(COMPATIBLE);
        assertThat(check(unknownPower, "GPU_PSU_CABLE_POWER").status()).isEqualTo(NEEDS_CHECK);
        details.put("psu", detail("psu", psu(750, List.of(new CatalogSpecification.PsuPowerConnector("PCIE_6_2PIN", 2, 2, 50)))));
        assertThat(check(run("gpu", "psu", null, null), "GPU_PSU_CABLE_POWER").status()).isEqualTo(NEEDS_CHECK);
    }

    @Test
    void sixteenPinVariantsAndUnspecifiedSixteenPinCannotBeSubstitutedByShapeOrAdapters() {
        details.put("psu", detail("psu", psu(750, List.of(new CatalogSpecification.PsuPowerConnector("PCIE_12V_2X6", 1, 1, 600)))));
        for (String type : List.of("PCIE_12VHPWR", "PCIE_16PIN_UNSPECIFIED")) {
            details.put("gpu", detail("gpu", gpu("300", "MINIMUM", List.of(new CatalogSpecification.GpuPowerConnector(type, 1)))));
            assertThat(check(run("gpu", "psu", null, null), "GPU_PSU_CONNECTORS").status()).isEqualTo(NEEDS_CHECK);
        }
        details.put("gpu", detail("gpu", gpu("300", "MINIMUM", List.of(new CatalogSpecification.GpuPowerConnector("PCIE_12V_2X6", 1)))));
        assertThat(check(run("gpu", "psu", null, null), "GPU_PSU_CONNECTORS").status()).isEqualTo(COMPATIBLE);
    }

    @Test
    void missingConnectorInventoryIsUnknownWhileConfirmedNoAuxiliaryInputIsASeparateFact() {
        details.put("psu", detail("psu", new CatalogSpecification.Psu(750, "ATX", decimal("150"), null, null, null, false, List.of())));
        assertThat(check(run("gpu", "psu", null, null), "GPU_PSU_CONNECTORS").status()).isEqualTo(NEEDS_CHECK);
        details.put("gpu", detail("gpu", gpu("300", "MINIMUM", List.of())));
        assertThat(check(run("gpu", "psu", null, null), "GPU_PSU_CONNECTORS").status()).isEqualTo(COMPATIBLE);
        details.put("psu", detail("psu", psu(750, List.of())));
        assertThat(check(run("gpu", "psu", null, null), "GPU_PSU_CONNECTORS").status()).isEqualTo(COMPATIBLE);
    }

    @Test
    void confirmedStandardPlugShortageIsNotOverriddenByAnotherUnknownSixteenPinRequirement() {
        details.put("psu", detail("psu", psu(750, List.of(new CatalogSpecification.PsuPowerConnector("PCIE_12V_2X6", 1, 1, 600)))));
        for (String type : List.of("PCIE_12VHPWR", "PCIE_16PIN_UNSPECIFIED")) {
            details.put("gpu", detail("gpu", gpu("300", "MINIMUM", List.of(
                    new CatalogSpecification.GpuPowerConnector(type, 1), new CatalogSpecification.GpuPowerConnector("PCIE_8PIN", 1)))));
            assertThat(check(run("gpu", "psu", null, null), "GPU_PSU_CONNECTORS").status()).isEqualTo(INCOMPATIBLE);
        }
    }

    @Test
    void summedCablePowerDoesNotProveIndividualCableLoadsAreWithinRatings() {
        details.put("gpu", detail("gpu", gpu("300", "MINIMUM", List.of(
                new CatalogSpecification.GpuPowerConnector("PCIE_8PIN", 1), new CatalogSpecification.GpuPowerConnector("PCIE_6PIN", 1)))));
        details.put("psu", detail("psu", psu(750, List.of(
                new CatalogSpecification.PsuPowerConnector("PCIE_8PIN", 1, 1, 300),
                new CatalogSpecification.PsuPowerConnector("PCIE_6PIN", 1, 1, 50)))));
        var result = run("gpu", "psu", null, null);
        assertThat(check(result, "GPU_PSU_CONNECTORS").status()).isEqualTo(COMPATIBLE);
        assertThat(check(result, "GPU_PSU_CABLE_POWER").status()).isEqualTo(NEEDS_CHECK);
        assertThat(check(result, "GPU_PSU_CABLE_POWER").message()).contains("합계는 충분하지만", "개별 케이블");
        assertThat(result.status()).isEqualTo(NEEDS_CHECK);
    }

    @Test
    void confirmedMinimumOutputFailureWinsWhenConnectorInventoryAndDimensionsAreUnknown() {
        details.put("psu", detail("psu", new CatalogSpecification.Psu(600, null, null, null, null, null, false, List.of())));
        details.put("case", detail("case", new CatalogSpecification.Case(false, List.of(), false, List.of(),
                null, null, null, null, null, null, null, false, false)));
        var result = run("gpu", "psu", "case", null);
        assertThat(check(result, "GPU_PSU_WATTAGE").status()).isEqualTo(INCOMPATIBLE);
        assertThat(check(result, "GPU_PSU_CONNECTORS").status()).isEqualTo(NEEDS_CHECK);
        assertThat(check(result, "GPU_CASE_LENGTH").status()).isEqualTo(NEEDS_CHECK);
        assertThat(result.status()).isEqualTo(INCOMPATIBLE);
    }

    @Test
    void previousCpuRamFailureCannotBeOverriddenByPassingHardwareChecks() {
        var base = new CompatibilityDtos.Result("CPU_MOTHERBOARD_RAM_V1", INCOMPATIBLE, false,
                List.of(new CompatibilityDtos.Check("CPU_SOCKET", INCOMPATIBLE, "소켓이 다릅니다.",
                        List.of("cpuProductId", "motherboardProductId"), List.of(), List.of(SOURCE))),
                summary(), List.of(), List.of(), List.of(), CatalogDtos.ATTRIBUTIONS);
        var result = new HardwareCompatibilityRules(request("gpu", "psu", null, null), details).extend(base);
        assertThat(result.status()).isEqualTo(INCOMPATIBLE);
        assertThat(check(result, "CPU_SOCKET").status()).isEqualTo(INCOMPATIBLE);
    }

    private CompatibilityDtos.Result run(String gpu, String psu, String enclosure, List<String> present) {
        return new HardwareCompatibilityRules(request(gpu, psu, enclosure, present), details).extend(
                new CompatibilityDtos.Result("CPU_MOTHERBOARD_RAM_V1", COMPATIBLE, false, List.of(), summary(),
                        List.of(), List.of(), List.of(), CatalogDtos.ATTRIBUTIONS));
    }
    private static CompatibilityDtos.Request request(String gpu, String psu, String enclosure, List<String> present) {
        return new CompatibilityDtos.Request(null, "board", List.of(), null, null, null, gpu, psu, enclosure, present);
    }
    private static CompatibilityDtos.MemorySummary summary() { return new CompatibilityDtos.MemorySummary(0, BigInteger.ZERO, null, false); }
    private static CompatibilityDtos.Check check(CompatibilityDtos.Result result, String code) {
        return result.checks().stream().filter(item -> item.code().equals(code)).findFirst().orElseThrow();
    }
    private static CatalogDtos.Detail detail(String id, CatalogSpecification spec) {
        return new CatalogDtos.Detail(new CatalogProductView(id, spec.type(), "Fixture", id, null,
                CatalogVerificationStatus.UNVERIFIED, false, Instant.EPOCH, Instant.EPOCH, null), spec, List.of(SOURCE), CatalogDtos.ATTRIBUTIONS);
    }
    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
    private static CatalogSpecification.Gpu gpu(String length, String basis, List<CatalogSpecification.GpuPowerConnector> connectors) {
        return new CatalogSpecification.Gpu("NVIDIA", "Fixture GPU", null, null, null, null, null,
                decimal(length), decimal("130"), decimal("50"), decimal("2"), decimal("200"), "MANUFACTURER", 650, basis, true, connectors);
    }
    private static CatalogSpecification.Psu psu(int watts, List<CatalogSpecification.PsuPowerConnector> connectors) {
        return new CatalogSpecification.Psu(watts, "ATX", decimal("150"), decimal("150"), decimal("86"), null, true, connectors);
    }
    private static CatalogSpecification.Case enclosure(String gpuLength, boolean gpuUnconditional, boolean psuUnconditional) {
        return new CatalogSpecification.Case(true, List.of("ATX", "MICRO_ATX", "MINI_ITX"), true, List.of("ATX"), decimal("180"),
                decimal(gpuLength), decimal("150"), decimal("60"), 7, null, null, gpuUnconditional, psuUnconditional);
    }
}
