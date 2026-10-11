import { csrfHeaders } from '../../lib/csrf.ts'
import { getPartStatus } from '../pc/partDraft.ts'
import type { PartDraft } from '../pc/types.ts'

// 백엔드 POST /api/compatibility/check 계약. 새 부품 존재 여부는 미연결과 빈 자리를 구분한다.
export type CompatibilityStatus = 'COMPATIBLE' | 'NEEDS_CHECK' | 'INCOMPATIBLE'
export type CompatibilityCheck = { code: string; status: CompatibilityStatus; message: string; affectedFields: string[] }
export type CompatibilityResult = { status: CompatibilityStatus; checks: CompatibilityCheck[]; scope?: string; fullPcCompatibilityChecked?: boolean }
export type CompatibilityRequest = {
  cpuProductId: string | null
  motherboardProductId: string | null
  ram: { catalogProductId: string | null; quantity: number }[]
  gpuProductId?: string | null
  psuProductId?: string | null
  caseProductId?: string | null
  presentComponents?: ('GPU' | 'PSU' | 'CASE')[]
}

const STATUSES: readonly string[] = ['COMPATIBLE', 'NEEDS_CHECK', 'INCOMPATIBLE']
const SINGLE_COMPONENTS = [
  ['CPU', 'CPU', 'cpuProductId'], ['MOTHERBOARD', '메인보드', 'motherboardProductId'],
  ['GPU', '그래픽카드', 'gpuProductId'], ['PSU', '파워', 'psuProductId'], ['CASE', '케이스', 'caseProductId'],
] as const

const filled = (drafts: PartDraft[], type: PartDraft['type']) =>
  drafts.filter((draft) => draft.type === type && getPartStatus(draft) !== 'empty')

// 요청의 ram[i]와 같은 순서다. 보낼 수 없는 수량은 localCompatibilityChecks에서 따로 알린다.
export function ramDraftsForCheck(drafts: PartDraft[]): PartDraft[] {
  return filled(drafts, 'RAM').filter((draft) => Number.isInteger(draft.quantity) && draft.quantity >= 1 && draft.quantity <= 64)
}

// 여러 행이나 복수 수량을 단일 부품처럼 검사하지 않는다.
function singleProductId(drafts: PartDraft[], type: typeof SINGLE_COMPONENTS[number][0]): string | null {
  const rows = filled(drafts, type)
  return rows.length === 1 && rows[0].quantity === 1 ? rows[0].catalogProductId ?? null : null
}

// API에서 표현하지 못하거나 검증을 통과할 수 없는 입력을 검사 결과에서 빠뜨리지 않는다.
export function localCompatibilityChecks(drafts: PartDraft[]): CompatibilityCheck[] {
  const checks: CompatibilityCheck[] = []
  const hardwareSelected = (['GPU', 'PSU', 'CASE'] as const).some((type) => filled(drafts, type).length > 0)
  for (const [type, label, field] of SINGLE_COMPONENTS) {
    const rows = filled(drafts, type)
    if (rows.length > 1) checks.push({
      code: `${type}_SELECTION`, status: 'NEEDS_CHECK',
      message: `${label} 항목이 ${rows.length}개입니다. 검사할 ${label} 1개를 정해 주세요.`, affectedFields: [field],
    })
    if (rows.length === 1 && rows[0].quantity !== 1) checks.push({
      code: `${type}_QUANTITY`, status: 'NEEDS_CHECK',
      message: `${label} 수량은 1개인 구성을 검사합니다. 실제 수량과 구성을 확인해 주세요.`, affectedFields: [field],
    })
    // 하드웨어만 선택한 요청과 미연결 CPU·보드가 있는 초안은 서버에서 같은 null ID다.
    if (hardwareSelected && ['CPU', 'MOTHERBOARD'].includes(type) && rows.length === 1
      && rows[0].quantity === 1 && !rows[0].catalogProductId) checks.push({
      code: `${type}_CATALOG_LINK`, status: 'NEEDS_CHECK',
      message: `입력한 ${label}를 카탈로그 상품에 연결해야 해당 규격을 검사할 수 있습니다.`, affectedFields: [field],
    })
  }
  for (const draft of filled(drafts, 'RAM')) {
    if (!Number.isInteger(draft.quantity) || draft.quantity < 1 || draft.quantity > 64) checks.push({
      code: `RAM_QUANTITY_${draft.key}`, status: 'NEEDS_CHECK',
      message: `RAM "${draft.displayName}"의 실제 장착 수량을 1~64 사이 정수로 입력해 주세요. 이 항목은 서버 검사에서 제외됐습니다.`,
      affectedFields: ['ram'],
    })
  }
  if (ramDraftsForCheck(drafts).length > 64) checks.push({
    code: 'RAM_SELECTION_COUNT', status: 'NEEDS_CHECK',
    message: 'RAM 항목은 64개까지 검사할 수 있습니다. 항목 수를 정리해 주세요.', affectedFields: ['ram'],
  })
  return checks
}

