package com.pcupgradelab.catalog;

import com.pcupgradelab.pc.PartType;

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
        ReferencePrice referencePrice
) {
    public record ReferencePrice(BigDecimal amountKrw, CatalogPriceStatus status, Instant updatedAt) { }

    static CatalogProductView from(CatalogProduct product, CatalogReferencePrice price) {
        return new CatalogProductView(product.getId(), product.getType(), product.getManufacturer(),
                product.getModelName(), product.getPartNumber(), product.getVerificationStatus(),
                product.isActive(), product.getCreatedAt(), product.getUpdatedAt(),
                new ReferencePrice(price.getAmountKrw(), price.getStatus(), price.getUpdatedAt()));
    }
}
