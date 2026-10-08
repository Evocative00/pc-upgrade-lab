import { useEffect, useRef, useState } from 'react'
import type { PartDraft } from '../pc/types.ts'
import {
  buildCompatibilityRequest, checkCompatibility, localCompatibilityChecks, mergeCompatibilityChecks,
  type CompatibilityCheck, type CompatibilityRequest, type CompatibilityResult,
} from './compatibility.ts'

export type CompatibilityState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'done'; result: CompatibilityResult }
  | { status: 'error'; message: string }

// 부품이 바뀌면 잠시 기다렸다가 다시 검사한다. 취소된 이전 성공·실패 응답은 반영하지 않는다.
export function useCompatibility(drafts: PartDraft[], enabled: boolean): CompatibilityState {
  const request = enabled ? buildCompatibilityRequest(drafts) : null
  const localChecks = enabled ? localCompatibilityChecks(drafts) : []
  const inputKey = request || localChecks.length > 0 ? JSON.stringify({ request, localChecks }) : null
  const [state, setState] = useState<{ key: string | null; value: CompatibilityState }>({ key: null, value: { status: 'idle' } })
  const generation = useRef(0)

  useEffect(() => {
    if (!inputKey) return
    const input = JSON.parse(inputKey) as { request: CompatibilityRequest | null; localChecks: CompatibilityCheck[] }
    if (!input.request) return
    const currentGeneration = ++generation.current
    const controller = new AbortController()
    const timer = setTimeout(() => {
      checkCompatibility(input.request!, controller.signal).then(
        (result) => {
          if (!controller.signal.aborted && generation.current === currentGeneration) setState({
            key: inputKey, value: { status: 'done', result: mergeCompatibilityChecks(result, input.localChecks) },
          })
        },
        (error: unknown) => {
          if (!controller.signal.aborted && generation.current === currentGeneration) setState({ key: inputKey, value: { status: 'error',
            message: error instanceof Error ? error.message : '호환성 검사에 실패했습니다.' } })
        },
      )
    }, 300)
    return () => {
      clearTimeout(timer)
      controller.abort()
    }
  }, [inputKey])

  // 같은 구성에 돌아와도 재검사가 끝나기 전에는 지난 성공·오류를 표시하지 않는다.
  if (state.key !== inputKey) {
    setState({ key: inputKey, value: { status: inputKey ? 'loading' : 'idle' } })
  }
  if (!inputKey) return { status: 'idle' }
  if (!request) return { status: 'done', result: mergeCompatibilityChecks({ status: 'COMPATIBLE', checks: [] }, localChecks) }
  return state.key === inputKey ? state.value : { status: 'loading' }
}
