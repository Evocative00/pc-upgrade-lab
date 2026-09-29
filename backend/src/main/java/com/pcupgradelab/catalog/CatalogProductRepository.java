package com.pcupgradelab.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

/** 공용 부품의 기본 정보 저장·조회. 실제 등록은 가격 행도 만드는 서비스를 사용한다. */
public interface CatalogProductRepository extends JpaRepository<CatalogProduct, String> {
}
