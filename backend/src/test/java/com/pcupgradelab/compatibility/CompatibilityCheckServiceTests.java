package com.pcupgradelab.compatibility;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.catalog.memory.CpuMemoryQueryService;
import com.pcupgradelab.catalog.support.MotherboardCpuQueryService;
import com.pcupgradelab.common.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CompatibilityCheckServiceTests {
    private final CatalogQueryService catalog = mock(CatalogQueryService.class);
    private final CpuMemoryQueryService memory = mock(CpuMemoryQueryService.class);
    private final MotherboardCpuQueryService support = mock(MotherboardCpuQueryService.class);
    private final CompatibilityCheckService service = new CompatibilityCheckService(catalog, memory, support);

    @Test
    void legacyRequestKeepsV1AndHardwareOnlyRequestDoesNotGenerateCpuOrRamSelectionNoise() {
        var legacy = new CompatibilityDtos.Request(null, null, List.of(), null, null, null);
        assertThat(service.check(legacy).scope()).isEqualTo("CPU_MOTHERBOARD_RAM_V1");
        assertThat(legacy.hardwareScopeRequested()).isFalse();
        var emptyPresence = request(null, null, null, List.of());
        assertThat(emptyPresence.hardwareScopeRequested()).isFalse();
        assertThat(service.check(emptyPresence).status()).isEqualTo(CompatibilityDtos.Status.NEEDS_CHECK);
        assertThat(service.check(emptyPresence).checks()).isNotEmpty();
        var present = request(null, null, null, List.of("GPU"));
        var result = service.check(present);
        assertThat(result.scope()).isEqualTo(HardwareCompatibilityRules.SCOPE);
        assertThat(result.checks()).extracting(CompatibilityDtos.Check::code).containsExactly("CATALOG_LINK_gpuProductId");
        assertThat(result.fullPcCompatibilityChecked()).isFalse();
        verifyNoInteractions(catalog, memory, support);
    }

    @Test
    void selectedHardwareIdsAreLoadedAndTypedEvenWhenLegacyPresenceListIsAbsent() {
        var gpu = new CatalogSpecification.Gpu("NVIDIA", "Fixture", null, null, null, null, null,
                null, null, null, null, null, null, 650, "RECOMMENDED", true, List.of());
        var psu = new CatalogSpecification.Psu(750, "ATX", new BigDecimal("150"), null, null, null, true, List.of());
        when(catalog.findById("gpu")).thenReturn(detail("gpu", gpu));
        when(catalog.findById("psu")).thenReturn(detail("psu", psu));
        var result = service.check(request("gpu", "psu", null, null));
        assertThat(result.scope()).isEqualTo(HardwareCompatibilityRules.SCOPE);
        assertThat(result.checks()).extracting(CompatibilityDtos.Check::code).containsExactly("GPU_PSU_WATTAGE", "GPU_PSU_CONNECTORS");
        verify(catalog).findById("gpu");
        verify(catalog).findById("psu");
        verifyNoInteractions(memory, support);
        assertThatThrownBy(() -> service.check(request(null, "gpu", null, List.of("PSU"))))
                .isInstanceOf(ApiException.class).hasMessageContaining("상품의 종류");
    }

    @Test
    void explicitPresenceTypesAreValidatedBeforeLookupAndInputCopiesRemainImmutable() {
        for (var presence : List.of(List.of("COOLER"), List.of("GPU", "GPU"), List.of("gpu")))
            assertThatThrownBy(() -> service.check(request(null, null, null, presence))).isInstanceOf(ApiException.class);
        var source = new java.util.ArrayList<>(List.of("GPU"));
        var request = request(null, null, null, source);
        source.add("PSU");
        assertThat(request.presentComponents()).containsExactly("GPU");
        assertThatThrownBy(() -> request.presentComponents().add("CASE")).isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(catalog, memory, support);
    }

    @Test
    void explicitHardwarePresenceDoesNotHideSelectedCpuBoardOrRamChecks() {
        when(catalog.findById("cpu")).thenReturn(detail("cpu",
                new CatalogSpecification.Cpu("AM4", 6, 12, null, null, null, false, null)));
        when(catalog.findById("board")).thenReturn(detail("board",
                new CatalogSpecification.Motherboard("LGA1700", "B760", "ATX", "DDR4", "DIMM", 4, null, false)));
        var request = new CompatibilityDtos.Request("cpu", "board", List.of(), null, null, null,
                null, null, null, List.of("GPU"));
        var result = service.check(request);
        assertThat(result.scope()).isEqualTo(HardwareCompatibilityRules.SCOPE);
        assertThat(result.checks()).anySatisfy(check -> {
            assertThat(check.code()).isEqualTo("CPU_SOCKET");
            assertThat(check.status()).isEqualTo(CompatibilityDtos.Status.INCOMPATIBLE);
        });
        assertThat(result.checks()).anySatisfy(check -> assertThat(check.code()).isEqualTo("RAM_SELECTION"));
        assertThat(result.checks()).anySatisfy(check -> assertThat(check.code()).isEqualTo("CATALOG_LINK_gpuProductId"));
        assertThat(result.status()).isEqualTo(CompatibilityDtos.Status.INCOMPATIBLE);
        verify(memory).findByProductId("cpu");
        verify(support).find("board", "cpu");
    }

    private static CompatibilityDtos.Request request(String gpu, String psu, String enclosure, List<String> present) {
        return new CompatibilityDtos.Request(null, null, List.of(), null, null, null, gpu, psu, enclosure, present);
    }
    private static CatalogDtos.Detail detail(String id, CatalogSpecification spec) {
        return new CatalogDtos.Detail(new CatalogProductView(id, spec.type(), "Fixture", id, null,
                CatalogVerificationStatus.UNVERIFIED, false, Instant.EPOCH, Instant.EPOCH, null), spec, List.of(), List.of());
    }
}
