package com.pcupgradelab.catalog.identity;

import com.pcupgradelab.pc.PartType;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/** 기존 카탈로그와 같은 local 검토용 읽기 API. PC 연결이나 모델 등록을 수행하지 않는다. */
@RestController
@Profile("local")
@RequestMapping("/api/catalog/models")
public class CatalogModelController {
    private final CatalogModelQueryService service;
    public CatalogModelController(CatalogModelQueryService service) { this.service = service; }

    @GetMapping
    public CatalogIdentityDtos.PageResponse search(@RequestParam(required = false) PartType type,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) { return service.search(type, q, page, size); }

    @GetMapping("/{id}")
    public CatalogIdentityDtos.Detail detail(@PathVariable String id) { return service.findById(id); }
}
