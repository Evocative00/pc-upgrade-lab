package com.pcupgradelab.catalog;

import com.pcupgradelab.pc.PartType;
import com.pcupgradelab.catalog.identity.CatalogIdentityKind;
import com.pcupgradelab.catalog.identity.CatalogRole;

import java.math.BigDecimal;
import java.time.Instant;

/** JPA 엔티티를 밖으로 노출하지 않는 내부 조회 결과. 상세 제원·공개 검색 API는 후속 단계다. */
public record CatalogProductView(
        String id,
        PartType type,
        String manufacturer,
        String modelName,
        String partNumber,
        CatalogVerificationStatus verificationStatus,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        ReferencePrice referencePrice,
        CurrentPrice currentPrice,
        String canonicalId,
        String modelId,
        CatalogIdentityKind identityKind,
        CatalogRole role,
        PriceStatus priceStatus
) {
    public record ReferencePrice(BigDecimal amountKrw, CatalogPriceStatus status, Instant updatedAt) { }

    /** 가장 최근에 기록한 국내 신품 상품가. 기준가격 산정과 별개다. */
    public record CurrentPrice(long amountKrw, String sourceName, String sourceUrl, Instant observedAt) { }

    /** 중앙 조회의 가용성·신선도와 합계에 사용할 수 있는 가격을 구분한다. */
    public record PriceStatus(String origin, String lookupStatus, String freshness, String catalogVersion,
                              Instant checkedAt, Instant lastSuccessAt, boolean includedInTotal) { }

    public CatalogProductView(String id, PartType type, String manufacturer, String modelName,
                              String partNumber, CatalogVerificationStatus verificationStatus,
                              boolean active, Instant createdAt, Instant updatedAt, ReferencePrice referencePrice,
                              CurrentPrice currentPrice, String canonicalId, String modelId,
                              CatalogIdentityKind identityKind, CatalogRole role) {
        this(id, type, manufacturer, modelName, partNumber, verificationStatus, active, createdAt, updatedAt,
                referencePrice, currentPrice, canonicalId, modelId, identityKind, role, null);
    }

    public CatalogProductView withPrice(CurrentPrice price, PriceStatus status) {
        return new CatalogProductView(id, type, manufacturer, modelName, partNumber, verificationStatus,
                active, createdAt, updatedAt, referencePrice, price, canonicalId, modelId, identityKind, role, status);
    }

    public CatalogProductView(String id, PartType type, String manufacturer, String modelName,
                              String partNumber, CatalogVerificationStatus verificationStatus,
                              boolean active, Instant createdAt, Instant updatedAt, ReferencePrice referencePrice) {
        this(id, type, manufacturer, modelName, partNumber, verificationStatus, active,
                createdAt, updatedAt, referencePrice, null);
    }

    public CatalogProductView(String id, PartType type, String manufacturer, String modelName,
                              String partNumber, CatalogVerificationStatus verificationStatus,
                              boolean active, Instant createdAt, Instant updatedAt, ReferencePrice referencePrice,
                              CurrentPrice currentPrice) {
        this(id, type, manufacturer, modelName, partNumber, verificationStatus, active,
                createdAt, updatedAt, referencePrice, currentPrice, null, null,
                CatalogIdentityKind.LEGACY_UNCLASSIFIED, CatalogRole.UNASSIGNED);
    }

    static CatalogProductView from(CatalogProduct product, CatalogReferencePrice price) {
        return from(product, price, null);
    }

    static CatalogProductView from(CatalogProduct product, CatalogReferencePrice price, CurrentPrice currentPrice) {
        return new CatalogProductView(product.getId(), product.getType(), product.getManufacturer(),
                product.getModelName(), product.getPartNumber(), product.getVerificationStatus(),
                product.isActive(), product.getCreatedAt(), product.getUpdatedAt(),
                new ReferencePrice(price.getAmountKrw(), price.getStatus(), price.getUpdatedAt()), currentPrice,
                product.getCanonicalId(), product.getModelId(), product.getIdentityKind(), product.getRole());
    }
}
