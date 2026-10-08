package com.pcupgradelab.catalog.price;

import com.pcupgradelab.catalog.CatalogProductView;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CatalogCurrentPriceService {
    private final CatalogPriceObservationRepository observations;
    private final CatalogPriceDatabaseTimeZone databaseTimeZone;

    public CatalogCurrentPriceService(CatalogPriceObservationRepository observations,
                                       CatalogPriceDatabaseTimeZone databaseTimeZone) {
        this.observations = observations;
        this.databaseTimeZone = databaseTimeZone;
    }

    public Map<String, CatalogProductView.CurrentPrice> latestForProducts(Collection<String> productIds) {
        if (productIds.isEmpty()) return Map.of();
        // DTO 변환까지 UTC 범위 안에서 끝내고 매핑의 시각/지연 참조를 밖으로 내보내지 않는다.
        return databaseTimeZone.read(() -> observations.findLatestForProducts(productIds).stream()
                .collect(Collectors.toMap(CatalogPriceObservation::getProductId, CatalogPriceObservation::toView)));
    }
}
