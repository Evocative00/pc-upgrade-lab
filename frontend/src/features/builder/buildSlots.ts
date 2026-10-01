import type { CatalogProduct, CatalogSpecification } from '../catalog/catalogTypes.ts'
import type { PartType } from '../pc-scan/types.ts'

// 구성 화면의 자리. SSD·HDD는 화면 위치만 다르고 카탈로그 종류는 둘 다 STORAGE다.
export type SlotId = 'CPU' | 'COOLER' | 'MOTHERBOARD' | 'RAM' | 'GPU' | 'SSD' | 'HDD' | 'PSU' | 'CASE' | 'MONITOR'

export const SLOTS: readonly { id: SlotId; type: PartType; label: string }[] = [
  { id: 'CPU', type: 'CPU', label: 'CPU' },
  { id: 'COOLER', type: 'COOLER', label: '쿨러' },
  { id: 'MOTHERBOARD', type: 'MOTHERBOARD', label: '메인보드' },
  { id: 'RAM', type: 'RAM', label: '메모리' },
  { id: 'GPU', type: 'GPU', label: '그래픽카드' },
  { id: 'SSD', type: 'STORAGE', label: 'SSD' },
  { id: 'HDD', type: 'STORAGE', label: 'HDD' },
  { id: 'PSU', type: 'PSU', label: '파워' },
  { id: 'CASE', type: 'CASE', label: '케이스' },
  { id: 'MONITOR', type: 'MONITOR', label: '모니터' },
]

// specs가 null이면 상세 조회 전이거나 실패한 상태다. 이름만으로 제원을 추정하지 않는다.
export type BuildPart = { product: CatalogProduct; specs: CatalogSpecification | null }
export type Build = Partial<Record<SlotId, BuildPart>>

// 케이스 그림에 넣을 두 줄 라벨. 예: 'Ryzen 5 5500GT' → ['RYZEN 5', '5500GT']
export function chipLabel(modelName: string): [string, string] {
  const words = modelName.trim().split(/\s+/)
  if (words.length < 2) return [modelName.toUpperCase(), '']
  return [words.slice(0, -1).join(' ').toUpperCase(), words[words.length - 1]]
}

// 메모리는 모듈 1개당 용량과 세대만 쓴다. 모르면 '미확인'으로 두고 0GB로 쓰지 않는다.
export function ramLabel(specs: CatalogSpecification | null): [string, string] {
  const bytes = specs?.moduleCapacityBytes
  const type = specs?.memoryType
  return [
    typeof bytes === 'number' ? `${Math.round(bytes / 1024 ** 3)}GB` : '용량 미확인',
    typeof type === 'string' ? type : '',
  ]
}

// 제품 구성 모듈이 1개면 슬롯 하나, 2개 이상이면 두 슬롯을 채운다. 모르면 하나만 채운다.
export function ramSlotCount(specs: CatalogSpecification | null): 1 | 2 {
  return typeof specs?.moduleCount === 'number' && specs.moduleCount >= 2 ? 2 : 1
}

// 확정 가격만 더한다. 미확정 가격은 0원으로 취급하지 않고 개수로 따로 알린다.
export function priceTotal(build: Build): { confirmedKrw: number; unconfirmed: number } {
  let confirmedKrw = 0
  let unconfirmed = 0
  for (const part of Object.values(build)) {
    if (!part) continue
    const price = part.product.referencePrice
    if (price.status === 'CONFIRMED' && price.amountKrw !== null) confirmedKrw += price.amountKrw
    else unconfirmed += 1
  }
  return { confirmedKrw, unconfirmed }
}
