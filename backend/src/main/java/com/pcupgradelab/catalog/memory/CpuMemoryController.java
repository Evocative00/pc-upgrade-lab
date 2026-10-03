package com.pcupgradelab.catalog.memory;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 기존 상세 응답과 분리한 local 전용 보완 자료 조회 API. */
@RestController
@Profile("local")
@RequestMapping("/api/catalog/products")
public class CpuMemoryController {
    private final CpuMemoryQueryService service;

    public CpuMemoryController(CpuMemoryQueryService service) { this.service = service; }

    @GetMapping("/{id}/memory-support")
    public CpuMemoryQueryService.View memorySupport(@PathVariable String id) {
        return service.findByProductId(id);
    }
}
