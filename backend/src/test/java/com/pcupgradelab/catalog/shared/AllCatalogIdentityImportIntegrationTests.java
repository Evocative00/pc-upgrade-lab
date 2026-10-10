package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.catalog.identity.*;
import com.pcupgradelab.catalog.pilot.CatalogPilotImportLoader;
import com.pcupgradelab.catalog.price.CatalogPriceImportService;
import com.pcupgradelab.catalog.seed.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

/** Dedicated H2: service-owned transactions prove preflight safety, preservation and idempotent replay. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:all-catalog-identity-import;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "catalog.shared-prices.enabled=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AllCatalogIdentityImportIntegrationTests {
    @Autowired AllCatalogIdentityImportService service;
    @Autowired CatalogEntryService entries;
    @Autowired CatalogSeedLoader seeds;
    @Autowired CatalogProductRepository products;
    @Autowired CatalogProductSourceRepository sources;
    @Autowired CatalogIdentityService identities;
    @Autowired CatalogPriceImportService prices;
    @Autowired JdbcTemplate jdbc;

    @Test void preflightsAllRowsPreservesPricesSpecsPcLinksAndPilotThenApplies293OnlyOnce() {
        var root = Path.of("..").toAbsolutePath().normalize();
        var plan = new AllCatalogManifestGenerator().load(root);
        var pilot = new CatalogPilotImportLoader().load(root);
        for (var batch : CatalogSeedBatch.values()) for (var seed : seeds.load(batch)) entries.create(seed);
        var modelIds = new HashMap<String,String>();
        for (var model : pilot.models()) modelIds.put(model.canonicalId(),identities.registerModel(model).id());
        for (var part : pilot.parts()) {
            var source = sources.findBySourceNameAndExternalId(part.sourceIdentity().sourceName(),part.sourceIdentity().externalId());
            final String id;
            if (source.isPresent()) {
                id = source.orElseThrow().getProduct().getId();
                sources.saveAndFlush(new CatalogProductSource(products.findById(id).orElseThrow(),part.manufacturerEvidence()));
            } else id = entries.create(new CatalogEntryCreateRequest(part.product(),part.specification(),List.of(part.manufacturerEvidence()))).product().id();
            var evidence = sources.findBySourceNameAndExternalId(part.manufacturerEvidence().sourceName(),part.manufacturerEvidence().externalId()).orElseThrow();
            identities.bindProduct(new CatalogIdentityRequests.ProductBinding(id,part.proposedCanonicalId(),modelIds.get(part.modelCanonicalId()),
                    part.identityKind(),part.role(),evidence.getId(),part.bindingReviewScope()));
        }
        prices.apply(plan.prices());
        var legacy = plan.products().stream().filter(p -> p.bindLegacy()).findFirst().orElseThrow();
        var legacyId = sources.findBySourceNameAndExternalId(legacy.sourceIdentity().sourceName(),legacy.sourceIdentity().externalId()).orElseThrow().getProduct().getId();
        jdbc.update("INSERT INTO pc_configuration(id,name,name_normalized,version,created_at,updated_at) VALUES(1,'Identity test','identity test',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO pc_part(pc_id,type,display_name,quantity,source,catalog_product_id,match_status,specs) VALUES(1,?,?,1,'MANUAL',?,'MATCHED','{}')",
                legacy.identity().type().name(),legacy.identity().modelName(),legacyId);
        assertThat(countCanonical()).isEqualTo(14);
        var before = preservedRows();
        var pilotRows = jdbc.queryForList("SELECT * FROM catalog_product WHERE canonical_id IS NOT NULL ORDER BY id");
        assertThat(service.preview(plan)).isEqualTo(new AllCatalogIdentityImportService.Result(true,307,293,0,14,0,0,0));
        assertThat(countCanonical()).isEqualTo(14);
        assertThat(preservedRows()).isEqualTo(before);

        // A conflict at the end of the preflight must leave every other canonical binding untouched.
        var last = plan.products().getLast();
        var lastId = sources.findBySourceNameAndExternalId(last.sourceIdentity().sourceName(),last.sourceIdentity().externalId()).orElseThrow().getProduct().getId();
        jdbc.update("UPDATE catalog_product SET model_name=? WHERE id=?",last.identity().modelName() + " mismatch",lastId);
        assertThatThrownBy(() -> service.apply(plan)).isInstanceOf(IllegalArgumentException.class);
        assertThat(countCanonical()).isEqualTo(14);
        jdbc.update("UPDATE catalog_product SET model_name=? WHERE id=?",last.identity().modelName(),lastId);

        assertThat(service.apply(plan)).isEqualTo(new AllCatalogIdentityImportService.Result(false,307,293,0,14,0,0,0));
        assertThat(countCanonical()).isEqualTo(307);
        assertThat(preservedRows()).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM catalog_product WHERE identity_kind<>'LEGACY_UNCLASSIFIED' ORDER BY id")).isEqualTo(pilotRows);
        var boundRows = jdbc.queryForList("SELECT * FROM catalog_product ORDER BY id");
        assertThat(service.apply(plan)).isEqualTo(new AllCatalogIdentityImportService.Result(false,307,0,293,14,0,0,0));
        assertThat(jdbc.queryForList("SELECT * FROM catalog_product ORDER BY id")).isEqualTo(boundRows);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product WHERE is_active=TRUE OR verification_status<>'UNVERIFIED'",Integer.class)).isZero();
    }
    private int countCanonical() { return jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product WHERE canonical_id IS NOT NULL",Integer.class); }
    private Map<String,Object> preservedRows() {
        var rows = new LinkedHashMap<String,Object>();
        rows.put("product",jdbc.queryForList("SELECT id,type,manufacturer,model_name,part_number,is_active,verification_status,created_at FROM catalog_product ORDER BY id"));
        for (String table : List.of("catalog_reference_price","catalog_price_mapping","catalog_price_observation","catalog_product_source",
                "cpu_spec","motherboard_spec","ram_spec","gpu_spec","monitor_spec","storage_spec","pc_configuration","pc_part"))
            rows.put(table,jdbc.queryForList("SELECT * FROM " + table).stream().map(row -> {
                var normalized = new LinkedHashMap<String,Object>();
                row.forEach((key,value) -> normalized.put(key,value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value));
                return normalized;
            }).toList());
        return rows;
    }
}
