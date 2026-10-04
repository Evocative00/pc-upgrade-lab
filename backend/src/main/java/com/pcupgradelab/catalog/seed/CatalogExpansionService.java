package com.pcupgradelab.catalog.seed;

import com.pcupgradelab.catalog.CatalogProductSourceRepository;
import com.pcupgradelab.catalog.CatalogSourceName;
import com.pcupgradelab.catalog.CatalogEntryCreateRequest;
import com.pcupgradelab.catalog.CatalogSpecification;
import com.pcupgradelab.catalog.memory.CpuMemorySeedLoader;
import com.pcupgradelab.catalog.memory.CpuMemorySeedService;
import com.pcupgradelab.catalog.support.MotherboardCpuSeedLoader;
import com.pcupgradelab.catalog.support.MotherboardCpuSeedService;
import com.pcupgradelab.pc.PartType;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 승인된 제품 확장과 호환 근거를 함께 커밋한다. 어느 단계든 실패하면 이번 실행 전체를 롤백한다. */
@Service
public class CatalogExpansionService {
    private final CatalogSeedLoader loader;
    private final CatalogProductSourceRepository sources;
    private final CatalogSeedService catalog;
    private final CpuMemorySeedService memory;
    private final MotherboardCpuSeedService boards;
    private final CpuMemorySeedLoader memoryLoader;
    private final MotherboardCpuSeedLoader boardLoader;

    public CatalogExpansionService(CatalogSeedLoader loader, CatalogProductSourceRepository sources,
                                   CatalogSeedService catalog, CpuMemorySeedService memory,
                                   MotherboardCpuSeedService boards, CpuMemorySeedLoader memoryLoader,
                                   MotherboardCpuSeedLoader boardLoader) {
        this.loader = loader; this.sources = sources; this.catalog = catalog;
        this.memory = memory; this.boards = boards;
        this.memoryLoader = memoryLoader; this.boardLoader = boardLoader;
    }

