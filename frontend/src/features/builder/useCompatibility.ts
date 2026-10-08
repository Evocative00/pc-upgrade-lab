import { useEffect, useState } from 'react'
import type { PartDraft } from '../pc/types.ts'
import { buildCompatibilityRequest, checkCompatibility, type CompatibilityResult } from './compatibility.ts'

export type CompatibilityState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'done'; result: CompatibilityResult }
  | { status: 'error'; message: string }

// 부품이 바뀌면 잠시 기다렸다가 다시 검사한다. 이전 요청은 취소한다.
export function useCompatibility(drafts: PartDraft[], enabled: boolean): CompatibilityState {
  const request = enabled ? buildCompatibilityRequest(drafts) : null
  const requestKey = request && JSON.stringify(request)
  const [state, setState] = useState<{ key: string | null; value: CompatibilityState }>({ key: null, value: { status: 'idle' } })

  useEffect(() => {
    if (!requestKey) return
    const controller = new AbortController()
    const timer = setTimeout(() => {
      checkCompatibility(JSON.parse(requestKey), controller.signal).then(
        (result) => setState({ key: requestKey, value: { status: 'done', result } }),
        (error: unknown) => {
          if (!controller.signal.aborted) setState({ key: requestKey, value: { status: 'error',
            message: error instanceof Error ? error.message : '호환성 검사에 실패했습니다.' } })
        },
      )
    }, 300)
    return () => {
      clearTimeout(timer)
      controller.abort()
    }
  }, [requestKey])

  if (!requestKey) return { status: 'idle' }
  return state.key === requestKey ? state.value : { status: 'loading' }
}
