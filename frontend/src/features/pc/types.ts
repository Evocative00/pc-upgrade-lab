import type { PartInput } from '../pc-scan/types.ts'

// PC API 요청·응답 형식. 기준: docs/week1-contract.md
// 부품 한 항목은 공통 타입 PartInput을 그대로 사용한다.
export type PcRequest = {
  name: string
  parts: PartInput[]
}

export type PcDetail = PcRequest & {
  id: number
  createdAt: string
  updatedAt: string
}

export type PcSummary = {
  id: number
  name: string
  createdAt: string
  updatedAt: string
}

export type PcPage = {
  items: PcSummary[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

// 입력 폼 전용 부품 항목. 아래 필드는 화면에서만 쓰고 API 요청에서는 뺀다.
export type PartDraft = PartInput & {
  // React 목록 key
  key: string
  // 이번 입력 화면에서 사용자가 고쳤는지. AUTO 덮어쓰기 확인과 보완 표시에 쓴다.
  edited: boolean
  // 저장소에서 불러왔는지. 과거에 보완했는지 알 수 없으므로 AUTO 교체 전에 확인한다.
  persisted: boolean
  // 입력 중인 용량 문자열. 바이트 변환·반올림 때문에 사용자가 쓴 값이 사라지지 않게 한다.
  capacityText?: string
}
