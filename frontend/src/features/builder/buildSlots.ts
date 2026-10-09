import type { CatalogDetail, CatalogModel, CatalogProduct, CatalogSpecification } from '../catalog/catalogTypes.ts'
import type { PartType } from '../pc-scan/types.ts'
import { createManualDraft, getPartStatus, linkCatalog, linkCatalogModel, withEmptyRows } from '../pc/partDraft.ts'
import type { PartDraft } from '../pc/types.ts'

// SSD·HDD는 기존 화면 위치 ID다. 저장장치의 실제 종류를 뜻하지 않는다.
export type SlotId = 'CPU' | 'COOLER' | 'MOTHERBOARD' | 'RAM' | 'GPU' | 'SSD' | 'HDD' | 'PSU' | 'CASE' | 'MONITOR'
export type PickTarget = { slot: SlotId; draftKey: string | null }

export const SLOTS: readonly { id: SlotId; type: PartType; label: string }[] = [
  { id: 'CPU', type: 'CPU', label: 'CPU' },
  { id: 'COOLER', type: 'COOLER', label: '쿨러' },
  { id: 'MOTHERBOARD', type: 'MOTHERBOARD', label: '메인보드' },
  { id: 'RAM', type: 'RAM', label: '메모리' },
  { id: 'GPU', type: 'GPU', label: '그래픽카드' },
  { id: 'SSD', type: 'STORAGE', label: '저장장치 1' },
  { id: 'HDD', type: 'STORAGE', label: '저장장치 2' },
  { id: 'PSU', type: 'PSU', label: '파워' },
  { id: 'CASE', type: 'CASE', label: '케이스' },
  { id: 'MONITOR', type: 'MONITOR', label: '모니터' },
]

export function slotInfo(id: SlotId) {
  return SLOTS.find((slot) => slot.id === id)!
}

// 이름이 있는 입력 항목을 그림의 자리에 배치한다. 종류마다 첫 항목이 그 자리에 들어간다.
// 저장장치는 화면에서 고른 자리를 먼저 따르고, 나머지는 입력 순서로 배치한다.
// 현재 제원으로 SSD/HDD 종류를 확정하지 않으므로 위치만 표시한다.
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

// 자리에 들어가지 못한 저장장치도 STORAGE 카탈로그에서 검색한다.
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

// 사용자 PC의 1개당 용량을 우선한다. 미확인일 때만 카탈로그 제원으로 보완한다.
// 모르면 '미확인'으로 두고 0GB로 쓰지 않는다.
export function ramLabel(specs: Specs | null, catalog: Specs | null = null): [string, string] {
  const bytes = specs?.capacityBytes ?? specs?.moduleCapacityBytes ?? catalog?.moduleCapacityBytes
  const type = specs?.memoryType ?? catalog?.memoryType
  return [
    typeof bytes === 'number' && Number.isFinite(bytes) && bytes > 0
      ? `${Number((bytes / 1024 ** 3).toFixed(2))}GB` : '용량 미확인',
    typeof type === 'string' ? type : '',
  ]
}

const validQuantity = (quantity: number) => Number.isInteger(quantity) && quantity >= 1 && quantity <= 64

export type RamModuleView = { key: string; name: string; label: [string, string] }

// 그림은 첫 두 모듈만 그린다. 실제 장착 수량과 메인보드 슬롯 수는 별개다.
export function ramModuleViews(drafts: PartDraft[], specificationOf: (draft: PartDraft) => Specs | null) {
  const modules: RamModuleView[] = []
  let installedCount = 0
  for (const draft of drafts) {
    if (draft.type !== 'RAM' || getPartStatus(draft) === 'empty' || !validQuantity(draft.quantity)) continue
    installedCount += draft.quantity
    for (let index = 0; index < draft.quantity && modules.length < 2; index += 1) {
      modules.push({ key: `${draft.key}:${index}`, name: draft.displayName,
        label: ramLabel(draft.specs, specificationOf(draft)) })
    }
  }
  return { modules, installedCount }
}

