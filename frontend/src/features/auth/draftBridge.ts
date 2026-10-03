import { isPcRequest } from '../pc/pcRepository.ts'
import type { PcRequest } from '../pc/types.ts'

// 로그인 왕복 동안 작성 중인 PC 구성을 브라우저 탭에 임시 보관한다.
// 초안 저장 형식·위치는 재훈 담당 기능으로 교체될 수 있다. 교체 시 이 파일의 세 함수만 바꾼다.
// 복구 규칙: 로그인 후 폼에 채워 보여 주기만 하고, 사용자가 저장 버튼을 눌러야 서버에 저장한다.
// 저장에 성공했을 때만 clearDraft를 호출한다. 로그인 취소·실패·로그아웃에서는 지우지 않는다.

export type PcDraft = {
  request: PcRequest
  // 수정 중이던 저장 PC면 그 ID, 새 구성이면 null
  pcId: number | null
  savedAt: string
}

const KEY = 'pc-upgrade-lab:pc-draft:v1'

type DraftStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

function defaultStorage(): DraftStorage | null {
  try {
    return globalThis.sessionStorage ?? null
  } catch {
    // 개인정보 보호 설정 등으로 저장소 접근이 막힌 경우
    return null
  }
}

export function saveDraft(request: PcRequest, pcId: number | null, storage = defaultStorage()): boolean {
  if (storage === null) return false
  const draft: PcDraft = { request, pcId, savedAt: new Date().toISOString() }
  try {
    storage.setItem(KEY, JSON.stringify(draft))
    return true
  } catch {
    return false
  }
}

export function loadDraft(storage = defaultStorage()): PcDraft | null {
  if (storage === null) return null
  try {
    const value: unknown = JSON.parse(storage.getItem(KEY) ?? 'null')
    if (typeof value !== 'object' || value === null) return null
    const draft = value as Record<string, unknown>
    const pcId = draft.pcId
    if (!isPcRequest(draft.request) || typeof draft.savedAt !== 'string' ||
      !(pcId === null || (Number.isSafeInteger(pcId) && Number(pcId) > 0))) {
      return null
    }
    return { request: draft.request, pcId: pcId as number | null, savedAt: draft.savedAt }
  } catch {
    return null
  }
}

export function clearDraft(storage = defaultStorage()) {
  try {
    storage?.removeItem(KEY)
  } catch {
    // 지우지 못해도 다음 저장 때 덮어쓴다.
  }
}

// 저장에 성공한 구성의 초안만 지운다. 다른 PC나 새 구성 초안은 남긴다.
export function clearDraftFor(pcId: number | null, storage = defaultStorage()) {
  if (loadDraft(storage)?.pcId === pcId) clearDraft(storage)
}
