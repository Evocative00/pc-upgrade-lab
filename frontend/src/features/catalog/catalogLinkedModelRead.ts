import { CatalogApiError } from './catalogClient.ts'
import type { CatalogModelDetail, CatalogProduct } from './catalogTypes.ts'

export type LinkedModelProduct = Pick<CatalogProduct, 'id' | 'type' | 'modelId'>
type ModelDetailReader = { get: (id: string, signal?: AbortSignal) => Promise<CatalogModelDetail> }

export async function readLinkedCatalogModel(client: ModelDetailReader, product: LinkedModelProduct,
  signal?: AbortSignal): Promise<CatalogModelDetail> {
  if (!product.modelId) throw new CatalogApiError('연결된 모델이 없습니다.', null, 'INVALID_INPUT')
  signal?.throwIfAborted()
  const detail = await client.get(product.modelId, signal)
  signal?.throwIfAborted()
  if (detail.model.id !== product.modelId || detail.model.type !== product.type ||
    !detail.productCandidates.some((candidate) => candidate.id === product.id && candidate.type === product.type)) {
    throw new CatalogApiError('제품과 연결된 모델 정보가 일치하지 않습니다. 제품 상세를 새로고침해 주세요.',
      200, 'INVALID_RESPONSE')
  }
  return detail
}
