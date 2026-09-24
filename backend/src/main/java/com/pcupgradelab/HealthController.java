package com.pcupgradelab;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 기본 API 통신 확인용. 이 고정 UP 응답은 DB 연결 검사가 아니며, DB 상태는 /actuator/health에서 확인한다. */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of(
                "status", "UP",
                "service", "pc-upgrade-lab"
        );
    }
}