package com.pcupgradelab.pricetrend;

import com.pcupgradelab.catalog.CatalogProduct;
import com.pcupgradelab.catalog.CatalogProductRepository;
import com.pcupgradelab.common.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 화면 조회용. 읽기 전용 트랜잭션에서 등록된 관측을 읽어 매번 판정을 계산하며 DB에 쓰지 않는다. */
@Service
@Transactional(readOnly = true)
public class PriceInsightService {
    private static final String BASIS = "REGISTERED_OBSERVATIONS";
    private final CatalogProductRepository products;
    private final PriceHistoryReader reader;

    public PriceInsightService(CatalogProductRepository products, PriceHistoryReader reader) {
        this.products = products;
        this.reader = reader;
    }

    public PriceTrendDtos.HistoryResponse history(String productId) {
        var product = product(productId);
        var grouped = new LinkedHashMap<PricePoint.OfferKey, List<PricePoint>>();
        for (var point : reader.pointsFor(product.getId())) {
            grouped.computeIfAbsent(point.offerKey(), key -> new ArrayList<>()).add(point);
        }
        var series = grouped.values().stream().map(points -> {
            var last = points.getLast();
            return new PriceTrendDtos.Series(last.sourceName(), last.externalId(), last.sourceUrl(), last.saleSku(),
                    last.saleUnit(), last.moduleCount(), points.stream()
                    .map(point -> new PriceTrendDtos.Point(point.amountKrw(), point.observedAt(),
                            point.evidenceUrl(), point.origin()))
                    .toList());
        }).toList();
        return new PriceTrendDtos.HistoryResponse(product.getId(), product.getCanonicalId(), series);
    }

    public PriceTrendDtos.InsightResponse insight(String productId) {
        return insight(productId, Instant.now());
    }

    PriceTrendDtos.InsightResponse insight(String productId, Instant now) {
        var product = product(productId);
        var analysis = PriceTrendAnalyzer.analyze(reader.pointsFor(product.getId()), now);
        return new PriceTrendDtos.InsightResponse(product.getId(), product.getCanonicalId(), BASIS,
                analysis.methodVersion(), analysis.analyzedAt(), analysis.verdict(), analysis.reason(),
                analysis.stale(), analysis.lowConfidence(), analysis.windowStart(), analysis.windowEnd(),
                PriceTrendAnalyzer.MIN_COMPARABLE_DAYS, analysis.minObservedDays(),
                (int) PriceTrendAnalyzer.STALE_AFTER.toDays(), analysis.sampleCount(),
                analysis.observedDayCount(), analysis.latest(), analysis.medianKrw(), analysis.minKrw(),
                analysis.maxKrw(), analysis.changeRate7d());
    }

    private CatalogProduct product(String productId) {
        return products.findById(productId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "CATALOG_PRODUCT_NOT_FOUND", "부품을 찾을 수 없습니다."));
    }
}
