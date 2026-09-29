package com.pcupgradelab.catalog;

/** 기준가격 확인 상태. 가격 미확인은 금액 0원이 아니라 UNCONFIRMED와 NULL로 표현한다. */
public enum CatalogPriceStatus {
    UNCONFIRMED, INSUFFICIENT_HISTORY, CONFIRMED
}
