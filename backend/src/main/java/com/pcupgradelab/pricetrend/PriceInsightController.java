package com.pcupgradelab.pricetrend;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 기존 카탈로그 API와 같이 local 프로필에서만 등록되는 GET 전용 API. 로그인 없이 조회한다. */
@RestController
@Profile("local")
@RequestMapping("/api/catalog/products/{id}")
public class PriceInsightController {
    private final PriceInsightService service;

    public PriceInsightController(PriceInsightService service) {
        this.service = service;
    }

    @GetMapping("/price-history")
    public PriceTrendDtos.HistoryResponse history(@PathVariable String id) {
        return service.history(id);
    }

    @GetMapping("/price-insight")
    public PriceTrendDtos.InsightResponse insight(@PathVariable String id) {
        return service.insight(id);
    }
}
