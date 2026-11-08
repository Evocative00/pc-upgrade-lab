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

test('만료 가격은 현재가가 아닌 지난 판매가로 쓴다', () => {
  const value = detail()
  Object.assign(value.product.priceStatus!, { freshness: 'EXPIRED', includedInTotal: false })
  const priced = toPricedProduct(value)
  assert.equal(priced?.priceKind, 'PAST')
  assert.equal(priced?.observedAt, time)
})

for (const [name, status] of [
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
  const detailCalls: string[] = []
  const result = await loadUpgradeProducts({
    async search(type, _query, options) {
      assert.equal(options?.includeSpecifications, true)
      return { items: type === 'GPU' ? records.map((item) => item.product) : [],
        page: 0, size: 100, totalElements: 2, totalPages: 1, attributions: [] }
    },
    async get(id) { detailCalls.push(id); return records.find((item) => item.product.id === id)! },
  }, new AbortController().signal)
  assert.deepEqual(detailCalls, ['expensive', 'cheap'])
  assert.deepEqual(result.GPU?.map((item) => item.id), ['expensive', 'cheap'])
  assert.equal(result.GPU?.[1].specification?.vramBytes, 12 * 1024 ** 3)
})

test('선택한 종류의 묶음 제원은 상세 조회 없이 목록 가격·출처로 추천한다', async () => {
  const current = detail('current', 400_000)
  const past = detail('past', 200_000)
  const local = detail('local', 100_000)
  Object.assign(past.product.priceStatus!, { freshness: 'EXPIRED', includedInTotal: false })
  Object.assign(local.product.priceStatus!, { origin: 'LOCAL', lookupStatus: 'NOT_IN_SCOPE', freshness: null,
    catalogVersion: null, checkedAt: null, lastSuccessAt: null })
  const attributions = [{ name: 'Example', notice: 'Example source', url: 'https://example.com',
    license: 'Example license', licenseUrl: 'https://example.com/license' }]
  const requested: string[] = []
  const result = await loadUpgradeProducts({
    async search(type, query, options) {
      assert.equal(query, '')
      assert.equal(options?.includeSpecifications, true)
      const page = options?.page ?? 0
      requested.push(`${type}:${page}`)
      const records = page === 0 ? [current, local] : [past]
      return { items: records.map(({ product }) => product),
        specifications: Object.fromEntries(records.map(({ product, specification }) => [product.id, specification])),
        page, size: 100, totalElements: 3, totalPages: 2, attributions }
    },
    async get() { assert.fail('batched products must not request details') },
  }, new AbortController().signal, ['GPU'])
  assert.deepEqual(requested, ['GPU:0', 'GPU:1'])
  assert.deepEqual(result.GPU, [current, past].map((entry) => toPricedProduct({ ...entry, attributions })))
  assert.deepEqual(result.CPU, [])
  assert.deepEqual(result.RAM, [])
})

test('묶음 제원 일부가 누락되면 상세 조회로 숨기지 않는다', async () => {
  await assert.rejects(loadUpgradeProducts({
    async search() { return { items: [detail().product], specifications: {},
      page: 0, size: 100, totalElements: 1, totalPages: 1, attributions: [] } },
    async get() { assert.fail('partial batched specifications must not fall back') },
  }, new AbortController().signal, ['GPU']), /추천 상품과 일치하는 제원 자료가 없습니다/)
})

test('취소 이후 도착한 묶음 목록은 추천 자료로 확정하지 않는다', async () => {
  const controller = new AbortController()
  const entry = detail()
  const request = loadUpgradeProducts({
    async search() {
      controller.abort()
      return { items: [entry.product], specifications: { [entry.product.id]: entry.specification },
        page: 0, size: 100, totalElements: 1, totalPages: 1, attributions: [] }
    },
    async get() { assert.fail('cancelled request must not fetch details') },
  }, controller.signal, ['GPU'])
  await assert.rejects(request, { name: 'AbortError' })
})

test('카탈로그 기본값인 비활성(미검증) 상품도 가격이 있으면 추천 후보로 쓴다', () => {
  const value = detail()
  value.product.active = false
  value.product.verificationStatus = 'UNVERIFIED'
  assert.equal(toPricedProduct(value)?.priceKind, 'CURRENT')
})
