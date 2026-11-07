package com.pcupgradelab.pricetrend;

import com.pcupgradelab.catalog.price.CatalogPriceDatabaseTimeZone;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관측이 있는 모든 상품을 분석한다. 미리보기는 쓰지 않고, apply만 price_analysis_result에 행을 추가한다. */
@Service
public class PriceTrendAnalysisService {
    private final PriceHistoryReader reader;
    private final PriceAnalysisResultRepository results;
    private final CatalogPriceDatabaseTimeZone databaseTimeZone;

    public PriceTrendAnalysisService(PriceHistoryReader reader, PriceAnalysisResultRepository results,
                                     CatalogPriceDatabaseTimeZone databaseTimeZone) {
        this.reader = reader;
        this.results = results;
        this.databaseTimeZone = databaseTimeZone;
    }

    public record Summary(boolean dryRun, int products, Map<PriceTrendAnalyzer.Verdict, Integer> verdicts) { }

    @Transactional(readOnly = true)
    public Summary preview(Instant now) {
        return run(now, true);
    }

    @Transactional
    public Summary apply(Instant now) {
        return databaseTimeZone.write(() -> run(now, false));
    }

    private Summary run(Instant now, boolean dryRun) {
        if (now == null) throw new IllegalArgumentException("Analysis time is required");
        var counts = new EnumMap<PriceTrendAnalyzer.Verdict, Integer>(PriceTrendAnalyzer.Verdict.class);
        for (var verdict : PriceTrendAnalyzer.Verdict.values()) counts.put(verdict, 0);
        var ids = reader.productIdsWithPrices();
        for (String productId : ids) {
            var analysis = PriceTrendAnalyzer.analyze(reader.pointsFor(productId), now);
            counts.merge(analysis.verdict(), 1, Integer::sum);
            if (!dryRun) results.save(new PriceAnalysisResult(productId, analysis));
        }
        return new Summary(dryRun, ids.size(), Map.copyOf(counts));
    }
}
