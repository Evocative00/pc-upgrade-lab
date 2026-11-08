package com.pcupgradelab.pricetrend;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PriceHistoryObservationRepository extends JpaRepository<PriceHistoryObservation, Long> {
    List<PriceHistoryObservation> findAllByProductIdOrderByObservedAtAscIdAsc(String productId);

    Optional<PriceHistoryObservation> findBySourceNameAndExternalIdAndObservedAt(
            String sourceName, String externalId, Instant observedAt);

    Optional<PriceHistoryObservation> findFirstBySourceNameAndExternalIdOrderByObservedAtAsc(
            String sourceName, String externalId);

    @Query("select distinct h.productId from PriceHistoryObservation h")
    List<String> findProductIdsWithHistory();
}
