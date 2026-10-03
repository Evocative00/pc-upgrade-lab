package com.pcupgradelab.catalog.memory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static com.pcupgradelab.catalog.memory.CpuMemorySupport.MemoryType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CpuMemorySupportTests {
    @Test
    void publishedFactsKeepUnknownsAndIntelAlternativeMemoryTypesDistinct() {
        var items = new CpuMemorySeedLoader().load();
        assertThat(items).hasSize(12);
        assertThat(items.stream().mapToInt(item -> item.support().supportedTypes().size()).sum()).isEqualTo(16);
        var ryzen3600 = items.getFirst().support();
        assertThat(ryzen3600.maxMemoryBytes()).isNull();
        assertThat(ryzen3600.channelCount()).isEqualTo(2);
        assertThat(ryzen3600.memoryTypesKnown()).isTrue();
        var ryzen5600x = items.stream().filter(item -> item.modelName().equals("Ryzen 5 5600X")).findFirst().orElseThrow();
        assertThat(ryzen5600x.support().maxMemoryBytes()).isNull();
        assertThat(ryzen5600x.support().channelCount()).isNull();
        var intel = items.getLast().support();
        assertThat(intel.maxMemoryBytes()).isEqualTo(192L * 1024 * 1024 * 1024);
        assertThat(intel.supportedTypes().stream().map(CpuMemorySupport.TypeSupport::memoryType).toList())
                .containsExactly(DDR4, DDR5);
        assertThat(intel.supportedTypes().stream().map(CpuMemorySupport.TypeSupport::maxStandardDataRateMts).toList())
                .containsExactly(3200, 5600);
        assertThat(items.stream().filter(item -> item.socketCode().equals("AM5")))
                .allSatisfy(item -> assertThat(item.support().supportedTypes().getFirst().dataRateConditions())
                        .contains("5200", "3600", "rank", "EXPO"));
    }

    @Test
    void rejectsContradictionsAndCopiesTypeListsWithoutTreatingUnknownAsUnsupported() {
        var ddr4 = new CpuMemorySupport.TypeSupport(DDR4, null, "속도 미확인");
        var types = new ArrayList<>(List.of(ddr4));
        var unknown = new CpuMemorySupport(false, null, null, "용량 미확인", types);
        types.clear();
        assertThat(unknown.supportedTypes()).containsExactly(ddr4);
        assertThatThrownBy(() -> unknown.supportedTypes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new CpuMemorySupport(false, null, null, "근거 미확인", List.of()).memoryTypesKnown()).isFalse();
        assertThatThrownBy(() -> new CpuMemorySupport(true, null, null, "조건", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CpuMemorySupport(true, -1L, 2, "조건", List.of(ddr4))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CpuMemorySupport(true, null, 0, "조건", List.of(ddr4))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CpuMemorySupport(true, null, 32768, "조건", List.of(ddr4))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CpuMemorySupport(true, null, 2, " ", List.of(ddr4))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CpuMemorySupport(true, null, 2, "조건", List.of(ddr4, ddr4))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CpuMemorySupport.TypeSupport(DDR5, 0, "조건")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fixedFileReaderRejectsMissingUnknownDuplicateAndMalformedFactsBeforeAnyDatabaseWrite() throws Exception {
        String json;
        try (var input = new ClassPathResource("catalog/enrichment/cpu-memory-v1.json").getInputStream()) {
            json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var loader = new CpuMemorySeedLoader();
        assertThat(loader.read(json.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8))).hasSize(12);
        for (String invalid : List.of(
                json + " {}",
                json.replaceFirst("\\\"seedName\\\":", "\"unexpected\":true,\"seedName\":"),
                json.replaceFirst("\\\"memoryTypesKnown\\\": true,", ""),
                json.replaceFirst("\\\"memoryTypesKnown\\\": true", "\"memoryTypesKnown\":null"),
                json.replaceFirst("\\\"channelCount\\\": 2", "\"channelCount\":2.5"),
                json.replaceFirst("\\\"channelCount\\\": 2", "\"channelCount\":2,\"channelCount\":3"),
                json.replace("\"DDR4\"", "\"DDR3\""),
                json.replace("www.amd.com", "www.example.com"),
                json.replace("04984638-674b-4384-8866-a7b20d55f8b4", "f34f9bc1-0136-43ee-bd78-a9efbd0e5f17"))) {
            assertThatThrownBy(() -> loader.read(invalid.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Invalid CPU memory enrichment");
        }
    }
}
