import { useEffect, useRef, useState } from 'react'
import type { ScanResult, ScanSession, ScanStatus, ScanView } from './types'

type UiStatus = ScanStatus | 'IDLE' | 'PREPARING' | 'CONNECTION_ERROR'
const terminal: ScanStatus[] = ['COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED', 'EXPIRED']

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

export function usePcScan() {
  const [session, setSession] = useState<ScanSession | null>(null)
  const [status, setStatus] = useState<UiStatus>('IDLE')
  const [result, setResult] = useState<ScanResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const requestGeneration = useRef(0)
  const prepareController = useRef<AbortController | null>(null)

  useEffect(() => () => {
    requestGeneration.current += 1
    prepareController.current?.abort()
  }, [])

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
        signal: AbortSignal.any([controller.signal, AbortSignal.timeout(8000)]),
      }))
      if (generation !== requestGeneration.current) return
      // Launch only the exact scheme/grammar owned by our installed collector.
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
        // Allows an in-flight completion to be read once after the local deadline.
        if (Date.now() > Date.parse(session.expiresAt) + 5000) {
          setStatus('EXPIRED')
          return
        }
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
