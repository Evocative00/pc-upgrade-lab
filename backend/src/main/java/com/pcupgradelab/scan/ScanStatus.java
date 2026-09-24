package com.pcupgradelab.scan;

/**
 * CREATED(실행 대기) → RUNNING(수집 중) → COMPLETED(완료) 또는 COMPLETED_WITH_WARNINGS(일부 보완 필요).
 * 수집 실패는 FAILED, 제한 시간 내 완료하지 못하면 EXPIRED. 상태 변경 규칙은 ScanService가 관리한다.
 */
public enum ScanStatus { CREATED, RUNNING, COMPLETED, COMPLETED_WITH_WARNINGS, FAILED, EXPIRED }
