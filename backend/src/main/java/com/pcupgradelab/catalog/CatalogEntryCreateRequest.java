package com.pcupgradelab.catalog;

import java.util.HashSet;
import java.util.List;

/** 한 제품에 해당 종류의 제원 하나와 하나 이상의 출처를 묶는 내부 등록 요청이다. */
public record CatalogEntryCreateRequest(
        CatalogProductCreateRequest product,
        CatalogSpecification specification,
        List<CatalogSourceInput> sources
) {
    public CatalogEntryCreateRequest {
        if (product == null || specification == null) {
            throw new IllegalArgumentException("Product and specification are required");
        }
        if (product.type() != specification.type()) {
            throw new IllegalArgumentException("Product type must match its specification type");
        }
        if (sources == null || sources.isEmpty() || sources.stream().anyMatch(source -> source == null)) {
            throw new IllegalArgumentException("At least one non-null source is required");
        }
        sources = List.copyOf(sources);
        var identities = new HashSet<SourceIdentity>();
        for (var source : sources) {
            if (source.externalId() != null
                    && !identities.add(new SourceIdentity(source.sourceName(), source.externalId()))) {
                throw new IllegalArgumentException("A source identity occurs more than once in this request");
            }
        }
    }

    private record SourceIdentity(CatalogSourceName name, String externalId) { }
}
