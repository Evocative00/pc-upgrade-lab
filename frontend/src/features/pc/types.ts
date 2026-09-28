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

// 입력 폼 전용 부품 항목. key와 edited는 화면에서만 쓰고 저장할 때 뺀다.
export type PartDraft = PartInput & {
  // React 목록 key
  key: string
  // 자동 인식 항목을 사용자가 고쳤는지. 다시 불러와 덮어쓸 때 확인을 받는 기준이다.
  edited: boolean
}
