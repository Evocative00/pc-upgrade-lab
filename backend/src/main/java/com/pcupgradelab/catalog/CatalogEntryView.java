package com.pcupgradelab.catalog;

import java.util.List;

/** 제원과 원본 자료를 확인하는 내부 상세 조회 결과. 공개 검색 API와는 분리한다. */
public record CatalogEntryView(
        CatalogProductView product,
        CatalogSpecification specification,
        List<CatalogSourceView> sources
) {
    public CatalogEntryView {
        sources = List.copyOf(sources);
    }
}
