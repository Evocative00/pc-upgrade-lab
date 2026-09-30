package com.pcupgradelab.catalog;

import com.pcupgradelab.common.ApiException;
import com.pcupgradelab.pc.PartType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 검토용 읽기 서비스. 제품 상태·제원·가격을 수정하거나 호환성을 판정하지 않는다. */
@Service
@Transactional(readOnly = true)
public class CatalogQueryService {
    private final CatalogProductRepository products;
    private final CatalogReferencePriceRepository prices;
    private final CatalogEntryService entries;

    public CatalogQueryService(CatalogProductRepository products, CatalogReferencePriceRepository prices,
                               CatalogEntryService entries) {
        this.products = products;
        this.prices = prices;
        this.entries = entries;
    }

    /** type 생략 = 전체 종류, q 생략/공백 = 해당 종류 전체. 검색어는 대소문자를 구분하지 않는다. */
    public CatalogDtos.PageResponse search(PartType type, String q, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT",
                    "page는 0 이상, size는 1~100이어야 하며 조회 범위가 너무 크면 안 됩니다.");
        }
        if (q != null && q.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "검색어는 최대 100자까지 가능합니다.");
        }
        String keyword = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        // %, _, !도 검색어 자체로 처리한다. SQL 구문에는 사용자 입력을 이어 붙이지 않는다.
        String pattern = "%" + keyword.replace("!", "!!").replace("%", "!%")
                .replace("_", "!_") + "%";
        var pageable = PageRequest.of(page, size, Sort.by("type", "manufacturer", "modelName", "id"));
        var result = products.searchForReview(type, pattern, pageable);

        // 페이지 내 기준가격만 한 번에 읽는다. 목록 조회에서 제품마다 상세 제원·출처를 읽지 않는다.
        var ids = result.getContent().stream().map(CatalogProduct::getId).toList();
        var pricesById = prices.findAllById(ids).stream().collect(Collectors.toMap(
                CatalogReferencePrice::getProductId, Function.identity()));
        var items = result.getContent().stream().map(product -> {
            var price = pricesById.get(product.getId());
            if (price == null) {
                throw new IllegalStateException("Catalog product is missing its reference price row: "
                        + product.getId());
            }
            return CatalogProductView.from(product, price);
        }).toList();
        return new CatalogDtos.PageResponse(items, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages(), CatalogDtos.ATTRIBUTIONS);
    }

    public CatalogDtos.Detail findById(String id) {
        return entries.findById(id).map(CatalogDtos.Detail::from).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "CATALOG_PRODUCT_NOT_FOUND", "부품을 찾을 수 없습니다."));
    }
}
