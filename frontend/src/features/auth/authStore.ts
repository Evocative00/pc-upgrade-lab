import { useSyncExternalStore } from 'react'
import { authClient, type AuthUser } from './authClient.ts'

// 화면 전체가 공유하는 로그인 상태. 회원이 바뀌면 user.id가 바뀌므로 개인 PC 화면은 이 값을 key로 다시 불러온다.
export type AuthState =
  | { status: 'loading' }
  | { status: 'signedIn'; user: AuthUser }
  | { status: 'signedOut' }
  | { status: 'unavailable' }

let state: AuthState = { status: 'loading' }
const listeners = new Set<() => void>()

function setState(next: AuthState) {
  state = next
  listeners.forEach((listener) => listener())
}

export function getAuthState() {
  return state
}

export async function refreshAuth(): Promise<AuthState> {
  const result = await authClient.me()
  setState(result)
  return result
}

// PC API가 401을 돌려주면 세션이 끝난 것이다. 이전 회원 정보와 목록을 더 보여 주지 않는다.
export function markSignedOut() {
  if (state.status !== 'signedOut') setState({ status: 'signedOut' })
}

export async function logout() {
  try {
    await authClient.logout()
  } finally {
    // 비회원 초안(draftBridge)은 지우지 않는다.
    setState({ status: 'signedOut' })
  }
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function useAuth(): AuthState {
  return useSyncExternalStore(subscribe, getAuthState)
}

// 개인 PC 화면을 회원별로 새로 마운트하기 위한 key. 회원 전환 시 이전 회원의 목록이 남지 않는다.
export function authKey(auth: AuthState) {
  return auth.status === 'signedIn' ? `user-${auth.user.id}` : auth.status
}
