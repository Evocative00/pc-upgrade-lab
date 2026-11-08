package com.pcupgradelab.pricetrend;

import com.pcupgradelab.catalog.price.CatalogPriceImportBatch;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** DB 없이 판정 규칙만 검사한다. */
class PriceTrendAnalyzerTests {
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");

    @Test
    void noObservationIsInsufficient() {
        var analysis = PriceTrendAnalyzer.analyze(List.of(), NOW);
        assertThat(analysis.verdict()).isEqualTo(PriceTrendAnalyzer.Verdict.INSUFFICIENT_DATA);
        assertThat(analysis.reason()).isEqualTo(PriceTrendAnalyzer.Reason.NO_OBSERVATION);
        assertThat(analysis.latest()).isNull();
        assertThat(analysis.medianKrw()).isNull();
    }

    @Test
    void staleDataStillGetsAVerdictWithAStaleFlagAndWindowAnchoredToTheLatestObservation() {
        var latestAt = NOW.minus(Duration.ofDays(8));
        var points = new ArrayList<>(daily("A", 30, latestAt.minus(Duration.ofDays(1)), 100_000));
        points.add(point("A", 110_000, latestAt.toString()));
        var analysis = PriceTrendAnalyzer.analyze(points, NOW);
        assertThat(analysis.verdict()).isEqualTo(PriceTrendAnalyzer.Verdict.WAIT);
        assertThat(analysis.stale()).isTrue();
        assertThat(analysis.lowConfidence()).isFalse();
        assertThat(analysis.observedDayCount()).isEqualTo(31);
        assertThat(analysis.windowEnd()).isEqualTo(latestAt);
        assertThat(analysis.windowStart()).isEqualTo(latestAt.minus(PriceTrendAnalyzer.WINDOW));
        assertThat(analysis.analyzedAt()).isEqualTo(NOW);
    }

    @Test
    void staleFlagStartsExactlyAtSevenDays() {
        var almost = daily("A", 20, NOW.minus(Duration.ofDays(7)).plusSeconds(1), 100_000);
        assertThat(PriceTrendAnalyzer.analyze(almost, NOW).stale()).isFalse();
        var seven = daily("A", 20, NOW.minus(Duration.ofDays(7)), 100_000);
        assertThat(PriceTrendAnalyzer.analyze(seven, NOW).stale()).isTrue();
    }

    @Test
    void currentRepositoryLikeDataWithThreeDaysGetsALowConfidenceVerdict() {
        var points = List.of(
                point("A", 399_000, "2026-10-08T10:26:57.700Z"),
                point("A", 399_000, "2026-10-09T16:28:00.260Z"),
                point("A", 399_000, "2026-10-10T08:10:24.509Z"));
        var recent = PriceTrendAnalyzer.analyze(points, Instant.parse("2026-10-11T00:00:00Z"));
        assertThat(recent.verdict()).isEqualTo(PriceTrendAnalyzer.Verdict.FAIR);
        assertThat(recent.lowConfidence()).isTrue();
        assertThat(recent.stale()).isFalse();
        assertThat(recent.observedDayCount()).isEqualTo(3);
        var now = PriceTrendAnalyzer.analyze(points, NOW);
        assertThat(now.verdict()).isEqualTo(PriceTrendAnalyzer.Verdict.FAIR);
        assertThat(now.stale()).isTrue();
        assertThat(now.lowConfidence()).isTrue();
    }

    @Test
    void oneObservedDayIsInsufficientAndTwoDaysIsEnoughToCompare() {
        var latestAt = NOW.minus(Duration.ofHours(1));
        var oneDay = PriceTrendAnalyzer.analyze(List.of(point("A", 100_000, latestAt.minus(Duration.ofHours(2)).toString()),
                point("A", 90_000, latestAt.toString())), NOW);
        assertThat(oneDay.verdict()).isEqualTo(PriceTrendAnalyzer.Verdict.INSUFFICIENT_DATA);
        assertThat(oneDay.reason()).isEqualTo(PriceTrendAnalyzer.Reason.TOO_FEW_DAYS);
        assertThat(oneDay.lowConfidence()).isFalse();
        assertThat(oneDay.latest().amountKrw()).isEqualTo(90_000);

        var twoDays = PriceTrendAnalyzer.analyze(List.of(point("A", 100_000, latestAt.minus(Duration.ofDays(1)).toString()),
                point("A", 100_000, latestAt.toString())), NOW);
        assertThat(twoDays.verdict()).isEqualTo(PriceTrendAnalyzer.Verdict.FAIR);
        assertThat(twoDays.lowConfidence()).isTrue();
    }

