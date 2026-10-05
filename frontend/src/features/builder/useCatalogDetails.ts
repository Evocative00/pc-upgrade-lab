import { useCallback, useEffect, useRef, useState } from 'react'
import { catalogClient } from '../catalog/catalogClient.ts'
import type { CatalogDetail } from '../catalog/catalogTypes.ts'

export type DetailEntry = { status: 'loading' } | { status: 'done'; detail: CatalogDetail } | { status: 'error'; message: string }

// 연결된 카탈로그 제품의 상세(가격·제원)를 한 번씩 받아 둔다. 결과가 없으면 불러오는 중이다.
// 검색 창·부품 행·저장된 PC 등 어느 경로로 연결돼도 같은 방식으로 그림과 요약에 반영된다.
export function useCatalogDetails(ids: string[]) {
  const [entries, setEntries] = useState<Record<string, DetailEntry>>({})
  const started = useRef(new Set<string>())
  const controllers = useRef(new Map<string, AbortController>())
  const idKey = JSON.stringify([...new Set(ids)].sort())

  const fetchDetail = useCallback((id: string) => {
    controllers.current.get(id)?.abort()
    const controller = new AbortController()
    controllers.current.set(id, controller)
    started.current.add(id)
    catalogClient.get(id, controller.signal).then(
      (detail) => {
        if (!controller.signal.aborted) setEntries((prev) => ({ ...prev, [id]: { status: 'done', detail } }))
      },
      (error: unknown) => {
        if (!controller.signal.aborted) setEntries((prev) => ({ ...prev, [id]: { status: 'error',
          message: error instanceof Error ? error.message : '제원을 불러오지 못했습니다.' } }))
      },
    ).finally(() => {
      if (controllers.current.get(id) === controller) controllers.current.delete(id)
    })
  }, [])

  useEffect(() => {
    for (const id of JSON.parse(idKey) as string[]) {
      if (started.current.has(id)) continue
      fetchDetail(id)
    }
  }, [idKey, fetchDetail])

  useEffect(() => {
    const startedIds = started.current
    const running = controllers.current
    return () => {
      running.forEach((controller) => controller.abort())
      running.clear()
      // 개발 모드에서 화면을 다시 붙이면 중단된 요청을 다시 보내도록 비운다.
      startedIds.clear()
    }
  }, [])

  function retry(id: string) {
    if (!ids.includes(id)) return
    setEntries((prev) => ({ ...prev, [id]: { status: 'loading' } }))
    fetchDetail(id)
  }

  return { entries, retry }
}
