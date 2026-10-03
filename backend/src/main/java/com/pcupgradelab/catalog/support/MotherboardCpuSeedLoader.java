package com.pcupgradelab.catalog.support;

import com.pcupgradelab.catalog.CatalogSourceInput;
import com.pcupgradelab.catalog.CatalogSourceName;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 확인한 제조사 발췌 자료만 적재한다. 실행 시 외부 사이트를 조회하지 않는다. */
@Component
public class MotherboardCpuSeedLoader {
    public static final String REVISION = "b6fc3a559b871106ba89d9d8b8ad4beeb886a799";
    public static final String SEED_NAME = "motherboard-cpu-v1";
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();

    public Manifest load() {
        try (var input = new ClassPathResource("catalog/enrichment/motherboard-cpu-v1.json").getInputStream()) {
            return read(input.readAllBytes());
        } catch (IOException ex) { throw new IllegalStateException("Cannot read motherboard CPU enrichment resource", ex); }
    }

    Manifest read(byte[] data) {
        try {
            var manifest = mapper.treeToValue(mapper.readTree(data), Manifest.class);
            if (!SEED_NAME.equals(manifest.seedName()) || manifest.cpus() == null || manifest.cpus().size() != 12
                    || manifest.items() == null || manifest.items().size() != 16) {
                throw new IllegalArgumentException("motherboard-cpu-v1 requires 12 CPU identities and 16 board profiles");
            }
            var cpuIds = new HashSet<String>();
            for (var cpu : manifest.cpus()) if (cpu == null || !cpuIds.add(cpu.externalId())) {
                throw new IllegalArgumentException("CPU identities must be distinct");
            }
            var boardIds = new HashSet<String>();
            int pairs = 0;
            for (var item : manifest.items()) {
                if (item == null || !boardIds.add(item.board().externalId())) throw new IllegalArgumentException("board identities must be distinct");
                item.source();
                var expected = manifest.cpus().stream().filter(cpu -> cpu.socketCode().equals(item.board().socketCode()))
                        .map(Identity::externalId).collect(java.util.stream.Collectors.toSet());
                var actual = item.support().entries().stream().map(CpuEntryInput::cpuExternalId)
                        .collect(java.util.stream.Collectors.toSet());
                if (!expected.equals(actual)) throw new IllegalArgumentException("each board must account for every current CPU of its socket");
                pairs += actual.size();
            }
            if (pairs != 64) throw new IllegalArgumentException("expected 64 current same-socket board/CPU pairs");
            return manifest;
        } catch (RuntimeException ex) { throw new IllegalStateException("Invalid motherboard CPU enrichment: " + ex.getMessage(), ex); }
    }

    public record Manifest(String seedName, List<Identity> cpus, List<Item> items) {
        public Manifest {
            if (cpus != null) cpus = List.copyOf(cpus);
            if (items != null) items = List.copyOf(items);
        }
    }

    public record Identity(String externalId, String manufacturer, String modelName, String partNumber, String socketCode) {
        public Identity {
            if (externalId == null || !UUID.fromString(externalId).toString().equals(externalId)) throw new IllegalArgumentException("external ID must be a canonical UUID");
            MotherboardCpuSupport.text(manufacturer, 255, "manufacturer");
            MotherboardCpuSupport.text(modelName, 255, "model name");
            MotherboardCpuSupport.text(socketCode, 32, "socket");
            if (partNumber != null) MotherboardCpuSupport.text(partNumber, 255, "part number");
        }
    }

    public record SupportInput(MotherboardCpuSupport.RevisionScope revisionScope, String hardwareRevision,
                               String conditions, List<CpuEntryInput> entries) {
        public SupportInput {
            if (entries == null) throw new IllegalArgumentException("entries are required");
            entries = List.copyOf(entries);
            new MotherboardCpuSupport(revisionScope, hardwareRevision, conditions,
                    entries.stream().map(entry -> entry.resolve(Function.identity())).toList());
        }
        public MotherboardCpuSupport resolve(Function<String, String> resolveCpuId) {
            return new MotherboardCpuSupport(revisionScope, hardwareRevision, conditions,
                    entries.stream().map(entry -> entry.resolve(resolveCpuId)).toList());
        }
    }

    public record CpuEntryInput(String cpuExternalId, String variantKey, MotherboardCpuSupport.SupportStatus supportStatus,
                               String reportedCpuName, String cpuStepping, MotherboardCpuSupport.BiosRequirement biosRequirement,
                               String minimumBiosVersion, String manufacturerBiosLabel, String sourceUrl, String conditions) {
        public CpuEntryInput {
            if (cpuExternalId == null || !UUID.fromString(cpuExternalId).toString().equals(cpuExternalId)) {
                throw new IllegalArgumentException("CPU external ID must be a canonical UUID");
            }
        }
        MotherboardCpuSupport.Entry resolve(Function<String, String> resolveCpuId) {
            return new MotherboardCpuSupport.Entry(resolveCpuId.apply(cpuExternalId), variantKey, supportStatus, reportedCpuName,
                    cpuStepping, biosRequirement, minimumBiosVersion, manufacturerBiosLabel, sourceUrl, conditions);
        }
    }

    public record Item(Identity board, SupportInput support, String sourceUrl, String retrievedAt) {
        public Item {
            if (board == null || support == null || retrievedAt == null) throw new IllegalArgumentException("board, support and retrievedAt are required");
            MotherboardCpuSupport.officialUrl(sourceUrl);
            String host = switch (board.manufacturer()) {
                case "MSI" -> "www.msi.com";
                case "ASUS" -> "www.asus.com";
                case "ASRock" -> "www.asrock.com";
                case "Gigabyte" -> "www.gigabyte.com";
                default -> throw new IllegalArgumentException("unknown motherboard manufacturer");
            };
            if (!host.equals(URI.create(sourceUrl).getHost()) || support.entries().stream()
                    .anyMatch(entry -> !host.equals(URI.create(entry.sourceUrl()).getHost()))) {
                throw new IllegalArgumentException("evidence must belong to this motherboard's manufacturer");
            }
            Instant.parse(retrievedAt);
        }

        @SuppressWarnings("unchecked")
        public CatalogSourceInput source() {
            return new CatalogSourceInput(CatalogSourceName.MANUFACTURER, null, SEED_NAME, sourceUrl,
                    Map.of("payloadKind", "CURATED_SUPPORT_EXTRACT", "scope", "CURRENT_CATALOG_SUBSET",
                            "listComplete", false, "board", JsonMapper.builder().build().convertValue(board, Map.class),
                            "support", JsonMapper.builder().build().convertValue(support, Map.class)), Instant.parse(retrievedAt));
        }
    }
}
