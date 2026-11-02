import assert from 'node:assert/strict'
import test from 'node:test'
import type { CatalogDetail, CatalogPriceStatus, ReferenceEstimate } from '../src/features/catalog/catalogTypes.ts'
import { loadUpgradeProducts, toPricedProduct } from '../src/features/planner/recommendationProducts.ts'

const time = '2026-11-02T00:00:00Z'
function detail(id = 'gpu', amount = 200_000): CatalogDetail {
  return { product: { id, type: 'GPU', manufacturer: 'NVIDIA', modelName: 'GeForce RTX 3060 12GB',
    partNumber: null, verificationStatus: 'PARTIAL', active: true, createdAt: time, updatedAt: time,
    currentPrice: { amountKrw: amount, sourceName: 'TEST', sourceUrl: 'https://example.com', observedAt: time },
    priceStatus: { origin: 'SHARED', lookupStatus: 'OK', freshness: 'FRESH', catalogVersion: 'test',
      checkedAt: time, lastSuccessAt: time, includedInTotal: true },
    referencePrice: { amountKrw: null, status: 'UNCONFIRMED', updatedAt: time } },
    specification: { vramBytes: 12 * 1024 ** 3 }, sources: [], attributions: [] }
}

for (const [name, status] of [
  ['만료', { freshness: 'EXPIRED', includedInTotal: false }],
  ['연결 실패', { lookupStatus: 'UNAVAILABLE', includedInTotal: false }],
  ['로컬', { origin: 'LOCAL' }],
] as [string, Partial<CatalogPriceStatus>][]) {
  test(`${name} 가격은 추천의 현재가로 쓰지 않는다`, () => {
    const value = detail()
    Object.assign(value.product.priceStatus!, status)
    assert.equal(toPricedProduct(value), null)
  })
}

test('유효한 중앙 가격과 참고 추정가를 구분하고 RAM 판매 단위를 대조한다', () => {
  assert.equal(toPricedProduct(detail())?.priceKind, 'CURRENT')
  const value = detail()
  value.product.type = 'RAM'
  value.product.currentPrice = null
  value.product.referenceEstimate = { amountKrw: 100_000, moduleCount: 2 } as ReferenceEstimate
  value.specification = { moduleCount: 2, moduleCapacityBytes: 16 * 1024 ** 3, memoryType: 'DDR4' }
  assert.equal(toPricedProduct(value)?.priceKind, 'ESTIMATE')
  value.product.referenceEstimate.moduleCount = 1
  assert.equal(toPricedProduct(value), null)
})

test('같은 이름의 다른 상품 ID·가격도 잃지 않고 상세 제원을 가져온다', async () => {
  const records = [detail('expensive', 400_000), detail('cheap', 200_000)]
  const result = await loadUpgradeProducts({
    async search(type) { return { items: type === 'GPU' ? records.map((item) => item.product) : [],
      page: 0, size: 100, totalElements: 2, totalPages: 1, attributions: [] } },
    async get(id) { return records.find((item) => item.product.id === id)! },
  }, new AbortController().signal)
  assert.deepEqual(result.GPU?.map((item) => item.id), ['expensive', 'cheap'])
  assert.equal(result.GPU?.[1].specification?.vramBytes, 12 * 1024 ** 3)
})

test('취소 이후 도착한 목록은 추천 자료로 확정하지 않는다', async () => {
  const controller = new AbortController()
  const request = loadUpgradeProducts({
    async search() { controller.abort(); return { items: [], page: 0, size: 100, totalElements: 0, totalPages: 0, attributions: [] } },
    async get() { return detail() },
  }, controller.signal)
  await assert.rejects(request, { name: 'AbortError' })
})
