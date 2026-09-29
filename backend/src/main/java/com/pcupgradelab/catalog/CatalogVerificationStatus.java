package com.pcupgradelab.catalog;

/** 공용 제품의 제원 확인 상태. 검증되지 않은 제품은 처음에 UNVERIFIED로 생성한다. */
public enum CatalogVerificationStatus {
    UNVERIFIED, PARTIAL, CORE_VERIFIED
}
