package com.pcupgradelab.catalog.memory;

import com.pcupgradelab.catalog.CatalogSourceInput;
import com.pcupgradelab.catalog.CatalogSourceName;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 고정된 제조사 확인 자료만 읽는다. 외부 요청/가격 생성/원본 seed 수정은 하지 않는다. */
@Component
public class CpuMemorySeedLoader {
    public static final String REVISION = "b6fc3a559b871106ba89d9d8b8ad4beeb886a799";
    public static final String INITIAL = "cpu-memory-v1";
    public static final String EXPANSION = "cpu-memory-expand100";
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();

    public List<Item> load() {
        return load(INITIAL);
    }

    public List<Item> load(String batch) {
        expectedCount(batch);
        try (var input = new ClassPathResource("catalog/enrichment/" + batch + ".json").getInputStream()) {
            return read(input.readAllBytes());
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read CPU memory enrichment resource", ex);
        }
    }

    List<Item> read(byte[] data) {
        try {
            // Tree 파싱에서 중복 JSON 키도 거부한 다음 생성자/필드 검증을 적용한다.
            var manifest = mapper.treeToValue(mapper.readTree(data), Manifest.class);
            if (manifest.items() == null || manifest.items().size() != expectedCount(manifest.seedName())) {
                throw new IllegalArgumentException("Unexpected CPU profile count for " + manifest.seedName());
            }
            var seen = new HashSet<String>();
            for (var item : manifest.items()) {
                if (item == null || !seen.add(item.externalId())) {
                    throw new IllegalArgumentException("CPU external IDs must be distinct");
                }
                item.source();
            }
            return List.copyOf(manifest.items());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Invalid CPU memory enrichment: " + ex.getMessage(), ex);
        }
    }

    public record Manifest(String seedName, List<Item> items) { }

    private static int expectedCount(String batch) {
        if (INITIAL.equals(batch)) return 12;
        if (EXPANSION.equals(batch)) return 25;
        throw new IllegalArgumentException("Unknown CPU memory seed batch: " + batch);
    }

    public record Item(String externalId, String manufacturer, String modelName, String partNumber,
                       String socketCode, CpuMemorySupport support, String sourceUrl, String retrievedAt) {
        public Item {
            if (externalId == null || !UUID.fromString(externalId).toString().equals(externalId)) {
                throw new IllegalArgumentException("externalId must be a canonical UUID");
            }
            if (manufacturer == null || modelName == null || socketCode == null || support == null
                    || modelName.isBlank()) throw new IllegalArgumentException("CPU identity and support are required");
            String host = sourceUrl == null ? null : URI.create(sourceUrl).getHost();
            if (!("AMD".equals(manufacturer) && "www.amd.com".equals(host))
                    && !("Intel".equals(manufacturer) && "www.intel.com".equals(host))) {
                throw new IllegalArgumentException("sourceUrl must be the CPU manufacturer's official website");
            }
            if (retrievedAt == null) throw new IllegalArgumentException("retrievedAt is required");
            Instant.parse(retrievedAt);
        }

        public CatalogSourceInput source() {
            return source(INITIAL);
        }

        public CatalogSourceInput source(String batch) {
            expectedCount(batch);
            return new CatalogSourceInput(CatalogSourceName.MANUFACTURER, null, batch, sourceUrl,
                    Map.of("payloadKind", "CURATED_SPEC_EXTRACT", "scope", "CPU_MEMORY_SUPPORT",
                            "modelName", modelName, "support", mapperPayload(support)), Instant.parse(retrievedAt));
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> mapperPayload(CpuMemorySupport support) {
            return JsonMapper.builder().build().convertValue(support, Map.class);
        }
    }
}
