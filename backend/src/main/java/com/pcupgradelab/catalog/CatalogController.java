package com.pcupgradelab.catalog;

import com.pcupgradelab.pc.PartType;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * local 프로필에서만 등록되는 검토용 API. 비활성·미검증 제품도 반환한다.
 * 공개 서비스로 전환할 때에는 인증과 제품 노출 정책을 별도로 확정해야 한다.
 */
@RestController
@Profile("local")
@RequestMapping("/api/catalog/products")
public class CatalogController {
    private final CatalogQueryService service;

    public CatalogController(CatalogQueryService service) {
        this.service = service;
    }

    @GetMapping
    public CatalogDtos.PageResponse search(
            @RequestParam(required = false) PartType type,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.search(type, q, page, size);
    }

    @GetMapping("/{id}")
    public CatalogDtos.Detail detail(@PathVariable String id) {
        return service.findById(id);
    }
}
