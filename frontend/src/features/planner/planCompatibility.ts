import {
  buildCompatibilityRequest, localCompatibilityChecks, ramDraftsForCheck,
  type CompatibilityCheck, type CompatibilityResult,
} from '../builder/compatibility.ts'
import { useCompatibility, type CompatibilityState } from '../builder/useCompatibility.ts'
import type { PartInput, PartType } from '../pc-scan/types.ts'
import { getPartStatus } from '../pc/partDraft.ts'
import type { PartDraft } from '../pc/types.ts'
import type { UpgradePlan } from './upgradeRecommendation.ts'

/**
 * 추천 플랜의 최종 구성을 기존 호환성 검사 API로 검사하고, 결과를 최종 구성 목록의 부품 행마다 붙일 수 있게 정리한다.
 * 부품 목록은 추천 플랜의 '최종 구성' 한 곳에서만 보여 준다.
 *
 * 화면 상태는 API의 3단계에 '참고'를 더한 4단계다. 필요한 정보가 제조사 자료 하나뿐이라고
 * 서버가 명시한 경우에만 '참고'로 표시하고, 장착 부품이나 BIOS 확인이 필요한 판정은 유지한다.
 */
export type CompatStatus = 'COMPATIBLE' | 'INFO' | 'NEEDS_CHECK' | 'INCOMPATIBLE'

