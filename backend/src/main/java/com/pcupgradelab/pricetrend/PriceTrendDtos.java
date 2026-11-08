package com.pcupgradelab.pricetrend;

import com.pcupgradelab.catalog.price.CatalogPriceImportBatch;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 가격 판단 API 응답. 기존 currentPrice·priceStatus·referenceEstimate와 별개의 DTO다. */
public final class PriceTrendDtos {
    private PriceTrendDtos() { }

    public record HistoryResponse(String productId, String canonicalId, List<Series> series) { }

    public record Series(String sourceName, String externalId, String sourceUrl, String saleSku,
                         CatalogPriceImportBatch.SaleUnit saleUnit, Integer moduleCount, List<Point> points) { }

    public record Point(long amountKrw, Instant observedAt, String evidenceUrl, PricePoint.Origin origin) { }

    /**
     * basis는 항상 REGISTERED_OBSERVATIONS다. 참고가나 추정가로 계산하지 않았다는 표시다.
     * stale은 마지막 관측이 staleAfterDays 이상 지난 경우, lowConfidence는 판정했지만 관측일이
     * minObservedDays 미만인 경우다. 판정은 관측일이 minComparableDays 이상일 때만 낸다.
     */
    public record InsightResponse(String productId, String canonicalId, String basis, String methodVersion,
                                  Instant analyzedAt, PriceTrendAnalyzer.Verdict verdict,
                                  PriceTrendAnalyzer.Reason reason, boolean stale, boolean lowConfidence,
                                  Instant windowStart, Instant windowEnd,
                                  int minComparableDays, int minObservedDays, int staleAfterDays,
                                  int sampleCount, int observedDayCount,
                                  PriceTrendAnalyzer.Latest latest, Long medianKrw, Long minKrw, Long maxKrw,
                                  BigDecimal changeRate7d) { }
}