    @Test
    void lowConfidenceFlagClearsAtFourteenDays() {
        var latestAt = NOW.minus(Duration.ofHours(1));
        var thirteen = PriceTrendAnalyzer.analyze(daily("A", 13, latestAt, 100_000), NOW);
        assertThat(thirteen.verdict()).isEqualTo(PriceTrendAnalyzer.Verdict.FAIR);
        assertThat(thirteen.lowConfidence()).isTrue();
        // 변동 없는 시계열은 최저가와 같아도 좋은 가격이 아니라 평균으로 본다.
        var enough = PriceTrendAnalyzer.analyze(daily("A", 14, latestAt, 100_000), NOW);
        assertThat(enough.verdict()).isEqualTo(PriceTrendAnalyzer.Verdict.FAIR);
        assertThat(enough.reason()).isEqualTo(PriceTrendAnalyzer.Reason.WITHIN_RANGE);
        assertThat(enough.lowConfidence()).isFalse();
    }

    @Test
    void verdictBoundariesUseMedianAndPeriodLow() {
        assertThat(withLatest(97_000).reason()).isEqualTo(PriceTrendAnalyzer.Reason.BELOW_MEDIAN);
        assertThat(withLatest(97_001).verdict()).isEqualTo(PriceTrendAnalyzer.Verdict.FAIR);
        assertThat(withLatest(105_000).verdict()).isEqualTo(PriceTrendAnalyzer.Verdict.WAIT);
        assertThat(withLatest(104_999).verdict()).isEqualTo(PriceTrendAnalyzer.Verdict.FAIR);

        // 기간 최저가 98,000원 대비 102% 이내면 중앙값보다 조금 낮지 않아도 좋은 가격이다.
        var points = new ArrayList<>(daily("A", 20, NOW.minus(Duration.ofDays(2)), 100_000));
        points.add(point("A", 98_000, NOW.minus(Duration.ofDays(25)).toString()));
        points.add(point("A", 99_900, NOW.minus(Duration.ofHours(1)).toString()));
        var low = PriceTrendAnalyzer.analyze(points, NOW);
        assertThat(low.minKrw()).isEqualTo(98_000);
        assertThat(low.reason()).isEqualTo(PriceTrendAnalyzer.Reason.NEAR_PERIOD_LOW);
    }

    @Test
    void otherSaleConfigurationsAreNotMixedAndDuplicatesCountOnce() {
        var points = new ArrayList<>(daily("A", 20, NOW.minus(Duration.ofHours(2)), 100_000));
        // 다른 판매처 상품(B)은 훨씬 싸지만 최근 관측된 A 시계열과 섞지 않는다.
        points.addAll(daily("B", 20, NOW.minus(Duration.ofDays(3)), 50_000));
        // 같은 관측이 두 테이블에 있으면 한 건으로 본다.
        var duplicate = points.getLast();
        points.add(new PricePoint(duplicate.sourceName(), duplicate.externalId(), duplicate.sourceUrl(),
                duplicate.saleSku(), duplicate.saleUnit(), duplicate.moduleCount(), duplicate.amountKrw(),
                duplicate.observedAt(), duplicate.evidenceUrl(), PricePoint.Origin.HISTORY));
        var analysis = PriceTrendAnalyzer.analyze(points, NOW);
        assertThat(analysis.latest().externalId()).isEqualTo("A");
        assertThat(analysis.minKrw()).isEqualTo(100_000);
        assertThat(analysis.sampleCount()).isEqualTo(20);
    }