export const COMPAT_TEXT: Record<CompatStatus | 'UNCHECKED', string> = {
  COMPATIBLE: '호환', INFO: '참고', NEEDS_CHECK: '확인 필요', INCOMPATIBLE: '비호환', UNCHECKED: '검사 제외',
}
export const COMPAT_CLASS: Record<CompatStatus | 'UNCHECKED', string> = {
  COMPATIBLE: 'pass', INFO: 'info', NEEDS_CHECK: 'unknown', INCOMPATIBLE: 'fail', UNCHECKED: 'unchecked',
}
// 호환성 검사 API가 보는 부품. 저장장치·쿨러·모니터는 검사하지 않는다.
const CHECKED_TYPES: PartType[] = ['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'PSU', 'CASE']
const SINGLE_FIELDS: Record<string, PartType> = {
  cpuProductId: 'CPU', motherboardProductId: 'MOTHERBOARD', gpuProductId: 'GPU', psuProductId: 'PSU', caseProductId: 'CASE',
}
const RANK: Record<CompatStatus, number> = { COMPATIBLE: 0, INFO: 1, NEEDS_CHECK: 2, INCOMPATIBLE: 3 }

// 같은 검사 코드도 사용자 입력이나 장착 조건 확인을 요구할 수 있다.
const DOCUMENTATION_GAPS: Readonly<Record<string, string>> = {
  CPU_MANUFACTURER_SUPPORT: 'manufacturerCpuSupport',
  CPU_RAM_CAPACITY: 'cpuMemorySupport.maxMemoryBytes',
  MOTHERBOARD_RAM_CAPACITY: 'motherboardSpecification.maxMemoryBytes',
}

/** 검사 항목 하나의 화면 상태 */
export function displayStatus(check: CompatibilityCheck): CompatStatus {
  const required = check.requiredInformation
  return check.status === 'NEEDS_CHECK' && required?.length === 1 &&
    required[0] === DOCUMENTATION_GAPS[check.code] ? 'INFO' : check.status
}

// 최종 구성 부품 하나의 검사 상태. 검사 대상이 아니거나 검사 중이면 null.
export type PartCompat = { status: CompatStatus | 'UNCHECKED'; messages: string[] } | null

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

export type CompatCounts = Record<CompatStatus, number>
// 부품 조합 한 줄: 'CPU ↔ 메인보드'처럼 사용자가 궁금한 단위로 검사 항목을 묶는다.
export type CompatPair = { label: string; status: CompatStatus; reason: string }

/** 검사 항목 화면 상태별 개수 */
export function compatCounts(result: CompatibilityResult | null): CompatCounts {
  const counts: CompatCounts = { COMPATIBLE: 0, INFO: 0, NEEDS_CHECK: 0, INCOMPATIBLE: 0 }
  for (const check of result?.checks ?? []) counts[displayStatus(check)] += 1
  return counts
}

// 검사 항목 코드 → 부품 조합. 순서가 화면 순서다.
const PAIR_GROUPS: { label: string; match: (code: string) => boolean }[] = [
  { label: 'CPU ↔ 메인보드', match: (code) => code === 'CPU_SOCKET' },
  { label: 'CPU ↔ 메인보드 BIOS', match: (code) => code === 'CPU_BIOS' || code === 'CPU_MANUFACTURER_SUPPORT' },
  { label: '메모리 ↔ CPU', match: (code) => code.startsWith('CPU_RAM_') },
  { label: '메모리 ↔ 메인보드', match: (code) => code.startsWith('MOTHERBOARD_RAM_') || code.startsWith('RAM_') },
  { label: '그래픽카드 ↔ 파워', match: (code) => code.startsWith('GPU_PSU_') },
  { label: '그래픽카드 ↔ 케이스', match: (code) => code.startsWith('GPU_CASE_') },
  { label: '케이스 ↔ 메인보드·파워', match: (code) => code === 'MOTHERBOARD_CASE_FORM' || code.startsWith('PSU_CASE_') },
]

// 조합 한 줄에 보일 짧은 이유. 비호환·확인 필요는 원문을, 참고는 짧은 안내를, 호환은 ':' 뒤 값(예: AM4, DDR4)만 쓴다.
function shortReason(status: CompatStatus, code: string, message: string): string {
  if (status === 'INCOMPATIBLE') return message
  if (status === 'INFO') {
    return code === 'CPU_MANUFACTURER_SUPPORT' ? '제조사 CPU 지원 자료 없음' : '최대 메모리 정보 없음'
  }
  if (status === 'NEEDS_CHECK') return code.startsWith('CATALOG_LINK') ? '카탈로그 연결 필요' : message
  const value = message.split(':').slice(1).join(':').trim()
  return value ? `${value} 일치` : '맞음'
}

/**
 * 검사 결과를 부품 조합별 한 줄로 묶는다. 조합의 상태는 그 안에서 가장 나쁜 항목을 따른다.
 * 그래픽카드가 있는데 파워·케이스를 입력하지 않았으면 '미입력' 줄을 더한다.
 */
export function compatibilityPairs(result: CompatibilityResult | null, missing: PartType[], hasGpu: boolean): CompatPair[] {
  const pairs: CompatPair[] = []
  for (const group of PAIR_GROUPS) {
    const checks = (result?.checks ?? []).filter((check) => group.match(check.code))
    if (!checks.length) continue
    const worst = checks.reduce((top, check) => RANK[displayStatus(check)] > RANK[displayStatus(top)] ? check : top)
    const status = displayStatus(worst)
    pairs.push({ label: group.label, status, reason: shortReason(status, worst.code, worst.message) })
  }
  if (hasGpu && missing.includes('PSU') && !pairs.some((pair) => pair.label === '그래픽카드 ↔ 파워')) {
    pairs.push({ label: '그래픽카드 ↔ 파워', status: 'NEEDS_CHECK', reason: '파워 미입력' })
  }
  if (hasGpu && missing.includes('CASE') && !pairs.some((pair) => pair.label === '그래픽카드 ↔ 케이스')) {
    pairs.push({ label: '그래픽카드 ↔ 케이스', status: 'NEEDS_CHECK', reason: '케이스 미입력' })
  }
  return pairs
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
    const status = displayStatus(check)
    const local = localChecks.get(check.code)
    const indexes = local ? localCheckIndexes(local, drafts) : check.affectedFields.flatMap(fieldIndexes)
    for (const index of new Set(indexes)) {
      found[index].rank = Math.max(found[index].rank ?? 0, RANK[status])
      if (status !== 'COMPATIBLE' && !found[index].messages.includes(check.message)) found[index].messages.push(check.message)
    }
  }
  const byIndex: PartCompat[] = drafts.map((draft, index) => {
    if (!CHECKED_TYPES.includes(draft.type)) return null
    if (!draft.catalogProductId) return { status: 'UNCHECKED', messages: [] }
    if (!result) return null
    const status = (Object.keys(RANK) as CompatStatus[]).find((key) => RANK[key] === found[index].rank) ?? 'UNCHECKED'
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
  const state = useCompatibility(drafts, true, { delayMs: 0, reuseSuccess: true })
  return summarizePlanCompatibility(drafts, plan, state)
}