    @Transactional
    public Result seed() {
        for (var batch : CatalogSeedBatch.values()) {
            if (batch == CatalogSeedBatch.EXPAND_100 || batch == CatalogSeedBatch.EXPAND_300) continue;
            for (var item : loader.load(batch)) {
                String externalId = item.sources().stream()
                        .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES)
                        .findFirst().orElseThrow().externalId();
                if (!sources.existsBySourceNameAndExternalId(CatalogSourceName.BUILDCORES, externalId)) {
                    throw new IllegalStateException("Register the original 61 catalog products before week2-expand100; missing "
                            + externalId);
                }
            }
        }
        // 원래 12 CPU/16 보드 자료를 먼저 검증하며, v1 출처는 확장 후에도 그대로 보존한다.
        memory.seed();
        boards.seed();
        var products = catalog.seed(CatalogSeedBatch.EXPAND_100);
        var cpuMemory = memory.seed(CpuMemorySeedLoader.EXPANSION);
        var boardSupport = boards.seed(MotherboardCpuSeedLoader.EXPANSION);
        return new Result(products, cpuMemory, boardSupport);
    }

    /** 검증된 161종을 보존하고 139종 및 해당 CPU/보드 연결만 추가한다. */
    @Transactional
    public Result seed300() {
        assertExpansion300ResourcesConnect();
        for (var batch : CatalogSeedBatch.values()) {
            if (batch == CatalogSeedBatch.EXPAND_300) continue;
            for (var item : loader.load(batch)) {
                String externalId = item.sources().stream()
                        .filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES)
                        .findFirst().orElseThrow().externalId();
                if (!sources.existsBySourceNameAndExternalId(CatalogSourceName.BUILDCORES, externalId)) {
                    throw new IllegalStateException("Register and verify the original 161 catalog products before week2-expand300; missing "
                            + externalId);
                }
            }
            // 모든 ID가 존재함을 먼저 확인했으므로 이 호출은 제품/제원/출처 검사만 수행해야 한다.
            if (catalog.seed(batch).created() != 0) {
                throw new IllegalStateException("The original 161 catalog products changed during verification");
            }
        }
        // 누락된 이전 자료를 자동 복구하지 않는다. 현재 최신 프로필이 이미 300이면 전체 자료도 검증한다.
        memory.verify(CpuMemorySeedLoader.INITIAL);
        memory.verify(CpuMemorySeedLoader.EXPANSION);
        boards.verify(MotherboardCpuSeedLoader.EXPANSION);
        var products = catalog.seed(CatalogSeedBatch.EXPAND_300);
        var cpuMemory = memory.seed(CpuMemorySeedLoader.EXPANSION_300);
        var boardSupport = boards.seed(MotherboardCpuSeedLoader.EXPANSION_300);
        return new Result(products, cpuMemory, boardSupport);
    }

    /** 개수만 같은 다른 제품 묶음을 허용하지 않도록 승인된 제품/메모리/보드 자료를 연결해 확인한다. */
    private void assertExpansion300ResourcesConnect() {
        var newProducts = loader.load(CatalogSeedBatch.EXPAND_300);
        var requiredAdditions = Map.of(PartType.CPU, 33L, PartType.MOTHERBOARD, 29L, PartType.RAM, 19L,
                PartType.GPU, 46L, PartType.MONITOR, 12L);
        var actualAdditions = newProducts.stream().collect(Collectors.groupingBy(item -> item.product().type(), Collectors.counting()));
        if (!requiredAdditions.equals(actualAdditions)) {
            throw new IllegalStateException("week2-expand300 must contain the confirmed 33 CPUs, 29 boards, 19 RAM, 46 GPUs and 12 monitors");
        }
        var approved = new HashMap<String, CatalogEntryCreateRequest>();
        for (var batch : CatalogSeedBatch.values()) {
            var products = batch == CatalogSeedBatch.EXPAND_300 ? newProducts : loader.load(batch);
            for (var product : products) {
                if (approved.putIfAbsent(externalId(product), product) != null) {
                    throw new IllegalStateException("Approved catalog expansion batches contain a duplicate BuildCores identity");
                }
            }
        }
        var expectedCpus = identities(approved, PartType.CPU);
        var expectedBoards = identities(approved, PartType.MOTHERBOARD);
        if (approved.size() != 300 || expectedCpus.size() != 70 || expectedBoards.size() != 70) {
            throw new IllegalStateException("The approved combined catalog must contain 300 products, 70 CPUs and 70 boards");
        }
        Set<String> newCpuIds = newProducts.stream().filter(item -> item.product().type() == PartType.CPU)
                .map(CatalogExpansionService::externalId).collect(Collectors.toSet());
        var memoryProfiles = memoryLoader.load(CpuMemorySeedLoader.EXPANSION_300);
        var actualNewCpuIds = memoryProfiles.stream().map(CpuMemorySeedLoader.Item::externalId).collect(Collectors.toSet());
        if (!newCpuIds.equals(actualNewCpuIds) || memoryProfiles.stream().anyMatch(item ->
                !new MotherboardCpuSeedLoader.Identity(item.externalId(), item.manufacturer(), item.modelName(),
                        item.partNumber(), item.socketCode()).equals(expectedCpus.get(item.externalId())))) {
            throw new IllegalStateException("The new CPU memory profiles must exactly match the confirmed 33 CPU catalog identities");
        }
        var supportManifest = boardLoader.load(MotherboardCpuSeedLoader.EXPANSION_300);
        var actualCpus = supportManifest.cpus().stream().collect(Collectors.toMap(MotherboardCpuSeedLoader.Identity::externalId, identity -> identity));
        var actualBoards = supportManifest.items().stream().map(MotherboardCpuSeedLoader.Item::board)
                .collect(Collectors.toMap(MotherboardCpuSeedLoader.Identity::externalId, identity -> identity));
        if (!expectedCpus.equals(actualCpus) || !expectedBoards.equals(actualBoards)) {
            throw new IllegalStateException("The CPU support matrix must exactly match all 70 CPU and 70 motherboard catalog identities");
        }
    }

    private static Map<String, MotherboardCpuSeedLoader.Identity> identities(Map<String, CatalogEntryCreateRequest> products, PartType type) {
        var result = new HashMap<String, MotherboardCpuSeedLoader.Identity>();
        for (var entry : products.entrySet()) {
            var product = entry.getValue().product();
            if (product.type() != type) continue;
            String socket = type == PartType.CPU
                    ? ((CatalogSpecification.Cpu) entry.getValue().specification()).socketCode()
                    : ((CatalogSpecification.Motherboard) entry.getValue().specification()).socketCode();
            result.put(entry.getKey(), new MotherboardCpuSeedLoader.Identity(entry.getKey(), product.manufacturer(),
                    product.modelName(), product.partNumber(), socket));
        }
        return result;
    }

    private static String externalId(CatalogEntryCreateRequest item) {
        return item.sources().stream().filter(source -> source.sourceName() == CatalogSourceName.BUILDCORES)
                .findFirst().orElseThrow().externalId();
    }

    public record Result(CatalogSeedService.Result products, CpuMemorySeedService.Result cpuMemory,
                         MotherboardCpuSeedService.Result boardSupport) { }
}
