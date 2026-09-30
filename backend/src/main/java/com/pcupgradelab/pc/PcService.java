package com.pcupgradelab.pc;

import com.pcupgradelab.catalog.CatalogProduct;
import com.pcupgradelab.catalog.CatalogProductRepository;
import com.pcupgradelab.common.ApiException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.pcupgradelab.pc.PcDtos.*;

/**
 * PC 등록·조회·수정 비즈니스 로직.
 * 엔티티를 직접 노출하지 않고 DTO 변환을 수행하며, 트랜잭션 범위 안에서 처리한다.
 */
@Service
@Transactional(readOnly = true)
public class PcService {
    // 로그인 구현 전 로컬 전용 소유자 식별값. 클라이언트 요청으로 받지 않고 서버가 결정한다.
    public static final String DEFAULT_OWNER_KEY = "local-dev";

    private final PcConfigurationRepository repository;
    private final CatalogProductRepository catalogProducts;

    public PcService(PcConfigurationRepository repository, CatalogProductRepository catalogProducts) {
        this.repository = repository;
        this.catalogProducts = catalogProducts;
    }

    /**
     * 신규 PC 구성을 등록한다.
     */
    @Transactional
    public Detail create(Request request) {
        var pc = new PcConfiguration(DEFAULT_OWNER_KEY, request.name(), request.parts());
        validateCatalogLinks(request.parts());
        var saved = repository.save(pc);
        return Detail.from(saved);
    }

    /**
     * PC 상세 정보를 조회한다. (부품 목록 포함)
     */
    public Detail findById(Long id) {
        var pc = repository.findByIdAndOwnerKey(id, DEFAULT_OWNER_KEY)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PC_NOT_FOUND", "PC를 찾을 수 없습니다."));
        return Detail.from(pc);
    }

    /**
     * 저장된 PC 목록을 페이징하여 요약 정보로 조회한다.
     * 정렬 기준은 docs/week1-contract.md 규격에 따라 updatedAt DESC, id DESC이다.
     */
    public PageResponse<Summary> findAll(int page, int size) {
        if (page < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "page는 0 이상이어야 합니다.");
        }
        if (size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "size는 1에서 100 사이여야 합니다.");
        }
        // JPA의 조회 시작 위치는 int 범위다. 곱셈 전에 long으로 바꿔 계산 자체의 넘침도 방지한다.
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "요청한 페이지 범위가 너무 큽니다.");
        }
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        var result = repository.findAllByOwnerKey(DEFAULT_OWNER_KEY, pageable);
        var items = result.getContent().stream().map(Summary::from).toList();
        return new PageResponse<>(items, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    /**
     * PC 이름과 부품 목록 전체를 수정한다.
     * 기존 PC ID를 유지하고, orphanRemoval을 통해 이전 부품 행을 깔끔하게 교체한다.
     */
    @Transactional
    public Detail update(Long id, Request request) {
        try {
            var pc = repository.findByIdAndOwnerKey(id, DEFAULT_OWNER_KEY)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PC_NOT_FOUND", "PC를 찾을 수 없습니다."));
            // 기존 이름과 부품 목록을 바꾸기 전에 모든 연결을 확인한다.
            validateCatalogLinks(request.parts());
            pc.update(request.name(), request.parts());
            repository.flush();
            return Detail.from(pc);
        } catch (OptimisticLockingFailureException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "다른 요청에 의해 이미 변경되었습니다. 최신 정보를 다시 확인해 주세요.");
        }
    }

    private void validateCatalogLinks(List<PartInput> parts) {
        if (parts == null) throw new IllegalArgumentException("PC parts are required");
        var ids = parts.stream().map(PartInput::catalogProductId)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return;

        // 같은 제품에 연결한 RAM 여러 개도 한 번에 조회한다.
        // 현재 로컬 검토 단계에서는 비활성·미검증 제품도 연결할 수 있다.
        // 카탈로그 상태·가격이나 장치의 원문·수량·제원은 이 검사로 변경하지 않는다.
        var typesById = catalogProducts.findAllById(ids).stream()
                .collect(Collectors.toMap(CatalogProduct::getId, CatalogProduct::getType));
        for (int i = 0; i < parts.size(); i++) {
            var part = parts.get(i);
            if (part.catalogProductId() == null) continue;
            var catalogType = typesById.get(part.catalogProductId());
            if (catalogType == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CATALOG_LINK_NOT_FOUND",
                        (i + 1) + "번째 부품의 연결 제품이 없습니다. 부품을 다시 검색하거나 연결을 해제해 주세요.");
            }
            if (catalogType != part.type()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CATALOG_LINK_TYPE_MISMATCH",
                        (i + 1) + "번째 부품과 연결 제품의 종류가 다릅니다. 같은 종류의 부품을 선택해 주세요.");
            }
        }
    }
}
