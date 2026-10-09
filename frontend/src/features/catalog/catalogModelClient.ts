import type { PartType } from '../pc-scan/types.ts'
import { CatalogApiError } from './catalogClient.ts'
import { createCatalogRead, isNullableText, isRecord, isText } from './catalogRead.ts'
import type { CatalogModel, CatalogModelPage } from './catalogTypes.ts'

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
  }
}
export const catalogModelClient = createCatalogModelClient()
