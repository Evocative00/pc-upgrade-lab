import type { CatalogClient } from '../catalog/catalogClient.ts'
import { isCurrentPriceIncluded } from '../catalog/catalogPresentation.ts'
import type { CatalogAttribution, CatalogDetail, CatalogProduct, CatalogSpecification } from '../catalog/catalogTypes.ts'
import { productScore, ramModules, UPGRADE_PART_KINDS, type PricedProduct, type UpgradePartKind } from './upgradeRecommendation.ts'

export type UpgradeProducts = Partial<Record<UpgradePartKind, PricedProduct[]>>

// 검색 응답과 상세 응답의 ID·종류·판매 단위를 함께 확인한다.
// active(is_active)는 판매 중 여부가 아니다. 카탈로그 상품은 기본이 미검증·비활성이고(중앙 가격 자료 227종 모두 false)
// 시드도 활성 상품을 거부하므로, 이 값으로 걸러내면 추천 후보가 하나도 남지 않는다. 판매 여부는 가격 자료로만 판단한다.
export function toPricedProduct(detail: Pick<CatalogDetail, 'product' | 'specification' | 'attributions'>): PricedProduct | null {
  const { product, specification } = detail
  const current = product.priceStatus?.origin === 'SHARED' && isCurrentPriceIncluded(product) ? product.currentPrice : null
  // 중앙 서버가 정상 조회했지만 관측 후 7일이 지나 현재가에서 빠진 가격은 '지난 판매가'로 표시해 쓴다.
  // 연결 실패·로컬 가격은 쓰지 않는다.
  const status = product.priceStatus
  const past = !current && status?.origin === 'SHARED' && status.lookupStatus === 'OK' && status.freshness === 'EXPIRED'
    ? product.currentPrice : null
  const estimate = product.referenceEstimate
  const price = current ?? past ?? estimate
  if (!price || !Number.isSafeInteger(price.amountKrw) || price.amountKrw <= 0) return null
  if (product.type === 'RAM') {
    const modules = ramModules(specification)
    if (!modules || !current && !past && estimate?.moduleCount !== modules.quantity) return null
  }
  return { id: product.id, name: `${product.manufacturer} ${product.modelName}`, priceKrw: price.amountKrw,
    priceKind: current ? 'CURRENT' : past ? 'PAST' : 'ESTIMATE', priceSource: (current ?? past)?.sourceName ?? null,
    priceUrl: (current ?? past)?.sourceUrl ?? null, observedAt: (current ?? past)?.observedAt ?? null, specification, attributions: detail.attributions }
}

type Candidate = { product: CatalogProduct; specification?: CatalogSpecification; attributions: CatalogAttribution[] }

export async function loadUpgradeProducts(client: CatalogClient, signal: AbortSignal,
  kinds: readonly UpgradePartKind[] = UPGRADE_PART_KINDS): Promise<UpgradeProducts> {
  signal.throwIfAborted()
  const lists = await Promise.all(UPGRADE_PART_KINDS.filter((kind) => kinds.includes(kind)).map(async (kind) => {
    const found = new Map<string, Candidate>()
    for (let page = 0; page < 5; page++) {
      const result = await client.search(kind, '', { page, size: 100, signal, includeSpecifications: true })
      signal.throwIfAborted()
      for (const product of result.items) {
        const specification = result.specifications?.[product.id]
        if (result.specifications !== undefined &&
          (!Object.hasOwn(result.specifications, product.id) || !specification)) {
          throw new Error('추천 상품과 일치하는 제원 자료가 없습니다.')
        }
        if (product.type === kind &&
          (product.currentPrice || product.referenceEstimate) &&
          (kind === 'RAM' || productScore(kind, `${product.manufacturer} ${product.modelName}`, kind === 'CPU' ? 'GAME' : 'WORK') !== null ||
            kind === 'CPU' && productScore(kind, product.modelName, 'WORK') !== null)) {
          found.set(product.id, { product, specification, attributions: result.attributions })
        }
      }
      if (page + 1 >= result.totalPages) break
    }
    return { kind, products: [...found.values()] }
  }))
  signal.throwIfAborted()
  const jobs = lists.flatMap(({ kind, products }) => products.map(({ product, specification, attributions }) => ({
    kind, product, needsDetail: specification === undefined,
    priced: specification === undefined ? null : toPricedProduct({ product, specification, attributions }),
  })))
  const fallback = jobs.filter((job) => job.needsDetail)
  const output: UpgradeProducts = { CPU: [], GPU: [], RAM: [] }
  // 묶음 응답을 지원하지 않는 서버에서만 이전 방식으로 제원을 확인한다.
  let cursor = 0
  await Promise.all(Array.from({ length: Math.min(6, fallback.length) }, async () => {
    while (cursor < fallback.length) {
      signal.throwIfAborted()
      const job = fallback[cursor++]
      const detail = await client.get(job.product.id, signal)
      signal.throwIfAborted()
      if (detail.product.id !== job.product.id || detail.product.type !== job.kind) throw new Error('추천 상품의 ID·종류가 일치하지 않습니다.')
      job.priced = toPricedProduct(detail)
    }
  }))
  signal.throwIfAborted()
  for (const { kind, priced } of jobs) if (priced) output[kind]!.push(priced)
  return output
}
