package com.pcupgradelab.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 개발용 조회 API의 응답. 상세 제원은 product.type으로 구분하고 미확인 값은 null로 유지한다. */
public final class CatalogDtos {
    private CatalogDtos() { }

    // seed NOTICE.txt의 출처·라이선스를 화면에서도 표시할 수 있도록 함께 전달한다.
    public static final List<Attribution> ATTRIBUTIONS = List.of(new Attribution(
            "BuildCores OpenDB", "Contains data from BuildCores OpenDB.",
            "https://github.com/buildcores/buildcores-open-db",
            "ODC-By-1.0", "https://opendatacommons.org/licenses/by/1-0/"));

    public record Attribution(String name, String notice, String url,
                              String license, String licenseUrl) { }

    /** 기본 목록은 공통 정보와 가격만 반환한다. 요청한 CPU·GPU·RAM 목록에는 제원을 함께 반환한다. */
    public record PageResponse(List<CatalogProductView> items, int page, int size,
                               long totalElements, int totalPages, List<Attribution> attributions,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, CatalogSpecification> specifications) {
        public PageResponse {
            items = List.copyOf(items);
            attributions = List.copyOf(attributions);
            if (specifications != null) specifications = Map.copyOf(specifications);
        }
    }

    /** 원본 JSON(rawPayload)과 DB 내부 출처 행 ID는 HTTP 응답으로 내보내지 않는다. */
    public record Source(CatalogSourceName sourceName, String externalId, String sourceRevision,
                         String sourceUrl, Instant retrievedAt) {
        static Source from(CatalogSourceView source) {
            return new Source(source.sourceName(), source.externalId(), source.sourceRevision(),
                    source.sourceUrl(), source.retrievedAt());
        }
    }

    public record Detail(CatalogProductView product, CatalogSpecification specification,
                         List<Source> sources, List<Attribution> attributions) {
        public Detail {
            sources = List.copyOf(sources);
            attributions = List.copyOf(attributions);
        }

        static Detail from(CatalogEntryView entry) {
            return new Detail(entry.product(), entry.specification(),
                    entry.sources().stream().map(Source::from).toList(), ATTRIBUTIONS);
        }
    }
}
