package com.pcupgradelab.pricetrend;

import com.pcupgradelab.catalog.price.CatalogPriceObservation;
import java.time.Instant;
import java.util.List;
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
            select count(o) > 0 from CatalogPriceObservation o join o.mapping m
            where m.sourceName = :sourceName and m.externalId = :externalId and o.observedAt = :observedAt
            """)
    boolean existsObservation(@Param("sourceName") String sourceName, @Param("externalId") String externalId,
                              @Param("observedAt") Instant observedAt);

    /** 기존 판매처 매핑이 있으면 그 상품 ID를 돌려준다. 다른 상품에 연결된 판매처 상품은 가져오지 않는다. */
    @Query("""
            select m.product.id from CatalogPriceMapping m
            where m.sourceName = :sourceName and m.externalId = :externalId
            """)
    List<String> findMappedProductIds(@Param("sourceName") String sourceName, @Param("externalId") String externalId);

    @Query("select distinct o.product.id from CatalogPriceObservation o")
    List<String> findProductIdsWithObservations();
}
