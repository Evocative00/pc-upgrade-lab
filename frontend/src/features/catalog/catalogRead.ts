import { CatalogApiError } from './catalogClient.ts'

export const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
export const isText = (value: unknown, max = 2048): value is string =>
  typeof value === 'string' && value.trim().length > 0 && value.length <= max
export const isNullableText = (value: unknown): boolean => value === null || isText(value)
export const isWebUrl = (value: unknown): boolean => {
  if (!isText(value)) return false
  try { return ['https:', 'http:'].includes(new URL(value).protocol) } catch { return false }
}

export function createCatalogRead(fetcher: typeof fetch = globalThis.fetch, timeoutMs = 10_000) {
  return async function read<T>(url: string, validate: (body: unknown) => body is T, signal?: AbortSignal): Promise<T> {
    const controller = new AbortController()
    const cancel = () => controller.abort()
    signal?.addEventListener('abort', cancel, { once: true })
    if (signal?.aborted) cancel()
    const timer = setTimeout(cancel, timeoutMs)
    try {
      controller.signal.throwIfAborted()
      const response = await fetcher(url, { method: 'GET', headers: { Accept: 'application/json' },
        cache: 'no-store', signal: controller.signal })
      const body: unknown = await response.json().catch(() => null)
      controller.signal.throwIfAborted()
      if (!response.ok) throw new CatalogApiError(isRecord(body) && isText(body.message)
        ? body.message : `카탈로그 조회에 실패했습니다 (HTTP ${response.status}).`, response.status,
      isRecord(body) && isText(body.code) ? body.code : 'HTTP_ERROR')
      if (response.status !== 200 || !validate(body)) throw new CatalogApiError(
        '카탈로그 응답 형식이 올바르지 않습니다. 백엔드 버전을 확인해 주세요.', response.status, 'INVALID_RESPONSE')
      return body
    } catch (error) {
      if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
      if (error instanceof CatalogApiError) throw error
      throw new CatalogApiError(controller.signal.aborted ? '조회 시간이 초과되었습니다.' : '카탈로그 서버에 연결하지 못했습니다.',
        null, controller.signal.aborted ? 'REQUEST_TIMEOUT' : 'NETWORK_ERROR')
    } finally {
      clearTimeout(timer)
      signal?.removeEventListener('abort', cancel)
    }
  }
}
