import { useSyncExternalStore } from 'react'

// 라우터 라이브러리를 넣기 전까지 쓰는 간단한 해시 라우팅
export type Route =
  | { name: 'list' }
  | { name: 'new' }
  | { name: 'detail'; id: number }
  | { name: 'edit'; id: number }
  | { name: 'login' }
  | { name: 'loginSuccess' }
  | { name: 'loginFailure'; reason: LoginFailureReason }
  | { name: 'notFound' }

// 로그인 실패 화면 구분. 서버가 모르는 값을 보내도 일반 실패로 안내한다.
export type LoginFailureReason = 'cancelled' | 'error'

export const paths = {
  list: () => '#/pcs',
  new: () => '#/pcs/new',
  detail: (id: number) => `#/pcs/${id}`,
  edit: (id: number) => `#/pcs/${id}/edit`,
  // 로그인 성공·실패 경로는 인증 서버의 리다이렉트 주소와 같아야 한다. (docs/week2-auth-pc-contract.md)
  login: () => '#/login',
  loginSuccess: () => '#/login/success',
  loginFailure: (reason: LoginFailureReason) => `#/login/failure?reason=${reason}`,
}

export function parseRoute(hash: string): Route {
  const [pathPart, query = ''] = hash.replace(/^#\/?/, '').split('?', 2)
  const segments = pathPart.split('/').filter(Boolean)

  if (segments.length === 0) {
    return { name: 'list' }
  }

  // 예전 PC 구성하기 주소. 구성 화면은 새 PC 등록 화면으로 합쳤다.
  if (segments[0] === 'build' && segments.length === 1) {
    return { name: 'new' }
  }

  if (segments[0] === 'login') {
    if (segments.length === 1) return { name: 'login' }
    if (segments.length === 2 && segments[1] === 'success') return { name: 'loginSuccess' }
    if (segments.length === 2 && segments[1] === 'failure') {
      const reason = new URLSearchParams(query).get('reason')
      return { name: 'loginFailure', reason: reason === 'cancelled' ? 'cancelled' : 'error' }
    }
    return { name: 'notFound' }
  }

  if (segments[0] !== 'pcs') {
    return { name: 'notFound' }
  }

  const [, idText, action] = segments

  if (idText === undefined) {
    return { name: 'list' }
  }

  if (idText === 'new' && action === undefined) {
    return { name: 'new' }
  }

  // PC ID는 서버가 발급하는 양의 정수다.
  if (!/^[1-9]\d*$/.test(idText)) {
    return { name: 'notFound' }
  }

  const id = Number(idText)

  if (action === undefined) {
    return { name: 'detail', id }
  }

  if (action === 'edit' && segments.length === 3) {
    return { name: 'edit', id }
  }

  return { name: 'notFound' }
}

function subscribe(callback: () => void) {
  window.addEventListener('hashchange', callback)

  return () => window.removeEventListener('hashchange', callback)
}

export function useRoute(): Route {
  const hash = useSyncExternalStore(subscribe, () => window.location.hash)

  return parseRoute(hash)
}

export function navigate(path: string) {
  window.location.hash = path
}
