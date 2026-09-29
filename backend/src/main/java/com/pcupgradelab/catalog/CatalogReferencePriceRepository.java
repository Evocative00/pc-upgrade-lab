package com.pcupgradelab.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

/** 부품 ID를 그대로 키로 사용하는 현재 기준가격 저장 공간. */
public interface CatalogReferencePriceRepository extends JpaRepository<CatalogReferencePrice, String> {
}
