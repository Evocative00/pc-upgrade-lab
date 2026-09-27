package com.pcupgradelab.pc;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import static com.pcupgradelab.pc.PcDtos.*;

/**
 * PC 등록·조회·수정 REST Controller.
 * docs/week1-contract.md의 공통 API 규격을 준수한다.
 */
@RestController
@RequestMapping("/api/pcs")
public class PcController {
    private final PcService service;

    public PcController(PcService service) {
        this.service = service;
    }

    /**
     * POST /api/pcs : PC 등록
     * 성공 시 201 Created, Location 헤더, 생성된 PC 상세 본문을 반환한다.
     */
    @PostMapping
    public ResponseEntity<Detail> create(@Valid @RequestBody Request request) {
        var detail = service.create(request);
        var location = URI.create("/api/pcs/" + detail.id());
        return ResponseEntity.created(location).body(detail);
    }

    /**
     * GET /api/pcs?page=0&size=20 : 저장한 PC 목록 조회
     * 성공 시 200 OK와 페이징 요약 정보를 반환한다.
     */
    @GetMapping
    public ResponseEntity<PageResponse<Summary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.findAll(page, size));
    }

    /**
     * GET /api/pcs/{id} : PC 상세 조회
     * 성공 시 200 OK와 부품 목록을 포함한 상세 정보를 반환한다.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Detail> read(@PathVariable Long id) {
        return ResponseEntity.ok(service.findById(id));
    }

    /**
     * PUT /api/pcs/{id} : PC 이름과 부품 목록 수정
     * 성공 시 200 OK와 변경 후 PC 상세 정보를 반환한다.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Detail> update(
            @PathVariable Long id,
            @Valid @RequestBody Request request) {
        return ResponseEntity.ok(service.update(id, request));
    }
}
