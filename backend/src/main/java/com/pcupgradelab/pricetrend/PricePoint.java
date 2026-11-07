package com.pcupgradelab.pricetrend;

import com.pcupgradelab.catalog.price.CatalogPriceImportBatch;
import java.time.Instant;

/**
 * 가격 판단의 입력이 되는 관측 1건. 기존 catalog_price_observation과 새 price_history_observation을
 * 같은 형태로 읽는다. 참고가(referenceEstimate·referencePrice)는 이 형태로 만들지 않는다.
 */
public record PricePoint(String sourceName, String externalId, String sourceUrl, String saleSku,
                         CatalogPriceImportBatch.SaleUnit saleUnit, Integer moduleCount,
                         long amountKrw, Instant observedAt, String evidenceUrl, Origin origin) {

    /** CATALOG는 기존 가격 관측 테이블, HISTORY는 가격 판단 기능이 별도로 가져온 이력이다. */
    public enum Origin { CATALOG, HISTORY }

    /** 같은 판매처 상품·판매 단위끼리만 비교한다. 다른 판매 구성은 한 시계열로 섞지 않는다. */
    public record OfferKey(String sourceName, String externalId,
                           CatalogPriceImportBatch.SaleUnit saleUnit, Integer moduleCount) { }

    /** 동일 관측 판정 키. 두 테이블에 같은 관측이 있으면 한 건으로 본다. */
    public record ObservationKey(String sourceName, String externalId, Instant observedAt) { }

    /** 기존 관측 테이블 JPQL 조회용 생성자. */
    public PricePoint(String sourceName, String externalId, String sourceUrl, String saleSku,
                      CatalogPriceImportBatch.SaleUnit saleUnit, Integer moduleCount,
                      Long amountKrw, Instant observedAt, String evidenceUrl) {
        this(sourceName, externalId, sourceUrl, saleSku, saleUnit, moduleCount,
                amountKrw.longValue(), observedAt, evidenceUrl, Origin.CATALOG);
    }

    public OfferKey offerKey() {
        return new OfferKey(sourceName, externalId, saleUnit, moduleCount);
    }

    public ObservationKey observationKey() {
        return new ObservationKey(sourceName, externalId, observedAt);
    }
}
