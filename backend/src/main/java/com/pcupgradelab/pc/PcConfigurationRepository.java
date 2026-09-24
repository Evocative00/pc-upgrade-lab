package com.pcupgradelab.pc;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * PC 저장·조회 창구. Spring Data JPA가 인터페이스의 구현을 만들어 주므로 직접 SQL을 작성하지 않는다.
 * 서비스는 서버가 정한 ownerKey를 전달하고, 엔티티에서 응답 DTO를 만드는 작업까지 트랜잭션 안에서 처리한다.
 */
public interface PcConfigurationRepository extends JpaRepository<PcConfiguration, Long> {
    // 상세 조회: PC ID와 소유자를 함께 확인하며, 응답에 필요한 부품 목록도 함께 가져온다.
    @EntityGraph(attributePaths = "parts")
    Optional<PcConfiguration> findByIdAndOwnerKey(Long id, String ownerKey);

    // 목록 조회: 요약 정보만 사용한다. 부품 컬렉션까지 한 번에 가져오는 조회를 페이지 처리와 섞지 않는다.
    // 페이지 번호·크기·정렬은 API 서비스가 Pageable로 전달한다.
    Page<PcConfiguration> findAllByOwnerKey(String ownerKey, Pageable pageable);
}
