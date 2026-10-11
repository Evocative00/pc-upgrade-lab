package com.pcupgradelab.catalog.identity;

/** 재고·가격·제원 검증 상태와 독립인 사용 목적. 기존 자료의 목적을 추정하지 않는다. */
public enum CatalogRole {
    UNASSIGNED, INSTALLED_PC_REFERENCE, PURCHASE_CANDIDATE, BOTH
}
