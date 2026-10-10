package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.catalog.identity.*;
import com.pcupgradelab.catalog.price.*;
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

/** Build the authoritative fixture once; real service transactions test rollback and replay on dedicated H2. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:retail-catalog-apply;MODE=MySQL;DB_CLOSE_DELAY=-1", "catalog.shared-prices.enabled=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RetailCatalogApplyIntegrationTests {
    @Autowired RetailCatalogApplyService service;
    @Autowired AllCatalogIdentityImportService fullIdentities;
    @Autowired CatalogEntryService entries;
    @Autowired CatalogSeedLoader seeds;
    @Autowired CatalogProductRepository products;
    @Autowired CatalogProductSourceRepository sources;
    @Autowired CatalogIdentityService identities;
    @Autowired CatalogPriceImportService prices;
    @Autowired CatalogPriceMappingRepository mappings;
    @Autowired JdbcTemplate jdbc;

    @Test void preservesEveryExistingTableRejectsConflictsRollsBackLateFailureAndReplaysOnlyCompletedApplication() {
        var plan = new RetailCatalogApplyLoader().load(Path.of("..").toAbsolutePath().normalize());
        prepare(plan);
        // Installed-PC fixture points at an existing canonical catalog product; apply must preserve it byte-for-byte.
        String legacyId = sources.findBySourceNameAndExternalId(plan.baseline().products().getFirst().sourceIdentity().sourceName(),
                plan.baseline().products().getFirst().sourceIdentity().externalId()).orElseThrow().getProduct().getId();
        jdbc.update("INSERT INTO pc_configuration(id,name,name_normalized,version,created_at,updated_at) VALUES(1,'Retail test','retail test',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO pc_part(pc_id,type,display_name,quantity,source,catalog_product_id,match_status,specs) VALUES(1,?,?,1,'MANUAL',?,'MATCHED','{}')",
                plan.baseline().products().getFirst().identity().type().name(),"Existing installed part",legacyId);
        var preview = service.preview(plan);
        assertThat(preview.dryRun()).isTrue(); assertThat(preview.createdProducts()).isEqualTo(1);
        assertThat(preview.before()).isEqualTo(preview.after());
        assertThat(preview.before().keySet()).contains("pc_part","catalog_model","catalog_model_source","catalog_price_observation");

        // A provider/product collision is detected before any registration.
        var conflict = mappings.saveAndFlush(new CatalogPriceMapping(products.findById(legacyId).orElseThrow(),plan.sale().price().offer()));
        assertThatThrownBy(() -> service.apply(plan)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already mapped");
        assertThat(products.count()).isEqualTo(307);
        mappings.deleteById(conflict.getId()); mappings.flush();

        // Force the last approved INSERT to fail after product, specification, source and binding have already flushed.
        jdbc.execute("ALTER TABLE catalog_price_observation ADD CONSTRAINT ck_retail_late_failure CHECK (amount_krw<>275350)");
        assertThatThrownBy(() -> service.apply(plan)).isInstanceOf(RuntimeException.class);
        jdbc.execute("ALTER TABLE catalog_price_observation DROP CONSTRAINT ck_retail_late_failure");
        assertThat(products.count()).isEqualTo(307);
        assertThat(sources.findBySourceNameAndExternalId(CatalogSourceName.MANUFACTURER,RetailCatalogApplyLoader.SOURCE_ID)).isEmpty();
        assertThat(service.preview(plan).before()).isEqualTo(preview.before());

        var applied = service.apply(plan);
        assertThat(applied.dryRun()).isFalse(); assertThat(applied.createdProducts()).isEqualTo(1);
        assertThat(applied.createdSources()).isEqualTo(1); assertThat(applied.productBindings()).isEqualTo(1);
        assertThat(applied.createdPriceMappings()).isEqualTo(1); assertThat(applied.createdPriceObservations()).isEqualTo(1);
        assertThat(applied.modelWrites()).isZero(); assertThat(products.count()).isEqualTo(308);
        for (String table : applied.before().keySet()) {
            long delta = applied.after().get(table).rows()-applied.before().get(table).rows();
            assertThat(delta).as(table).isEqualTo(RetailCatalogPreservationAudit.INSERT_TABLES.containsKey(table) ? 1 : 0);
            if (!RetailCatalogPreservationAudit.INSERT_TABLES.containsKey(table))
                assertThat(applied.after().get(table)).as(table).isEqualTo(applied.before().get(table));
        }
        var product = products.findById(applied.productId()).orElseThrow();
        assertThat(product.getCanonicalId()).isEqualTo(RetailCatalogApplyLoader.PRODUCT_CANONICAL);
        assertThat(product.getIdentityKind()).isEqualTo(CatalogIdentityKind.PHYSICAL_VARIANT);
        assertThat(product.isActive()).isFalse(); assertThat(product.getVerificationStatus()).isEqualTo(CatalogVerificationStatus.UNVERIFIED);
        var replay = service.apply(plan);
        assertThat(replay.createdProducts()).isZero(); assertThat(replay.createdPriceObservations()).isZero();
        assertThat(replay.unchangedProducts()).isEqualTo(1); assertThat(replay.before()).isEqualTo(applied.after());
        assertThat(replay.before()).isEqualTo(replay.after());

        // An incomplete historical application is rejected, not silently repaired by the apply command.
        jdbc.update("DELETE FROM catalog_price_observation WHERE product_id=?",applied.productId());
        assertThatThrownBy(() -> service.apply(plan)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Partial retail price");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_price_observation WHERE product_id=?",Integer.class,applied.productId())).isZero();
        prices.apply(plan.priceBatch());
        jdbc.update("UPDATE catalog_product SET identity_review_scope='changed review' WHERE id=?",applied.productId());
        assertThatThrownBy(() -> service.apply(plan)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Partial or differing");
        assertThat(products.count()).isEqualTo(308);
    }
    private void prepare(RetailCatalogApplyLoader.Plan plan) {
        for (var batch : CatalogSeedBatch.values()) for (var seed : seeds.load(batch)) entries.create(seed);
        var modelIds = new HashMap<String,String>();
        for (var model : plan.pilot().models()) modelIds.put(model.canonicalId(),identities.registerModel(model).id());
        for (var part : plan.pilot().parts()) {
            var source = sources.findBySourceNameAndExternalId(part.sourceIdentity().sourceName(),part.sourceIdentity().externalId());
            String id;
            if (source.isPresent()) {
                id=source.orElseThrow().getProduct().getId();
                sources.saveAndFlush(new CatalogProductSource(products.findById(id).orElseThrow(),part.manufacturerEvidence()));
            } else id=entries.create(new CatalogEntryCreateRequest(part.product(),part.specification(),List.of(part.manufacturerEvidence()))).product().id();
            var evidence=sources.findBySourceNameAndExternalId(part.manufacturerEvidence().sourceName(),part.manufacturerEvidence().externalId()).orElseThrow();
            identities.bindProduct(new CatalogIdentityRequests.ProductBinding(id,part.proposedCanonicalId(),modelIds.get(part.modelCanonicalId()),
                    part.identityKind(),part.role(),evidence.getId(),part.bindingReviewScope()));
        }
        prices.apply(plan.baseline().prices());
        fullIdentities.apply(plan.baseline());
    }
}
