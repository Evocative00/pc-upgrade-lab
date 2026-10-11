package com.pcupgradelab.catalog.storage;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.pc.PartType;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Dedicated H2 only; every fixture is rolled back. No actual MySQL or price collection. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:storage-catalog-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "catalog.seed.enabled=false"})
@ActiveProfiles({"local", "test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class StorageCatalogIntegrationTests {
    @Autowired CatalogEntryService entries;
    @Autowired MotherboardStorageService storage;
    @Autowired MotherboardStorageQueryService query;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void h2Only() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:storage-catalog-test");
        }
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void storageEntryRoundTripsNominalCapacityAndNullHeatsinkWithoutCreatingCurrentPrice() throws Exception {
        var specification = StorageSpecificationTests.drive(1_000_000_000_000L, "PCIE", "NVME", "4.0", 4, "1.4", null);
        String id = create(PartType.STORAGE, specification);
        entityManager.clear();
        var entry = entries.findById(id).orElseThrow();
        assertThat(entry.specification()).isEqualTo(specification);
        assertThat(entry.product().referencePrice().amountKrw()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_price_observation", Long.class)).isZero();
        mvc.perform(get("/api/catalog/products/{id}", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.product.type").value("STORAGE"))
                .andExpect(jsonPath("$.specification.capacityBytes").value(1_000_000_000_000L))
                .andExpect(jsonPath("$.specification.heatsinkIncluded").isEmpty());
    }

    @Test
    void databaseRejectsCapacityBasisAndSataPcieContradictions() {
        String id = create(PartType.STORAGE, StorageSpecificationTests.drive(1_000_000_000_000L,
                "PCIE", "NVME", "4.0", 4, "1.4", null));
        assertThatThrownBy(() -> jdbc.update("UPDATE storage_spec SET capacity_bytes = ? WHERE product_id = ?",
                1_099_511_627_776L, id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE storage_spec SET capacity_basis = NULL WHERE product_id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE storage_spec SET bus_interface = 'SATA' WHERE product_id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE storage_spec SET dimensions_basis = NULL WHERE product_id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void storageSupportDistinguishesAbsentAndPartialDataAndPreservesRawConditions() throws Exception {
        String board = createBoard();
        mvc.perform(get("/api/catalog/products/{id}/storage-support", board)).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataAvailable").value(false))
                .andExpect(jsonPath("$.completeDataKnown").value(false))
                .andExpect(jsonPath("$.profiles", hasSize(0)));
        var rule = new MotherboardStorageSupport.Rule("sharing", "CPU family must be checked",
                "Minimum BIOS not verified", MotherboardStorageSupport.RuleEffect.PORT_SHARED, "SATA2",
                "When M2_1 operates in SATA mode, SATA2 is disabled; keep manufacturer condition verbatim.");
        var slot = new MotherboardStorageSupport.Slot("M2_1", "M2", "B_M", List.of("2280"),
                List.of("PCIE", "SATA"), List.of("NVME", "ATA"), "4.0", 4, "3.0", "CHIPSET",
                null, "Synthetic dual-protocol fixture", List.of(rule));
        storage.register(board, new MotherboardStorageSupport(MotherboardStorageSupport.RevisionScope.EXACT,
                "1.0", false, "Partial manual excerpt", List.of(slot), List.of(MotherboardStorageSupportTests.source())));
        entityManager.clear();
        var value = query.find(board);
        assertThat(value.dataAvailable()).isTrue();
        assertThat(value.completeDataKnown()).isFalse();
        assertThat(value.profiles().getFirst().slots().getFirst().rules().getFirst()).isEqualTo(rule);
        mvc.perform(get("/api/catalog/products/{id}/storage-support", board)).andExpect(status().isOk())
                .andExpect(jsonPath("$.profiles[0].revisionKey").value("REV:1.0"))
                .andExpect(jsonPath("$.profiles[0].slots[0].supportedBusInterfaces", hasSize(2)))
                .andExpect(jsonPath("$.profiles[0].slots[0].nvmeBootSupport").isEmpty())
                .andExpect(jsonPath("$.profiles[0].slots[0].rules[0].rawCondition").value(rule.rawCondition()))
                .andExpect(jsonPath("$.profiles[0].sources[0].supportedFacts").isNotEmpty());
    }

    @Test
    void unknownSlotFactsRemainEmptyAndNoRevisionIsInvented() throws Exception {
        String board = createBoard();
        var slot = new MotherboardStorageSupport.Slot("M2_1", "M2", null,
                List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of());
        storage.register(board, new MotherboardStorageSupport(MotherboardStorageSupport.RevisionScope.MODEL,
                null, false, null, List.of(slot), List.of(MotherboardStorageSupportTests.source())));
        entityManager.clear();
        mvc.perform(get("/api/catalog/products/{id}/storage-support", board)).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataAvailable").value(true))
                .andExpect(jsonPath("$.completeDataKnown").value(false))
                .andExpect(jsonPath("$.profiles[0].hardwareRevision").isEmpty())
                .andExpect(jsonPath("$.profiles[0].slots[0].supportedProtocols", hasSize(0)));
    }

    @Test
    void wrongProductTypeAndMissingProductAreRejected() throws Exception {
        String drive = create(PartType.STORAGE, StorageSpecificationTests.drive(1_000_000_000_000L,
                "SATA", null, null, null, null, "3.0"));
        mvc.perform(get("/api/catalog/products/{id}/storage-support", drive)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/products/{id}/storage-support", "missing")).andExpect(status().isNotFound());
    }

    private String createBoard() {
        return create(PartType.MOTHERBOARD, new CatalogSpecification.Motherboard("AM5", "B850", "MICRO_ATX",
                "DDR5", "DIMM", 4, null, null));
    }
    private String create(PartType type, CatalogSpecification specification) {
        return entries.create(new CatalogEntryCreateRequest(new CatalogProductCreateRequest(type, "Fixture",
                "Storage integration " + type, null), specification, List.of(new CatalogSourceInput(
                CatalogSourceName.MANUAL, null, "fixture", "https://example.com/storage", Map.of(),
                Instant.parse("2026-10-09T00:00:00Z"))))).product().id();
    }
}
