package com.pcupgradelab.pc;

import com.pcupgradelab.auth.CurrentUser;
import com.pcupgradelab.catalog.CatalogProduct;
import com.pcupgradelab.catalog.CatalogProductRepository;
import com.pcupgradelab.common.ApiException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.pcupgradelab.pc.PcDtos.*;

/**
 * PC 등록·조회·수정·삭제 비즈니스 로직.
 * 엔티티를 직접 노출하지 않고 DTO 변환을 수행하며, 트랜잭션 범위 안에서 처리한다.
 * 모든 요청은 로그인 회원(CurrentUser)의 PC로 한정한다. 다른 회원의 PC는 존재하지 않는 것처럼 404로 응답한다.
 */
@Service
@Transactional(readOnly = true)
public class PcService {
    private static final String NAME_UNIQUE_CONSTRAINT = "uk_pc_configuration_user_name";

    private final PcConfigurationRepository repository;
    private final CatalogProductRepository catalogProducts;
    private final CurrentUser currentUser;

    public PcService(PcConfigurationRepository repository, CatalogProductRepository catalogProducts,
                     CurrentUser currentUser) {
        this.repository = repository;
        this.catalogProducts = catalogProducts;
        this.currentUser = currentUser;
    }

    /**
     * 신규 PC 구성을 등록한다.
     */
    @Transactional
    public Detail create(Request request) {
        var userId = requireUserId();
        var pc = new PcConfiguration(userId, request.name(), request.parts());
        validateCatalogLinks(request.parts());
        if (repository.existsByUserIdAndNameNormalized(userId, PcConfiguration.normalizeName(request.name()))) {
            throw duplicateName();
        }
        try {
            var saved = repository.saveAndFlush(pc);
            return Detail.from(saved);
        } catch (DataIntegrityViolationException e) {
            // 위 검사 이후 같은 이름이 동시에 저장된 경우도 DB 제약으로 막고 같은 응답을 준다.
            throw translateIntegrityViolation(e);
        }
    }

    /**
     * PC 상세 정보를 조회한다. (부품 목록 포함)
     */
    public Detail findById(Long id) {
        return Detail.from(findOwned(id, requireUserId()));
    }

    /**
     * 저장된 PC 목록을 페이징하여 요약 정보로 조회한다.
     * 정렬 기준은 docs/week1-contract.md 규격에 따라 updatedAt DESC, id DESC이다.
     */
    public PageResponse<Summary> findAll(int page, int size) {
        var userId = requireUserId();
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
        var result = repository.findAllByUserId(userId, pageable);
        var items = result.getContent().stream().map(Summary::from).toList();
        return new PageResponse<>(items, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    /**
     * PC 이름과 부품 목록 전체를 수정한다.
     * 기존 PC ID를 유지하고, orphanRemoval을 통해 이전 부품 행을 깔끔하게 교체한다.
     */
    @Transactional
    public Detail update(Long id, Request request) {
        var userId = requireUserId();
        try {
            var pc = findOwned(id, userId);
            // 기존 이름과 부품 목록을 바꾸기 전에 모든 연결과 이름 중복을 확인한다. 자기 PC는 중복 대상에서 제외한다.
            validateCatalogLinks(request.parts());
            if (repository.existsByUserIdAndNameNormalizedAndIdNot(userId, PcConfiguration.normalizeName(request.name()), id)) {
                throw duplicateName();
            }
            pc.update(request.name(), request.parts());
            repository.flush();
            return Detail.from(pc);
        } catch (OptimisticLockingFailureException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "다른 요청에 의해 이미 변경되었습니다. 최신 정보를 다시 확인해 주세요.");
        } catch (DataIntegrityViolationException e) {
            throw translateIntegrityViolation(e);
        }
    }

    /**
     * 저장된 PC와 부품을 삭제한다. 다른 회원의 PC나 이미 삭제된 PC는 404로 응답한다.
     */
    @Transactional
    public void delete(Long id) {
        // 부품 행은 PcConfiguration의 cascade·orphanRemoval로 함께 삭제된다.
        repository.delete(findOwned(id, requireUserId()));
    }

    private Long requireUserId() {
        return currentUser.id().orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다."));
    }

    private PcConfiguration findOwned(Long id, Long userId) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PC_NOT_FOUND", "PC를 찾을 수 없습니다."));
    }

    private static ApiException duplicateName() {
        return new ApiException(HttpStatus.CONFLICT, "PC_NAME_DUPLICATE",
                "같은 이름의 PC가 이미 있습니다. 다른 이름을 입력해 주세요.");
    }

    private static RuntimeException translateIntegrityViolation(DataIntegrityViolationException e) {
        // MySQL과 H2 모두 위반한 제약 이름을 메시지에 포함한다. 다른 제약 위반은 그대로 전달한다.
        var message = e.getMostSpecificCause().getMessage();
        if (message != null && message.toLowerCase(Locale.ROOT).contains(NAME_UNIQUE_CONSTRAINT)) {
            return duplicateName();
        }
        return e;
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
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PART_ID",
                        (i + 1) + "번째 부품의 연결 제품이 없습니다. 부품을 다시 검색하거나 연결을 해제해 주세요.");
            }
            if (catalogType != part.type()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PART_CATEGORY_MISMATCH",
                        (i + 1) + "번째 부품과 연결 제품의 종류가 다릅니다. 같은 종류의 부품을 선택해 주세요.");
            }
        }
    }
}
