package com.pcupgradelab.catalog;

import com.pcupgradelab.pc.PartType;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 전용 H2 DB에서 실제 커밋·롤백을 검사한다. 테스트 트랜잭션은 사용하지 않으며 MySQL 검증은 별도다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:catalog-entry-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogEntryServiceTests {
    private static final long GIB = 1024L * 1024 * 1024;
    private static final Instant RETRIEVED_AT = Instant.parse("2026-09-01T12:00:00Z");
    private static final String REVISION = "a".repeat(40);
    private static final List<String> TABLES = List.of("catalog_product_source", "cpu_spec",
            "motherboard_spec", "ram_spec", "catalog_reference_price", "catalog_product");

    @Autowired CatalogEntryService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void requireEmptyDedicatedDatabase() throws SQLException {
        assertDedicatedDatabase();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertAllTablesEmpty();
    }

    @AfterEach
    void cleanCommittedFixtures() throws SQLException {
        assertDedicatedDatabase();
        for (String table : TABLES) jdbc.update("DELETE FROM " + table);
    }

    @Test
    void commitsAndReloadsCpuMotherboardAndRamWithTheirOwnSpecifications() {
        for (CatalogSpecification specification : List.of(cpu(), motherboard(), ram())) {
            CatalogEntryView created = service.create(request(specification, List.of(manufacturerSource())));
            CatalogEntryView reloaded = service.findById(created.product().id()).orElseThrow();
            assertThat(UUID.fromString(reloaded.product().id()).toString()).isEqualTo(created.product().id());
            assertThat(reloaded.product().type()).isEqualTo(specification.type());
            assertThat(reloaded.product().verificationStatus()).isEqualTo(CatalogVerificationStatus.UNVERIFIED);
            assertThat(reloaded.product().active()).isFalse();
            assertThat(reloaded.product().referencePrice().status()).isEqualTo(CatalogPriceStatus.UNCONFIRMED);
            assertThat(reloaded.product().referencePrice().amountKrw()).isNull();
            assertThat(reloaded.specification()).isEqualTo(specification);
            assertThat(reloaded.sources()).hasSize(1);
            assertThat(reloaded.sources().getFirst().rawPayload()).isNull();
            if (reloaded.specification() instanceof CatalogSpecification.Ram value) {
                assertThat(value.moduleCapacityBytes()).isEqualTo(25_769_803_776L);
                assertThat(value.moduleCount()).isEqualTo(2);
                assertThat(value.moduleCapacityBytes() * value.moduleCount()).isEqualTo(48 * GIB);
                assertThat(value.isEcc()).isNull();
                assertThat(value.voltageV()).isEqualTo(new BigDecimal("1.350"));
            }
        }
        assertThat(rowCount("catalog_product")).isEqualTo(3);
        assertThat(rowCount("catalog_reference_price")).isEqualTo(3);
        assertThat(rowCount("catalog_product_source")).isEqualTo(3);
        for (String table : List.of("cpu_spec", "motherboard_spec", "ram_spec")) {
            assertThat(rowCount(table)).isEqualTo(1);
        }
    }

    @Test
    void commitsAndReloadsAllEightCpuExtensionFieldsWithoutChangingTdpOrPrice() {
        var hybrid = new CatalogSpecification.Cpu("LGA1700", 14, 20, null, 5100, null, false, null,
                new BigDecimal("125"), new BigDecimal("181"), 6, 8, 3500, 2600, 5100, 3900);
        var created = service.create(request(hybrid, List.of(manufacturerSource())));
        var reloaded = service.findById(created.product().id()).orElseThrow();
        assertThat(reloaded.specification()).isEqualTo(hybrid);
        assertThat(reloaded.product().referencePrice().amountKrw()).isNull();
        var stored = jdbc.queryForMap("SELECT * FROM cpu_spec WHERE product_id = ?", created.product().id());
        assertThat(stored.get("TDP_W")).isNull();
        assertThat(stored.get("BASE_CLOCK_MHZ")).isNull();
        assertThat(((Number) stored.get("PERFORMANCE_CORE_COUNT")).intValue()).isEqualTo(6);
        assertThat(((Number) stored.get("EFFICIENT_CORE_COUNT")).intValue()).isEqualTo(8);
        assertThat(stored.get("PROCESSOR_BASE_POWER_W")).isEqualTo(new BigDecimal("125.00"));
        assertThat(stored.get("MAXIMUM_TURBO_POWER_W")).isEqualTo(new BigDecimal("181.00"));
    }

    @Test
    void rawPayloadSurvivesCallerMutationAndPreservesNestedNumbersNullsAndLists() {
        String externalId = UUID.randomUUID().toString();
        var memory = new LinkedHashMap<String, Object>();
        memory.put("module_capacity_bytes", 24 * GIB);
        memory.put("unknown", null);
        var memoryTypes = new ArrayList<>(Arrays.asList("DDR4", "DDR5", null));
        var raw = new LinkedHashMap<String, Object>();
        raw.put("opendb_id", externalId);
        raw.put("memory", memory);
        raw.put("memory_types", memoryTypes);
        CatalogSourceInput source = buildCores(externalId, raw);
        var sources = new ArrayList<>(List.of(source, manufacturerSource()));
        CatalogEntryCreateRequest input = request(cpu(), sources);
        raw.put("opendb_id", "changed-after-construction");
        memory.put("module_capacity_bytes", 0);
        memoryTypes.clear();
        sources.clear();

        String id = service.create(input).product().id();
        CatalogEntryView reloaded = service.findById(id).orElseThrow();
        assertThat(reloaded.sources()).hasSize(2);
        CatalogSourceView storedSource = reloaded.sources().stream()
                .filter(value -> value.sourceName() == CatalogSourceName.BUILDCORES).findFirst().orElseThrow();
        Map<String, Object> stored = storedSource.rawPayload();
        assertThat(stored.get("opendb_id")).isEqualTo(externalId);
        Map<?, ?> storedMemory = (Map<?, ?>) stored.get("memory");
        Number capacity = (Number) storedMemory.get("module_capacity_bytes");
        assertThat(capacity.longValue()).isEqualTo(24 * GIB);
        assertThat(storedMemory.containsKey("unknown")).isTrue();
        assertThat(storedMemory.get("unknown")).isNull();
        List<?> storedTypes = (List<?>) stored.get("memory_types");
        assertThat(storedTypes).isEqualTo(Arrays.asList("DDR4", "DDR5", null));
        assertThat(storedSource.retrievedAt()).isEqualTo(RETRIEVED_AT);
        assertThatThrownBy(() -> stored.put("new-key", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(storedMemory::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(storedTypes::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(reloaded.sources()::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mismatchedTypesMissingSourcesAndDuplicateRequestIdentitiesLeaveNoRows() {
        CatalogSourceInput source = buildCores(UUID.randomUUID().toString());
        List<Runnable> invalidCalls = List.of(
                () -> service.create(new CatalogEntryCreateRequest(product(PartType.CPU), ram(), List.of(source))),
                () -> service.create(request(cpu(), List.of())),
                () -> service.create(request(cpu(), List.of(source, source))));
        for (Runnable invalidCall : invalidCalls) {
            assertThatThrownBy(invalidCall::run).isInstanceOf(IllegalArgumentException.class);
            assertAllTablesEmpty();
        }
    }

    @Test
    void duplicateExternalIdentityDoesNotChangeThePreviouslyCommittedEntry() {
        CatalogSourceInput source = buildCores(UUID.randomUUID().toString());
        CatalogEntryView first = service.create(request(cpu(), List.of(source)));
        assertThatThrownBy(() -> service.create(request(ram(), List.of(source))))
                .isInstanceOf(IllegalArgumentException.class);
        CatalogEntryView remaining = service.findById(first.product().id()).orElseThrow();
        assertThat(remaining.product().id()).isEqualTo(first.product().id());
        assertThat(remaining.specification()).isEqualTo(cpu());
        assertThat(remaining.sources()).hasSize(1);
        assertThat(remaining.sources().getFirst().externalId()).isEqualTo(source.externalId());
        for (String table : List.of("catalog_product", "catalog_reference_price", "cpu_spec", "catalog_product_source")) {
            assertThat(rowCount(table)).isEqualTo(1);
        }
        assertThat(rowCount("ram_spec")).isZero();
        assertThat(rowCount("motherboard_spec")).isZero();
    }

    @Test
    void sourceInsertFailureRollsBackProductPriceAndSpecification() {
        assertAllTablesEmpty();
        // 전용 DB에서 마지막 저장 단계인 출처 INSERT를 거절한다. 앞선 flush도 모두 롤백되어야 한다.
        jdbc.execute("""
                ALTER TABLE catalog_product_source
                ADD CONSTRAINT ck_test_reject_catalog_source CHECK (1 = 0)
                """);
        try {
            Throwable failure = catchThrowable(() -> service.create(request(cpu(), List.of(manufacturerSource()))));
            assertThat(failure).isNotNull();
            assertThat(NestedExceptionUtils.getMostSpecificCause(failure).getMessage())
                    .containsIgnoringCase("ck_test_reject_catalog_source");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertAllTablesEmpty();
        } finally {
            jdbc.execute("ALTER TABLE catalog_product_source DROP CONSTRAINT ck_test_reject_catalog_source");
        }
    }

    @Test
    void invalidBuildCoresIdentityRevisionAndSourceMetadataAreRejectedBeforeSaving() {
        String id = UUID.randomUUID().toString();
        List<Runnable> invalidInputs = List.of(
                () -> buildCores("not-a-uuid"),
                () -> buildCores(id, Map.of("opendb_id", UUID.randomUUID().toString())),
                () -> new CatalogSourceInput(CatalogSourceName.BUILDCORES, id, null,
                        "https://example.invalid/buildcores", Map.of("opendb_id", id), RETRIEVED_AT),
                () -> new CatalogSourceInput(CatalogSourceName.BUILDCORES, id, "not-a-commit",
                        "https://example.invalid/buildcores", Map.of("opendb_id", id), RETRIEVED_AT),
                () -> new CatalogSourceInput(CatalogSourceName.MANUFACTURER, null, null,
                        "/relative/path", null, RETRIEVED_AT),
                () -> new CatalogSourceInput(CatalogSourceName.MANUFACTURER, null, null,
                        "https://example.invalid/specifications", null, null));
        for (Runnable invalidInput : invalidInputs) {
            assertThatThrownBy(invalidInput::run).isInstanceOf(IllegalArgumentException.class);
        }
        assertAllTablesEmpty();
    }

    @Test
    void unknownEntriesAreEmptyButMultipleSpecificationRowsAreIntegrityFailures() {
        assertThat(service.findById(UUID.randomUUID().toString())).isEmpty();
        CatalogEntryView created = service.create(request(cpu(), List.of(manufacturerSource())));
        // FK만으로 종류 불일치를 막을 수 없으므로 기존 CPU에 다른 종류의 행을 직접 추가한다.
        jdbc.update("INSERT INTO ram_spec (product_id) VALUES (?)", created.product().id());
        assertThatThrownBy(() -> service.findById(created.product().id())).isInstanceOf(IllegalStateException.class);
    }

    private CatalogEntryCreateRequest request(CatalogSpecification specification, List<CatalogSourceInput> sources) {
        return new CatalogEntryCreateRequest(product(specification.type()), specification, sources);
    }

    private CatalogProductCreateRequest product(PartType type) {
        return new CatalogProductCreateRequest(type, "Test Manufacturer", "Entry fixture " + type, null);
    }

    private CatalogSpecification.Cpu cpu() {
        return new CatalogSpecification.Cpu("AM5", 8, 16, 3600, 5000,
                new BigDecimal("65.50"), null, null);
    }

    private CatalogSpecification.Motherboard motherboard() {
        return new CatalogSpecification.Motherboard("AM5", "B650", "ATX", "DDR5", "DIMM", 4, 256 * GIB, null);
    }

    private CatalogSpecification.Ram ram() {
        return new CatalogSpecification.Ram("DDR5", 24 * GIB, 2, 6000, "DIMM", 288, null,
                "UNBUFFERED", new BigDecimal("1.350"), new BigDecimal("32.50"));
    }

    private CatalogSourceInput manufacturerSource() {
        return new CatalogSourceInput(CatalogSourceName.MANUFACTURER, null, null,
                "https://example.invalid/manufacturer/specifications", null, RETRIEVED_AT);
    }

    private CatalogSourceInput buildCores(String externalId) {
        return buildCores(externalId, Map.of("opendb_id", externalId));
    }

    private CatalogSourceInput buildCores(String externalId, Map<String, Object> rawPayload) {
        return new CatalogSourceInput(CatalogSourceName.BUILDCORES, externalId, REVISION,
                "https://example.invalid/buildcores/parts.json", rawPayload, RETRIEVED_AT);
    }

    private int rowCount(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertAllTablesEmpty() {
        for (String table : TABLES) assertThat(rowCount(table)).as("Rows in %s", table).isZero();
    }

    private void assertDedicatedDatabase() throws SQLException {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL().split(";", 2)[0]).isEqualTo("jdbc:h2:mem:catalog-entry-test");
        }
    }
}
