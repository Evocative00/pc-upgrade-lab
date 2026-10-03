package com.pcupgradelab.catalog.support;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
@RequestMapping("/api/catalog/products")
public class MotherboardCpuController {
    private final MotherboardCpuQueryService service;
    public MotherboardCpuController(MotherboardCpuQueryService service) { this.service = service; }
    @GetMapping("/{id}/cpu-support")
    public MotherboardCpuQueryService.View find(@PathVariable String id,
                                                @RequestParam(name = "cpuProductId", required = false) String cpuId) {
        return service.find(id, cpuId);
    }
}
