// 프런트와 백엔드가 공유하는 JSON 규격. Java의 PartInput/ScanDtos와 함께 관리한다.
export type PartType = 'CPU' | 'GPU' | 'MOTHERBOARD' | 'RAM' | 'STORAGE' | 'PSU' | 'CASE' | 'COOLER' | 'MONITOR'
/** 부품 한 항목. 같은 이름의 RAM·저장장치가 여러 항목으로 존재할 수 있다. */
export type PartInput = {
  type: PartType
  displayName: string
  // 자동 검출 원문은 사용자가 표시 이름이나 제원을 보완해도 보존한다.
  rawName: string | null
  // 장치 개수이며 용량과 다르다. specs.capacityBytes는 장치 한 개의 용량(bytes)이다.
  quantity: number
  source: 'AUTO' | 'MANUAL'
  // 카탈로그 미연결: ID는 null, 상태는 UNMATCHED. 이름만 보고 연결 완료로 판단하지 않는다.
  catalogProductId: string | null
  matchStatus: 'UNMATCHED' | 'MATCHED'
  // 종류마다 다른 제원을 담는다. 미확인 값은 null이며, 중첩 객체·배열은 사용하지 않는다.
  specs: Record<string, string | number | boolean | null>
}
export type ScanWarning = { scope: string; code: string; message: string }
/** 자동 수집 결과. 서버에 도착한 결과이며, 사용자의 PC 구성으로 DB에 저장됐다는 뜻은 아니다. */
export type ScanResult = {
  schemaVersion: 1
  collectorVersion: string
  collectedAt: string
  parts: PartInput[]
  warnings: ScanWarning[]
}
// 서버가 관리하는 검사 상태. IDLE/PREPARING/CONNECTION_ERROR는 usePcScan의 화면 전용 상태다.
export type ScanStatus = 'CREATED' | 'RUNNING' | 'COMPLETED' | 'COMPLETED_WITH_WARNINGS' | 'FAILED' | 'EXPIRED'
// readToken은 브라우저 조회용, launchUri 안의 token은 수집기 쓰기용이다. 로그·localStorage에 저장하지 않는다.
export type ScanSession = { sessionId: string; readToken: string; launchUri: string; expiresAt: string }
/** 반복 조회 응답. 상태에 따라 result/failure가 null일 수 있으므로 화면에서 존재 여부를 확인한다. */
export type ScanView = {
  sessionId: string
  status: ScanStatus
  expiresAt: string
  result: ScanResult | null
  failure: { code: string; message: string } | null
}
