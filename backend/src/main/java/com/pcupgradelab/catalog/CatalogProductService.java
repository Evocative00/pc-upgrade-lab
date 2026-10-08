package com.pcupgradelab.catalog;

import com.pcupgradelab.catalog.price.CatalogCurrentPriceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.List;

/**
 * 공용 부품의 기본 등록·조회. 상세 제원과 출처 적재를 붙이기 전의 첫 저장 단위다.
 * 새 부품은 미검증·비활성 상태로 두고 가격 미확정 행을 반드시 함께 만든다.
 */
@Service
@Transactional(readOnly = true)
public class CatalogProductService {
    private final CatalogProductRepository products;
    private final CatalogReferencePriceRepository prices;
    private final CatalogCurrentPriceService currentPrices;

    public CatalogProductService(CatalogProductRepository products, CatalogReferencePriceRepository prices,
                                 CatalogCurrentPriceService currentPrices) {
        this.products = products;
        this.prices = prices;
        this.currentPrices = currentPrices;
    }

    @Transactional
    public CatalogProductView create(CatalogProductCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Catalog product request is required");
        }
        var product = new CatalogProduct(request.type(), request.manufacturer(),
                request.modelName(), request.partNumber());
        // flush는 SQL을 보내지만 커밋하지 않는다. 다음 가격 저장이 실패하면 부품도 롤백된다.
        var savedProduct = products.saveAndFlush(product);
        var savedPrice = prices.saveAndFlush(CatalogReferencePrice.unconfirmed(savedProduct));
        return CatalogProductView.from(savedProduct, savedPrice);
    }

    /** 검토용 내부 조회이므로 아직 활성화되지 않은 부품도 ID로 조회할 수 있다. */
    public Optional<CatalogProductView> findById(String id) {
        if (id == null || id.isBlank() || id.length() > 128) {
            throw new IllegalArgumentException("Catalog product ID is required (max 128 characters)");
        }
        return products.findById(id).map(product -> {
            var price = prices.findById(id).orElseThrow(() -> new IllegalStateException(
                    "Catalog product is missing its reference price row: " + id));
            return CatalogProductView.from(product, price, currentPrices.latestForProducts(List.of(id)).get(id));
        });
    }
}
