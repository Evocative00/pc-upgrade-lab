import {
  buildCompatibilityRequest, localCompatibilityChecks, ramDraftsForCheck,
  type CompatibilityCheck, type CompatibilityResult, type CompatibilityStatus,
} from '../builder/compatibility.ts'
import { useCompatibility, type CompatibilityState } from '../builder/useCompatibility.ts'
import type { PartInput, PartType } from '../pc-scan/types.ts'
import { getPartStatus } from '../pc/partDraft.ts'
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

// 서버의 ram[i]는 실제 요청에 보낸 RAM 순서다. 단일 부품도 요청에 포함한 행만 연결한다.
function requestedFieldIndexes(drafts: PartDraft[]): (field: string) => number[] {
  const request = buildCompatibilityRequest(drafts)
  const singles = new Map(Object.entries(SINGLE_FIELDS).map(([field, type]) => {
    const rows = drafts.filter((draft) => draft.type === type && getPartStatus(draft) !== 'empty')
    const id = request?.[field as 'cpuProductId' | 'motherboardProductId' | 'gpuProductId' | 'psuProductId' | 'caseProductId']
    return [field, id && rows.length === 1 && rows[0].quantity === 1 ? [drafts.indexOf(rows[0])] : []] as const
  }))
  const rams = request?.ram.length ? ramDraftsForCheck(drafts) : []
  return (field) => {
    if (singles.has(field)) return singles.get(field)!
    if (field === 'ram') return rams.map((draft) => drafts.indexOf(draft))
    const index = /^ram\[(\d+)\]/.exec(field)?.[1]
    return index !== undefined && rams[Number(index)] ? [drafts.indexOf(rams[Number(index)])] : []
  }
}

// 로컬 검증은 서버 요청에서 제외한 행에도 적용한다. 수량 오류 RAM은 해당 행만 가리킨다.
function localCheckIndexes(check: CompatibilityCheck, drafts: PartDraft[]): number[] {
  if (check.code.startsWith('RAM_QUANTITY_')) {
    const key = check.code.slice('RAM_QUANTITY_'.length)
    return drafts.flatMap((draft, index) => draft.key === key ? [index] : [])
  }
  return check.affectedFields.flatMap((field) => {
    const type = SINGLE_FIELDS[field] ?? (field === 'ram' ? 'RAM' : null)
    return type ? drafts.flatMap((draft, index) => draft.type === type && getPartStatus(draft) !== 'empty' ? [index] : []) : []
  })
}

/** 요청에 포함한 행의 서버 결과와 요청에서 제외한 행의 로컬 검증을 구분해 표시한다. */
export function summarizePlanCompatibility(drafts: PartDraft[], plan: UpgradePlan, state: CompatibilityState): PlanCompatibility {
  const result = state.status === 'done' ? state.result : null
  const fieldIndexes = requestedFieldIndexes(drafts)
  const localChecks = new Map(localCompatibilityChecks(drafts).map((check) => [check.code, check]))

  const found = drafts.map(() => ({ rank: null as number | null, messages: [] as string[] }))
  for (const check of result?.checks ?? []) {
    const local = localChecks.get(check.code)
    const indexes = local ? localCheckIndexes(local, drafts) : check.affectedFields.flatMap(fieldIndexes)
    for (const index of new Set(indexes)) {
      found[index].rank = Math.max(found[index].rank ?? 0, RANK[check.status])
      if (check.status !== 'COMPATIBLE' && !found[index].messages.includes(check.message)) found[index].messages.push(check.message)
    }
  }
  const byIndex: PartCompat[] = drafts.map((draft, index) => {
    if (!CHECKED_TYPES.includes(draft.type)) return null
    if (!draft.catalogProductId) return { status: 'UNCHECKED', messages: [] }
    if (!result) return null
    const status = (Object.keys(RANK) as CompatibilityStatus[]).find((key) => RANK[key] === found[index].rank) ?? 'UNCHECKED'
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

export function usePlanCompatibility(finalParts: PartInput[], plan: UpgradePlan): PlanCompatibility {
  const drafts: PartDraft[] = finalParts.map((part, index) => ({ ...part, key: `${part.type}-${index}`, edited: false, persisted: false }))
  const state = useCompatibility(drafts, true)
  return summarizePlanCompatibility(drafts, plan, state)
}
