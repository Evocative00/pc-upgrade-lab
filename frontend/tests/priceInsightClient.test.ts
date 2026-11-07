import assert from 'node:assert/strict'
import test from 'node:test'
import { CatalogApiError } from '../src/features/catalog/catalogClient.ts'
import { createPriceInsightClient, isPriceInsight } from '../src/features/catalog/priceInsightClient.ts'

const insight = {
  productId: 'cpu-1', canonicalId: '0af25373-4f49-3301-851c-2989163fbf91', basis: 'REGISTERED_OBSERVATIONS',
  methodVersion: 'trend-v2', analyzedAt: '2026-11-07T00:00:00Z', verdict: 'FAIR', reason: 'WITHIN_RANGE',
  stale: true, lowConfidence: true,
  windowStart: '2026-07-12T08:10:24.509Z', windowEnd: '2026-10-10T08:10:24.509Z',
  minComparableDays: 2, minObservedDays: 14, staleAfterDays: 7,
  sampleCount: 3, observedDayCount: 3,
  latest: { amountKrw: 399000, observedAt: '2026-10-10T08:10:24.509Z', sourceName: 'DANAWA',
    externalId: '62653718', saleSku: '100-100001405WOF' },
  medianKrw: 399000, minKrw: 399000, maxKrw: 399000, changeRate7d: null,
}
const history = {
  productId: 'cpu-1', canonicalId: null, series: [{
    sourceName: 'DANAWA', externalId: '62653718', sourceUrl: 'https://prod.danawa.com/info/?pcode=62653718',
    saleSku: '100-100001405WOF', saleUnit: 'PRODUCT', moduleCount: null, points: [
      { amountKrw: 399000, observedAt: '2026-10-08T10:26:57.700Z', evidenceUrl: 'https://example.com/a', origin: 'HISTORY' },
      { amountKrw: 399000, observedAt: '2026-10-10T08:10:24.509Z', evidenceUrl: 'https://example.com/b', origin: 'CATALOG' },
    ],
  }],
}

function fetcherFor(body: unknown, status = 200, calls: string[] = []): typeof fetch {
  return (async (input: RequestInfo | URL) => {
    calls.push(String(input))
    return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch
}

test('reads insight and history from the product-scoped GET endpoints', async () => {
  const calls: string[] = []
  const client = createPriceInsightClient(fetcherFor(insight, 200, calls))
  const result = await client.insight('cpu-1')
  assert.equal(result.verdict, 'FAIR')
  assert.equal(result.stale, true)
  assert.equal(result.lowConfidence, true)
  const historyClient = createPriceInsightClient(fetcherFor(history, 200, calls))
  assert.equal((await historyClient.history('cpu-1')).series[0].points.length, 2)
  assert.deepEqual(calls, ['/api/catalog/products/cpu-1/price-insight', '/api/catalog/products/cpu-1/price-history'])
})

test('rejects inconsistent verdicts, non-UTC timestamps and zero prices', () => {
  assert.equal(isPriceInsight(insight), true)
  assert.equal(isPriceInsight({ ...insight, verdict: 'GOOD_PRICE', reason: 'BELOW_MEDIAN' }), true)
  assert.equal(isPriceInsight({ ...insight, verdict: 'INSUFFICIENT_DATA' }), false)
  assert.equal(isPriceInsight({ ...insight, reason: 'TOO_FEW_DAYS' }), false)
  // 최신화 여부는 사유가 아니라 표시로 전달한다.
  assert.equal(isPriceInsight({ ...insight, verdict: 'INSUFFICIENT_DATA', reason: 'LATEST_EXPIRED' }), false)
  // 판정이 없는데 관측일 부족 표시가 붙은 응답은 거절한다.
  assert.equal(isPriceInsight({ ...insight, verdict: 'INSUFFICIENT_DATA', reason: 'TOO_FEW_DAYS' }), false)
  assert.equal(isPriceInsight({ ...insight, verdict: 'INSUFFICIENT_DATA', reason: 'TOO_FEW_DAYS', lowConfidence: false }), true)
  assert.equal(isPriceInsight({ ...insight, stale: 'yes' }), false)
  assert.equal(isPriceInsight({ ...insight, minComparableDays: undefined }), false)
  assert.equal(isPriceInsight({ ...insight, medianKrw: null }), false)
  assert.equal(isPriceInsight({ ...insight, analyzedAt: '2026-11-07T09:00:00+09:00' }), false)
  assert.equal(isPriceInsight({ ...insight, latest: { ...insight.latest, amountKrw: 0 } }), false)
  assert.equal(isPriceInsight({ ...insight, basis: 'REFERENCE_ESTIMATE' }), false)
  assert.equal(isPriceInsight({ ...insight, latest: null }), false)
  assert.equal(isPriceInsight({ ...insight, verdict: 'INSUFFICIENT_DATA', reason: 'NO_OBSERVATION', latest: null,
    stale: false, lowConfidence: false, sampleCount: 0, observedDayCount: 0,
    medianKrw: null, minKrw: null, maxKrw: null }), true)
})

test('rejects a response for another product and surfaces 404 codes', async () => {
  await assert.rejects(createPriceInsightClient(fetcherFor({ ...insight, productId: 'other' })).insight('cpu-1'),
    (error: unknown) => error instanceof CatalogApiError && error.code === 'INVALID_RESPONSE')
  await assert.rejects(createPriceInsightClient(fetcherFor({ code: 'CATALOG_PRODUCT_NOT_FOUND', message: '부품을 찾을 수 없습니다.' }, 404))
    .history('missing'), (error: unknown) => error instanceof CatalogApiError && error.status === 404 &&
      error.code === 'CATALOG_PRODUCT_NOT_FOUND')
  const badRam = { ...history, series: [{ ...history.series[0], saleUnit: 'RAM_KIT', moduleCount: null }] }
  await assert.rejects(createPriceInsightClient(fetcherFor(badRam)).history('cpu-1'),
    (error: unknown) => error instanceof CatalogApiError && error.code === 'INVALID_RESPONSE')
})
