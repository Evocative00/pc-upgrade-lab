import type { CatalogProduct, CatalogSpecification } from '../catalog/catalogTypes.ts'
import type { PartType } from '../pc-scan/types.ts'
import type { PartDraft } from '../pc/types.ts'

// 구성 화면의 자리. SSD·HDD는 화면 위치만 다르고 부품 종류는 둘 다 STORAGE다.
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

export function slotInfo(id: SlotId) {
  return SLOTS.find((slot) => slot.id === id)!
}

// 이름이 있는 입력 항목을 그림의 자리에 배치한다. 종류마다 첫 항목이 그 자리에 들어간다.
// 저장장치는 화면에서 고른 자리(visualSlot)를 먼저 따르고, 나머지는 입력 순서대로 SSD → HDD에 넣는다.
// ponytail: Windows 보고값(Win32_DiskDrive)으로는 SSD/HDD를 구분할 수 없어 순서로 나눈다. 저장장치 제원이 카탈로그에 생기면 그걸로 바꾼다.
export function assignSlots(drafts: PartDraft[]): Partial<Record<SlotId, PartDraft>> {
  const slots: Partial<Record<SlotId, PartDraft>> = {}
  const named = drafts.filter((draft) => draft.displayName.trim() !== '')
  for (const draft of named) {
    if (draft.type !== 'STORAGE') slots[draft.type] ??= draft
    else if (draft.visualSlot) slots[draft.visualSlot] ??= draft
  }
  for (const draft of named.filter((item) => item.type === 'STORAGE')) {
    if (Object.values(slots).includes(draft)) continue
    if (!slots.SSD) slots.SSD = draft
    else if (!slots.HDD) slots.HDD = draft
  }
  return slots
}

// 입력 항목이 그림의 어느 자리인지. 자리에 들어가지 못한 저장장치는 SSD 자리 검색으로 연다.
export function slotOfDraft(drafts: PartDraft[], draft: PartDraft): SlotId {
  const placed = Object.entries(assignSlots(drafts)).find(([, item]) => item.key === draft.key)
  return placed ? placed[0] as SlotId : draft.type === 'STORAGE' ? 'SSD' : draft.type
}

// 케이스 그림에 넣을 두 줄 라벨. 예: 'Ryzen 5 5500GT' → ['RYZEN 5', '5500GT']
export function chipLabel(modelName: string): [string, string] {
  const words = modelName.trim().split(/\s+/)
  if (words.length < 2) return [modelName.toUpperCase(), '']
  return [words.slice(0, -1).join(' ').toUpperCase(), words[words.length - 1]]
}

type Specs = CatalogSpecification | PartDraft['specs']

// 메모리는 모듈 1개당 용량과 세대만 쓴다. 카탈로그 값이 없으면 입력한 1개당 용량을 쓴다.
// 모르면 '미확인'으로 두고 0GB로 쓰지 않는다.
export function ramLabel(specs: Specs | null): [string, string] {
  const bytes = specs?.moduleCapacityBytes ?? specs?.capacityBytes
  const type = specs?.memoryType
  return [
    typeof bytes === 'number' && bytes > 0 ? `${Math.round(bytes / 1024 ** 3)}GB` : '용량 미확인',
    typeof type === 'string' ? type : '',
  ]
}

// 채울 메모리 슬롯 수(최대 2). 장착 수량 합계와 제품 구성 모듈 수 중 큰 값을 쓴다.
// 2개짜리 제품을 고르면 두 슬롯을 채우지만, 저장되는 장착 수량은 사용자가 입력한 값 그대로다.
export function ramSlotCount(quantities: number[], kitModules: unknown): number {
  const installed = quantities.filter((value) => Number.isInteger(value) && value > 0)
    .reduce((sum, value) => sum + value, 0)
  const kit = typeof kitModules === 'number' ? kitModules : 0
  return Math.min(2, Math.max(installed, kit))
}

// 확정 가격만 더한다. 미확정·미연결(null)은 0원으로 취급하지 않고 개수로 따로 알린다.
export function priceTotal(prices: (CatalogProduct['referencePrice'] | null)[]): { confirmedKrw: number; unconfirmed: number } {
  let confirmedKrw = 0
  let unconfirmed = 0
  for (const price of prices) {
    if (price?.status === 'CONFIRMED' && price.amountKrw !== null) confirmedKrw += price.amountKrw
    else unconfirmed += 1
  }
  return { confirmedKrw, unconfirmed }
}
