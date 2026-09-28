package com.pcupgradelab.pc;

import com.pcupgradelab.common.ApiException;
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

    public PcService(PcConfigurationRepository repository) {
        this.repository = repository;
    }

    /**
     * 신규 PC 구성을 등록한다.
     */
    @Transactional
    public Detail create(Request request) {
        var pc = new PcConfiguration(DEFAULT_OWNER_KEY, request.name(), request.parts());
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
            pc.update(request.name(), request.parts());
            repository.flush();
            return Detail.from(pc);
        } catch (OptimisticLockingFailureException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "다른 요청에 의해 이미 변경되었습니다. 최신 정보를 다시 확인해 주세요.");
        }
    }
}
