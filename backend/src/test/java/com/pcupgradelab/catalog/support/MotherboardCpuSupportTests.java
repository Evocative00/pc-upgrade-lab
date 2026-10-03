package com.pcupgradelab.catalog.support;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static com.pcupgradelab.catalog.support.MotherboardCpuSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MotherboardCpuSupportTests {
    private static final String URL = "https://www.msi.com/Motherboard/B550-A-PRO/support";

    @Test
    void differentSteppingsPreserveDifferentBiosAndVendorInstructionsDoNotBecomeFakeVersions() {
        var b0 = entry("B0", BiosRequirement.VERSION, "7C56vA4", "7C56vA4.zip");
        var b2 = entry("B2", BiosRequirement.VERSION, "7C56vA7", "7C56vA7.zip");
        var profile = new MotherboardCpuSupport(RevisionScope.MODEL, null, "제조사 목록", List.of(b2, b0));
        assertThat(profile.revisionKey()).isEqualTo("MODEL");
        assertThat(profile.entries()).containsExactly(b0, b2);
        assertThat(entry("B2", BiosRequirement.UNKNOWN, null, "Latest Beta BIOS").minimumBiosVersion()).isNull();
        assertThat(entry("B0", BiosRequirement.ALL, null, "All").biosRequirement()).isEqualTo(BiosRequirement.ALL);
        assertThatThrownBy(() -> entry("B0", BiosRequirement.VERSION, "ALL", "All")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry("B0", BiosRequirement.ALL, "0", "All")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry("B0", BiosRequirement.VERSION, "0245", "1205")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry("B0", BiosRequirement.ALL, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MotherboardCpuSupport(RevisionScope.EXACT, null, "조건", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MotherboardCpuSupport(RevisionScope.MODEL, "1.0", "조건", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MotherboardCpuSupport(RevisionScope.MODEL, null, "조건", List.of(b0, b0))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new MotherboardCpuSupport(RevisionScope.EXACT, "1.0", "조건", List.of()).revisionKey()).isEqualTo("REV:1.0");
    }

    @Test
    void unverifiedSupportCannotAssertARevisionOrBiosAndEvidenceMustBeOfficialHttps() {
        assertThat(new Entry("cpu", "UNSPECIFIED", SupportStatus.UNVERIFIED, "미확인 CPU", null,
                BiosRequirement.UNKNOWN, null, null, URL, "자료 미확인").supportStatus()).isEqualTo(SupportStatus.UNVERIFIED);
        assertThatThrownBy(() -> new Entry("cpu", "B0", SupportStatus.UNVERIFIED, "CPU", "B0",
                BiosRequirement.UNKNOWN, null, null, URL, "조건")).isInstanceOf(IllegalArgumentException.class);
        for (String url : List.of("https://www.msi.com.attacker.example/support", "http://www.msi.com/support", "https://name@www.msi.com/support")) {
            assertThatThrownBy(() -> new Entry("cpu", "B0", SupportStatus.LISTED, "CPU", "B0",
                    BiosRequirement.UNKNOWN, null, null, url, "조건")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void manifestAccountsForAllSixtyFourPairsButIsExplicitlyOnlyTheCurrentCatalogSubset() throws Exception {
        var loader = new MotherboardCpuSeedLoader();
        var manifest = loader.load();
        assertThat(manifest.cpus()).hasSize(12);
        assertThat(manifest.items()).hasSize(16);
        assertThat(manifest.items().stream().mapToLong(item -> item.support().entries().stream()
                .map(MotherboardCpuSeedLoader.CpuEntryInput::cpuExternalId).distinct().count()).sum()).isEqualTo(64);
        assertThat(manifest.items().stream().mapToInt(item -> item.support().entries().size()).sum()).isEqualTo(71);
        assertThat(manifest.items()).allSatisfy(item -> assertThat(item.source().rawPayload()).containsEntry("listComplete", false));
        assertThat(manifest.items().stream().flatMap(item -> item.support().entries().stream())
                .filter(entry -> entry.biosRequirement() == BiosRequirement.UNKNOWN).toList())
                .singleElement().satisfies(entry -> {
                    assertThat(entry.supportStatus()).isEqualTo(SupportStatus.LISTED);
                    assertThat(entry.minimumBiosVersion()).isNull();
                    assertThat(entry.manufacturerBiosLabel()).isEqualTo("Latest Beta BIOS");
                });
        String text;
        try (var input = new ClassPathResource("catalog/enrichment/motherboard-cpu-v1.json").getInputStream()) {
            text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String invalid : List.of(text.replaceFirst("\\{", "{\"seedName\":\"duplicate\","), text + " {}",
                text.replace("\"supportStatus\": \"LISTED\"", "\"supportStatus\": \"COMPATIBLE\""),
                text.replaceFirst("\"minimumBiosVersion\": \"7C95v2A\"", "\"minimumBiosVersion\": \"invented\""),
                text.replaceFirst("\"socketCode\": \"AM4\"", "\"socketCode\": \"UNKNOWN\""))) {
            assertThatThrownBy(() -> loader.read(invalid.getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalStateException.class);
        }
    }

    private static Entry entry(String stepping, BiosRequirement kind, String version, String label) {
        return new Entry("cpu", stepping, SupportStatus.LISTED, "Ryzen 5 5600X", stepping, kind, version, label, URL, "제조사 조건");
    }
}
