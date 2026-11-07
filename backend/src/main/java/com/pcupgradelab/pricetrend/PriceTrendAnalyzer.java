package com.pcupgradelab.pricetrend;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 등록된 관측만으로 가격 수준을 판정하는 통계 규칙. 미래 가격을 수치로 예측하지 않는다.
 * 판정은 비교할 관측일이 2일 이상이면 내고, 최신화 여부와 자료량은 별도 표시(stale·lowConfidence)로 알린다.
 * DB·Spring에 의존하지 않는 순수 함수이며 기준과 임계값은 이 클래스의 상수에만 둔다(팀 결정 사항).
 */
public final class PriceTrendAnalyzer {
    public static final String METHOD_VERSION = "trend-v2";
    /** 비교 기간. 마지막 관측 시각부터 거슬러 올라간다. */
    public static final Duration WINDOW = Duration.ofDays(90);
    /** 판정을 내는 최소 고유 관측일(UTC 일자) 수. 이보다 적으면 비교 대상이 없어 자료 부족이다. */
    public static final int MIN_COMPARABLE_DAYS = 2;
    /** 충분한 자료로 보는 고유 관측일 수. 미만이면 판정에 관측일 부족(참고용) 표시를 붙인다. */
    public static final int MIN_OBSERVED_DAYS = 14;
    /** 마지막 관측이 이만큼 지나면 최신화되지 않음 표시를 붙인다(기존 중앙 판매가 만료 기준과 같다). */
    public static final Duration STALE_AFTER = Duration.ofDays(7);
    /** 최신가 ≤ 중앙값 × 97% 이면 좋은 가격. */
    public static final int GOOD_MEDIAN_PERCENT = 97;
    /** 최신가가 중앙값보다 낮고 기간 최저가 × 102% 이하이면 좋은 가격. */
    public static final int GOOD_MIN_PERCENT = 102;
    /** 최신가 ≥ 중앙값 × 105% 이면 대기 권장. */
    public static final int WAIT_MEDIAN_PERCENT = 105;
    private static final Duration CHANGE_SPAN = Duration.ofDays(7);

    private PriceTrendAnalyzer() { }

    public enum Verdict { GOOD_PRICE, FAIR, WAIT, INSUFFICIENT_DATA }

    public enum Reason {
        NO_OBSERVATION, TOO_FEW_DAYS,
        BELOW_MEDIAN, NEAR_PERIOD_LOW, ABOVE_MEDIAN, WITHIN_RANGE
    }

    public record Latest(long amountKrw, Instant observedAt, String sourceName, String externalId, String saleSku) { }

    public record Analysis(String methodVersion, Instant analyzedAt, Instant windowStart, Instant windowEnd,
                           int minObservedDays, int sampleCount, int observedDayCount, Latest latest,
                           Long medianKrw, Long minKrw, Long maxKrw, BigDecimal changeRate7d,
                           Verdict verdict, Reason reason, boolean stale, boolean lowConfidence) { }

