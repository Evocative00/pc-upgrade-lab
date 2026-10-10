package com.pcupgradelab.catalog.identity;

import com.pcupgradelab.catalog.CatalogProductSourceRepository;
import com.pcupgradelab.catalog.RamSpecRepository;
import com.pcupgradelab.pc.PartType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 승인 입력을 원자적으로 등록한다. 서버 시작·조회·스캔 중에 자동 호출하지 않는다. */
@Service
@Transactional(readOnly = true)
public class CatalogIdentityService {
    private final CatalogModelRepository models;
    private final CatalogModelSourceRepository sources;
    private final CatalogModelAliasRepository aliases;
    private final CatalogIdentityProductRepository products;
    private final CatalogProductSourceRepository productSources;
    private final RamSpecRepository ram;
    private final EntityManager entityManager;

    public CatalogIdentityService(CatalogModelRepository models, CatalogModelSourceRepository sources,
                                  CatalogModelAliasRepository aliases, CatalogIdentityProductRepository products,
                                  CatalogProductSourceRepository productSources, RamSpecRepository ram, EntityManager entityManager) {
        this.models = models; this.sources = sources; this.aliases = aliases; this.products = products;
        this.productSources = productSources; this.ram = ram;
        this.entityManager = entityManager;
    }

    @Transactional
    public CatalogIdentityDtos.Model registerModel(CatalogIdentityRequests.ModelRegistration input) {
        if (input == null) throw new IllegalArgumentException("model registration is required");
        var existing = models.findByCanonicalId(input.canonicalId());
        if (existing.isPresent()) {
            var model = existing.orElseThrow();
            if (!model.matches(input)) throw new IllegalArgumentException("Existing model differs; no implicit overwrite");
            assertExistingSourcesAndAliases(model, input);
            return CatalogIdentityDtos.Model.from(model);
        }
        // 외부 원본을 다른 모델에 다시 연결하지 않는다. 최종 동시성 검사는 DB UNIQUE가 맡는다.
        for (var source : input.sources()) {
            var identity = source.source();
            if (identity.externalId() != null && sources.findBySourceNameAndExternalId(identity.sourceName(), identity.externalId()).isPresent())
                throw new IllegalArgumentException("Model source identity is already registered");
        }
        var model = models.saveAndFlush(new CatalogModel(input));
        var savedSources = new ArrayList<CatalogModelSource>();
        for (var source : input.sources()) savedSources.add(sources.saveAndFlush(new CatalogModelSource(model, source)));
        for (var alias : input.aliases())
            aliases.saveAndFlush(new CatalogModelAlias(model, alias.rawAlias(), savedSources.get(alias.sourceIndex())));
        return CatalogIdentityDtos.Model.from(model);
    }

    /** A database preview checks the complete registration without invoking any creation path. */
    public CatalogIdentityDtos.Model verifyModel(CatalogIdentityRequests.ModelRegistration input) {
        if (input == null) throw new IllegalArgumentException("model registration is required");
        var model = models.findByCanonicalId(input.canonicalId()).orElseThrow(() -> new IllegalArgumentException("Unknown model"));
        if (!model.matches(input)) throw new IllegalArgumentException("Existing model differs; no implicit overwrite");
        assertExistingSourcesAndAliases(model, input);
        return CatalogIdentityDtos.Model.from(model);
    }

    @Transactional
    public CatalogIdentityDtos.ProductCandidate bindProduct(CatalogIdentityRequests.ProductBinding input) {
        if (input == null) throw new IllegalArgumentException("product binding is required");
        var product = products.findForIdentityBinding(input.productId()).orElseThrow(() -> new IllegalArgumentException("Unknown product"));
        // 상위 트랜잭션이 이미 읽은 엔티티도 잠금 후 최신 DB 상태로 검증한다. 다른 엔티티의 캐시는 비우지 않는다.
        entityManager.refresh(product, LockModeType.PESSIMISTIC_WRITE);
        var sameCanonical = products.findByCanonicalId(input.canonicalId());
        if (sameCanonical.isPresent() && !sameCanonical.orElseThrow().getId().equals(product.getId()))
            throw new IllegalArgumentException("Canonical ID is already bound to another product");
        var source = productSources.findById(input.evidenceSourceId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown product identity evidence"));
        if (!source.getProduct().getId().equals(product.getId()))
            throw new IllegalArgumentException("Identity evidence belongs to another product");
        if (input.modelId() != null) {
            var model = models.findById(input.modelId()).orElseThrow(() -> new IllegalArgumentException("Unknown model"));
            if (model.getType() != product.getType()) throw new IllegalArgumentException("Product and model types differ");
        }
        if (input.identityKind() == CatalogIdentityKind.RETAIL_KIT) {
            if (product.getType() != PartType.RAM) throw new IllegalArgumentException("Only RAM can be a retail kit");
            var spec = ram.findById(product.getId()).orElseThrow(() -> new IllegalArgumentException("RAM kit specification is required"))
                    .toSpecification();
            if (spec.moduleCount() == null || spec.moduleCount() < 2)
                throw new IllegalArgumentException("RETAIL_KIT requires at least two documented modules");
        }
        product.bindReviewedIdentity(input);
        products.flush();
        return CatalogIdentityDtos.ProductCandidate.from(product);
    }

    private void assertExistingSourcesAndAliases(CatalogModel model, CatalogIdentityRequests.ModelRegistration input) {
        var actualSources = sources.findAllByModel_IdOrderByIdAsc(model.getId());
        var actualAliases = aliases.findAllByModel_IdOrderByIdAsc(model.getId());
        if (actualSources.size() != input.sources().size() || actualAliases.size() != input.aliases().size())
            throw new IllegalArgumentException("Existing model evidence differs");
        var sourceMatches = new ArrayList<CatalogModelSource>();
        for (var expected : input.sources()) sourceMatches.add(actualSources.stream().filter(source -> source.matches(expected))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Existing model source differs")));
        for (var expected : input.aliases()) {
            Long evidenceId = sourceMatches.get(expected.sourceIndex()).getId();
            if (actualAliases.stream().noneMatch(alias -> alias.getRawAlias().equals(expected.rawAlias())
                    && alias.getNormalizedAlias().equals(IdentityValues.alias(expected.rawAlias()))
                    && alias.getSource().getId().equals(evidenceId)))
                throw new IllegalArgumentException("Existing model alias differs");
        }
    }
}
