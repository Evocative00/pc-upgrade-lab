package com.pcupgradelab.catalog.price;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CatalogPriceObservationRepository extends JpaRepository<CatalogPriceObservation, Long> {
    Optional<CatalogPriceObservation> findByMapping_IdAndObservedAt(Long mappingId, Instant observedAt);

    /** 페이지 전체를 한 번 조회한다. 각 제품의 이력을 전부 읽거나 N+1 조회하지 않는다. */
    @Query("""
            select o from CatalogPriceObservation o join fetch o.mapping
            where o.product.id in :productIds and not exists (
                select newer.id from CatalogPriceObservation newer
                where newer.product.id = o.product.id
                  and (newer.observedAt > o.observedAt
                    or (newer.observedAt = o.observedAt and newer.id > o.id))
            )
            """)
    List<CatalogPriceObservation> findLatestForProducts(@Param("productIds") Collection<String> productIds);
}
