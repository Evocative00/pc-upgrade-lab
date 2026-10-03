package com.pcupgradelab.compatibility;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.catalog.memory.CpuMemoryQueryService;
import com.pcupgradelab.catalog.memory.CpuMemorySupport;
import com.pcupgradelab.catalog.support.MotherboardCpuQueryService;
import com.pcupgradelab.catalog.support.MotherboardCpuSupport;
import java.math.BigInteger;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static com.pcupgradelab.compatibility.CompatibilityDtos.Status.*;
import static org.assertj.core.api.Assertions.assertThat;

class CompatibilityRulesTests {
    private static final long GIB = 1L << 30;
    private static final String URL = "https://www.asrock.com/MB/AMD/B550M%20Pro4/index.asp";
    private static final CatalogDtos.Source SOURCE = new CatalogDtos.Source(CatalogSourceName.MANUFACTURER,
            "fixture", "fixture-v1", URL, Instant.parse("2026-10-02T00:00:00Z"));
    private final Map<String, CatalogDtos.Detail> details = new HashMap<>(Map.of(
            "cpu", detail("cpu", new CatalogSpecification.Cpu("AM4", 6, 12, null, null, null, false, null)),
            "board", detail("board", new CatalogSpecification.Motherboard("AM4", "B550", "MICRO_ATX",
                    "DDR4", "DIMM", 4, 128 * GIB, null)),
            "ram", detail("ram", ram("DDR4", 8 * GIB, 2, 3200, "DIMM", false, "UNBUFFERED"))));
    private CpuMemoryQueryService.View memory = memory(true, 128 * GIB, "DDR4");
    private MotherboardCpuQueryService.View support = support(MotherboardCpuSupport.RevisionScope.MODEL,
            null, List.of(entry("B0", "B0", MotherboardCpuSupport.BiosRequirement.VERSION, "P1.20")));

    @Test
    void exactMinimumBiosAndConfirmedConditionsPassOnlyWithinDeclaredScopeWithoutCountingKitsTwice() {
        var result = run(List.of(new CompatibilityDtos.RamInput("ram", 2)), null, "B0", "P1.20");
        assertThat(result.status()).isEqualTo(COMPATIBLE);
        assertThat(result.scope()).isEqualTo("CPU_MOTHERBOARD_RAM_V1");
        assertThat(result.fullPcCompatibilityChecked()).isFalse();
        assertThat(result.memory().installedModuleCount()).isEqualTo(2);
        assertThat(result.memory().totalCapacityBytes()).isEqualTo(BigInteger.valueOf(16 * GIB));
        assertThat(result.attributions()).isEqualTo(CatalogDtos.ATTRIBUTIONS);
        assertThat(result.components()).allSatisfy(component ->
                assertThat(component.catalogVerificationStatus()).isEqualTo(CatalogVerificationStatus.UNVERIFIED));
        assertThat(result.checks()).allSatisfy(check -> assertThat(check.status()).isEqualTo(COMPATIBLE));
        assertThat(check(result, "CPU_BIOS").evidence()).containsExactly(SOURCE);
    }

    @Test
    void duplicateCatalogModulesRepresentDifferentInstalledModulesAndAreNotDeduplicated() {
        var result = run(List.of(new CompatibilityDtos.RamInput("ram", 1),
                new CompatibilityDtos.RamInput("ram", 1)), null, "B0", "P1.20");
        assertThat(result.status()).isEqualTo(COMPATIBLE);
        assertThat(result.memory().installedModuleCount()).isEqualTo(2);
        assertThat(result.memory().totalCapacityBytes()).isEqualTo(BigInteger.valueOf(16 * GIB));
    }

    @Test
    void aDifferentBiosIsNeverOrderedAsAStringOrInferredToBeUnsupported() {
        for (String version : List.of("P1.9", "P9.99", "P1.20.zip", "p1.20")) {
            var result = run(List.of(new CompatibilityDtos.RamInput("ram", 2)), null, "B0", version);
            assertThat(result.status()).as(version).isEqualTo(NEEDS_CHECK);
            assertThat(check(result, "CPU_BIOS").status()).isEqualTo(NEEDS_CHECK);
            assertThat(result.checks()).noneMatch(check -> check.status() == INCOMPATIBLE);
        }
    }

