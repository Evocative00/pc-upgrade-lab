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

export type CompatibilityOptions = { delayMs?: number; reuseSuccess?: boolean }
const MAX_SUCCESS_RESULTS = 8

// 편집 중에는 입력이 멈출 때까지 기다리고, 추천 플랜처럼 선택한 구성은 바로 검사할 수 있다.
export function useCompatibility(drafts: PartDraft[], enabled: boolean,
  { delayMs = 300, reuseSuccess = false }: CompatibilityOptions = {}): CompatibilityState {
  const request = enabled ? buildCompatibilityRequest(drafts) : null
  const localChecks = enabled ? localCompatibilityChecks(drafts) : []
  const inputKey = request || localChecks.length > 0 ? JSON.stringify({ request, localChecks }) : null
  const [state, setState] = useState<{
    key: string | null; value: CompatibilityState; successes: { key: string; result: CompatibilityResult }[]
  }>({ key: null, value: { status: 'idle' }, successes: [] })
  const cached = reuseSuccess ? state.successes.find((entry) => entry.key === inputKey)?.result : undefined
  const generation = useRef(0)

  useEffect(() => {
    if (!inputKey || cached) return
    const input = JSON.parse(inputKey) as { request: CompatibilityRequest | null; localChecks: CompatibilityCheck[] }
    if (!input.request) return
    const currentGeneration = ++generation.current
    const controller = new AbortController()
    const run = () => {
      if (controller.signal.aborted) return
      checkCompatibility(input.request!, controller.signal).then(
        (result) => {
          if (controller.signal.aborted || generation.current !== currentGeneration) return
          const merged = mergeCompatibilityChecks(result, input.localChecks)
          setState((previous) => previous.key === inputKey ? {
            key: inputKey, value: { status: 'done', result: merged },
            successes: reuseSuccess ? [...previous.successes.filter((entry) => entry.key !== inputKey)
              .slice(-(MAX_SUCCESS_RESULTS - 1)), { key: inputKey, result: merged }] : previous.successes,
          } : previous)
        },
        (error: unknown) => {
          if (controller.signal.aborted || generation.current !== currentGeneration) return
          setState((previous) => previous.key === inputKey ? { ...previous, value: { status: 'error',
            message: error instanceof Error ? error.message : '호환성 검사에 실패했습니다.' } } : previous)
        },
      )
    }
    const timer = delayMs > 0 ? setTimeout(run, delayMs) : undefined
    // Strict Mode가 곧바로 정리한 효과는 서버에 요청을 보내지 않는다.
    if (timer === undefined) queueMicrotask(run)
    return () => {
      if (timer !== undefined) clearTimeout(timer)
      controller.abort()
    }
  }, [inputKey, cached, delayMs, reuseSuccess])

  // 새 구성에는 이전 오류나 다른 구성의 성공을 보여 주지 않는다.
  if (state.key !== inputKey) {
    setState({ ...state, key: inputKey, value: { status: inputKey ? 'loading' : 'idle' } })
  }
  if (!inputKey) return { status: 'idle' }
  if (!request) return { status: 'done', result: mergeCompatibilityChecks({ status: 'COMPATIBLE', checks: [] }, localChecks) }
  if (cached) return { status: 'done', result: cached }
  return state.key === inputKey ? state.value : { status: 'loading' }
}