    @Test
    void differentSkuUnderTheSameExternalIdCannotChangeTheOlderSkuSeries() {
        var points = new ArrayList<>(daily("A", 14, NOW.minus(Duration.ofHours(2)), 100_000));
        var last = points.getLast();
        points.add(new PricePoint(last.sourceName(), last.externalId(), last.sourceUrl(), "Different SKU",
                last.saleUnit(), last.moduleCount(), 50_000, NOW.minus(Duration.ofHours(1)), last.evidenceUrl(),
                PricePoint.Origin.HISTORY));

        var analysis = PriceTrendAnalyzer.analyze(points, NOW);

        assertThat(analysis.latest().saleSku()).isEqualTo("Different SKU");
        assertThat(analysis.verdict()).isEqualTo(PriceTrendAnalyzer.Verdict.INSUFFICIENT_DATA);
        assertThat(analysis.sampleCount()).isEqualTo(1);
    }

    @Test
    void multipleObservationsPerDayUseTheLastAndChangeRateComparesSevenDaysEarlier() {
        var points = new ArrayList<>(daily("A", 20, NOW.minus(Duration.ofHours(1)).minus(Duration.ofDays(1)), 100_000));
        points.add(point("A", 90_000, NOW.minus(Duration.ofHours(3)).toString()));
        points.add(point("A", 110_000, NOW.minus(Duration.ofHours(1)).toString()));
        var analysis = PriceTrendAnalyzer.analyze(points, NOW);
        assertThat(analysis.sampleCount()).isEqualTo(22);
        assertThat(analysis.observedDayCount()).isEqualTo(21);
        assertThat(analysis.latest().amountKrw()).isEqualTo(110_000);
        assertThat(analysis.changeRate7d()).isEqualByComparingTo(new BigDecimal("0.1000"));
        assertThat(analysis.verdict()).isEqualTo(PriceTrendAnalyzer.Verdict.WAIT);
    }

    @Test
    void observationsOutsideTheWindowOrInTheFutureAreIgnored() {
        var points = new ArrayList<>(daily("A", 14, NOW.minus(Duration.ofHours(1)), 100_000));
        points.add(point("A", 10_000, NOW.minus(Duration.ofDays(120)).toString()));
        points.add(point("A", 10_000, NOW.plus(Duration.ofDays(1)).toString()));
        var analysis = PriceTrendAnalyzer.analyze(points, NOW);
        assertThat(analysis.minKrw()).isEqualTo(100_000);
        assertThat(analysis.latest().amountKrw()).isEqualTo(100_000);
        assertThat(analysis.windowStart()).isEqualTo(NOW.minus(Duration.ofHours(1)).minus(PriceTrendAnalyzer.WINDOW));
    }

    private PriceTrendAnalyzer.Analysis withLatest(long latest) {
        var points = new ArrayList<>(daily("A", 20, NOW.minus(Duration.ofDays(1)), 100_000));
        // 기간 최저가를 따로 둬서 중앙값 경계만 검사한다.
        points.add(point("A", 90_000, NOW.minus(Duration.ofDays(30)).toString()));
        points.add(point("A", latest, NOW.minus(Duration.ofHours(1)).toString()));
        return PriceTrendAnalyzer.analyze(points, NOW);
    }

    /** latestAt부터 하루씩 과거로 days개 관측을 만든다. */
    private static List<PricePoint> daily(String offer, int days, Instant latestAt, long amount) {
        var points = new ArrayList<PricePoint>();
        for (int i = days - 1; i >= 0; i--) points.add(point(offer, amount, latestAt.minus(Duration.ofDays(i)).toString()));
        return points;
    }

    private static PricePoint point(String offer, long amount, String observedAt) {
        return new PricePoint("DANAWA", offer, "https://example.com/" + offer, "SKU-" + offer,
                CatalogPriceImportBatch.SaleUnit.PRODUCT, null, amount, Instant.parse(observedAt),
                "https://example.com/" + offer, PricePoint.Origin.CATALOG);
    }
}