    @Test
    void missingSteppingPreservesDistinctMinimumBiosCandidatesRatherThanChoosingOne() {
        support = support(MotherboardCpuSupport.RevisionScope.MODEL, null, List.of(
                entry("B0", "B0", MotherboardCpuSupport.BiosRequirement.VERSION, "P1.20"),
                entry("B2", "B2", MotherboardCpuSupport.BiosRequirement.VERSION, "P1.80")));
        var result = run(List.of(new CompatibilityDtos.RamInput("ram", 2)), null, null, "P1.20");
        assertThat(result.status()).isEqualTo(NEEDS_CHECK);
        assertThat(check(result, "CPU_MANUFACTURER_SUPPORT").requiredInformation()).contains("cpuStepping");
        assertThat(result.cpuSupportCandidates()).extracting(CompatibilityDtos.SupportCandidate::minimumBiosVersion)
                .containsExactly("P1.20", "P1.80");
        assertThat(check(run(List.of(new CompatibilityDtos.RamInput("ram", 2)), null, "B2", "P1.20"), "CPU_BIOS")
                .status()).isEqualTo(NEEDS_CHECK);
    }

    @Test
    void allBiosDoesNotRequireCurrentVersionButUnknownBetaBiosAlwaysRequiresReview() {
        support = support(MotherboardCpuSupport.RevisionScope.MODEL, null,
                List.of(entry("B0", "B0", MotherboardCpuSupport.BiosRequirement.ALL, null)));
        assertThat(run(List.of(new CompatibilityDtos.RamInput("ram", 2)), null, "B0", null).status())
                .isEqualTo(COMPATIBLE);
        support = support(MotherboardCpuSupport.RevisionScope.MODEL, null,
                List.of(entry("B0", "B0", MotherboardCpuSupport.BiosRequirement.UNKNOWN, null)));
        var beta = run(List.of(new CompatibilityDtos.RamInput("ram", 2)), null, "B0", "Latest Beta BIOS");
        assertThat(check(beta, "CPU_MANUFACTURER_SUPPORT").status()).isEqualTo(COMPATIBLE);
        assertThat(check(beta, "CPU_BIOS").status()).isEqualTo(NEEDS_CHECK);
    }

    @Test
    void revisionQualifiedSupportIsNotAppliedToAnUnknownOrDifferentPcb() {
        support = support(MotherboardCpuSupport.RevisionScope.EXACT, "1.0",
                List.of(entry("default", null, MotherboardCpuSupport.BiosRequirement.VERSION, "P1.20")));
        for (String revision : new String[]{null, "2.0"}) {
            var result = run(List.of(new CompatibilityDtos.RamInput("ram", 2)), revision, null, "P1.20");
            assertThat(check(result, "CPU_MANUFACTURER_SUPPORT").status()).isEqualTo(NEEDS_CHECK);
            assertThat(result.status()).isEqualTo(NEEDS_CHECK);
        }
        assertThat(run(List.of(new CompatibilityDtos.RamInput("ram", 2)), "1.0", null, "P1.20").status())
                .isEqualTo(COMPATIBLE);
    }

    @Test
    void reportedSteppingHyphensAndExplicitSlashAlternativesAreAcceptedWithoutInventingOtherSteppings() {
        support = support(MotherboardCpuSupport.RevisionScope.MODEL, null,
                List.of(entry("B-0", "B-0", MotherboardCpuSupport.BiosRequirement.VERSION, "P1.20")));
        assertThat(run(List.of(new CompatibilityDtos.RamInput("ram", 2)), null, "B0", "P1.20").status())
                .isEqualTo(COMPATIBLE);
        support = support(MotherboardCpuSupport.RevisionScope.MODEL, null,
                List.of(entry("C0-H0", "C0 / H0", MotherboardCpuSupport.BiosRequirement.VERSION, "P1.20")));
        assertThat(run(List.of(new CompatibilityDtos.RamInput("ram", 2)), null, "H0", "P1.20").status())
                .isEqualTo(COMPATIBLE);
        assertThat(run(List.of(new CompatibilityDtos.RamInput("ram", 2)), null, "B2", "P1.20").status())
                .isEqualTo(NEEDS_CHECK);
    }

    @Test
    void sameSocketWithoutListedManufacturerSupportDoesNotPassAndUnverifiedIsNotUnsupported() {
        support = support(MotherboardCpuSupport.RevisionScope.MODEL, null, List.of());
        var missing = run(List.of(new CompatibilityDtos.RamInput("ram", 2)), null, "B0", "P1.20");
        assertThat(check(missing, "CPU_SOCKET").status()).isEqualTo(COMPATIBLE);
        assertThat(check(missing, "CPU_MANUFACTURER_SUPPORT").status()).isEqualTo(NEEDS_CHECK);
        support = support(MotherboardCpuSupport.RevisionScope.MODEL, null, List.of(
                new MotherboardCpuSupport.Entry("cpu", "unverified", MotherboardCpuSupport.SupportStatus.UNVERIFIED,
                        "fixture CPU", null, MotherboardCpuSupport.BiosRequirement.UNKNOWN, null, null, URL, "Unverified fixture")));
        assertThat(run(List.of(new CompatibilityDtos.RamInput("ram", 2)), null, null, null).status())
                .isEqualTo(NEEDS_CHECK);
    }