// 새 부품이 있으면 미연결 상태도 서버에 알린다. 기존 요청 모양은 계속 지원한다.
export function buildCompatibilityRequest(drafts: PartDraft[]): CompatibilityRequest | null {
  const rams = ramDraftsForCheck(drafts)
  const request: CompatibilityRequest = {
    cpuProductId: singleProductId(drafts, 'CPU'),
    motherboardProductId: singleProductId(drafts, 'MOTHERBOARD'),
    ram: rams.length > 64 ? [] : rams.map((draft) => ({ catalogProductId: draft.catalogProductId ?? null, quantity: draft.quantity })),
  }
  const presentComponents = (['GPU', 'PSU', 'CASE'] as const).filter((type) => filled(drafts, type).length > 0)
  if (presentComponents.length > 0) Object.assign(request, {
    gpuProductId: singleProductId(drafts, 'GPU'), psuProductId: singleProductId(drafts, 'PSU'),
    caseProductId: singleProductId(drafts, 'CASE'), presentComponents,
  })
  const linked = request.cpuProductId || request.motherboardProductId || request.ram.some((item) => item.catalogProductId)
  return linked || presentComponents.length > 0 ? request : null
}

function overallStatus(statuses: CompatibilityStatus[]): CompatibilityStatus {
  return statuses.includes('INCOMPATIBLE') ? 'INCOMPATIBLE'
    : statuses.includes('NEEDS_CHECK') ? 'NEEDS_CHECK' : 'COMPATIBLE'
}

export function mergeCompatibilityChecks(result: CompatibilityResult, localChecks: CompatibilityCheck[]): CompatibilityResult {
  const checks = [...result.checks, ...localChecks]
  return { ...result, checks, status: overallStatus([result.status, ...checks.map((check) => check.status)]) }
}

// 불가(INCOMPATIBLE) 판정을 받은 부품 행 key → 경고 문구. affectedFields의 이름은 요청 필드명이다.
export function incompatibleByDraft(drafts: PartDraft[], result: CompatibilityResult): Map<string, string[]> {
  const singles = new Map<string, PartDraft | undefined>(SINGLE_COMPONENTS.map(([type, , field]) => {
    const rows = filled(drafts, type)
    return [field, rows.length === 1 && rows[0].quantity === 1 ? rows[0] : undefined]
  }))
  const ramRows = ramDraftsForCheck(drafts)
  const rams = ramRows.length > 64 ? [] : ramRows
  const warnings = new Map<string, string[]>()
  for (const check of result.checks) {
    if (check.status !== 'INCOMPATIBLE') continue
    for (const field of check.affectedFields) {
      const index = /^ram\[(\d+)\]/.exec(field)?.[1]
      const targets = singles.has(field) ? [singles.get(field)]
        : field === 'ram' ? rams : index !== undefined ? [rams[Number(index)]] : []
      for (const draft of targets) {
        if (!draft) continue
        const list = warnings.get(draft.key) ?? []
        if (!list.includes(check.message)) list.push(check.message)
        warnings.set(draft.key, list)
      }
    }
  }
  return warnings
}

function isResult(value: unknown): value is CompatibilityResult {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  const body = value as Record<string, unknown>
  if (typeof body.status !== 'string' || !STATUSES.includes(body.status) || !Array.isArray(body.checks) || body.checks.length === 0) return false
  const validChecks = body.checks.every((check: unknown) => {
    if (typeof check !== 'object' || check === null || Array.isArray(check)) return false
    const item = check as Record<string, unknown>
    return typeof item.code === 'string' && typeof item.status === 'string' && STATUSES.includes(item.status) &&
      typeof item.message === 'string' && Array.isArray(item.affectedFields) &&
      item.affectedFields.every((field) => typeof field === 'string')
  })
  return validChecks && body.status === overallStatus((body.checks as CompatibilityCheck[]).map((check) => check.status))
}

export async function checkCompatibility(request: CompatibilityRequest, signal: AbortSignal,
  fetcher: typeof fetch = globalThis.fetch): Promise<CompatibilityResult> {
  let response: Response
  try {
    response = await fetcher('/api/compatibility/check', {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json', ...csrfHeaders() },
      body: JSON.stringify(request), cache: 'no-store', signal,
    })
  } catch (error) {
    if (signal.aborted) throw error
    throw new Error('호환성 검사 서버에 연결하지 못했습니다. 백엔드 실행 상태를 확인해 주세요.', { cause: error })
  }
  const body: unknown = await response.json().catch(() => null)
  if (response.status === 404) throw new Error('호환성 검사 API를 사용할 수 없습니다. 백엔드가 local 프로필로 실행 중인지 확인해 주세요.')
  if (!response.ok) throw new Error(`호환성 검사에 실패했습니다 (HTTP ${response.status}).`)
  if (!isResult(body)) throw new Error('호환성 검사 응답 형식이 올바르지 않습니다.')
  if (request.presentComponents?.length && (body.scope !== 'CPU_MOTHERBOARD_RAM_GPU_PSU_CASE_V2'
    || body.fullPcCompatibilityChecked !== false)) {
    throw new Error('그래픽카드·파워·케이스 검사에 필요한 백엔드 버전을 확인해 주세요.')
  }
  return body
}
