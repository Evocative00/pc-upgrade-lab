package com.pcupgradelab.catalog;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 과거 판매·출시·유사 제원 자료의 참고가. 현재 구매가나 장기 산정 기준가격을 대체하지 않는다. */
public record CatalogReferenceEstimate(
        long amountKrw, Basis basis, IdentityScope identityScope, SaleUnit saleUnit, Integer moduleCount,
        LocalDate sourceDate, Instant reviewedAt, Confidence confidence, Method method,
        Long rangeLowKrw, Long rangeHighKrw, List<SourceQuote> sourceQuotes, String notes) {
    public enum Basis { MODEL_RETAIL_REFERENCE, LAUNCH_PRICE, SIMILAR_PART_ESTIMATE, HISTORICAL_RETAIL }
    public enum IdentityScope { MODEL, EXACT_PRODUCT, SIMILAR_SPEC }
    public enum SaleUnit { PRODUCT, RAM_KIT }
    public enum Confidence { VERIFIED_MODEL, ESTIMATED }
    public enum Method { DIRECT_MODEL_QUOTE, OFFICIAL_MODEL_LAUNCH, SPEC_NEIGHBOUR_MEDIAN }
    public record SourceQuote(String canonicalId, String modelName, long amountKrw, String sourceName,
                              String sourceUrl, LocalDate sourceDate, SaleUnit saleUnit, Integer moduleCount) { }

    public CatalogReferenceEstimate {
        if (sourceQuotes != null) sourceQuotes = List.copyOf(sourceQuotes);
    }
}