    @Test
    void definiteSocketMismatchWinsEvenWhenBiosAndSupportInformationAreMissing() {
        details.put("cpu", detail("cpu", new CatalogSpecification.Cpu("LGA1700", 6, 12, null, null, null, false, null)));
        support = null;
        var result = run(List.of(new CompatibilityDtos.RamInput("ram", 2)), null, null, null);
        assertThat(result.status()).isEqualTo(INCOMPATIBLE);
        assertThat(check(result, "CPU_SOCKET").status()).isEqualTo(INCOMPATIBLE);
        assertThat(check(result, "CPU_BIOS").status()).isEqualTo(NEEDS_CHECK);
    }

    @Test
    void cpuSupportForBothDdrTypesDoesNotGiveADdr4BoardSupportForDdr5() {
        memory = memory(true, 128 * GIB, "DDR4", "DDR5");
        details.put("ram", detail("ram", ram("DDR5", 8 * GIB, 1, 6000, "DIMM", false, "UNBUFFERED")));
        var result = run(List.of(new CompatibilityDtos.RamInput("ram", 2)), null, "B0", "P1.20");
        assertThat(check(result, "CPU_RAM_TYPE_0").status()).isEqualTo(COMPATIBLE);
        assertThat(check(result, "MOTHERBOARD_RAM_TYPE_0").status()).isEqualTo(INCOMPATIBLE);
        assertThat(result.status()).isEqualTo(INCOMPATIBLE);
    }

    @Test
    void absentDdrEntryFailsOnlyForACompleteCpuMemoryTypeList() {
        details.put("ram", detail("ram", ram("DDR5", 8 * GIB, 1, 6000, "DIMM", false, "UNBUFFERED")));
        memory = memory(false, 128 * GIB, "DDR4");
        assertThat(check(run(List.of(new CompatibilityDtos.RamInput("ram", 2)), null, "B0", "P1.20"), "CPU_RAM_TYPE_0").status())
                .isEqualTo(NEEDS_CHECK);
        memory = memory(true, 128 * GIB, "DDR4");
        assertThat(check(run(List.of(new CompatibilityDtos.RamInput("ram", 2)), null, "B0", "P1.20"), "CPU_RAM_TYPE_0").status())
                .isEqualTo(INCOMPATIBLE);
    }

    @Test
    void formFactorAndSlotCountAreCheckedUsingActualInstalledModules() {
        details.put("ram", detail("ram", ram("DDR4", 8 * GIB, 1, 3200, "SODIMM", false, "UNBUFFERED")));
        var result = run(List.of(new CompatibilityDtos.RamInput("ram", 5)), null, "B0", "P1.20");
        assertThat(check(result, "MOTHERBOARD_RAM_FORM_0").status()).isEqualTo(INCOMPATIBLE);
        assertThat(check(result, "RAM_SLOT_COUNT").status()).isEqualTo(INCOMPATIBLE);
        assertThat(result.memory().installedModuleCount()).isEqualTo(5);
    }

    @Test
    void incompleteCapacityStaysUnknownButKnownSubtotalCanAlreadyProveExceededLimit() {
        details.put("ram", detail("ram", ram("DDR4", 32 * GIB, 1, 3200, "DIMM", false, "UNBUFFERED")));
        var result = run(List.of(new CompatibilityDtos.RamInput("ram", 5),
                new CompatibilityDtos.RamInput(null, 1)), null, "B0", "P1.20");
        assertThat(result.memory().totalCapacityBytes()).isNull();
        assertThat(result.memory().capacityComplete()).isFalse();
        assertThat(result.memory().knownCapacityBytes()).isEqualTo(BigInteger.valueOf(160 * GIB));
        assertThat(check(result, "CPU_RAM_CAPACITY").status()).isEqualTo(INCOMPATIBLE);
        assertThat(check(result, "MOTHERBOARD_RAM_CAPACITY").status()).isEqualTo(INCOMPATIBLE);
        assertThat(result.status()).isEqualTo(INCOMPATIBLE);
    }

    @Test
    void unknownMaximumAndUnknownModuleCapacityAreNotConvertedToZeroOrUnlimited() {
        memory = memory(true, null, "DDR4");
        var result = run(List.of(new CompatibilityDtos.RamInput("ram", 2)), null, "B0", "P1.20");
        assertThat(result.status()).isEqualTo(NEEDS_CHECK);
        assertThat(check(result, "CPU_RAM_CAPACITY").requiredInformation()).contains("cpuMemorySupport.maxMemoryBytes");
        details.put("ram", detail("ram", ram("DDR4", null, 1, 3200, "DIMM", false, "UNBUFFERED")));
        result = run(List.of(new CompatibilityDtos.RamInput("ram", 2)), null, "B0", "P1.20");
        assertThat(result.memory().totalCapacityBytes()).isNull();
        assertThat(check(result, "MOTHERBOARD_RAM_CAPACITY").status()).isEqualTo(NEEDS_CHECK);
        assertThat(run(List.of(), null, "B0", "P1.20").status()).isEqualTo(NEEDS_CHECK);
    }

