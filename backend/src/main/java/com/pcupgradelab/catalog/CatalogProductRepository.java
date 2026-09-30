package com.pcupgradelab.catalog;

import com.pcupgradelab.pc.PartType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 공용 부품의 기본 정보 저장·조회. 실제 등록은 가격 행도 만드는 서비스를 사용한다. */
public interface CatalogProductRepository extends JpaRepository<CatalogProduct, String> {
    // 검토용 검색이므로 비활성·미검증 제품도 포함한다. 공개 카탈로그의 노출 조건으로 재사용하지 않는다.
    // pattern은 서비스에서 SQL LIKE 특수문자를 이스케이프한 뒤 바인딩한다.
    @Query("""
            select p from CatalogProduct p
            where (:type is null or p.type = :type)
              and (lower(concat(concat(p.manufacturer, ' '), p.modelName)) like :pattern escape '!'
                   or lower(p.partNumber) like :pattern escape '!')
            """)
    Page<CatalogProduct> searchForReview(@Param("type") PartType type,
                                         @Param("pattern") String pattern,
                                         Pageable pageable);
}
