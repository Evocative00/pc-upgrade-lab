package com.pcupgradelab.catalog.support;

import com.pcupgradelab.catalog.CatalogProduct;
import com.pcupgradelab.catalog.CatalogProductSource;
import com.pcupgradelab.catalog.CatalogProductSourceRepository;
import com.pcupgradelab.catalog.CatalogSourceInput;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.CpuSpecRepository;
import com.pcupgradelab.catalog.MotherboardSpecRepository;
import com.pcupgradelab.pc.PartType;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 기존 제품만 보완한다. 변경된 지원 자료/출처는 덮어쓰지 않으며 실패 시 이번 묶음 전체를 롤백한다. */
@Service
public class MotherboardCpuSeedService {
    private final MotherboardCpuSeedLoader loader;
    private final MotherboardCpuSupportRepository support;
    private final CatalogProductSourceRepository sources;
    private final CpuSpecRepository cpus;
    private final MotherboardSpecRepository boards;
    private final EntityManager entityManager;

    public MotherboardCpuSeedService(MotherboardCpuSeedLoader loader, MotherboardCpuSupportRepository support,
                                     CatalogProductSourceRepository sources, CpuSpecRepository cpus,
                                     MotherboardSpecRepository boards, EntityManager entityManager) {
        this.loader = loader; this.support = support; this.sources = sources;
        this.cpus = cpus; this.boards = boards; this.entityManager = entityManager;
    }

    @Transactional
    public Result seed() {
        return seed(MotherboardCpuSeedLoader.SEED_NAME);
    }

