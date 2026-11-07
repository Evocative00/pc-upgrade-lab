package com.pcupgradelab.pricetrend;

import com.pcupgradelab.catalog.price.CatalogPriceImportBatch;
import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 가격 판단 기능이 별도로 쌓는 관측. 기존 상품은 product_id 문자열로만 참조하고 변경하지 않는다. */
@Entity
@Table(name = "price_history_observation")
public class PriceHistoryObservation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "product_id", nullable = false, length = 128)
    private String productId;
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
    @Column(name = "amount_krw", nullable = false)
    private Long amountKrw;
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;
    @Column(name = "evidence_url", nullable = false, length = 2048)
    private String evidenceUrl;
    @Column(name = "import_file", nullable = false, length = 255)
    private String importFile;
    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    protected PriceHistoryObservation() { }

    public PriceHistoryObservation(String productId, CatalogPriceImportBatch.Offer offer,
                                   CatalogPriceImportBatch.Price price, String importFile, Instant importedAt) {
        if (productId == null || offer == null || price == null || importFile == null || importedAt == null) {
            throw new IllegalArgumentException("Product, offer, price and import metadata are required");
        }
        this.productId = productId;
        sourceName = offer.sourceName();
        externalId = offer.externalId();
        sourceUrl = offer.sourceUrl();
        saleSku = offer.saleSku();
        saleUnit = offer.saleUnit();
        moduleCount = offer.moduleCount();
        amountKrw = price.amountKrw();
        observedAt = price.observedAt();
        evidenceUrl = price.evidenceUrl();
        this.importFile = importFile.length() > 255 ? importFile.substring(importFile.length() - 255) : importFile;
        this.importedAt = importedAt;
    }

    public String getProductId() { return productId; }
    public long getAmountKrw() { return amountKrw; }

    public PricePoint toPoint() {
        return new PricePoint(sourceName, externalId, sourceUrl, saleSku, saleUnit, moduleCount,
                amountKrw, observedAt, evidenceUrl, PricePoint.Origin.HISTORY);
    }
}
