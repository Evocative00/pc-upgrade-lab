import { isCurrentPriceIncluded } from '../catalog/catalogPresentation.ts'
import type { CatalogClient } from '../catalog/catalogClient.ts'
import type { CatalogDetail, CatalogProduct } from '../catalog/catalogTypes.ts'
import { productScore, ramModules, UPGRADE_PART_KINDS, type PricedProduct, type UpgradePartKind } from './upgradeRecommendation.ts'

export type UpgradeProducts = Partial<Record<UpgradePartKind, PricedProduct[]>>

// 검색 응답과 상세 응답의 ID·종류·판매 단위를 함께 확인한다.
export function toPricedProduct(detail: CatalogDetail): PricedProduct | null {
  const { product, specification } = detail
  const current = product.priceStatus?.origin === 'SHARED' && isCurrentPriceIncluded(product) ? product.currentPrice : null
  const estimate = product.referenceEstimate
  const price = current ?? estimate
  if (!product.active || !price || !Number.isSafeInteger(price.amountKrw) || price.amountKrw <= 0) return null
  if (product.type === 'RAM') {
    const modules = ramModules(specification)
    if (!modules || !current && estimate?.moduleCount !== modules.quantity) return null
  }
  return { id: product.id, name: `${product.manufacturer} ${product.modelName}`, priceKrw: price.amountKrw,
    priceKind: current ? 'CURRENT' : 'ESTIMATE', priceSource: current?.sourceName ?? null,
    priceUrl: current?.sourceUrl ?? null, specification, attributions: detail.attributions }
}

export async function loadUpgradeProducts(client: CatalogClient, signal: AbortSignal): Promise<UpgradeProducts> {
  const lists = await Promise.all(UPGRADE_PART_KINDS.map(async (kind) => {
    const found = new Map<string, CatalogProduct>()
    for (let page = 0; page < 5; page++) {
      const result = await client.search(kind, '', { page, size: 100, signal })
      signal.throwIfAborted()
      for (const product of result.items) {
        if (product.type === kind && product.active &&
          (product.currentPrice || product.referenceEstimate) &&
          (kind === 'RAM' || productScore(kind, `${product.manufacturer} ${product.modelName}`, kind === 'CPU' ? 'GAME' : 'WORK') !== null ||
            kind === 'CPU' && productScore(kind, product.modelName, 'WORK') !== null)) found.set(product.id, product)
      }
      if (page + 1 >= result.totalPages) break
    }
    return { kind, products: [...found.values()] }
  }))
  const jobs = lists.flatMap(({ kind, products }) => products.map((product) => ({ kind, product })))
  const output: UpgradeProducts = { CPU: [], GPU: [], RAM: [] }
  // 상품 제원 요청을 여섯 개씩 처리해 키트 수량·코어·VRAM을 이름에서 확정하지 않는다.
  let cursor = 0
  await Promise.all(Array.from({ length: Math.min(6, jobs.length) }, async () => {
    while (cursor < jobs.length) {
      signal.throwIfAborted()
      const { kind, product } = jobs[cursor++]
      const detail = await client.get(product.id, signal)
      signal.throwIfAborted()
      if (detail.product.id !== product.id || detail.product.type !== kind) throw new Error('추천 상품의 ID·종류가 일치하지 않습니다.')
      const priced = toPricedProduct(detail)
      if (priced) output[kind]!.push(priced)
    }
  }))
  return output
}