    @Transactional
    public Result seed(String batch) {
        var manifest = loader.load(batch);
        var cpuIds = new HashMap<String, String>();
        for (var input : manifest.cpus()) cpuIds.put(input.externalId(), resolve(input, PartType.CPU).getId());
        var items = new ArrayList<Item>();
        var expected = new ArrayList<MotherboardCpuSupport>();
        int extended = 0;
        for (var input : manifest.items()) {
            var board = resolve(input.board(), PartType.MOTHERBOARD);
            var profile = input.support().resolve(cpuIds::get);
            var stored = support.findByRevision(board.getId(), profile.revisionKey());
            boolean created = stored.isEmpty();
            if (created) {
                var source = sources.saveAndFlush(new CatalogProductSource(board, input.source(batch)));
                support.insert(board.getId(), source.getId(), profile);
            } else if (MotherboardCpuSeedLoader.EXPANSION.equals(batch)
                    && !profile.equals(stored.orElseThrow().support())) {
                var actual = stored.orElseThrow();
                var base = loader.load().items().stream()
                        .filter(item -> item.board().externalId().equals(input.board().externalId())).findFirst()
                        .orElseThrow(() -> conflict(input.board().modelName(), "only the original profiles may be extended"));
                assertMatches(base, base.support().resolve(cpuIds::get), actual, MotherboardCpuSeedLoader.SEED_NAME);
                if (!sameMetadata(profile, actual.support()) || !profile.entries().containsAll(actual.support().entries())) {
                    throw conflict(input.board().modelName(), "expansion must preserve every existing support entry");
                }
                var additions = profile.entries().stream().filter(entry -> !actual.support().entries().contains(entry)).toList();
                var source = sources.saveAndFlush(new CatalogProductSource(board, input.source(batch)));
                support.append(board.getId(), source.getId(), profile, additions);
                extended++;
            } else assertMatches(input, profile, stored.orElseThrow(), batch);
            int pairs = (int) profile.entries().stream().map(MotherboardCpuSupport.Entry::cpuProductId).distinct().count();
            items.add(new Item(board.getId(), board.getModelName(), profile.revisionKey(), pairs, profile.entries().size(), created));
            expected.add(profile);
        }
        entityManager.flush();
        entityManager.clear();
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            assertMatches(manifest.items().get(i), expected.get(i),
                    support.findByRevision(item.productId(), item.revisionKey()).orElseThrow(), batch);
        }
        int created = (int) items.stream().filter(Item::created).count();
        return new Result(created, extended, items.size() - created - extended, items.stream().mapToInt(Item::cpuPairs).sum(),
                items.stream().mapToInt(Item::variantRows).sum(), items);
    }

    private CatalogProduct resolve(MotherboardCpuSeedLoader.Identity input, PartType type) {
        var original = sources.findBySourceNameAndExternalId(CatalogSourceName.BUILDCORES, input.externalId())
                .orElseThrow(() -> conflict(input.modelName(), "catalog product is missing; register all existing seed batches first"));
        var product = original.getProduct();
        String socket = type == PartType.CPU
                ? cpus.findById(product.getId()).orElseThrow(() -> conflict(input.modelName(), "CPU specification is missing")).toSpecification().socketCode()
                : boards.findById(product.getId()).orElseThrow(() -> conflict(input.modelName(), "motherboard specification is missing")).toSpecification().socketCode();
        if (product.getType() != type || !Objects.equals(product.getManufacturer(), input.manufacturer())
                || !Objects.equals(product.getModelName(), input.modelName()) || !Objects.equals(product.getPartNumber(), input.partNumber())
                || !Objects.equals(socket, input.socketCode()) || !MotherboardCpuSeedLoader.REVISION.equals(original.getSourceRevision())) {
            throw conflict(input.modelName(), "existing identity, socket or BuildCores revision differs");
        }
        return product;
    }

    private void assertMatches(MotherboardCpuSeedLoader.Item input, MotherboardCpuSupport expected,
                               MotherboardCpuSupportRepository.Stored actual, String batch) {
        var source = sources.findById(actual.evidenceSourceId()).orElseThrow(() -> conflict(input.board().modelName(), "evidence is missing"));
        if (MotherboardCpuSeedLoader.SEED_NAME.equals(batch)
                && MotherboardCpuSeedLoader.EXPANSION.equals(source.getSourceRevision())) {
            // v1 재실행은 승인된 v2 전체 자료의 정확한 일치와 원래 v1 출처를 모두 확인한다.
            var expansion = loader.load(MotherboardCpuSeedLoader.EXPANSION);
            var expandedInput = expansion.items().stream()
                    .filter(item -> item.board().externalId().equals(input.board().externalId())).findFirst().orElseThrow();
            var ids = new HashMap<String, String>();
            for (var cpu : expansion.cpus()) ids.put(cpu.externalId(), resolve(cpu, PartType.CPU).getId());
            var expanded = expandedInput.support().resolve(ids::get);
            boolean originalEvidencePresent = sources.findAllByProduct_IdOrderByIdAsc(actual.motherboardProductId()).stream()
                    .anyMatch(candidate -> sourceMatches(actual.motherboardProductId(), candidate, input.source()));
            if (!sameMetadata(expected, expanded) || !expanded.entries().containsAll(expected.entries())
                    || !originalEvidencePresent || !expanded.equals(actual.support())
                    || !sourceMatches(actual.motherboardProductId(), source, expandedInput.source(MotherboardCpuSeedLoader.EXPANSION))) {
                throw conflict(input.board().modelName(), "expanded profile or original evidence differs");
            }
            return;
        }
        if (!expected.equals(actual.support()) || !sourceMatches(actual.motherboardProductId(), source, input.source(batch))) {
            throw conflict(input.board().modelName(), "existing CPU support or evidence differs; review it before changing it");
        }
    }

    private static boolean sameMetadata(MotherboardCpuSupport left, MotherboardCpuSupport right) {
        return left.revisionScope() == right.revisionScope() && Objects.equals(left.hardwareRevision(), right.hardwareRevision())
                && left.conditions().equals(right.conditions());
    }

    private static boolean sourceMatches(String productId, CatalogProductSource source, CatalogSourceInput evidence) {
        return productId.equals(source.getProduct().getId()) && source.getSourceName() == evidence.sourceName()
                && Objects.equals(source.getExternalId(), evidence.externalId())
                && Objects.equals(source.getSourceRevision(), evidence.sourceRevision())
                && source.getSourceUrl().equals(evidence.sourceUrl()) && source.getRetrievedAt().equals(evidence.retrievedAt())
                && Objects.equals(source.getRawPayload(), evidence.rawPayload());
    }

    private static IllegalStateException conflict(String model, String reason) {
        return new IllegalStateException("Motherboard CPU enrichment conflict for " + model + ": " + reason);
    }
    public record Item(String productId, String modelName, String revisionKey, int cpuPairs, int variantRows, boolean created) { }
    public record Result(int created, int extended, int skipped, int cpuPairs, int variantRows, List<Item> items) {
        public Result { items = List.copyOf(items); }
    }
}
