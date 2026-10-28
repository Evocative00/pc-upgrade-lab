import type { PartType } from '../pc-scan/types.ts'

type PartTypeInfo = {
  type: PartType
  label: string
  // 입력란 머리에 붙이는 짧은 영문 표시
  code: string
  // 화면에서 '+ 추가'로 여러 항목을 입력할 수 있는 종류
  multiple: boolean
  // 용량 입력 단위. specs.capacityBytes는 장치 1개당 bytes다.
  capacityUnit: { label: string; bytes: number } | null
}

const GIB = 1024 ** 3
const GB = 1000 ** 3

// 공통 규격의 9개 부품 종류. 순서가 곧 화면 표시 순서다.
export const PART_TYPES = [
  { type: 'CPU', code: 'CPU', label: 'CPU', multiple: false, capacityUnit: null },
  { type: 'COOLER', code: 'CLR', label: 'CPU 쿨러', multiple: false, capacityUnit: null },
  { type: 'MOTHERBOARD', code: 'MB', label: '메인보드', multiple: false, capacityUnit: null },
  { type: 'RAM', code: 'RAM', label: 'RAM', multiple: true, capacityUnit: { label: 'GiB', bytes: GIB } },
  { type: 'GPU', code: 'GPU', label: '그래픽카드', multiple: false, capacityUnit: null },
  { type: 'STORAGE', code: 'STG', label: '저장장치', multiple: true, capacityUnit: { label: 'GB', bytes: GB } },
  { type: 'PSU', code: 'PSU', label: '파워', multiple: false, capacityUnit: null },
  { type: 'CASE', code: 'CASE', label: '케이스', multiple: false, capacityUnit: null },
  { type: 'MONITOR', code: 'MON', label: '모니터', multiple: false, capacityUnit: null },
] as const satisfies readonly PartTypeInfo[]

export function getPartTypeInfo(type: PartType): PartTypeInfo {
  const info = PART_TYPES.find((item) => item.type === type)

  if (!info) {
    throw new Error(`알 수 없는 부품 종류: ${type}`)
  }

  return info
}
