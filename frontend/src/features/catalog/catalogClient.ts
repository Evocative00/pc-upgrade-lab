import type { PartType } from '../pc-scan/types.ts'
import { PART_TYPES } from '../pc/partCategories.ts'
import type { CatalogAttribution, CatalogDetail, CatalogPage, CatalogProduct } from './catalogTypes.ts'

type SearchOptions = { page?: number; size?: number; signal?: AbortSignal }
export interface CatalogClient {
  search(type: PartType, query: string, options?: SearchOptions): Promise<CatalogPage>
  get(id: string, signal?: AbortSignal): Promise<CatalogDetail>
}

export class CatalogApiError extends Error {
  readonly status: number | null
  readonly code: string

  constructor(message: string, status: number | null, code: string) {
    super(message)
    this.name = 'CatalogApiError'
    this.status = status
    this.code = code
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isText(value: unknown, max = 2048): value is string {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= max
}

function nullableText(value: unknown): boolean {
  return value === null || typeof value === 'string'
}

function isWebUrl(value: unknown): boolean {
  if (!isText(value)) return false
  try {
    return ['http:', 'https:'].includes(new URL(value).protocol)
  } catch { return false }
}

function isProduct(value: unknown): value is CatalogProduct {
  if (!isObject(value) || !isObject(value.referencePrice)) return false
  const price = value.referencePrice
  return isText(value.id, 128) && PART_TYPES.some(({ type }) => type === value.type) &&
    isText(value.manufacturer, 100) && isText(value.modelName, 255) && nullableText(value.partNumber) &&
    ['UNVERIFIED', 'PARTIAL', 'CORE_VERIFIED'].includes(String(value.verificationStatus)) &&
    typeof value.active === 'boolean' && isText(value.createdAt) && isText(value.updatedAt) &&
    isText(price.updatedAt) &&
    ['UNCONFIRMED', 'INSUFFICIENT_HISTORY', 'CONFIRMED'].includes(String(price.status)) &&
    (price.status === 'CONFIRMED'
      ? typeof price.amountKrw === 'number' && Number.isFinite(price.amountKrw) && price.amountKrw > 0
      : price.amountKrw === null)
}

function isAttributions(value: unknown): value is CatalogAttribution[] {
  return Array.isArray(value) && value.every((item: unknown) => isObject(item) &&
    isText(item.name) && isText(item.notice) && isWebUrl(item.url) &&
    isText(item.license) && isWebUrl(item.licenseUrl))
}

function isPage(value: unknown): value is CatalogPage {
  return isObject(value) && Array.isArray(value.items) && value.items.every(isProduct) &&
    Number.isSafeInteger(value.page) && Number(value.page) >= 0 &&
    Number.isSafeInteger(value.size) && Number(value.size) >= 1 && Number(value.size) <= 100 &&
    value.items.length <= Number(value.size) &&
    Number.isSafeInteger(value.totalElements) && Number(value.totalElements) >= 0 &&
    Number.isSafeInteger(value.totalPages) && Number(value.totalPages) >= 0 &&
    isAttributions(value.attributions)
}

function isDetail(value: unknown): value is CatalogDetail {
  if (!isObject(value) || !isProduct(value.product) || !isObject(value.specification)) return false
  // 원본 JSON은 받지 않는다. 제원은 스칼라 값과 GPU 보조전원 목록만 허용한다.
  const validSpecs = Object.keys(value.specification).length > 0 &&
    Object.entries(value.specification).every(([key, item]) =>
      item === null || typeof item === 'string' || typeof item === 'boolean' ||
      (typeof item === 'number' && Number.isFinite(item)) ||
      (key === 'powerConnectors' && Array.isArray(item) && item.every((connector: unknown) =>
        isObject(connector) && isText(connector.connectorType) &&
        Number.isSafeInteger(connector.connectorCount) && Number(connector.connectorCount) > 0)))
  return validSpecs && Array.isArray(value.sources) && value.sources.length > 0 &&
    value.sources.every((source: unknown) => isObject(source) &&
      ['BUILDCORES', 'MANUFACTURER', 'MANUAL'].includes(String(source.sourceName)) &&
      nullableText(source.externalId) && nullableText(source.sourceRevision) &&
      isWebUrl(source.sourceUrl) && isText(source.retrievedAt)) && isAttributions(value.attributions)
}

// 상대 주소를 사용하므로 Vite의 기존 /api 프록시와 연결된다. 오류 시 예시 데이터로 대체하지 않는다.
export function createHttpCatalogClient(
  fetcher: typeof fetch = globalThis.fetch,
  baseUrl = '/api/catalog/products',
  timeoutMs = 10_000,
): CatalogClient {
  async function request<T>(path: string, validate: (body: unknown) => body is T, signal?: AbortSignal): Promise<T> {
    const controller = new AbortController()
    const cancel = () => controller.abort()
    signal?.addEventListener('abort', cancel, { once: true })
    if (signal?.aborted) controller.abort()
    const timeout = setTimeout(() => controller.abort(), timeoutMs)
    try {
      controller.signal.throwIfAborted()
      const response = await fetcher(`${baseUrl}${path}`, {
        method: 'GET', headers: { Accept: 'application/json' },
        cache: 'no-store', signal: controller.signal,
      })
      const body: unknown = await response.json().catch(() => null)
      controller.signal.throwIfAborted()
      if (!response.ok) {
        const code = isObject(body) && typeof body.code === 'string' ? body.code : 'HTTP_ERROR'
        const message = response.status === 404 && code !== 'CATALOG_PRODUCT_NOT_FOUND'
          ? '부품 조회 API를 사용할 수 없습니다. 백엔드가 local 프로필로 실행 중인지 확인해 주세요.'
          : isObject(body) && isText(body.message) ? body.message : `부품 조회에 실패했습니다 (HTTP ${response.status}).`
        throw new CatalogApiError(message, response.status, code)
      }
      if (response.status !== 200 || !validate(body)) {
        throw new CatalogApiError('부품 응답 형식이 올바르지 않습니다. 백엔드 버전을 확인해 주세요.',
          response.status, 'INVALID_RESPONSE')
      }
      return body
    } catch (error) {
      // 사용자가 닫거나 다시 검색한 요청은 실패 메시지를 표시하지 않도록 취소로 전달한다.
      if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
      if (error instanceof CatalogApiError) throw error
      throw new CatalogApiError(controller.signal.aborted
        ? '부품 조회 시간이 초과되었습니다. 다시 시도해 주세요.'
        : '부품 서버에 연결하지 못했습니다. 백엔드 실행 상태를 확인해 주세요.',
      null, controller.signal.aborted ? 'REQUEST_TIMEOUT' : 'NETWORK_ERROR')
    } finally {
      clearTimeout(timeout)
      signal?.removeEventListener('abort', cancel)
    }
  }

  return {
    async search(type, query, { page = 0, size = 20, signal } = {}) {
      const params = new URLSearchParams({ type, q: query.trim(), page: String(page), size: String(size) })
      const result = await request(`?${params}`, isPage, signal)
      if (result.page !== page || result.size !== size || result.items.some((product) => product.type !== type)) {
        throw new CatalogApiError('검색 조건과 다른 부품 응답을 받았습니다.', 200, 'INVALID_RESPONSE')
      }
      return result
    },
    async get(id, signal) {
      const detail = await request(`/${encodeURIComponent(id)}`, isDetail, signal)
      if (detail.product.id !== id) {
        throw new CatalogApiError('선택한 제품과 상세 응답이 일치하지 않습니다.', 200, 'INVALID_RESPONSE')
      }
      return detail
    },
  }
}

export const catalogClient = createHttpCatalogClient()
