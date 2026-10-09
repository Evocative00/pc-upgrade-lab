package com.pcupgradelab.catalog.identity;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.catalog.price.*;
import com.pcupgradelab.pc.PartType;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:catalog-identity-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class CatalogIdentityTests {
    private static final Instant CHECKED = Instant.parse("2026-10-09T01:00:00Z");
    @Autowired CatalogIdentityService identity;
    @Autowired CatalogModelQueryService query;
    @Autowired CatalogProductService products;
    @Autowired CatalogProductRepository productRepository;
    @Autowired CatalogProductSourceRepository productSources;
    @Autowired CatalogPriceMappingRepository mappings;
    @Autowired CatalogPriceObservationRepository observations;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    @Test
    void reviewedSourceIdsAreStableAndDoNotContainLocalOrTypeIds() {
        String external = "5f6af3e1-5261-42c3-8c08-f40841cf9cb9";
        String expected = UUID.nameUUIDFromBytes(("pc-upgrade-lab/catalog/product/v1\0BUILDCORES\0" + external)
                .getBytes(StandardCharsets.UTF_8)).toString();
        assertThat(CanonicalCatalogIds.productForReviewedSource(CatalogSourceName.BUILDCORES, external)).isEqualTo(expected);
        assertThat(CanonicalCatalogIds.productForReviewedSource(CatalogSourceName.BUILDCORES, external.toUpperCase())).isEqualTo(expected);
        assertThat(CanonicalCatalogIds.productForReviewedSource(CatalogSourceName.MANUAL, external)).isNotEqualTo(expected);
        assertThatThrownBy(() -> CanonicalCatalogIds.productForReviewedSource(CatalogSourceName.BUILDCORES, "invented-product"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modelEvidenceAndAliasesRoundTripIdempotentlyWithoutChangingVerificationOrProducts() {
        var input = registration(UUID.randomUUID().toString(), "Core Ultra 5 245K", "fixture-model-245k");
        var first = identity.registerModel(input);
        entityManager.flush(); entityManager.clear();
        var second = identity.registerModel(input);
        assertThat(second).isEqualTo(first);
        assertThat(first.verificationStatus()).isEqualTo(CatalogVerificationStatus.UNVERIFIED);
        assertThat(count("catalog_model")).isEqualTo(1);
        assertThat(count("catalog_model_source")).isEqualTo(1);
        assertThat(count("catalog_model_alias")).isEqualTo(1);
        assertThat(count("catalog_product")).isZero();
        assertThat(count("catalog_price_observation")).isZero();
        var detail = query.findById(first.id());
        assertThat(detail.productCandidates()).isEmpty();
        assertThat(detail.aliases().getFirst().rawAlias()).isEqualTo("Intel(R) Core Ultra 5 245K");
        assertThat(detail.aliases().getFirst().normalizedAlias()).endsWith("245k");
        assertThat(query.search(PartType.CPU, "Intel(R) Core Ultra 5", 0, 20).items()).extracting(CatalogIdentityDtos.Model::id)
                .containsExactly(first.id());
        assertThat(query.search(PartType.RAM, "", 0, 20).items()).isEmpty();
    }

    @Test
    void existingModelAndExternalEvidenceAreNotOverwrittenOrFuzzilyMerged() {
        var input = registration(UUID.randomUUID().toString(), "Core Ultra 5 245K", "fixture-model-245k");
        var first = identity.registerModel(input);
        var changed = registration(input.canonicalId(), "Core Ultra 5 245KF", "fixture-model-245k");
        assertThatThrownBy(() -> identity.registerModel(changed)).isInstanceOf(IllegalArgumentException.class);
        var reusedSource = registration(UUID.randomUUID().toString(), "Different model", "fixture-model-245k");
        assertThatThrownBy(() -> identity.registerModel(reusedSource)).isInstanceOf(IllegalArgumentException.class);
        assertThat(query.findById(first.id()).model().modelName()).isEqualTo("Core Ultra 5 245K");
        assertThat(count("catalog_model")).isEqualTo(1);
    }

    @Test
    void explicitBindingPreservesProductUuidStoredPriceAndHistoricalObservation() {
        var before = products.create(new CatalogProductCreateRequest(PartType.CPU, "Fixture", "Reviewed CPU", "BOX-FIXTURE"));
        assertThat(before.canonicalId()).isNull();
        assertThat(before.modelId()).isNull();
        assertThat(before.identityKind()).isEqualTo(CatalogIdentityKind.LEGACY_UNCLASSIFIED);
        assertThat(before.role()).isEqualTo(CatalogRole.UNASSIGNED);
        var product = productRepository.findById(before.id()).orElseThrow();
        var source = productSources.saveAndFlush(new CatalogProductSource(product, source("fixture-product")));
        var mapping = mappings.saveAndFlush(new CatalogPriceMapping(product, new CatalogPriceImportBatch.Offer(
                "DANAWA", "fixture-price", "https://example.com/product", "BOX-FIXTURE", CatalogPriceImportBatch.SaleUnit.PRODUCT, null, CHECKED)));
        observations.saveAndFlush(new CatalogPriceObservation(mapping, new CatalogPriceImportBatch.Price(
                123456L, CHECKED, "https://example.com/offer", "NEW", true)));
        var model = identity.registerModel(registration(UUID.randomUUID().toString(), "Reviewed CPU", "fixture-cpu-model"));
        String canonicalId = CanonicalCatalogIds.productForReviewedSource(CatalogSourceName.MANUFACTURER, "fixture-product");
        var input = new CatalogIdentityRequests.ProductBinding(before.id(), canonicalId, model.id(),
                CatalogIdentityKind.PHYSICAL_VARIANT, CatalogRole.BOTH, source.getId(), "Fixture identity fields, not complete compatibility");
        identity.bindProduct(input);
        entityManager.flush(); entityManager.clear();
        var after = products.findById(before.id()).orElseThrow();
        identity.bindProduct(input);
        assertThat(after.id()).isEqualTo(before.id()).isNotEqualTo(canonicalId);
        assertThat(after.canonicalId()).isEqualTo(canonicalId);
        assertThat(after.modelId()).isEqualTo(model.id());
        assertThat(after.identityKind()).isEqualTo(CatalogIdentityKind.PHYSICAL_VARIANT);
        assertThat(after.verificationStatus()).isEqualTo(before.verificationStatus());
        assertThat(after.active()).isFalse();
        assertThat(after.referencePrice()).isEqualTo(before.referencePrice());
        assertThat(after.currentPrice().amountKrw()).isEqualTo(123456);
        assertThat(after.currentPrice().observedAt()).isEqualTo(CHECKED);
        assertThat(count("catalog_price_observation")).isEqualTo(1);
        assertThat(query.findById(model.id()).productCandidates()).extracting(CatalogIdentityDtos.ProductCandidate::id).containsExactly(before.id());
    }

    @Test
    void productBindingsRequireScopedEvidenceTypeAgreementAndUniqueCanonicalIdentity() {
        var cpu = products.create(new CatalogProductCreateRequest(PartType.CPU, "Fixture", "CPU", null));
        var gpu = products.create(new CatalogProductCreateRequest(PartType.GPU, "Fixture", "Card", null));
        var cpuSource = productSources.saveAndFlush(new CatalogProductSource(productRepository.findById(cpu.id()).orElseThrow(), source("fixture-cpu")));
        var gpuSource = productSources.saveAndFlush(new CatalogProductSource(productRepository.findById(gpu.id()).orElseThrow(), source("fixture-card")));
        var model = identity.registerModel(registration(UUID.randomUUID().toString(), "CPU", "fixture-cpu-model"));
        String canonical = UUID.randomUUID().toString();
        assertThatThrownBy(() -> identity.bindProduct(binding(cpu.id(), canonical, model.id(), gpuSource.getId())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another product");
        assertThatThrownBy(() -> identity.bindProduct(binding(gpu.id(), canonical, model.id(), gpuSource.getId())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("types differ");
        identity.bindProduct(binding(cpu.id(), canonical, model.id(), cpuSource.getId()));
        assertThatThrownBy(() -> identity.bindProduct(binding(gpu.id(), canonical, null, gpuSource.getId())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another product");
        assertThat(products.findById(gpu.id()).orElseThrow().canonicalId()).isNull();
    }

    @Test
    void databaseConstraintsAlsoRejectCrossProductEvidenceAndCrossTypeModelReferences() {
        var cpu = products.create(new CatalogProductCreateRequest(PartType.CPU, "Fixture", "CPU", null));
        var gpu = products.create(new CatalogProductCreateRequest(PartType.GPU, "Fixture", "Card", null));
        var gpuSource = productSources.saveAndFlush(new CatalogProductSource(productRepository.findById(gpu.id()).orElseThrow(), source("schema-card")));
        var model = identity.registerModel(registration(UUID.randomUUID().toString(), "CPU", "schema-cpu-model"));
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE catalog_product SET canonical_id = ?, identity_evidence_source_id = ?,
                    identity_review_scope = 'Fixture identity proof', identity_reviewed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, UUID.randomUUID().toString(), gpuSource.getId(), cpu.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE catalog_product SET canonical_id = ?, model_id = ?, identity_kind = 'MODEL_REFERENCE',
                    identity_evidence_source_id = ?, identity_review_scope = 'Fixture identity proof', identity_reviewed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, UUID.randomUUID().toString(), model.id(), gpuSource.getId(), gpu.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private CatalogIdentityRequests.ProductBinding binding(String product, String canonical, String model, long sourceId) {
        return new CatalogIdentityRequests.ProductBinding(product, canonical, model, CatalogIdentityKind.PHYSICAL_VARIANT,
                CatalogRole.INSTALLED_PC_REFERENCE, sourceId, "Fixture product identity review");
    }

    private CatalogIdentityRequests.ModelRegistration registration(String canonical, String name, String external) {
        return new CatalogIdentityRequests.ModelRegistration(canonical, PartType.CPU, "Fixture", name,
                CatalogModelKind.CPU_MODEL, CatalogRole.INSTALLED_PC_REFERENCE, "Core Ultra", "200S",
                List.of(new CatalogIdentityRequests.ModelSource(source(external), "CPU model and desktop family only")),
                List.of(new CatalogIdentityRequests.ModelAlias("Intel(R) Core Ultra 5 245K", 0)));
    }

    private CatalogSourceInput source(String external) {
        return new CatalogSourceInput(CatalogSourceName.MANUFACTURER, external, null, "https://example.com/spec/" + external,
                Map.of("record_kind", "TEST_IDENTITY_EXTRACT", "knownCount", 1L), CHECKED);
    }

    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
}
