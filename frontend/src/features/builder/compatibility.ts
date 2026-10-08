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

// 검사 대상 RAM. 수량이 API 범위(1~64)를 벗어난 행은 보내지 않는다. 요청의 ram[i]와 순서가 같다.
export function ramDraftsForCheck(drafts: PartDraft[]): PartDraft[] {
  return filled(drafts, 'RAM').filter((draft) => Number.isInteger(draft.quantity) && draft.quantity >= 1 && draft.quantity <= 64)
}

// 카탈로그에 연결된 CPU·메인보드·RAM이 하나도 없으면 검사하지 않는다(null).
export function buildCompatibilityRequest(drafts: PartDraft[]): CompatibilityRequest | null {
  const request: CompatibilityRequest = {
    cpuProductId: filled(drafts, 'CPU')[0]?.catalogProductId ?? null,
    motherboardProductId: filled(drafts, 'MOTHERBOARD')[0]?.catalogProductId ?? null,
    ram: ramDraftsForCheck(drafts).map((draft) => ({ catalogProductId: draft.catalogProductId ?? null, quantity: draft.quantity })),
  }
  const linked = request.cpuProductId || request.motherboardProductId || request.ram.some((item) => item.catalogProductId)
  return linked ? request : null
}

// 불가(INCOMPATIBLE) 판정을 받은 부품 행 key → 경고 문구. affectedFields의 이름은 요청 필드명이다.
export function incompatibleByDraft(drafts: PartDraft[], result: CompatibilityResult): Map<string, string[]> {
  const cpu = filled(drafts, 'CPU')[0]
  const board = filled(drafts, 'MOTHERBOARD')[0]
  const rams = ramDraftsForCheck(drafts)
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
  if (typeof value !== 'object' || value === null) return false
  const body = value as Record<string, unknown>
  return STATUSES.includes(String(body.status)) && Array.isArray(body.checks) &&
    body.checks.every((check: unknown) => {
      if (typeof check !== 'object' || check === null) return false
      const item = check as Record<string, unknown>
      return typeof item.code === 'string' && STATUSES.includes(String(item.status)) &&
        typeof item.message === 'string' && Array.isArray(item.affectedFields) &&
        item.affectedFields.every((field) => typeof field === 'string')
    })
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
