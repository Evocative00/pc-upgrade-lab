package com.pcupgradelab.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

/** 공용 제품 ID를 키로 사용하는 메인보드 제원 저장 공간. */
public interface MotherboardSpecRepository extends JpaRepository<MotherboardSpec, String> {
}