    public static Analysis analyze(List<PricePoint> points, Instant now) {
        if (points == null || now == null) throw new IllegalArgumentException("Points and analysis time are required");
        var series = primarySeries(points, now);
        if (series.isEmpty()) {
            return new Analysis(METHOD_VERSION, now, now.minus(WINDOW), now, MIN_OBSERVED_DAYS, 0, 0, null,
                    null, null, null, null, Verdict.INSUFFICIENT_DATA, Reason.NO_OBSERVATION, false, false);
        }
        PricePoint newest = series.getLast();
        var latest = new Latest(newest.amountKrw(), newest.observedAt(), newest.sourceName(),
                newest.externalId(), newest.saleSku());
        // 오래된 자료도 그 시점의 판매가끼리 비교하도록 비교 기간은 마지막 관측에 맞춘다.
        Instant windowEnd = newest.observedAt();
        Instant windowStart = windowEnd.minus(WINDOW);

        // 하루에 여러 번 관측해도 그날의 마지막 금액 하나만 쓴다. 빠진 날은 채우지 않는다.
        var daily = new TreeMap<LocalDate, Long>();
        int sampleCount = 0;
        for (var point : series) {
            if (point.observedAt().isBefore(windowStart)) continue;
            sampleCount++;
            daily.put(point.observedAt().atZone(ZoneOffset.UTC).toLocalDate(), point.amountKrw());
        }
        var values = daily.values().stream().sorted().toList();
        long median = median(values);
        long min = values.getFirst();
        long max = values.getLast();
        BigDecimal change = changeRate(daily, newest);
        int days = daily.size();
        boolean stale = !newest.observedAt().isAfter(now.minus(STALE_AFTER));

        if (days < MIN_COMPARABLE_DAYS) {
            return new Analysis(METHOD_VERSION, now, windowStart, windowEnd, MIN_OBSERVED_DAYS, sampleCount, days,
                    latest, median, min, max, change, Verdict.INSUFFICIENT_DATA, Reason.TOO_FEW_DAYS, stale, false);
        }
        long current = newest.amountKrw();
        Verdict verdict;
        Reason reason;
        if (current * 100 <= median * GOOD_MEDIAN_PERCENT) {
            verdict = Verdict.GOOD_PRICE;
            reason = Reason.BELOW_MEDIAN;
        } else if (current < median && current * 100 <= min * GOOD_MIN_PERCENT) {
            // 가격 변동이 없는 시계열(최신가 = 중앙값 = 최저가)은 좋은 가격으로 보지 않는다.
            verdict = Verdict.GOOD_PRICE;
            reason = Reason.NEAR_PERIOD_LOW;
        } else if (current * 100 >= median * WAIT_MEDIAN_PERCENT) {
            verdict = Verdict.WAIT;
            reason = Reason.ABOVE_MEDIAN;
        } else {
            verdict = Verdict.FAIR;
            reason = Reason.WITHIN_RANGE;
        }
        return new Analysis(METHOD_VERSION, now, windowStart, windowEnd, MIN_OBSERVED_DAYS, sampleCount, days, latest,
                median, min, max, change, verdict, reason, stale, days < MIN_OBSERVED_DAYS);
    }

    /** 같은 관측을 한 건으로 합친 뒤, 가장 최근에 관측된 판매 구성의 시계열을 시간순으로 돌려준다. */
    static List<PricePoint> primarySeries(List<PricePoint> points, Instant now) {
        var unique = new LinkedHashMap<PricePoint.ObservationKey, PricePoint>();
        for (var point : points) {
            if (point == null || point.observedAt().isAfter(now)) continue;
            unique.merge(point.observationKey(), point,
                    (first, second) -> first.origin() == PricePoint.Origin.CATALOG ? first : second);
        }
        var byOffer = new HashMap<PricePoint.OfferKey, List<PricePoint>>();
        for (var point : unique.values()) byOffer.computeIfAbsent(point.offerKey(), key -> new ArrayList<>()).add(point);
        Comparator<PricePoint> byTime = Comparator.comparing(PricePoint::observedAt);
        byOffer.values().forEach(list -> list.sort(byTime));
        return byOffer.values().stream()
                .max(Comparator.<List<PricePoint>, Instant>comparing(list -> list.getLast().observedAt())
                        .thenComparingInt(List::size))
                .orElse(List.of());
    }

    private static long median(List<Long> sorted) {
        int size = sorted.size();
        if (size % 2 == 1) return sorted.get(size / 2);
        return BigDecimal.valueOf(sorted.get(size / 2 - 1)).add(BigDecimal.valueOf(sorted.get(size / 2)))
                .divide(BigDecimal.TWO, 0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 최신가와 7일 이상 이전의 가장 가까운 관측일 금액을 비교한다. 그런 관측이 없으면 null이다. */
    private static BigDecimal changeRate(NavigableMap<LocalDate, Long> daily, PricePoint newest) {
        LocalDate baseDay = newest.observedAt().minus(CHANGE_SPAN).atZone(ZoneOffset.UTC).toLocalDate();
        var base = daily.floorEntry(baseDay);
        if (base == null) return null;
        return BigDecimal.valueOf(newest.amountKrw() - base.getValue())
                .divide(BigDecimal.valueOf(base.getValue()), 4, RoundingMode.HALF_UP);
    }
}
