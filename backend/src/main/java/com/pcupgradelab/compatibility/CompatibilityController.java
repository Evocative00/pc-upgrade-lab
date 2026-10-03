package com.pcupgradelab.compatibility;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local")
@RequestMapping("/api/compatibility")
public class CompatibilityController {
    private final CompatibilityCheckService service;
    public CompatibilityController(CompatibilityCheckService service) { this.service = service; }

    @PostMapping("/check")
    public CompatibilityDtos.Result check(@Valid @RequestBody CompatibilityDtos.Request request) {
        return service.check(request);
    }
}