    @Test
    void ratedSpeedAboveCpuStandardIsANoticeAndEccOrRegisteredMemoryRequiresSeparateEvidence() {
        details.put("ram", detail("ram", ram("DDR4", 8 * GIB, 2, 3600, "DIMM", false, "UNBUFFERED")));
        var result = run(List.of(new CompatibilityDtos.RamInput("ram", 2)), null, "B0", "P1.20");
        assertThat(result.status()).isEqualTo(COMPATIBLE);
        assertThat(result.notices()).anyMatch(notice -> notice.contains("3600 MT/s") && notice.contains("3200 MT/s"));
        details.put("ram", detail("ram", ram("DDR4", 8 * GIB, 1, 3200, "DIMM", true, "REGISTERED")));
        assertThat(run(List.of(new CompatibilityDtos.RamInput("ram", 2)), null, "B0", "P1.20").status())
                .isEqualTo(NEEDS_CHECK);
    }

    @Test
    void largeCapacityMathCannotOverflowAndTurnAnExceededLimitIntoACompatibleResult() {
        details.put("ram", detail("ram", ram("DDR4", Long.MAX_VALUE, 1, 3200, "DIMM", false, "UNBUFFERED")));
        var result = run(List.of(new CompatibilityDtos.RamInput("ram", 64)), null, "B0", "P1.20");
        assertThat(result.memory().totalCapacityBytes()).isEqualTo(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(64)));
        assertThat(check(result, "CPU_RAM_CAPACITY").status()).isEqualTo(INCOMPATIBLE);
    }

    private CompatibilityDtos.Result run(List<CompatibilityDtos.RamInput> ram, String revision, String stepping, String bios) {
        return new CompatibilityRules(new CompatibilityDtos.Request("cpu", "board", ram, revision, stepping, bios),
                details, memory, support).evaluate();
    }
    private static CompatibilityDtos.Check check(CompatibilityDtos.Result result, String code) {
        return result.checks().stream().filter(item -> item.code().equals(code)).findFirst().orElseThrow();
    }
    private static CatalogDtos.Detail detail(String id, CatalogSpecification specification) {
        return new CatalogDtos.Detail(new CatalogProductView(id, specification.type(), "Fixture", id, null,
                CatalogVerificationStatus.UNVERIFIED, false, Instant.EPOCH, Instant.EPOCH, null),
                specification, List.of(SOURCE), CatalogDtos.ATTRIBUTIONS);
    }
    private static CatalogSpecification.Ram ram(String type, Long bytes, int kitModules, int speed,
                                                String form, boolean ecc, String buffer) {
        return new CatalogSpecification.Ram(type, bytes, kitModules, speed, form, 288, ecc, buffer, null, null);
    }
    private static CpuMemoryQueryService.View memory(boolean complete, Long max, String... types) {
        return new CpuMemoryQueryService.View("cpu", true, complete, max, 2, "Fixture capacity conditions",
                java.util.Arrays.stream(types).map(type -> new CpuMemorySupport.TypeSupport(
                        CpuMemorySupport.MemoryType.valueOf(type), 3200, "Fixture rate conditions")).toList(), SOURCE);
    }
    private static MotherboardCpuQueryService.View support(MotherboardCpuSupport.RevisionScope scope, String revision,
                                                            List<MotherboardCpuSupport.Entry> entries) {
        return new MotherboardCpuQueryService.View("board", "cpu", true, false, !entries.isEmpty(), "Partial list",
                List.of(new MotherboardCpuQueryService.Profile(scope == MotherboardCpuSupport.RevisionScope.MODEL ? "MODEL" : "REV:" + revision,
                        scope, revision, "Fixture profile conditions",
                        entries.stream().map(entry -> new MotherboardCpuQueryService.Entry("fixture CPU", entry)).toList(), SOURCE)));
    }
    private static MotherboardCpuSupport.Entry entry(String key, String stepping,
                                                      MotherboardCpuSupport.BiosRequirement bios, String minimum) {
        return new MotherboardCpuSupport.Entry("cpu", key, MotherboardCpuSupport.SupportStatus.LISTED, "fixture CPU",
                stepping, bios, minimum, bios == MotherboardCpuSupport.BiosRequirement.ALL ? "ALL"
                        : bios == MotherboardCpuSupport.BiosRequirement.UNKNOWN ? "Latest Beta BIOS" : minimum, URL, "Fixture entry conditions");
    }
}
