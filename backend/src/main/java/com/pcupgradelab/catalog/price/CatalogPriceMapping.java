package com.pcupgradelab.catalog.price;

import com.pcupgradelab.catalog.CatalogProduct;
import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 매핑의 판매 구성은 고정한다. 재검토로 제품을 바꾸는 암묵적 덮어쓰기는 제공하지 않는다. */
@Entity
@Table(name = "catalog_price_mapping")
public class CatalogPriceMapping {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private CatalogProduct product;
    @Column(name = "source_name", nullable = false, length = 32)
    private String sourceName;
    @Column(name = "external_id", nullable = false, length = 128)
    private String externalId;
    @Column(name = "source_url", nullable = false, length = 2048)
    private String sourceUrl;
    @Column(name = "sale_sku", nullable = false, length = 255)
    private String saleSku;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "sale_unit", nullable = false, length = 16)
    private CatalogPriceImportBatch.SaleUnit saleUnit;
    @Column(name = "module_count")
    private Integer moduleCount;
    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

    protected CatalogPriceMapping() { }

    public CatalogPriceMapping(CatalogProduct product, CatalogPriceImportBatch.Offer offer) {
        if (product == null || offer == null) throw new IllegalArgumentException("Product and offer are required");
        this.product = product;
        sourceName = offer.sourceName();
        externalId = offer.externalId();
        sourceUrl = offer.sourceUrl();
        saleSku = offer.saleSku();
        saleUnit = offer.saleUnit();
        moduleCount = offer.moduleCount();
        verifiedAt = offer.verifiedAt();
    }

    public Long getId() { return id; }
    public CatalogProduct getProduct() { return product; }
    public String getSourceName() { return sourceName; }
    public String getSourceUrl() { return sourceUrl; }
    public CatalogPriceImportBatch.Offer toOffer() {
        return new CatalogPriceImportBatch.Offer(sourceName, externalId, sourceUrl,
                saleSku, saleUnit, moduleCount, verifiedAt);
    }
}
