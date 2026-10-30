import { useSyncExternalStore } from 'react'

// 라우터 라이브러리를 넣기 전까지 쓰는 간단한 해시 라우팅
export type Route =
  | { name: 'home' }
  | { name: 'list' }
  | { name: 'new' }
  | { name: 'detail'; id: number }
  | { name: 'edit'; id: number }
  | { name: 'upgrade' }
  | { name: 'upgradePc'; id: number }
  | { name: 'login' }
  | { name: 'loginSuccess' }
  | { name: 'loginFailure'; reason: LoginFailureReason }
  | { name: 'notFound' }

// 로그인 실패 화면 구분. 서버가 모르는 값을 보내도 일반 실패로 안내한다.
export type LoginFailureReason = 'cancelled' | 'error'

export const paths = {
  // 랜딩 화면. 주소가 비어 있을 때도 여기로 온다.
  home: () => '#/',
  list: () => '#/pcs',
  new: () => '#/pcs/new',
  detail: (id: number) => `#/pcs/${id}`,
  edit: (id: number) => `#/pcs/${id}/edit`,
  upgrade: () => '#/upgrade',
  upgradePc: (id: number) => `#/upgrade/pcs/${id}`,
  // 로그인 성공·실패 경로는 인증 서버의 리다이렉트 주소와 같아야 한다. (docs/week2-auth-pc-contract.md)
  login: () => '#/login',
  loginSuccess: () => '#/login/success',
  loginFailure: (reason: LoginFailureReason) => `#/login/failure?reason=${reason}`,
}

export function parseRoute(hash: string): Route {
  const [pathPart, query = ''] = hash.replace(/^#\/?/, '').split('?', 2)
  const segments = pathPart.split('/').filter(Boolean)

  if (segments.length === 0) {
    return { name: 'home' }
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

  if (segments[0] === 'upgrade') {
    if (segments.length === 1) return { name: 'upgrade' }
    if (segments.length === 3 && segments[1] === 'pcs' && /^[1-9]\d*$/.test(segments[2])) {
      return { name: 'upgradePc', id: Number(segments[2]) }
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
  const hash = useLocationHash()

  return parseRoute(hash)
}

export function useLocationHash() {
  return useSyncExternalStore(subscribe, () => window.location.hash)
}

export type PlannerStep = 1 | 2 | 3 | 4 | 5

export function plannerStepFromHash(hash: string, fallback: PlannerStep): PlannerStep {
  const step = new URLSearchParams(hash.split('?')[1] ?? '').get('step')
  return step !== null && /^[1-5]$/.test(step) ? Number(step) as PlannerStep : fallback
}

export function plannerStepPath(hash: string, step: PlannerStep) {
  const [path, query] = hash.split('?', 2)
  const params = new URLSearchParams(query)
  if (step === 1) params.delete('step')
  else params.set('step', String(step))
  return `${path}${params.size ? `?${params}` : ''}`
}

export function usePlannerStep(fallback: PlannerStep) {
  const step = plannerStepFromHash(useLocationHash(), fallback)
  return [step, (next: PlannerStep) => navigate(plannerStepPath(window.location.hash, next))] as const
}

export function replacePlannerStep(step: PlannerStep) {
  window.location.replace(plannerStepPath(window.location.hash, step))
}

export function navigate(path: string) {
  window.location.hash = path
}
