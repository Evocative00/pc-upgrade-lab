package com.pcupgradelab.catalog.pilot;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.catalog.memory.CpuMemorySeedLoader;
import com.pcupgradelab.catalog.memory.CpuMemorySupportRepository;
import com.pcupgradelab.catalog.price.CatalogPriceImportBatch;
import com.pcupgradelab.catalog.price.CatalogPriceImportService;
import com.pcupgradelab.catalog.seed.CatalogSeedBatch;
import com.pcupgradelab.catalog.seed.CatalogSeedLoader;
import com.pcupgradelab.catalog.support.MotherboardCpuSeedLoader;
import com.pcupgradelab.catalog.support.MotherboardCpuSupportRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Commits preparation separately, then verifies the service's own transaction in dedicated H2. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:catalog-pilot-import-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogPilotImportIntegrationTests {
    private static final List<String> TABLES = List.of("catalog_price_observation", "catalog_price_mapping",
            "motherboard_storage_rule", "motherboard_storage_slot_length", "motherboard_storage_slot_bus",
            "motherboard_storage_slot_protocol", "motherboard_storage_source", "motherboard_storage_slot",
            "motherboard_storage_profile", "motherboard_cpu_support", "motherboard_cpu_support_profile",
            "cpu_memory_type_support", "cpu_memory_support", "catalog_product_source", "gpu_power_connector",
            "storage_spec", "cpu_spec", "motherboard_spec", "ram_spec", "gpu_spec", "monitor_spec",
            "catalog_reference_price", "catalog_product", "catalog_model_alias", "catalog_model_source", "catalog_model");
    @Autowired CatalogPilotImportService service;
    @Autowired CatalogEntryService entries;
    @Autowired CatalogSeedLoader seeds;
    @Autowired CatalogProductRepository products;
    @Autowired CatalogProductSourceRepository sources;
    @Autowired CpuMemorySeedLoader memorySeeds;
    @Autowired CpuMemorySupportRepository memory;
    @Autowired MotherboardCpuSeedLoader supportSeeds;
    @Autowired MotherboardCpuSupportRepository support;
    @Autowired CatalogPriceImportService prices;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    private CatalogPilotImportLoader.Plan plan;
    private Map<Integer, String> existingIds;

    @BeforeEach
    void prepareOnlySevenExistingProductsAndOriginalCompatibilityEvidence() throws Exception {
        assertDedicatedH2();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(count("catalog_product")).isZero();
        plan = new CatalogPilotImportLoader().load(Path.of("..").toAbsolutePath().normalize());
        existingIds = new LinkedHashMap<>();
        var candidates = new ArrayList<CatalogEntryCreateRequest>();
        for (var batch : CatalogSeedBatch.values()) candidates.addAll(seeds.load(batch));
        for (var part : plan.parts()) {
            if (part.sourceIdentity().sourceName() != CatalogSourceName.BUILDCORES) continue;
            var original = candidates.stream().filter(request -> request.sources().stream().anyMatch(source ->
                    source.sourceName() == CatalogSourceName.BUILDCORES
                            && source.externalId().equals(part.sourceIdentity().externalId()))).findFirst().orElseThrow();
            assertThat(original.product()).isEqualTo(part.product());
            assertThat(original.specification()).isEqualTo(part.specification());
            existingIds.put(part.selectionNumber(), entries.create(original).product().id());
        }
        assertThat(existingIds).hasSize(7);
        var transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(status -> seedOriginalMemoryProfilesAndB650Support());
        assertThat(count("catalog_product")).isEqualTo(7);
        assertThat(count("cpu_memory_support")).isEqualTo(2);
        assertThat(count("motherboard_cpu_support")).isEqualTo(2);
        assertThat(count("catalog_price_observation")).isZero();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @AfterEach
    void removeOnlyDedicatedH2Fixtures() throws Exception {
        assertDedicatedH2();
        jdbc.update("""
                UPDATE catalog_product SET canonical_id=NULL, model_id=NULL, identity_kind='LEGACY_UNCLASSIFIED',
                role='UNASSIGNED', identity_evidence_source_id=NULL, identity_review_scope=NULL, identity_reviewed_at=NULL
                """);
        for (String table : TABLES) jdbc.update("DELETE FROM " + table);
    }

    @Test
    void previewDoesNotWriteAndApplyPreservesOldIdsSpecsAndEvidenceThenReplaysWithoutChanges() {
        var originalEntries = existingIds.values().stream().collect(Collectors.toMap(Function.identity(),
                id -> entries.findById(id).orElseThrow()));
        var originalMemory = List.of(memory.findByProductId(existingIds.get(1)).orElseThrow(),
                memory.findByProductId(existingIds.get(2)).orElseThrow());
        var originalSupport = support.findByRevision(existingIds.get(5), "MODEL").orElseThrow();
        var before = snapshot();
        var expected = new CatalogPilotImportService.Result(true, 7, 13, 14, 14, 4, 19, 2, 6, 8, 8);
        assertThat(service.preview(plan)).isEqualTo(expected);
        assertThat(snapshot()).isEqualTo(before);
        var applied = service.apply(plan);
        assertThat(applied).isEqualTo(new CatalogPilotImportService.Result(false, 7, 13, 14, 14, 4, 19, 2, 6, 8, 8));
        assertThat(count("catalog_product")).isEqualTo(14);
        assertThat(count("catalog_model")).isEqualTo(13);
        assertThat(count("catalog_model_source")).isEqualTo(14);
        assertThat(count("motherboard_storage_profile")).isEqualTo(4);
        assertThat(count("motherboard_storage_slot")).isEqualTo(19);
        assertThat(count("cpu_memory_support")).isEqualTo(4);
        assertThat(count("motherboard_cpu_support")).isEqualTo(8);
        assertThat(count("catalog_price_mapping")).isEqualTo(8);
        assertThat(count("catalog_price_observation")).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product WHERE canonical_id IS NOT NULL", Integer.class)).isEqualTo(14);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product WHERE is_active=TRUE OR verification_status<>'UNVERIFIED'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_model WHERE verification_status<>'UNVERIFIED'", Integer.class)).isZero();
        originalEntries.forEach((id, original) -> {
            var after = entries.findById(id).orElseThrow();
            assertThat(after.specification()).isEqualTo(original.specification());
            assertThat(after.product().id()).isEqualTo(original.product().id());
            assertThat(after.product().createdAt()).isEqualTo(original.product().createdAt());
            assertThat(after.product().referencePrice()).isEqualTo(original.product().referencePrice());
            assertThat(after.sources()).containsAll(original.sources());
        });
        for (var old : originalMemory) assertThat(memory.findByProductId(old.productId()).orElseThrow()).isEqualTo(old);
        assertThat(support.findByRevision(existingIds.get(5), "MODEL").orElseThrow()).isEqualTo(originalSupport);
        for (var part : plan.parts()) if (existingIds.containsKey(part.selectionNumber())) {
            assertThat(sources.findBySourceNameAndExternalId(CatalogSourceName.BUILDCORES, part.sourceIdentity().externalId())
                    .orElseThrow().getProduct().getId()).isEqualTo(existingIds.get(part.selectionNumber()));
        }
        var after = snapshot();
        var replay = new CatalogPilotImportService.Result(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(service.apply(plan)).isEqualTo(replay);
        assertThat(snapshot()).isEqualTo(after);
        assertThat(service.preview(plan)).isEqualTo(new CatalogPilotImportService.Result(true, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThat(snapshot()).isEqualTo(after);
    }

    @Test
    void lastSsdPriceInsertFailureRollsBackProductsModelsBindingsSlotsAndEarlierSevenPrices() {
        var before = snapshot();
        long lastAmount = plan.prices().items().getLast().price().amountKrw();
        jdbc.execute("ALTER TABLE catalog_price_observation ADD CONSTRAINT ck_pilot_price_fail CHECK (amount_krw <> " + lastAmount + ")");
        try {
            var failure = catchThrowable(() -> service.apply(plan));
            assertThat(failure).isNotNull().isInstanceOf(RuntimeException.class);
            assertThat(NestedExceptionUtils.getMostSpecificCause(failure).getMessage()).containsIgnoringCase("ck_pilot_price_fail");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(snapshot()).isEqualTo(before);
        } finally {
            jdbc.execute("ALTER TABLE catalog_price_observation DROP CONSTRAINT ck_pilot_price_fail");
        }
    }

    @Test
    void providerCollisionAndCommittedIdentityChangeAfterPreviewAreRejectedWithoutPartialWrites() {
        var gpu = plan.prices().items().getFirst();
        var ssd = plan.prices().items().getLast();
        // A pre-existing seller identity owned by another product must never be reassigned.
        prices.apply(new CatalogPriceImportBatch(1, List.of(new CatalogPriceImportBatch.Item(gpu.product(), ssd.offer(), ssd.price()))));
        var collisionBaseline = snapshot();
        assertThatThrownBy(() -> service.preview(plan)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already mapped");
        assertThatThrownBy(() -> service.apply(plan)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already mapped");
        assertThat(snapshot()).isEqualTo(collisionBaseline);
        jdbc.update("DELETE FROM catalog_price_observation");
        jdbc.update("DELETE FROM catalog_price_mapping");
        service.preview(plan);
        // Simulate a different committed session editing an existing product after that preview.
        jdbc.update("UPDATE catalog_product SET model_name=? WHERE id=?", "Externally changed model", existingIds.get(1));
        var changedBaseline = snapshot();
        assertThatThrownBy(() -> service.apply(plan)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity or specification differs");
        assertThat(snapshot()).isEqualTo(changedBaseline);
    }

    private void seedOriginalMemoryProfilesAndB650Support() {
        for (String batch : List.of(CpuMemorySeedLoader.INITIAL, CpuMemorySeedLoader.EXPANSION, CpuMemorySeedLoader.EXPANSION_300)) {
            for (var item : memorySeeds.load(batch)) for (int number : List.of(1, 2)) {
                var part = plan.parts().get(number - 1);
                if (!item.externalId().equals(part.sourceIdentity().externalId())) continue;
                var evidence = sources.saveAndFlush(new CatalogProductSource(products.getReferenceById(existingIds.get(number)), item.source(batch)));
                memory.insert(existingIds.get(number), evidence.getId(), item.support());
            }
        }
        var board = plan.parts().get(4);
        for (String batch : List.of(MotherboardCpuSeedLoader.EXPANSION_300, MotherboardCpuSeedLoader.EXPANSION, MotherboardCpuSeedLoader.SEED_NAME)) {
            var original = supportSeeds.load(batch).items().stream()
                    .filter(item -> item.board().externalId().equals(board.sourceIdentity().externalId())).findFirst();
            if (original.isEmpty()) continue;
            var item = original.orElseThrow();
            var cpuIds = Map.of(plan.parts().get(0).sourceIdentity().externalId(), existingIds.get(1),
                    plan.parts().get(1).sourceIdentity().externalId(), existingIds.get(2));
            var subset = new MotherboardCpuSeedLoader.SupportInput(item.support().revisionScope(), item.support().hardwareRevision(),
                    item.support().conditions(), item.support().entries().stream().filter(entry -> cpuIds.containsKey(entry.cpuExternalId())).toList());
            assertThat(subset.entries()).hasSize(2);
            var evidence = sources.saveAndFlush(new CatalogProductSource(products.getReferenceById(existingIds.get(5)), item.source(batch)));
            support.insert(existingIds.get(5), evidence.getId(), subset.resolve(cpuIds::get));
            return;
        }
        throw new IllegalStateException("Original B650 evidence is missing from the bundled reviewed seeds");
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : TABLES) {
            var rows = jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1, 2");
            rows.forEach(row -> row.replaceAll((key, value) -> value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value));
            result.put(table, rows);
        }
        return result;
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private void assertDedicatedH2() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:catalog-pilot-import-test");
        }
    }
}
