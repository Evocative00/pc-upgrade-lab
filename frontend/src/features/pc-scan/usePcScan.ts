import { useEffect, useRef, useState } from 'react'
import type { ScanResult, ScanSession, ScanStatus, ScanView } from './types'

type UiStatus = ScanStatus | 'IDLE' | 'PREPARING' | 'CONNECTION_ERROR'
const terminal: ScanStatus[] = ['COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED', 'EXPIRED']

// HTTP 오류는 서버의 공통 안내를 읽어 예외로 바꾼다. T는 TypeScript 타입 표기이며 JSON 전체 검증기는 아니다.
async function checkedJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const body: unknown = await response.json().catch(() => null)
    if (body && typeof body === 'object' && 'message' in body && typeof body.message === 'string') {
      throw new Error(body.message)
    }
    throw new Error(`서버 요청 실패 (${response.status})`)
  }
  return response.json() as Promise<T>
}

/**
 * 자동 인식의 통신과 상태를 담당하는 커스텀 훅(여러 컴포넌트에서 사용할 수 있는 React 상태 로직).
 * prepare: 검사 생성, reset: 화면에서 대기 취소, result: 수집 결과를 제공한다.
 * Windows 프로그램 실행은 패널의 링크 클릭으로, 입력 폼 반영과 PC 저장은 연결한 화면에서 처리한다.
 */
export function usePcScan() {
  const [session, setSession] = useState<ScanSession | null>(null)
  const [status, setStatus] = useState<UiStatus>('IDLE')
  const [result, setResult] = useState<ScanResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  // 새 검사·취소마다 번호를 바꾼다. 이전 요청이 늦게 끝나도 현재 화면을 덮어쓰지 못하게 하는 기준이다.
  const requestGeneration = useRef(0)
  const prepareController = useRef<AbortController | null>(null)

  // 컴포넌트가 사라지면 진행 중인 준비 요청을 중단하고, 남아 있는 응답도 무효화한다.
  useEffect(() => () => {
    requestGeneration.current += 1
    prepareController.current?.abort()
  }, [])

  // 이전 화면 상태를 비운 뒤 새 세션을 만든다. 프로그램 실행 자체는 사용자의 명시적인 링크 클릭을 기다린다.
  async function prepare() {
    const generation = ++requestGeneration.current
    prepareController.current?.abort()
    const controller = new AbortController()
    prepareController.current = controller
    setSession(null)
    setResult(null)
    setError(null)
    setStatus('PREPARING')
    try {
      const created = await checkedJson<ScanSession>(await fetch('/api/scan-sessions', {
        method: 'POST', headers: { 'X-PCUL-Client': 'web' },
        // 요청 한 번의 제한은 8초. 서버가 정한 검사 전체 제한 시간(2분)과는 별개다.
        signal: AbortSignal.any([controller.signal, AbortSignal.timeout(8000)]),
      }))
      if (generation !== requestGeneration.current) return
      // 설치한 수집기가 사용하는 URI 형식과 유효한 만료 시각만 실행 링크로 받아들인다.
      if (!/^pcupgradelab:\/\/scan\/\?id=[0-9a-f-]{36}&token=[A-Za-z0-9_-]{43}$/.test(created.launchUri)
          || !Number.isFinite(Date.parse(created.expiresAt))) {
        throw new Error('검사 준비 응답이 올바르지 않습니다.')
      }
      setSession(created)
      setStatus('CREATED')
    } catch (cause) {
      if (generation !== requestGeneration.current) return
      setStatus('CONNECTION_ERROR')
      setError(cause instanceof Error ? cause.message : '검사 준비에 실패했습니다.')
    }
  }

  // 세션이 생기면 상태를 반복 조회한다(폴링). 세션 교체·화면 취소 시 정리 함수가 기존 요청과 타이머를 멈춘다.
  useEffect(() => {
    if (!session) return
    const controller = new AbortController()
    const generation = requestGeneration.current
    let timer: ReturnType<typeof setTimeout> | undefined
    let stopped = false
    async function poll() {
      if (stopped || generation !== requestGeneration.current || !session) return
      try {
        const view = await checkedJson<ScanView>(await fetch(`/api/scan-sessions/${session.sessionId}`, {
          headers: { 'X-Scan-Token': session.readToken }, cache: 'no-store',
          signal: AbortSignal.any([controller.signal, AbortSignal.timeout(8000)]),
        }))
        if (stopped || generation !== requestGeneration.current) return
        if (view.sessionId !== session.sessionId) throw new Error('검사 요청이 일치하지 않습니다.')
        setStatus(view.status)
        setResult(view.result)
        setError(view.failure?.message ?? null)
        if (terminal.includes(view.status)) return
        // 경계 시점에 서버가 이미 완료한 결과를 읽을 여유를 둔다. 서버의 수집 마감 시간을 늘리는 처리는 아니다.
        if (Date.now() > Date.parse(session.expiresAt) + 5000) {
          setStatus('EXPIRED')
          return
        }
        // 이번 응답 처리가 끝난 뒤 1초 후 다시 조회해, 느린 요청이 여러 개 겹치지 않게 한다.
        timer = setTimeout(() => { void poll() }, 1000)
      } catch (cause) {
        if (stopped || generation !== requestGeneration.current) return
        setStatus('CONNECTION_ERROR')
        setError(cause instanceof Error ? cause.message : '결과를 확인하지 못했습니다.')
      }
    }
    void poll()
    return () => { stopped = true; controller.abort(); clearTimeout(timer) }
  }, [session])

  // 화면의 대기와 결과만 초기화한다. 이미 실행한 Windows 수집기나 서버 세션을 취소하는 API는 호출하지 않는다.
  function reset() {
    requestGeneration.current += 1
    prepareController.current?.abort()
    setSession(null)
    setResult(null)
    setError(null)
    setStatus('IDLE')
  }

  return { status, result, error, launchUri: status === 'CREATED' ? session?.launchUri : undefined, prepare, reset }
}
