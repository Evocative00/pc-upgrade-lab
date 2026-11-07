package com.pcupgradelab.pricetrend;

import com.pcupgradelab.catalog.price.CatalogPriceDatabaseTimeZone;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * 기존 가격 관측(catalog_price_observation)과 새 이력(price_history_observation)을 함께 읽는다.
 * 기존 테이블은 조회 전용 저장소로만 접근하며, 같은 관측은 기존 테이블의 행을 우선한다.
 * 호출자는 JPA 트랜잭션 안에 있어야 한다(TIMESTAMP를 UTC로 읽기 위해 기존 시간대 범위를 재사용한다).
 */
@Component
public class PriceHistoryReader {
    private final ExistingPriceObservationQuery existing;
    private final PriceHistoryObservationRepository history;
    private final CatalogPriceDatabaseTimeZone databaseTimeZone;

    public PriceHistoryReader(ExistingPriceObservationQuery existing, PriceHistoryObservationRepository history,
                              CatalogPriceDatabaseTimeZone databaseTimeZone) {
        this.existing = existing;
        this.history = history;
        this.databaseTimeZone = databaseTimeZone;
    }

    /** 중복을 제거한 관측을 시간순으로 돌려준다. */
    public List<PricePoint> pointsFor(String productId) {
        return databaseTimeZone.read(() -> {
            var unique = new LinkedHashMap<PricePoint.ObservationKey, PricePoint>();
            for (var point : existing.findPointsForProduct(productId)) unique.putIfAbsent(point.observationKey(), point);
            for (var row : history.findAllByProductIdOrderByObservedAtAscIdAsc(productId)) {
                var point = row.toPoint();
                unique.putIfAbsent(point.observationKey(), point);
            }
            var points = new ArrayList<>(unique.values());
            points.sort(Comparator.comparing(PricePoint::observedAt));
            return List.copyOf(points);
        });
    }

    /** 관측이 하나라도 있는 상품 ID. 분석 작업의 대상 목록이다. */
    public List<String> productIdsWithPrices() {
        var ids = new TreeSet<String>(existing.findProductIdsWithObservations());
        ids.addAll(history.findProductIdsWithHistory());
        return List.copyOf(ids);
    }
}
