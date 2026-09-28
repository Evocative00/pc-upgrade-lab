import { useSyncExternalStore } from 'react'

// 라우터 라이브러리를 넣기 전까지 쓰는 간단한 해시 라우팅
export type Route =
  | { name: 'list' }
  | { name: 'new' }
  | { name: 'detail'; id: number }
  | { name: 'edit'; id: number }
  | { name: 'notFound' }

export const paths = {
  list: () => '#/pcs',
  new: () => '#/pcs/new',
  detail: (id: number) => `#/pcs/${id}`,
  edit: (id: number) => `#/pcs/${id}/edit`,
}

export function parseRoute(hash: string): Route {
  const segments = hash.replace(/^#\/?/, '').split('/').filter(Boolean)

  if (segments.length === 0) {
    return { name: 'list' }
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
