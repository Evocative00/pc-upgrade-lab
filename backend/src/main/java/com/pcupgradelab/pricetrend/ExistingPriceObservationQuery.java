package com.pcupgradelab.pricetrend;

import com.pcupgradelab.catalog.price.CatalogPriceMapping;
import com.pcupgradelab.catalog.price.CatalogPriceObservation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 기존 가격 관측을 읽기만 하는 조회 전용 저장소.
 * JpaRepository가 아닌 Repository를 상속해 save·delete 메서드가 생성되지 않는다.
 */
public interface ExistingPriceObservationQuery extends Repository<CatalogPriceObservation, Long> {

    @Query("""
            select new com.pcupgradelab.pricetrend.PricePoint(
                m.sourceName, m.externalId, m.sourceUrl, m.saleSku, m.saleUnit, m.moduleCount,
                o.amountKrw, o.observedAt, o.evidenceUrl)
            from CatalogPriceObservation o join o.mapping m
            where o.product.id = :productId
            order by o.observedAt asc, o.id asc
            """)
    List<PricePoint> findPointsForProduct(@Param("productId") String productId);

    @Query("""
            select o from CatalogPriceObservation o
            where o.mapping.id = :mappingId and o.observedAt = :observedAt
            """)
    Optional<CatalogPriceObservation> findObservation(@Param("mappingId") Long mappingId,
                                                       @Param("observedAt") Instant observedAt);

    /** 상품 ID뿐 아니라 이미 검토한 판매 구성 전체를 대조한다. */
    @Query("""
            select m from CatalogPriceMapping m
            where m.sourceName = :sourceName and m.externalId = :externalId
            """)
    Optional<CatalogPriceMapping> findMapping(@Param("sourceName") String sourceName,
                                              @Param("externalId") String externalId);

    @Query("select distinct o.product.id from CatalogPriceObservation o")
    List<String> findProductIdsWithObservations();
}
