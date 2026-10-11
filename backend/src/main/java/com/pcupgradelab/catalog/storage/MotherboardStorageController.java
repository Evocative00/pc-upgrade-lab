package com.pcupgradelab.catalog.storage;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
@RequestMapping("/api/catalog/products")
public class MotherboardStorageController {
    private final MotherboardStorageQueryService service;
    public MotherboardStorageController(MotherboardStorageQueryService service) { this.service = service; }
    @GetMapping("/{id}/storage-support")
    public MotherboardStorageQueryService.View find(@PathVariable String id) { return service.find(id); }
}
