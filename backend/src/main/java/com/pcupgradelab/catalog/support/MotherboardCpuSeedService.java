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
    private static final List<String> APPROVED_BATCHES = List.of(MotherboardCpuSeedLoader.SEED_NAME,
            MotherboardCpuSeedLoader.EXPANSION, MotherboardCpuSeedLoader.EXPANSION_300);
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
        return apply(batch, true);
    }

    /** 누락이나 이전 단계 자료를 복구·확장하지 않고 승인된 현재 자료와 출처를 확인한다. */
    @Transactional(readOnly = true)
    public Result verify(String batch) {
        return apply(batch, false);
    }

    private Result apply(String batch, boolean allowWrite) {
        int requestedIndex = approvedIndex(batch);
        var context = new ApprovedContext(batch);
        var manifest = context.manifest(requestedIndex);
        context.resolveCpuIds(manifest);
        var items = new ArrayList<Item>();
        int extended = 0;
        for (var input : manifest.items()) {
            var board = resolve(input.board(), PartType.MOTHERBOARD);
            var profile = context.profile(input, manifest);
            var stored = support.findByRevision(board.getId(), profile.revisionKey());
            boolean created = stored.isEmpty();
            if (created) {
                if (!allowWrite) throw conflict(input.board().modelName(), "approved CPU support profile is missing");
                if (!context.history(input.board().externalId(), requestedIndex - 1).isEmpty()) {
                    throw conflict(input.board().modelName(), "approved prior CPU support profile is missing; earlier evidence must be preserved");
                }
                var source = sources.saveAndFlush(new CatalogProductSource(board, input.source(batch)));
                support.insert(board.getId(), source.getId(), profile);
            } else {
                var actual = stored.orElseThrow();
                int actualIndex = assertApprovedStored(input, actual, context);
                if (actualIndex < requestedIndex) {
                    if (!allowWrite) throw conflict(input.board().modelName(), "approved current expansion has not been registered");
                    var history = context.history(input.board().externalId(), requestedIndex);
                    assertPreservedHistory(input.board().modelName(), history);
                    if (history.size() < 2 || history.get(history.size() - 2).batchIndex() != actualIndex) {
                        throw conflict(input.board().modelName(), "expansion must follow the approved v1, expand100, expand300 sequence");
                    }
                    var additions = profile.entries().stream()
                            .filter(entry -> !actual.support().entries().contains(entry)).toList();
                    var source = sources.saveAndFlush(new CatalogProductSource(board, input.source(batch)));
                    support.append(board.getId(), source.getId(), profile, additions);
                    extended++;
                }
            }
            int pairs = (int) profile.entries().stream().map(MotherboardCpuSupport.Entry::cpuProductId).distinct().count();
            items.add(new Item(board.getId(), board.getModelName(), profile.revisionKey(), pairs, profile.entries().size(), created));
        }
        if (allowWrite) entityManager.flush();
        entityManager.clear();
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            int actualIndex = assertApprovedStored(manifest.items().get(i),
                    support.findByRevision(item.productId(), item.revisionKey()).orElseThrow(), context);
            if (actualIndex < requestedIndex) throw conflict(item.modelName(), "approved current expansion has not been registered");
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

    /** 오래된 묶음의 재실행도 저장된 최신 묶음 전체와 모든 이전 출처의 정확한 일치를 요구한다. */
    private int assertApprovedStored(MotherboardCpuSeedLoader.Item input,
                                     MotherboardCpuSupportRepository.Stored actual, ApprovedContext context) {
        var source = sources.findById(actual.evidenceSourceId()).orElseThrow(() -> conflict(input.board().modelName(), "evidence is missing"));
        int actualIndex = APPROVED_BATCHES.indexOf(source.getSourceRevision());
        if (actualIndex < 0) throw conflict(input.board().modelName(), "existing CPU support or evidence differs; source revision is not approved");
        var history = context.history(input.board().externalId(), actualIndex);
        if (history.isEmpty() || history.getLast().batchIndex() != actualIndex) {
            throw conflict(input.board().modelName(), "existing CPU support or evidence differs; board is absent from that approved manifest");
        }
        assertPreservedHistory(input.board().modelName(), history);
        var latest = history.getLast();
        if (!latest.profile().equals(actual.support())
                || !sourceMatches(actual.motherboardProductId(), source, latest.input().source(APPROVED_BATCHES.get(actualIndex)))) {
            throw conflict(input.board().modelName(), "existing CPU support or evidence differs; review it before changing it");
        }
        var evidence = sources.findAllByProduct_IdOrderByIdAsc(actual.motherboardProductId());
        for (var approved : history) {
            var expected = approved.input().source(APPROVED_BATCHES.get(approved.batchIndex()));
            if (evidence.stream().noneMatch(candidate -> sourceMatches(actual.motherboardProductId(), candidate, expected))) {
                throw conflict(input.board().modelName(), "expanded profile or original evidence differs; retained approved evidence is missing");
            }
        }
        return actualIndex;
    }

    /** 전체 저장 자료는 별도로 정확히 비교하며, 여기서는 승인된 단계 사이의 변경도 거부한다. */
    private static void assertPreservedHistory(String model, List<ApprovedProfile> history) {
        for (int i = 1; i < history.size(); i++) {
            var previous = history.get(i - 1).profile();
            var next = history.get(i).profile();
            var entries = new HashMap<String, MotherboardCpuSupport.Entry>();
            for (var entry : next.entries()) entries.put(entry.cpuProductId() + "/" + entry.variantKey(), entry);
            if (!history.get(i - 1).input().board().equals(history.get(i).input().board())
                    || !sameMetadata(previous, next) || previous.entries().stream()
                    .anyMatch(entry -> !entry.equals(entries.get(entry.cpuProductId() + "/" + entry.variantKey())))) {
                throw conflict(model, "expansion must preserve identity, metadata and every approved support variant exactly");
            }
        }
    }

    private static int approvedIndex(String batch) {
        int index = APPROVED_BATCHES.indexOf(batch);
        if (index < 0) throw new IllegalArgumentException("Unknown motherboard CPU seed batch: " + batch);
        return index;
    }

    private final class ApprovedContext {
        private final Map<Integer, MotherboardCpuSeedLoader.Manifest> manifests = new HashMap<>();
        private final Map<String, String> cpuIds = new HashMap<>();
        private final Map<String, MotherboardCpuSeedLoader.Identity> cpuIdentities = new HashMap<>();

        private ApprovedContext(String requestedBatch) {
            manifests.put(approvedIndex(requestedBatch), loader.load(requestedBatch));
        }

        private MotherboardCpuSeedLoader.Manifest manifest(int index) {
            return manifests.computeIfAbsent(index, key -> loader.load(APPROVED_BATCHES.get(key)));
        }

        private void resolveCpuIds(MotherboardCpuSeedLoader.Manifest manifest) {
            for (var cpu : manifest.cpus()) {
                var previous = cpuIdentities.putIfAbsent(cpu.externalId(), cpu);
                if (previous != null && !previous.equals(cpu)) throw conflict(cpu.modelName(), "approved CPU identity differs between expansion manifests");
                cpuIds.computeIfAbsent(cpu.externalId(), key -> resolve(cpu, PartType.CPU).getId());
            }
        }

        private MotherboardCpuSupport profile(MotherboardCpuSeedLoader.Item item, MotherboardCpuSeedLoader.Manifest manifest) {
            resolveCpuIds(manifest);
            return item.support().resolve(cpuIds::get);
        }

        private List<ApprovedProfile> history(String externalId, int throughIndex) {
            var profiles = new ArrayList<ApprovedProfile>();
            for (int index = 0; index <= throughIndex; index++) {
                var manifest = manifest(index);
                var input = manifest.items().stream().filter(item -> item.board().externalId().equals(externalId)).findFirst();
                if (input.isPresent()) profiles.add(new ApprovedProfile(index, input.orElseThrow(), profile(input.orElseThrow(), manifest)));
            }
            return List.copyOf(profiles);
        }
    }

    private record ApprovedProfile(int batchIndex, MotherboardCpuSeedLoader.Item input, MotherboardCpuSupport profile) { }

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
