import { csrfHeaders } from '../../lib/csrf.ts'
import type { PcDetail, PcPage, PcRequest, PcSummary } from './types.ts'

// 화면은 이 인터페이스만 사용한다. 실제 저장·조회는 Spring Boot의 /api/pcs가 담당한다.
export interface PcRepository {
  list(page?: number, size?: number): Promise<PcPage>
  get(id: number): Promise<PcDetail | null>
  create(request: PcRequest): Promise<PcDetail>
  update(id: number, request: PcRequest): Promise<PcDetail>
  delete(id: number): Promise<void>
}

type FieldError = { field: string; message: string }

export class PcApiError extends Error {
  readonly status: number | null
  readonly code: string
  readonly errors: FieldError[]

  constructor(message: string, status: number | null, code: string, errors: FieldError[] = []) {
    super(message)
    this.name = 'PcApiError'
    this.status = status
    this.code = code
    this.errors = errors
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isSummary(value: unknown): value is PcSummary {
  return isObject(value) && Number.isSafeInteger(value.id) && Number(value.id) > 0 &&
    typeof value.name === 'string' && typeof value.createdAt === 'string' &&
    typeof value.updatedAt === 'string'
}

// 다른 서버의 HTML/JSON을 저장 성공으로 처리하지 않도록 화면에 필요한 응답 구조를 확인한다.
// 브라우저에 임시 보관한 초안을 다시 읽을 때도 같은 기준을 사용한다.
export function isPcRequest(value: unknown): value is PcRequest {
  if (!isObject(value) || typeof value.name !== 'string' || !Array.isArray(value.parts)) return false

  const types = ['CPU', 'COOLER', 'MOTHERBOARD', 'RAM', 'GPU', 'STORAGE', 'PSU', 'CASE', 'MONITOR']
  return value.parts.every((part: unknown) => isObject(part) &&
    typeof part.type === 'string' && types.includes(part.type) &&
    typeof part.displayName === 'string' &&
    (part.rawName === null || typeof part.rawName === 'string') &&
    Number.isInteger(part.quantity) && Number(part.quantity) >= 1 && Number(part.quantity) <= 64 &&
    (part.source === 'AUTO' || part.source === 'MANUAL') &&
    (part.matchStatus === 'MATCHED' || part.matchStatus === 'UNMATCHED') &&
    (part.catalogProductId === null || typeof part.catalogProductId === 'string') &&
    (part.catalogModelId === undefined || part.catalogModelId === null ||
      typeof part.catalogModelId === 'string' && part.catalogModelId.trim().length > 0 && part.catalogModelId.length <= 128) &&
    (part.recognitionLevel === undefined || part.recognitionLevel === null ||
      ['SPEC_GROUP', 'MODEL', 'PHYSICAL_VARIANT'].includes(String(part.recognitionLevel))) &&
    ((part.catalogModelId === undefined || part.catalogModelId === null)
      ? part.recognitionLevel === undefined || part.recognitionLevel === null ||
        part.recognitionLevel === 'PHYSICAL_VARIANT' && part.matchStatus === 'MATCHED' && !!part.catalogProductId
      : part.recognitionLevel !== undefined && part.recognitionLevel !== null &&
        (part.recognitionLevel !== 'PHYSICAL_VARIANT' || part.matchStatus === 'MATCHED' && !!part.catalogProductId)) &&
    isObject(part.specs))
}

function isDetail(value: unknown): value is PcDetail {
  return isSummary(value) && isPcRequest(value)
}

// 삭제 성공(204)은 본문이 없다. 본문 읽기 실패는 null로 처리된다.
function isEmpty(value: unknown): value is null {
  return value === null
}

function isPage(value: unknown): value is PcPage {
  return isObject(value) && Array.isArray(value.items) && value.items.every(isSummary) &&
    Number.isInteger(value.page) && Number(value.page) >= 0 &&
    Number.isInteger(value.size) && Number(value.size) > 0 &&
    Number.isInteger(value.totalElements) && Number(value.totalElements) >= 0 &&
    Number.isInteger(value.totalPages) && Number(value.totalPages) >= 0
}

function responseError(status: number, body: unknown): PcApiError {
  const data = isObject(body) ? body : {}
  const errors = Array.isArray(data.errors)
    ? data.errors.filter((item): item is FieldError => isObject(item) &&
        typeof item.field === 'string' && typeof item.message === 'string')
    : []
  const fallback = status === 401
    ? '로그인이 필요합니다. 작성 중인 내용은 유지됩니다.'
    : status === 409
    ? '다른 요청과 수정이 겹쳤습니다. 입력 내용을 복사해 두고 최신 PC 정보를 확인해 주세요.'
    : `서버 요청에 실패했습니다 (HTTP ${status}). 입력 내용은 유지됩니다.`
  return new PcApiError(
    typeof data.message === 'string' && data.message.trim() ? data.message : fallback,
    status,
    typeof data.code === 'string' ? data.code : 'HTTP_ERROR',
    errors,
  )
}

// 테스트에서는 fetch와 주소를 바꿀 수 있다. 화면에서는 같은 출처의 /api만 사용한다.
// onUnauthorized: 401(로그인 필요·세션 만료)을 받으면 호출한다. 화면은 로그인 상태를 비우는 데 사용한다.
export function createHttpPcRepository(
  fetcher: typeof fetch = globalThis.fetch,
  baseUrl = '/api/pcs',
  timeoutMs = 10_000,
  onUnauthorized: () => void = () => {},
): PcRepository {
  async function request<T>(
    path: string,
    method: 'GET' | 'POST' | 'PUT' | 'DELETE',
    expectedStatus: number,
    isValid: (value: unknown) => value is T,
    payload?: PcRequest,
  ): Promise<T> {
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), timeoutMs)
    const isWrite = method !== 'GET'

    try {
      const response = await fetcher(`${baseUrl}${path}`, {
        method,
        headers: {
          Accept: 'application/json',
          ...(payload !== undefined ? { 'Content-Type': 'application/json' } : {}),
          ...(isWrite ? csrfHeaders() : {}),
        },
        body: payload === undefined ? undefined : JSON.stringify(payload),
        signal: controller.signal,
        cache: 'no-store',
      })
      const body: unknown = await response.json().catch(() => null)
      // 헤더를 받은 뒤 본문 읽기가 지연된 경우도 시간 초과로 구분한다.
      if (controller.signal.aborted) throw new DOMException('Request timed out', 'AbortError')
      if (response.status === 401) onUnauthorized()
      if (!response.ok) throw responseError(response.status, body)
      if (response.status !== expectedStatus || !isValid(body)) {
        throw new PcApiError(
          '서버 응답을 확인할 수 없습니다.' +
            (isWrite ? ' 저장 여부를 목록에서 확인한 뒤 다시 시도해 주세요.' : ' 백엔드 실행 상태를 확인해 주세요.'),
          response.status, 'INVALID_RESPONSE',
        )
      }
      return body
    } catch (error) {
      if (error instanceof PcApiError) throw error
      const message = controller.signal.aborted
        ? '서버 응답 시간이 초과되었습니다.'
        : '서버에 연결하지 못했습니다. 백엔드 실행 상태를 확인해 주세요.'
      // 전송 후 연결이 끊겼다면 서버에는 이미 저장됐을 수도 있다. 자동 재전송은 하지 않는다.
      throw new PcApiError(
        message + (isWrite ? ' 입력 내용은 유지됩니다. 저장 여부를 목록에서 확인한 뒤 다시 시도해 주세요.' : ''),
        null, controller.signal.aborted ? 'REQUEST_TIMEOUT' : 'NETWORK_ERROR',
      )
    } finally {
      clearTimeout(timeout)
    }
  }

  return {
    list: (page = 0, size = 20) => request(`?page=${page}&size=${size}`, 'GET', 200, isPage),
    async get(id) {
      try {
        return await request(`/${id}`, 'GET', 200, isDetail)
      } catch (error) {
        // PC 없음과 프록시/주소 설정 오류를 구분한다. 수정 요청의 404는 그대로 오류로 전달한다.
        if (error instanceof PcApiError && error.status === 404 && error.code === 'PC_NOT_FOUND') return null
        throw error
      }
    },
    create: (payload) => request('', 'POST', 201, isDetail, payload),
    update: (id, payload) => request(`/${id}`, 'PUT', 200, isDetail, payload),
    async delete(id) {
      await request(`/${id}`, 'DELETE', 204, isEmpty)
    },
  }
}
