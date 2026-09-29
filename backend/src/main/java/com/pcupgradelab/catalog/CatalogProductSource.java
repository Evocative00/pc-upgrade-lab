package com.pcupgradelab.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 외부 원본별 최신 가져오기 상태. 등록만으로 제품의 제원 검증 상태를 변경하지 않는다. */
@Entity
@Table(name = "catalog_product_source")
public class CatalogProductSource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private CatalogProduct product;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source_name", nullable = false, length = 32)
    private CatalogSourceName sourceName;

    @Column(name = "external_id", length = 128)
    private String externalId;

    @Column(name = "source_revision", length = 64)
    private String sourceRevision;

    @Column(name = "source_url", nullable = false, length = 2048)
    private String sourceUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "json")
    private Map<String, Object> rawPayload;

    @Column(name = "retrieved_at", nullable = false)
    private Instant retrievedAt;

    protected CatalogProductSource() { }

    public CatalogProductSource(CatalogProduct product, CatalogSourceInput input) {
        if (product == null || input == null) {
            throw new IllegalArgumentException("product and source input are required");
        }
        this.product = product;
        sourceName = input.sourceName();
        externalId = input.externalId();
        sourceRevision = input.sourceRevision();
        sourceUrl = input.sourceUrl();
        rawPayload = CatalogJsonValues.immutableObject(input.rawPayload());
        retrievedAt = input.retrievedAt();
    }

    public CatalogSourceView toView() {
        return new CatalogSourceView(id, sourceName, externalId, sourceRevision,
                sourceUrl, rawPayload, retrievedAt);
    }

    public Long getId() { return id; }
    public CatalogProduct getProduct() { return product; }
    public CatalogSourceName getSourceName() { return sourceName; }
    public String getExternalId() { return externalId; }
    public String getSourceRevision() { return sourceRevision; }
    public String getSourceUrl() { return sourceUrl; }
    public Map<String, Object> getRawPayload() { return CatalogJsonValues.immutableObject(rawPayload); }
    public Instant getRetrievedAt() { return retrievedAt; }
}
