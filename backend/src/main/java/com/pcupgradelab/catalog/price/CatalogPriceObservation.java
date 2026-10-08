package com.pcupgradelab.catalog.price;

import com.pcupgradelab.catalog.CatalogProduct;
import com.pcupgradelab.catalog.CatalogProductView;
import jakarta.persistence.*;
import java.time.Instant;

/** 관측 원본은 추가만 한다. 동일 매핑·시각의 서로 다른 가격은 검토가 필요하다. */
@Entity
@Table(name = "catalog_price_observation")
public class CatalogPriceObservation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mapping_id", nullable = false)
    private CatalogPriceMapping mapping;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private CatalogProduct product;
    @Column(name = "amount_krw", nullable = false)
    private Long amountKrw;
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;
    @Column(name = "evidence_url", nullable = false, length = 2048)
    private String evidenceUrl;

    protected CatalogPriceObservation() { }

    public CatalogPriceObservation(CatalogPriceMapping mapping, CatalogPriceImportBatch.Price price) {
        if (mapping == null || price == null) throw new IllegalArgumentException("Mapping and price are required");
        this.mapping = mapping;
        product = mapping.getProduct();
        amountKrw = price.amountKrw();
        observedAt = price.observedAt();
        evidenceUrl = price.evidenceUrl();
    }

    public String getProductId() { return product.getId(); }
    public boolean matches(CatalogPriceImportBatch.Price price) {
        return amountKrw.equals(price.amountKrw()) && observedAt.equals(price.observedAt())
                && evidenceUrl.equals(price.evidenceUrl());
    }
    public CatalogProductView.CurrentPrice toView() {
        return new CatalogProductView.CurrentPrice(amountKrw, mapping.getSourceName(),
                mapping.getSourceUrl(), observedAt);
    }
}
