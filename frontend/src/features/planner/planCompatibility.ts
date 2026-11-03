import { ramDraftsForCheck, type CompatibilityResult, type CompatibilityStatus } from '../builder/compatibility.ts'
import { useCompatibility, type CompatibilityState } from '../builder/useCompatibility.ts'
import type { PartInput, PartType } from '../pc-scan/types.ts'
import type { PartDraft } from '../pc/types.ts'
import type { UpgradePlan } from './upgradeRecommendation.ts'

/**
 * 추천 플랜의 최종 구성을 기존 호환성 검사 API로 검사하고, 결과를 최종 구성 목록의 부품 행마다 붙일 수 있게 정리한다.
 * 부품 목록은 추천 플랜의 '최종 구성' 한 곳에서만 보여 준다.
 */
export const COMPAT_TEXT: Record<CompatibilityStatus | 'UNCHECKED', string> = {
  COMPATIBLE: '호환', NEEDS_CHECK: '확인 필요', INCOMPATIBLE: '비호환', UNCHECKED: '검사 제외',
}
export const COMPAT_CLASS: Record<CompatibilityStatus | 'UNCHECKED', string> = {
  COMPATIBLE: 'pass', NEEDS_CHECK: 'unknown', INCOMPATIBLE: 'fail', UNCHECKED: 'unknown',
}
// 호환성 검사 API가 보는 부품. 저장장치·쿨러·모니터는 검사하지 않는다.
const CHECKED_TYPES: PartType[] = ['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'PSU', 'CASE']
const SINGLE_FIELDS: Record<string, PartType> = {
  cpuProductId: 'CPU', motherboardProductId: 'MOTHERBOARD', gpuProductId: 'GPU', psuProductId: 'PSU', caseProductId: 'CASE',
}
const RANK: Record<CompatibilityStatus, number> = { COMPATIBLE: 0, NEEDS_CHECK: 1, INCOMPATIBLE: 2 }

// 최종 구성 부품 하나의 검사 상태. 검사 대상이 아니거나 검사 중이면 null.
export type PartCompat = { status: CompatibilityStatus | 'UNCHECKED'; messages: string[] } | null

export type PlanCompatibility = {
  state: CompatibilityState
  result: CompatibilityResult | null
  // 최종 구성(applyPlan 결과) 순서의 부품별 상태
  byIndex: PartCompat[]
  unlinked: PartType[]
  missing: PartType[]
  // 비호환 판정을 받은 교체 제품의 카탈로그 상품 ID
  badReplacedIds: string[]
}

// 검사 항목의 affectedFields(요청 필드명)를 최종 구성 부품 위치로 바꾼다. 빌더의 요청 규칙과 같다.
function fieldIndexes(field: string, drafts: PartDraft[]): number[] {
  const type = SINGLE_FIELDS[field]
  if (type) {
    const rows = drafts.filter((draft) => draft.type === type)
    return rows.length === 1 && rows[0].quantity === 1 ? [drafts.indexOf(rows[0])] : []
  }
  const rams = ramDraftsForCheck(drafts)
  if (field === 'ram') return rams.map((draft) => drafts.indexOf(draft))
  const index = /^ram\[(\d+)\]/.exec(field)?.[1]
  return index !== undefined && rams[Number(index)] ? [drafts.indexOf(rams[Number(index)])] : []
}

export function usePlanCompatibility(finalParts: PartInput[], plan: UpgradePlan): PlanCompatibility {
  const drafts: PartDraft[] = finalParts.map((part, index) => ({ ...part, key: `${part.type}-${index}`, edited: false, persisted: false }))
  const state = useCompatibility(drafts, true)
  const result = state.status === 'done' ? state.result : null

  const found = drafts.map(() => ({ rank: 0, messages: [] as string[] }))
  for (const check of result?.checks ?? []) {
    if (check.status === 'COMPATIBLE') continue
    for (const index of new Set(check.affectedFields.flatMap((field) => fieldIndexes(field, drafts)))) {
      found[index].rank = Math.max(found[index].rank, RANK[check.status])
      if (!found[index].messages.includes(check.message)) found[index].messages.push(check.message)
    }
  }
  const byIndex: PartCompat[] = drafts.map((draft, index) => {
    if (!CHECKED_TYPES.includes(draft.type)) return null
    if (!draft.catalogProductId) return { status: 'UNCHECKED', messages: [] }
    if (!result) return null
    const status = (Object.keys(RANK) as CompatibilityStatus[]).find((key) => RANK[key] === found[index].rank) ?? 'COMPATIBLE'
    return { status, messages: found[index].messages }
  })
  const replaced = new Set(plan.items.flatMap((item) => item.choice ? [item.choice.id] : []))
  const badReplacedIds = [...new Set(drafts.filter((draft, index) => draft.catalogProductId && replaced.has(draft.catalogProductId)
    && byIndex[index]?.status === 'INCOMPATIBLE').map((draft) => draft.catalogProductId!))]
  return {
    state, result, byIndex, badReplacedIds,
    unlinked: CHECKED_TYPES.filter((type) => drafts.some((draft) => draft.type === type && !draft.catalogProductId)),
    missing: CHECKED_TYPES.filter((type) => !drafts.some((draft) => draft.type === type)),
  }
}
