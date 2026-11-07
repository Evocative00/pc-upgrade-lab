package com.pcupgradelab.pricetrend;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 명시적 분석 작업이 남기는 판정 스냅샷. 화면 API는 이 값을 읽지 않고 매번 새로 계산한다. */
@Entity
@Table(name = "price_analysis_result")
public class PriceAnalysisResult {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "product_id", nullable = false, length = 128)
    private String productId;
    @Column(name = "analyzed_at", nullable = false)
    private Instant analyzedAt;
    @Column(name = "method_version", nullable = false, length = 32)
    private String methodVersion;
    @Column(name = "window_start", nullable = false)
    private Instant windowStart;
    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;
    @Column(name = "sample_count", nullable = false)
    private Integer sampleCount;
    @Column(name = "observed_day_count", nullable = false)
    private Integer observedDayCount;
    @Column(name = "latest_amount_krw")
    private Long latestAmountKrw;
    @Column(name = "latest_observed_at")
    private Instant latestObservedAt;
    @Column(name = "median_krw")
    private Long medianKrw;
    @Column(name = "min_krw")
    private Long minKrw;
    @Column(name = "max_krw")
    private Long maxKrw;
    @Column(name = "change_rate_7d", precision = 9, scale = 4)
    private BigDecimal changeRate7d;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "verdict", nullable = false, length = 24)
    private PriceTrendAnalyzer.Verdict verdict;
    @Column(name = "reason_code", nullable = false, length = 32)
    private String reasonCode;

    protected PriceAnalysisResult() { }

    public PriceAnalysisResult(String productId, PriceTrendAnalyzer.Analysis analysis) {
        if (productId == null || analysis == null) throw new IllegalArgumentException("Product and analysis are required");
        this.productId = productId;
        analyzedAt = analysis.analyzedAt();
        methodVersion = analysis.methodVersion();
        windowStart = analysis.windowStart();
        windowEnd = analysis.windowEnd();
        sampleCount = analysis.sampleCount();
        observedDayCount = analysis.observedDayCount();
        if (analysis.latest() != null) {
            latestAmountKrw = analysis.latest().amountKrw();
            latestObservedAt = analysis.latest().observedAt();
        }
        medianKrw = analysis.medianKrw();
        minKrw = analysis.minKrw();
        maxKrw = analysis.maxKrw();
        changeRate7d = analysis.changeRate7d();
        verdict = analysis.verdict();
        reasonCode = analysis.reason().name();
    }

    public String getProductId() { return productId; }
    public PriceTrendAnalyzer.Verdict getVerdict() { return verdict; }
}
