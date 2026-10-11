package com.pcupgradelab.catalog.powercase;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.catalog.identity.*;
import com.pcupgradelab.catalog.price.*;
import com.pcupgradelab.catalog.seed.*;
import com.pcupgradelab.catalog.shared.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

/** 한 번 준비한 308종 H2 fixture에서 실제 서비스 트랜잭션의 보존·롤백·재실행을 검증한다. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:power-case-import;MODE=MySQL;DB_CLOSE_DELAY=-1", "catalog.shared-prices.enabled=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PowerCaseCatalogImportIntegrationTests {
    @Autowired PowerCaseCatalogImportService service;
    @Autowired RetailCatalogApplyService retail;
    @Autowired AllCatalogIdentityImportService fullIdentities;
    @Autowired CatalogEntryService entries;
    @Autowired CatalogSeedLoader seeds;
    @Autowired CatalogProductRepository products;
    @Autowired CatalogProductSourceRepository sources;
    @Autowired CatalogIdentityService identities;
    @Autowired CatalogPriceImportService prices;
    @Autowired CatalogPriceMappingRepository mappings;
    @Autowired CatalogPriceObservationRepository observations;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;

    @Test void appliesOnlyExactSevenInOneTransactionPreservesAllExistingRowsAndRejectsPartialOrConflictingReplay() throws Exception {
        PowerCaseImportFixtures.copyPublicInputs(directory);
        var loader = new PowerCaseCatalogImportLoader(); var pending = loader.loadForCheck(directory);
        var plan = loader.loadApproved(directory,PowerCaseImportFixtures.writeApproval(directory,pending,true));
        assertThatThrownBy(() -> service.apply(pending)).hasMessageContaining("Exact separate database approval");
        prepareBaseline(plan.baseline());
        var first = plan.baseline().baseline().products().getFirst();
        String legacyId = sources.findBySourceNameAndExternalId(first.sourceIdentity().sourceName(),first.sourceIdentity().externalId()).orElseThrow().getProduct().getId();
        jdbc.update("INSERT INTO pc_configuration(id,name,name_normalized,version,created_at,updated_at) VALUES(1,'Power case test','power case test',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO pc_part(pc_id,type,display_name,quantity,source,catalog_product_id,match_status,specs) VALUES(1,?,?,1,'MANUAL',?,'MATCHED','{}')",
                first.identity().type().name(),"Existing installed part",legacyId);
        var preview = service.preview(pending);
        assertThat(preview.dryRun()).isTrue(); assertThat(preview.plannedProducts()).isEqualTo(7);
        assertThat(preview.createdProducts()).isZero(); assertThat(preview.before()).isEqualTo(preview.after());
        assertThat(preview.before()).containsKeys("pc_part","catalog_model","catalog_model_source","psu_spec","case_spec");
        var approvedPrice = plan.preview().prices().getLast();
        var conflict = mappings.saveAndFlush(new CatalogPriceMapping(products.findById(legacyId).orElseThrow(),approvedPrice.offer()));
        assertThatThrownBy(() -> service.apply(plan)).hasMessageContaining("already mapped");
        assertThat(products.count()).isEqualTo(308); mappings.deleteById(conflict.getId()); mappings.flush();

        var primary = plan.preview().registrations().getFirst().entry().sources().getFirst();
        var wrongSource = sources.saveAndFlush(new CatalogProductSource(products.findById(legacyId).orElseThrow(),primary));
        assertThatThrownBy(() -> service.apply(plan)).hasMessageContaining("conflicting PSU/CASE source");
        sources.deleteById(wrongSource.getId()); sources.flush();

        jdbc.execute("ALTER TABLE catalog_price_observation ADD CONSTRAINT ck_power_case_late_failure CHECK (amount_krw<>" + approvedPrice.price().amountKrw() + ")");
        assertThatThrownBy(() -> service.apply(plan)).isInstanceOf(RuntimeException.class);
        jdbc.execute("ALTER TABLE catalog_price_observation DROP CONSTRAINT ck_power_case_late_failure");
        assertThat(products.count()).isEqualTo(308);
        assertThat(sources.findBySourceNameAndExternalId(primary.sourceName(),primary.externalId())).isEmpty();
        assertThat(service.preview(pending).before()).isEqualTo(preview.before());

        var applied = service.apply(plan);
        assertThat(applied.createdProducts()).isEqualTo(7); assertThat(applied.createdSources()).isEqualTo(14);
        assertThat(applied.productBindings()).isEqualTo(7); assertThat(applied.createdPriceMappings()).isEqualTo(plan.preview().prices().size());
        assertThat(applied.createdPriceObservations()).isEqualTo(plan.preview().prices().size());
        assertThat(applied.modelWrites()).isZero(); assertThat(applied.activations()).isZero(); assertThat(products.count()).isEqualTo(315);
        var expectedRows = new HashMap<>(plan.preview().report().proposedRegistrationRows());
        expectedRows.putAll(plan.preview().report().proposedPriceRows());
        for (String table : applied.before().keySet()) {
            assertThat(applied.after().get(table).rows()-applied.before().get(table).rows()).as(table).isEqualTo(expectedRows.getOrDefault(table,0).longValue());
            if (expectedRows.getOrDefault(table,0) == 0) assertThat(applied.after().get(table)).as(table).isEqualTo(applied.before().get(table));
        }
        for (int i=0; i<7; i++) {
            var product = products.findById(applied.productIds().get(i)).orElseThrow();
            assertThat(product.getCanonicalId()).isEqualTo(plan.preview().registrations().get(i).canonicalId());
            assertThat(product.getModelId()).isNull(); assertThat(product.getIdentityKind()).isEqualTo(CatalogIdentityKind.PHYSICAL_VARIANT);
            assertThat(product.getRole()).isEqualTo(CatalogRole.PURCHASE_CANDIDATE); assertThat(product.isActive()).isFalse();
            assertThat(product.getVerificationStatus()).isEqualTo(CatalogVerificationStatus.UNVERIFIED);
        }
        var replay = service.apply(plan);
        assertThat(replay.createdProducts()).isZero(); assertThat(replay.createdPriceObservations()).isZero();
        assertThat(replay.unchangedProducts()).isEqualTo(7); assertThat(replay.before()).isEqualTo(applied.after()); assertThat(replay.before()).isEqualTo(replay.after());
        var mapping = mappings.findBySourceNameAndExternalId(approvedPrice.offer().sourceName(),approvedPrice.offer().externalId()).orElseThrow();
        String pricedId = mapping.getProduct().getId();
        jdbc.update("DELETE FROM catalog_price_observation WHERE product_id=?",pricedId);
        assertThatThrownBy(() -> service.apply(plan)).hasMessageContaining("Partial or excessive PSU/CASE price");
        observations.saveAndFlush(new CatalogPriceObservation(mapping,approvedPrice.price()));
        jdbc.update("UPDATE catalog_product SET is_active=true WHERE id=?",applied.productIds().getFirst());
        assertThatThrownBy(() -> service.apply(plan)).hasMessageContaining("differing completed PSU/CASE registration");
        assertThat(products.findById(applied.productIds().getFirst()).orElseThrow().isActive()).isTrue();
        assertThat(products.count()).isEqualTo(315);
    }
    private void prepareBaseline(RetailCatalogApplyLoader.Plan plan) {
        for (var batch : CatalogSeedBatch.values()) for (var seed : seeds.load(batch)) entries.create(seed);
        var modelIds = new HashMap<String,String>();
        for (var model : plan.pilot().models()) modelIds.put(model.canonicalId(),identities.registerModel(model).id());
        for (var part : plan.pilot().parts()) {
            var source = sources.findBySourceNameAndExternalId(part.sourceIdentity().sourceName(),part.sourceIdentity().externalId());
            String id;
            if (source.isPresent()) {
                id=source.orElseThrow().getProduct().getId(); sources.saveAndFlush(new CatalogProductSource(products.findById(id).orElseThrow(),part.manufacturerEvidence()));
            } else id=entries.create(new CatalogEntryCreateRequest(part.product(),part.specification(),List.of(part.manufacturerEvidence()))).product().id();
            var evidence=sources.findBySourceNameAndExternalId(part.manufacturerEvidence().sourceName(),part.manufacturerEvidence().externalId()).orElseThrow();
            identities.bindProduct(new CatalogIdentityRequests.ProductBinding(id,part.proposedCanonicalId(),modelIds.get(part.modelCanonicalId()),part.identityKind(),part.role(),evidence.getId(),part.bindingReviewScope()));
        }
        prices.apply(plan.baseline().prices()); fullIdentities.apply(plan.baseline()); retail.apply(plan);
    }
}