export type PriceLine = { draft: PartDraft; detail: CatalogDetail | null }

// 일반 부품은 장치 수, RAM은 동일 제품의 실제 모듈 합계를 판매 묶음 수로 바꿔 계산한다.
// 묶음이 딱 맞지 않거나 단위가 미확인이면 임의로 나누거나 올림하지 않는다.
export function priceTotal(lines: PriceLine[]): { currentKrw: number; pricedItems: number; unpriced: number; quantityNeedsCheck: number } {
  let currentKrw = 0
  let pricedItems = 0
  let unpriced = 0
  let quantityNeedsCheck = 0
  const ramGroups = new Map<string, { amount: number; kit: unknown; quantity: number; items: number; valid: boolean }>()
  function addPrice(amount: number, items: number) {
    if (Number.isSafeInteger(amount) && Number.isSafeInteger(currentKrw + amount)) {
      currentKrw += amount
      pricedItems += items
    } else quantityNeedsCheck += 1
  }
  for (const { draft, detail } of lines) {
    const price = detail?.product.currentPrice
    if (!detail || detail.product.type !== draft.type || !price ||
      !Number.isSafeInteger(price.amountKrw) || price.amountKrw <= 0) {
      unpriced += 1
      continue
    }
    if (draft.type !== 'RAM') {
      if (validQuantity(draft.quantity)) addPrice(price.amountKrw * draft.quantity, 1)
      else quantityNeedsCheck += 1
      continue
    }
    const group = ramGroups.get(detail.product.id) ?? {
      amount: price.amountKrw, kit: detail.specification.moduleCount, quantity: 0, items: 0, valid: true,
    }
    group.quantity += draft.quantity
    group.items += 1
    group.valid &&= validQuantity(draft.quantity) && group.amount === price.amountKrw &&
      group.kit === detail.specification.moduleCount
    ramGroups.set(detail.product.id, group)
  }
  for (const { amount, kit, quantity, items, valid } of ramGroups.values()) {
    if (valid && typeof kit === 'number' && Number.isInteger(kit) && kit > 0 && quantity % kit === 0) {
      addPrice(amount * (quantity / kit), items)
    } else quantityNeedsCheck += 1
  }
  return { currentKrw, pricedItems, unpriced, quantityNeedsCheck }
}

// 최신 입력을 기준으로 연결한다. 검색 중 수량을 고쳤거나 항목을 지워도 옛 상태를 복원하지 않는다.
export function applyCatalogSelection(drafts: PartDraft[], target: PickTarget, product: CatalogProduct): PartDraft[] {
  return applySelection(drafts, target, product.type, (base) => linkCatalog(base, product))
}

export function applyCatalogModelSelection(drafts: PartDraft[], target: PickTarget, model: CatalogModel): PartDraft[] {
  return applySelection(drafts, target, model.type, (base) => linkCatalogModel(base, model))
}

function applySelection(drafts: PartDraft[], target: PickTarget, type: PartType,
  link: (base: PartDraft) => PartDraft): PartDraft[] {
  if (slotInfo(target.slot).type !== type) return drafts
  const key = target.draftKey ?? assignSlots(drafts)[target.slot]?.key
  const existing = drafts.find((draft) => draft.key === key && draft.type === type)
  if (target.draftKey !== null && !existing) return drafts
  const empty = drafts.find((draft) => draft.type === type && getPartStatus(draft) === 'empty')
  const base = existing ?? empty ?? createManualDraft(type)
  const visualSlot = target.draftKey === null && (target.slot === 'SSD' || target.slot === 'HDD')
    ? target.slot : base.visualSlot
  const linked = { ...link(base), visualSlot }
  return drafts.some((draft) => draft.key === base.key)
    ? drafts.map((draft) => draft.key === base.key ? linked : draft)
    : withEmptyRows([...drafts, linked])
}
