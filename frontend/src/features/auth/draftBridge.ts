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

type DraftSnapshot = Pick<PcDraft, 'request' | 'pcId'>
let activeDraft: (() => DraftSnapshot) | null = null

// 현재 작성 폼만 등록한다. 화면을 떠나면 해제하며, 서버 저장은 하지 않는다.
export function registerActiveDraft(read: () => DraftSnapshot): () => void {
  activeDraft = read
  return () => {
    if (activeDraft === read) activeDraft = null
  }
}

// 상단 로그인 링크처럼 폼 밖에서 이동해도 최신 입력을 먼저 보관한다.
export function saveActiveDraft(storage = defaultStorage()): boolean {
  if (activeDraft === null) return true
  const draft = activeDraft()
  // 복원할 수 없는 수량 입력은 이동을 막아 원래 폼에 남긴다.
  return isPcRequest(draft.request) && saveDraft(draft.request, draft.pcId, storage)
}

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
    // JSON은 NaN/Infinity를 null로 바꾼다. 용량 오류를 미확인 값으로 바꾸지 않는다.
    const serialized = JSON.stringify(draft, (_key, value) => {
      if (typeof value === 'number' && !Number.isFinite(value)) {
        throw new RangeError('Draft contains a non-finite number')
      }
      return value
    })
    storage.setItem(KEY, serialized)
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

// 사용자가 초기화하거나 삭제한 PC의 초안만 지운다. 다른 PC의 초안은 남긴다.
export function clearDraftFor(pcId: number | null, storage = defaultStorage()) {
  if (loadDraft(storage)?.pcId === pcId) clearDraft(storage)
}

// 늦게 도착한 저장 성공이 그 사이 작성한 다른 초안을 지우지 않게 한다.
export function clearSavedDraftFor(pcId: number | null, request: PcRequest, storage = defaultStorage()) {
  const draft = loadDraft(storage)
  if (draft?.pcId !== pcId) return
  const saved = JSON.stringify([draft.request.name.trim(), draft.request.parts])
  const submitted = JSON.stringify([request.name.trim(), request.parts])
  if (saved === submitted) clearDraft(storage)
}
