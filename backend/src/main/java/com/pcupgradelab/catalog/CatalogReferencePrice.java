package com.pcupgradelab.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 공용 제품 한 개의 현재 기준가격. 이번 단계에서는 가격 미확인 행만 생성한다. */
@Entity
@Table(name = "catalog_reference_price")
public class CatalogReferencePrice {
    @Id
    @Column(name = "product_id", nullable = false, length = 128, updatable = false)
    private String productId;

    // 제품 ID를 이 행의 PK이자 FK로 공유한다. 별도 가격 ID를 생성하지 않는다.
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private CatalogProduct product;

    @Column(name = "amount_krw", precision = 14, scale = 2)
    private BigDecimal amountKrw;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 24)
    private CatalogPriceStatus status = CatalogPriceStatus.UNCONFIRMED;

    @Column(length = 32)
    private String method;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "observed_day_count")
    private Integer observedDayCount;

    @Column(name = "sample_count")
    private Integer sampleCount;

    @Column(name = "price_basis", length = 500)
    private String priceBasis;

    @Column(name = "evidence_ref", length = 2048)
    private String evidenceRef;

    @Column(name = "calculated_at")
    private Instant calculatedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CatalogReferencePrice() { }

    private CatalogReferencePrice(CatalogProduct product) {
        if (product == null) {
            throw new IllegalArgumentException("product is required");
        }
        this.product = product;
        // productId는 여기서 복사하지 않는다. 새 행임을 유지하고 저장 시 @MapsId가 적용한다.
        // 금액·산정 근거·확정 시간도 NULL로 두어 미확인 가격을 0원으로 오해하지 않게 한다.
    }

    public static CatalogReferencePrice unconfirmed(CatalogProduct product) {
        return new CatalogReferencePrice(product);
    }

    @PrePersist
    void onCreate() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public String getProductId() { return productId; }
    public CatalogProduct getProduct() { return product; }
    public BigDecimal getAmountKrw() { return amountKrw; }
    public CatalogPriceStatus getStatus() { return status; }
    public String getMethod() { return method; }
    public LocalDate getPeriodStart() { return periodStart; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public Integer getObservedDayCount() { return observedDayCount; }
    public Integer getSampleCount() { return sampleCount; }
    public String getPriceBasis() { return priceBasis; }
    public String getEvidenceRef() { return evidenceRef; }
    public Instant getCalculatedAt() { return calculatedAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
