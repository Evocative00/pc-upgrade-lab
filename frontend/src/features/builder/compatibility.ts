import { csrfHeaders } from '../../lib/csrf.ts'
import { getPartStatus } from '../pc/partDraft.ts'
import type { PartDraft } from '../pc/types.ts'

// 백엔드 POST /api/compatibility/check 계약 (CompatibilityDtos). CPU·메인보드·RAM만 검사한다.
export type CompatibilityStatus = 'COMPATIBLE' | 'NEEDS_CHECK' | 'INCOMPATIBLE'
export type CompatibilityCheck = { code: string; status: CompatibilityStatus; message: string; affectedFields: string[] }
export type CompatibilityResult = { status: CompatibilityStatus; checks: CompatibilityCheck[] }
export type CompatibilityRequest = {
  cpuProductId: string | null
  motherboardProductId: string | null
  ram: { catalogProductId: string | null; quantity: number }[]
}

const STATUSES: readonly string[] = ['COMPATIBLE', 'NEEDS_CHECK', 'INCOMPATIBLE']

const filled = (drafts: PartDraft[], type: PartDraft['type']) =>
  drafts.filter((draft) => draft.type === type && getPartStatus(draft) !== 'empty')

// 요청의 ram[i]와 같은 순서다. 보낼 수 없는 수량은 localCompatibilityChecks에서 따로 알린다.
export function ramDraftsForCheck(drafts: PartDraft[]): PartDraft[] {
  return filled(drafts, 'RAM').filter((draft) => Number.isInteger(draft.quantity) && draft.quantity >= 1 && draft.quantity <= 64)
}

// 단일 CPU·보드 계약에 여러 행을 임의로 하나만 골라 보내지 않는다.
function singleProductId(drafts: PartDraft[], type: 'CPU' | 'MOTHERBOARD'): string | null {
  const rows = filled(drafts, type)
  return rows.length === 1 ? rows[0].catalogProductId ?? null : null
}

// API에서 표현하지 못하거나 검증을 통과할 수 없는 입력을 검사 결과에서 빠뜨리지 않는다.
export function localCompatibilityChecks(drafts: PartDraft[]): CompatibilityCheck[] {
  const checks: CompatibilityCheck[] = []
  for (const [type, label, field] of [
    ['CPU', 'CPU', 'cpuProductId'], ['MOTHERBOARD', '메인보드', 'motherboardProductId'],
  ] as const) {
    const rows = filled(drafts, type)
    if (rows.length > 1) checks.push({
      code: `${type}_SELECTION`, status: 'NEEDS_CHECK',
      message: `${label} 항목이 ${rows.length}개입니다. 검사할 ${label} 1개를 정해 주세요.`, affectedFields: [field],
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

// 카탈로그에 연결된 CPU·메인보드·RAM이 하나도 없으면 검사하지 않는다(null).
export function buildCompatibilityRequest(drafts: PartDraft[]): CompatibilityRequest | null {
  const rams = ramDraftsForCheck(drafts)
  const request: CompatibilityRequest = {
    cpuProductId: singleProductId(drafts, 'CPU'),
    motherboardProductId: singleProductId(drafts, 'MOTHERBOARD'),
    ram: rams.length > 64 ? [] : rams.map((draft) => ({ catalogProductId: draft.catalogProductId ?? null, quantity: draft.quantity })),
  }
  const linked = request.cpuProductId || request.motherboardProductId || request.ram.some((item) => item.catalogProductId)
  return linked ? request : null
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
  const cpuRows = filled(drafts, 'CPU')
  const boardRows = filled(drafts, 'MOTHERBOARD')
  const cpu = cpuRows.length === 1 ? cpuRows[0] : undefined
  const board = boardRows.length === 1 ? boardRows[0] : undefined
  const ramRows = ramDraftsForCheck(drafts)
  const rams = ramRows.length > 64 ? [] : ramRows
  const warnings = new Map<string, string[]>()
  for (const check of result.checks) {
    if (check.status !== 'INCOMPATIBLE') continue
    for (const field of check.affectedFields) {
      const index = /^ram\[(\d+)\]/.exec(field)?.[1]
      const targets = field === 'cpuProductId' ? [cpu] : field === 'motherboardProductId' ? [board]
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
  return body
}
