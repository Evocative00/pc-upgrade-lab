package com.pcupgradelab.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 출처 생성·조회. 동일 외부 ID의 중복은 V4의 고유 제약도 함께 방지한다. */
public interface CatalogProductSourceRepository extends JpaRepository<CatalogProductSource, Long> {
    List<CatalogProductSource> findAllByProduct_IdOrderByIdAsc(String productId);

    boolean existsBySourceNameAndExternalId(CatalogSourceName sourceName, String externalId);

    Optional<CatalogProductSource> findBySourceNameAndExternalId(CatalogSourceName sourceName, String externalId);
}
