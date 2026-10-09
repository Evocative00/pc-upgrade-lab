package com.pcupgradelab.catalog.storage;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class MotherboardStorageSupportTests {
    @Test
    void exactRevisionAndOpaqueCpuBiosConditionsRemainDistinctFromModelScope() {
        var raw = "M2_2 is disabled with the specified CPU; see vendor BIOS release notes.";
        var rule = new MotherboardStorageSupport.Rule("cpu-limit", "Ryzen 8000-series subset",
                "Vendor beta BIOS, version ordering not reviewed", MotherboardStorageSupport.RuleEffect.DISABLED,
                "M2_2", raw);
        var support = new MotherboardStorageSupport(MotherboardStorageSupport.RevisionScope.EXACT,
                "1.0", false, "Partial manufacturer table excerpt", List.of(m2(List.of(rule))), List.of(source()));
        assertThat(support.revisionKey()).isEqualTo("REV:1.0");
        assertThat(support.completeDataKnown()).isFalse();
        assertThat(support.slots().getFirst().rules().getFirst().rawCondition()).isEqualTo(raw);
        assertThatThrownBy(() -> new MotherboardStorageSupport(MotherboardStorageSupport.RevisionScope.MODEL,
                "1.0", false, null, List.of(), List.of(source()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MotherboardStorageSupport(MotherboardStorageSupport.RevisionScope.EXACT,
                null, false, null, List.of(), List.of(source()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptySupportListsAreUnknownAndCannotClaimNvmeBootOrPcieGeneration() {
        var unknown = new MotherboardStorageSupport.Slot("M2_1", "M2", null,
                List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of());
        assertThat(unknown.supportedProtocols()).isEmpty();
        assertThat(unknown.nvmeBootSupport()).isNull();
        assertThatThrownBy(() -> new MotherboardStorageSupport.Slot("M2_1", "M2", null,
                List.of(), List.of(), List.of(), "4.0", 4, null, null, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MotherboardStorageSupport.Slot("SATA1", "SATA", null,
                List.of(), List.of("SATA", "PCIE"), List.of(), null, null, null, null, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void factsRequireSourcesAndDuplicateSlotKeysAreRejected() {
        assertThatThrownBy(() -> new MotherboardStorageSupport(MotherboardStorageSupport.RevisionScope.MODEL,
                null, false, null, List.of(m2(List.of())), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MotherboardStorageSupport(MotherboardStorageSupport.RevisionScope.MODEL,
                null, false, null, List.of(m2(List.of()), m2(List.of())), List.of(source())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MotherboardStorageSupport.Source("https://user:secret@example.com/board",
                Instant.parse("2026-10-09T00:00:00Z"), null, null, "Fixture"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static MotherboardStorageSupport.Slot m2(List<MotherboardStorageSupport.Rule> rules) {
        return new MotherboardStorageSupport.Slot("M2_1", "M2", "M", List.of("2280", "2260"),
                List.of("PCIE"), List.of("NVME"), "4.0", 4, null, "CPU", null,
                "Fixture facts only; boot support not reviewed", rules);
    }
    static MotherboardStorageSupport.Source source() {
        return new MotherboardStorageSupport.Source("https://example.com/board/manual",
                Instant.parse("2026-10-09T00:00:00Z"), "fixture-v1", "Storage section",
                "Synthetic fixture: M2_1 length, bus and raw condition excerpt");
    }
}
