package com.pcupgradelab.catalog.memory;

import com.pcupgradelab.catalog.CatalogProduct;
import com.pcupgradelab.catalog.CatalogProductSource;
import com.pcupgradelab.catalog.CatalogProductSourceRepository;
import com.pcupgradelab.catalog.CatalogSourceInput;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.CpuSpecRepository;
import com.pcupgradelab.pc.PartType;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 이미 등록한 CPU만 보완한다. 누락/충돌/출처/제약 실패 시 이번 보완 묶음 전체를 롤백한다. */
@Service
public class CpuMemorySeedService {
    private final CpuMemorySeedLoader loader;
    private final CpuMemorySupportRepository memory;
    private final CatalogProductSourceRepository sources;
    private final CpuSpecRepository cpus;
    private final EntityManager entityManager;

    public CpuMemorySeedService(CpuMemorySeedLoader loader, CpuMemorySupportRepository memory,
                                CatalogProductSourceRepository sources, CpuSpecRepository cpus,
                                EntityManager entityManager) {
        this.loader = loader;
        this.memory = memory;
        this.sources = sources;
        this.cpus = cpus;
        this.entityManager = entityManager;
    }

    @Transactional
    public Result seed() {
        return seed(CpuMemorySeedLoader.INITIAL);
    }

    @Transactional
    public Result seed(String batch) {
        var expected = loader.load(batch);
        var items = new ArrayList<Item>();
        for (var input : expected) {
            var product = resolve(input);
            var stored = memory.findByProductId(product.getId());
            boolean created = stored.isEmpty();
            if (created) {
                var source = sources.saveAndFlush(new CatalogProductSource(product, input.source(batch)));
                memory.insert(product.getId(), source.getId(), input.support());
            } else {
                assertMatches(input, stored.orElseThrow(), batch);
            }
            items.add(new Item(product.getId(), product.getModelName(), created));
        }
        entityManager.flush();
        entityManager.clear();
        // JDBC 자료와 JPA 출처를 모두 DB에서 다시 읽어 커밋 전에 확인한다.
        for (int i = 0; i < items.size(); i++) {
            assertMatches(expected.get(i), memory.findByProductId(items.get(i).productId()).orElseThrow(), batch);
        }
        int created = (int) items.stream().filter(Item::created).count();
        return new Result(created, items.size() - created, items);
    }

    private CatalogProduct resolve(CpuMemorySeedLoader.Item input) {
        var original = sources.findBySourceNameAndExternalId(CatalogSourceName.BUILDCORES, input.externalId())
                .orElseThrow(() -> conflict(input.modelName(), "CPU is missing; register week2-initial, week2-intel and week2-amd first"));
        var product = original.getProduct();
        var cpu = cpus.findById(product.getId()).orElseThrow(() -> conflict(input.modelName(), "CPU specification is missing"));
        if (product.getType() != PartType.CPU || !Objects.equals(product.getManufacturer(), input.manufacturer())
                || !Objects.equals(product.getModelName(), input.modelName())
                || !Objects.equals(product.getPartNumber(), input.partNumber())
                || !Objects.equals(cpu.toSpecification().socketCode(), input.socketCode())
                || !CpuMemorySeedLoader.REVISION.equals(original.getSourceRevision())) {
            throw conflict(input.modelName(), "existing CPU identity, socket or source revision differs");
        }
        return product;
    }

    private void assertMatches(CpuMemorySeedLoader.Item expected, CpuMemorySupportRepository.Stored actual, String batch) {
        var source = sources.findById(actual.evidenceSourceId()).orElseThrow(() -> conflict(expected.modelName(), "evidence is missing"));
        CatalogSourceInput input = expected.source(batch);
        if (!expected.support().equals(actual.support()) || !actual.productId().equals(source.getProduct().getId())
                || source.getSourceName() != input.sourceName() || !Objects.equals(source.getExternalId(), input.externalId())
                || !Objects.equals(source.getSourceRevision(), input.sourceRevision())
                || !source.getSourceUrl().equals(input.sourceUrl()) || !source.getRetrievedAt().equals(input.retrievedAt())
                || !sameJson(input.rawPayload(), source.getRawPayload())) {
            throw conflict(expected.modelName(), "existing memory support or evidence differs; review it before changing it");
        }
    }

    private static boolean sameJson(Object expected, Object actual) {
        if (expected instanceof Number left && actual instanceof Number right) {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
        }
        if (expected instanceof Map<?, ?> left && actual instanceof Map<?, ?> right) {
            return left.keySet().equals(right.keySet()) && left.keySet().stream().allMatch(key -> sameJson(left.get(key), right.get(key)));
        }
        if (expected instanceof List<?> left && actual instanceof List<?> right) {
            if (left.size() != right.size()) return false;
            for (int i = 0; i < left.size(); i++) if (!sameJson(left.get(i), right.get(i))) return false;
            return true;
        }
        return Objects.equals(expected, actual);
    }

    private static IllegalStateException conflict(String model, String reason) {
        return new IllegalStateException("CPU memory enrichment conflict for " + model + ": " + reason);
    }

    public record Item(String productId, String modelName, boolean created) { }
    public record Result(int created, int skipped, List<Item> items) {
        public Result { items = List.copyOf(items); }
    }
}
