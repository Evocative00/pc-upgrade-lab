import type { PartType } from '../pc-scan/types.ts'
import { CatalogApiError } from './catalogClient.ts'
import { createCatalogRead, isNullableText, isRecord, isText, isWebUrl } from './catalogRead.ts'
import type { CatalogModel, CatalogModelDetail, CatalogModelPage, CatalogModelSource } from './catalogTypes.ts'

const kindType: Record<CatalogModel['kind'], PartType> = {
  CPU_MODEL: 'CPU', GPU_CHIP_MODEL: 'GPU', BOARD_MODEL: 'MOTHERBOARD',
  RAM_MODULE_MODEL: 'RAM', RAM_SPEC_GROUP: 'RAM', STORAGE_MODEL: 'STORAGE',
}
export function isCatalogModel(value: unknown): value is CatalogModel {
  return isRecord(value) && isText(value.id, 128) && isText(value.canonicalId, 36) &&
    typeof value.kind === 'string' && kindType[value.kind as CatalogModel['kind']] === value.type &&
    isText(value.manufacturer, 255) && isText(value.modelName, 255) &&
    ['UNASSIGNED', 'INSTALLED_PC_REFERENCE', 'PURCHASE_CANDIDATE', 'BOTH'].includes(String(value.role)) &&
    ['UNVERIFIED', 'PARTIAL', 'CORE_VERIFIED'].includes(String(value.verificationStatus)) &&
    isNullableText(value.family) && isNullableText(value.series) && isText(value.createdAt) && isText(value.updatedAt)
}
function isPage(value: unknown): value is CatalogModelPage {
  return isRecord(value) && Array.isArray(value.items) && value.items.every(isCatalogModel) &&
    Number.isSafeInteger(value.page) && Number(value.page) >= 0 &&
    Number.isSafeInteger(value.size) && Number(value.size) >= 1 && Number(value.size) <= 100 &&
    value.items.length <= Number(value.size) && Number.isSafeInteger(value.totalElements) &&
    Number(value.totalElements) >= 0 && Number.isSafeInteger(value.totalPages) && Number(value.totalPages) >= 0
}
function isModelSource(value: unknown): value is CatalogModelSource {
  return isRecord(value) && ['BUILDCORES', 'MANUFACTURER', 'MANUAL'].includes(String(value.sourceName)) &&
    isNullableText(value.externalId) && isNullableText(value.sourceRevision) &&
    isWebUrl(value.sourceUrl) && isText(value.retrievedAt) && isText(value.reviewScope, 4096)
}
function isDetail(value: unknown): value is CatalogModelDetail {
  if (!isRecord(value) || !isCatalogModel(value.model)) return false
  const type = value.model.type
  return Array.isArray(value.sources) && value.sources.every(isModelSource) &&
    Array.isArray(value.aliases) && value.aliases.every((alias: unknown) => isRecord(alias) &&
      isText(alias.rawAlias) && isText(alias.normalizedAlias) && isModelSource(alias.evidence)) &&
    Array.isArray(value.productCandidates) && value.productCandidates.every((product: unknown) =>
      isRecord(product) && isText(product.id, 128) && product.type === type &&
      isNullableText(product.canonicalId) && isText(product.manufacturer, 255) &&
      isText(product.modelName, 255) && isNullableText(product.partNumber) &&
      ['LEGACY_UNCLASSIFIED', 'MODEL_REFERENCE', 'PHYSICAL_VARIANT', 'RETAIL_KIT'].includes(String(product.identityKind)) &&
      ['UNASSIGNED', 'INSTALLED_PC_REFERENCE', 'PURCHASE_CANDIDATE', 'BOTH'].includes(String(product.role)))
}
export function createCatalogModelClient(fetcher: typeof fetch = globalThis.fetch, baseUrl = '/api/catalog/models') {
  const read = createCatalogRead(fetcher)
  return {
    async search(type: PartType, query: string, { page = 0, size = 20, signal }: {
      page?: number; size?: number; signal?: AbortSignal
    } = {}): Promise<CatalogModelPage> {
      const params = new URLSearchParams({ type, q: query.trim(), page: String(page), size: String(size) })
      const result = await read(`${baseUrl}?${params}`, isPage, signal)
      if (result.page !== page || result.size !== size || result.items.some((model) => model.type !== type)) {
        throw new CatalogApiError('검색 조건과 다른 모델 응답을 받았습니다.', 200, 'INVALID_RESPONSE')
      }
      return result
    },
    async get(id: string, signal?: AbortSignal): Promise<CatalogModelDetail> {
      if (!isText(id, 128)) throw new CatalogApiError('모델 ID를 확인해 주세요.', null, 'INVALID_INPUT')
      const result = await read(`${baseUrl}/${encodeURIComponent(id)}`, isDetail, signal)
      if (result.model.id !== id) {
        throw new CatalogApiError('연결된 모델 ID와 상세 응답이 일치하지 않습니다.', 200, 'INVALID_RESPONSE')
      }
      return result
    },
  }
}
export const catalogModelClient = createCatalogModelClient()
