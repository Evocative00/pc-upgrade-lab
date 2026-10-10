import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { CatalogApiError, createHttpCatalogClient } from '../src/features/catalog/catalogClient.ts'
import type { CatalogDetail, CatalogPage, CatalogPriceStatus, CatalogProduct, CatalogSpecification, ReferenceEstimate } from '../src/features/catalog/catalogTypes.ts'

const attributions = [{ name: 'BuildCores OpenDB', notice: 'Contains data from BuildCores OpenDB.',
  url: 'https://github.com/buildcores/buildcores-open-db', license: 'ODC-By-1.0',
  licenseUrl: 'https://opendatacommons.org/licenses/by/1-0/' }]
const timestamp = '2026-09-30T00:00:00Z'
const details: CatalogDetail[] = ['week2-initial', 'week2-gpu', 'week2-monitor'].flatMap((batch) => {
  // 실제 18종 fixture로 대용량 정수·소수·null·전원 단자 응답을 검사한다.
  const manifest = JSON.parse(readFileSync(new URL(`../../backend/src/main/resources/catalog/seed/${batch}/manifest.json`, import.meta.url), 'utf8')) as {
    items: { product: Pick<CatalogProduct, 'type' | 'manufacturer' | 'modelName' | 'partNumber'>; specification: CatalogSpecification }[]
  }
  return manifest.items.map((item, index) => ({
    product: { ...item.product, id: `ui-fixture-${batch}-${index}`, verificationStatus: 'UNVERIFIED', active: false,
      createdAt: timestamp, updatedAt: timestamp, currentPrice: null,
      referencePrice: { amountKrw: null, status: 'UNCONFIRMED', updatedAt: timestamp } },
    specification: item.specification,
    sources: [{ sourceName: 'BUILDCORES', externalId: 'source-fixture', sourceRevision: 'revision-fixture',
      sourceUrl: 'https://github.com/buildcores/buildcores-open-db', retrievedAt: timestamp }],
    attributions,
  }))
})
const gpu = details.filter(({ product }) => product.type === 'GPU').map(({ product }) => product)
function page(items: CatalogProduct[], overrides: Partial<CatalogPage> = {}): CatalogPage {
  return { items, page: 0, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0, attributions, ...overrides }
}
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const hasCode = (code: string) => (error: unknown) => error instanceof CatalogApiError && error.code === code
const sharedPriceStatus = (overrides: Partial<CatalogPriceStatus> = {}): CatalogPriceStatus => ({
  origin: 'SHARED', lookupStatus: 'OK', freshness: 'FRESH', catalogVersion: 'pilot-2026-10-10-v1',
  checkedAt: '2026-10-10T12:00:00Z', lastSuccessAt: '2026-10-10T11:00:00Z', includedInTotal: true, ...overrides,
})

test('실제 참고가 227종을 현재 상품가와 분리해 읽고 단일 RAM·판매 키트 단위를 보존한다', async () => {
  const preview = JSON.parse(readFileSync(new URL('../../data/catalog-shared/reference-prices-preview-2026-10-10.json', import.meta.url), 'utf8')) as {
    publicationApproved: boolean; products: { identity: { type: CatalogProduct['type'] }; referenceEstimate: ReferenceEstimate }[]
  }
  assert.equal(preview.publicationApproved, false)
  assert.equal(preview.products.length, 227)
  for (const record of preview.products) {
    const product = { ...gpu[0], type: record.identity.type, currentPrice: null, referenceEstimate: record.referenceEstimate }
    const client = createHttpCatalogClient(async () => json(page([product])))
    const result = (await client.search(product.type, '')).items[0]
    assert.deepEqual(result.referenceEstimate, record.referenceEstimate)
    assert.equal(result.currentPrice, null)
  }
})

test('참고가 근거·범위·원가격 단위·실제 날짜가 모순되면 API 응답을 거절한다', async () => {
  const preview = JSON.parse(readFileSync(new URL('../../data/catalog-shared/reference-prices-preview-2026-10-10.json', import.meta.url), 'utf8'))
  const base = preview.products.find((p: { identity: { type: string }; referenceEstimate: ReferenceEstimate }) =>
    p.identity.type === 'GPU' && p.referenceEstimate.confidence === 'ESTIMATED').referenceEstimate as ReferenceEstimate
  const invalid = [
    { ...base, amountKrw: 0 }, { ...base, rangeLowKrw: base.amountKrw + 1 }, { ...base, rangeHighKrw: null },
    { ...base, sourceDate: '2026-02-30' }, { ...base, confidence: 'VERIFIED_MODEL' },
    { ...base, saleUnit: 'RAM_KIT', moduleCount: 2 },
    { ...base, sourceQuotes: [{ ...base.sourceQuotes[0], sourceUrl: 'http://shop.example/p/1' }] },
    { ...base, sourceQuotes: [{ ...base.sourceQuotes[0], moduleCount: 2 }] },
    { ...base, sourceQuotes: [] },
    { ...base, basis: 'LAUNCH_PRICE', confidence: 'VERIFIED_MODEL', rangeLowKrw: null, rangeHighKrw: null, identityScope: 'EXACT_PRODUCT' },
  ]
  for (const referenceEstimate of invalid) {
    const client = createHttpCatalogClient(async () => json(page([{ ...gpu[0], referenceEstimate } as CatalogProduct])))
    await assert.rejects(() => client.search('GPU', ''), hasCode('INVALID_RESPONSE'))
  }
})

test('목록 규격·페이지·검색어 인코딩을 API와 맞춘다', async () => {
  const calls: { url: string; init: RequestInit }[] = []
  const expected = page(gpu.slice(4), { page: 2, size: 2, totalElements: 6, totalPages: 3 })
  const client = createHttpCatalogClient(async (url, init) => {
    calls.push({ url: String(url), init: init ?? {} })
    return json(expected)
  })
  assert.deepEqual(await client.search('GPU', '  RTX / + & %  ', { page: 2, size: 2 }), expected)
  const url = new URL(calls[0].url, 'http://test.invalid')
  assert.equal(url.pathname, '/api/catalog/products')
  assert.deepEqual(Object.fromEntries(url.searchParams), { type: 'GPU', q: 'RTX / + & %', page: '2', size: '2' })
  assert.equal(calls[0].init.method, 'GET')
  assert.equal(calls[0].init.cache, 'no-store')
  assert.equal(calls[0].init.body, undefined)
})

test('18종 제원과 null 가격·출처를 상세로 읽는다', async () => {
  assert.equal(details.length, 18)
  for (const detail of details) {
    const client = createHttpCatalogClient(async (url) => {
      assert.equal(String(url), `/api/catalog/products/${detail.product.id}`)
      return json(detail)
    })
    assert.deepEqual(await client.get(detail.product.id), detail)
  }
})

test('빈 목록은 정상 응답이며 미확정 가격을 0으로 바꾸지 않는다', async () => {
  const emptyClient = createHttpCatalogClient(async () => json(page([])))
  assert.deepEqual((await emptyClient.search('STORAGE', '')).items, [])
  const client = createHttpCatalogClient(async () => json(page(gpu)))
  assert.equal((await client.search('GPU', '')).items[0].referencePrice.amountKrw, null)
  assert.equal((await client.search('GPU', '')).items[0].currentPrice, null)
})

test('현재 상품가의 금액·출처·확인 시각을 목록과 상세에서 읽는다', async () => {
  const product = { ...gpu[0], currentPrice: { amountKrw: 123456, sourceName: '판매처',
    sourceUrl: 'https://shop.example/products/exact-model', observedAt: '2026-10-06T01:02:03.123456Z' } }
  const client = createHttpCatalogClient(async () => json(page([product])))
  assert.deepEqual((await client.search('GPU', '')).items[0].currentPrice, product.currentPrice)
  const detail = { ...details.find((item) => item.product.id === product.id)!, product }
  const detailClient = createHttpCatalogClient(async () => json(detail))
  assert.deepEqual((await detailClient.get(product.id)).product.currentPrice, product.currentPrice)
})

test('중앙 신선도·미확인·미등록·장애 캐시와 범위 밖 로컬 가격을 목록·상세에서 구분한다', async () => {
  const price = { amountKrw: 123456, sourceName: 'DANAWA', sourceUrl: 'https://shop.example/p/1', observedAt: timestamp }
  const combinations = [
    { currentPrice: price, priceStatus: sharedPriceStatus() },
    { currentPrice: price, priceStatus: sharedPriceStatus({ lastSuccessAt: '2026-10-10T12:00:00.100Z' }) },
    { currentPrice: price, priceStatus: sharedPriceStatus({ freshness: 'STALE' }) },
    { currentPrice: price, priceStatus: sharedPriceStatus({ freshness: 'EXPIRED', includedInTotal: false }) },
    ...(['NO_PRICE', 'UNKNOWN_PRODUCT'] as const).map((lookupStatus) => ({ currentPrice: null,
      priceStatus: sharedPriceStatus({ lookupStatus, freshness: 'NO_PRICE', includedInTotal: false }) })),
    ...(['FRESH', 'STALE', 'EXPIRED'] as const).map((freshness) => ({ currentPrice: price,
      priceStatus: sharedPriceStatus({ lookupStatus: 'UNAVAILABLE', freshness, includedInTotal: false }) })),
    { currentPrice: null, priceStatus: sharedPriceStatus({ lookupStatus: 'UNAVAILABLE', freshness: null,
      lastSuccessAt: null, includedInTotal: false }) },
    { currentPrice: null, priceStatus: sharedPriceStatus({ lookupStatus: 'UNAVAILABLE', freshness: 'NO_PRICE', includedInTotal: false }) },
    ...[price, null].map((currentPrice) => ({ currentPrice, priceStatus: sharedPriceStatus({ origin: 'LOCAL',
      lookupStatus: 'NOT_IN_SCOPE', freshness: null, catalogVersion: null, checkedAt: null, lastSuccessAt: null,
      includedInTotal: currentPrice !== null }) })),
    { currentPrice: price, priceStatus: null },
  ]
  for (const combination of combinations) {
    const product = { ...gpu[0], ...combination }
    const client = createHttpCatalogClient(async () => json(page([product])))
    assert.deepEqual((await client.search('GPU', '')).items[0].priceStatus, combination.priceStatus)
    const expected = { ...details.find((item) => item.product.id === product.id)!, product }
    const detailClient = createHttpCatalogClient(async () => json(expected))
    assert.deepEqual((await detailClient.get(product.id)).product, product)
  }
})

test('중앙 상태와 금액·신선도·합계 정책·버전·UTC 시각이 불일치하면 응답을 거절한다', async () => {
  const price = { amountKrw: 123456, sourceName: 'DANAWA', sourceUrl: 'https://shop.example/p/1', observedAt: timestamp }
  const invalid = [
    { currentPrice: price, priceStatus: sharedPriceStatus({ lookupStatus: 'NO_PRICE', freshness: 'NO_PRICE', includedInTotal: false }) },
    { currentPrice: price, priceStatus: sharedPriceStatus({ lookupStatus: 'UNKNOWN_PRODUCT', freshness: 'NO_PRICE', includedInTotal: false }) },
    { currentPrice: null, priceStatus: sharedPriceStatus() },
    { currentPrice: price, priceStatus: sharedPriceStatus({ freshness: 'EXPIRED' }) },
    { currentPrice: price, priceStatus: sharedPriceStatus({ includedInTotal: false }) },
    { currentPrice: price, priceStatus: sharedPriceStatus({ lookupStatus: 'UNAVAILABLE' }) },
    { currentPrice: price, priceStatus: sharedPriceStatus({ lookupStatus: 'NOT_IN_SCOPE' }) },
    { currentPrice: price, priceStatus: sharedPriceStatus({ catalogVersion: null }) },
    { currentPrice: price, priceStatus: sharedPriceStatus({ catalogVersion: '' }) },
    { currentPrice: price, priceStatus: sharedPriceStatus({ freshness: null }) },
    { currentPrice: price, priceStatus: sharedPriceStatus({ checkedAt: null }) },
    { currentPrice: price, priceStatus: sharedPriceStatus({ checkedAt: '2026-10-10T12:00:00+00:00' }) },
    { currentPrice: price, priceStatus: sharedPriceStatus({ checkedAt: '2026-02-30T12:00:00Z' }) },
    { currentPrice: price, priceStatus: sharedPriceStatus({ lastSuccessAt: null }) },
    { currentPrice: price, priceStatus: sharedPriceStatus({ lastSuccessAt: 'not-a-timestamp' }) },
    { currentPrice: null, priceStatus: sharedPriceStatus({ lookupStatus: 'UNAVAILABLE', freshness: 'NO_PRICE', lastSuccessAt: null, includedInTotal: false }) },
    { currentPrice: null, priceStatus: sharedPriceStatus({ lookupStatus: 'UNAVAILABLE', freshness: null, includedInTotal: false }) },
    { currentPrice: price, priceStatus: sharedPriceStatus({ origin: 'LOCAL', lookupStatus: 'NOT_IN_SCOPE' }) },
    { currentPrice: price, priceStatus: { ...sharedPriceStatus(), lookupStatus: null } },
    { currentPrice: price, priceStatus: { ...sharedPriceStatus(), includedInTotal: 'true' } },
  ]
  for (const combination of invalid) {
    const product = { ...gpu[0], ...combination }
    const client = createHttpCatalogClient(async () => json(page([product as CatalogProduct])))
    await assert.rejects(client.search('GPU', ''), hasCode('INVALID_RESPONSE'))
    const detailClient = createHttpCatalogClient(async () => json({ ...details.find((item) => item.product.id === product.id)!, product }))
    await assert.rejects(detailClient.get(product.id), hasCode('INVALID_RESPONSE'))
  }
})

test('현재가가 없는 이전 응답은 null로 정규화하고 확정 기준가격을 현재가로 대체하지 않는다', async () => {
  const legacy = Object.fromEntries(Object.entries(gpu[0]).filter(([key]) => key !== 'currentPrice')) as Omit<CatalogProduct, 'currentPrice'>
  const product = { ...legacy, referencePrice: { amountKrw: 99999, status: 'CONFIRMED', updatedAt: timestamp } }
  const client = createHttpCatalogClient(async () => json(page([product as CatalogProduct])))
  const result = (await client.search('GPU', '')).items[0]
  assert.equal(result.currentPrice, null)
  assert.equal(result.referencePrice.amountKrw, 99999)
  const detail = { ...details.find((item) => item.product.id === product.id)!, product }
  const detailClient = createHttpCatalogClient(async () => json(detail))
  assert.equal((await detailClient.get(product.id)).product.currentPrice, null)
})

test('잘못된 현재가·출처 URL·날짜를 유효한 구매가로 받아들이지 않는다', async () => {
  const valid = { amountKrw: 123456, sourceName: '판매처', sourceUrl: 'https://shop.example/p/1', observedAt: timestamp }
  for (const price of [
    ...[0, -1, 1.5, '10000', null, Number.MAX_SAFE_INTEGER + 1].map((amountKrw) => ({ ...valid, amountKrw })),
    { ...valid, sourceName: '' },
    ...['javascript:alert(1)', 'http://shop.example/p/1', '/p/1'].map((sourceUrl) => ({ ...valid, sourceUrl })),
    ...['yesterday', '2026-10-06', '2026-10-06T01:00:00', '2026-02-30T01:00:00Z',
      '2026-10-06T24:00:00Z', '2026-10-06T01:00:00+24:00'].map((observedAt) => ({ ...valid, observedAt })),
  ]) {
    const product = { ...gpu[0], currentPrice: price }
    const client = createHttpCatalogClient(async () => json(page([product as CatalogProduct])))
    await assert.rejects(client.search('GPU', ''), hasCode('INVALID_RESPONSE'))
    const detailClient = createHttpCatalogClient(async () => json({ ...details.find((item) => item.product.id === product.id)!, product }))
    await assert.rejects(detailClient.get(product.id), hasCode('INVALID_RESPONSE'))
  }
})

test('CPU 상세의 추가 전력·P/E 제원을 누락하거나 기존 TDP로 환산하지 않는다', async () => {
  const detail: CatalogDetail = { ...details[0], specification: {
    socketCode: 'LGA1700', coreCount: 14, threadCount: 20, baseClockMhz: null, boostClockMhz: 5100,
    tdpW: null, hasIntegratedGraphics: false, integratedGraphicsModel: null,
    processorBasePowerW: 125, maximumTurboPowerW: 181, performanceCoreCount: 6, efficientCoreCount: 8,
    performanceCoreBaseClockMhz: 3500, efficientCoreBaseClockMhz: 2600,
    performanceCoreBoostClockMhz: 5100, efficientCoreBoostClockMhz: 3900,
  } }
  const client = createHttpCatalogClient(async () => json(detail))
  assert.deepEqual((await client.get(detail.product.id)).specification, detail.specification)
})

test('다른 종류·페이지·제품 ID가 오면 연결 후보로 받아들이지 않는다', async () => {
  const badPages = [page([details[0].product]), page(gpu, { page: 1 }), page(gpu, { size: 30 }),
    page([{ ...gpu[0], id: '' }]), page([{ ...gpu[0], referencePrice: { ...gpu[0].referencePrice, amountKrw: 0 } }])]
  for (const body of badPages) {
    const client = createHttpCatalogClient(async () => json(body))
    await assert.rejects(client.search('GPU', ''), hasCode('INVALID_RESPONSE'))
  }
  const client = createHttpCatalogClient(async () => json(details[0]))
  await assert.rejects(client.get('different-product-id'), hasCode('INVALID_RESPONSE'))
})

test('잘못된 상세 구조와 실행 가능한 출처 URL은 표시하지 않는다', async () => {
  const detail = details[0]
  for (const body of [
    { ...detail, specification: {} },
    { ...detail, specification: { invalidNestedValue: { secret: true } } },
    { ...detail, sources: [{ ...detail.sources[0], sourceUrl: 'javascript:alert(1)' }] },
    { ...detail, attributions: [{ ...attributions[0], licenseUrl: 'data:text/html,invalid' }] },
  ]) {
    const client = createHttpCatalogClient(async () => json(body))
    await assert.rejects(client.get(detail.product.id), hasCode('INVALID_RESPONSE'))
  }
})

test('조회 API 없음·제품 없음·서버 오류를 빈 목록으로 숨기지 않는다', async () => {
  const unavailable = createHttpCatalogClient(async () => new Response('<html>Not Found</html>', { status: 404 }))
  await assert.rejects(unavailable.search('GPU', ''), (error: unknown) => {
    assert.ok(error instanceof CatalogApiError)
    assert.match(error.message, /local 프로필/)
    assert.ok(!error.message.includes('<html>'))
    return true
  })
  const missing = createHttpCatalogClient(async () => json({ code: 'CATALOG_PRODUCT_NOT_FOUND', message: '부품을 찾을 수 없습니다.' }, 404))
  await assert.rejects(missing.get('unknown-id'), hasCode('CATALOG_PRODUCT_NOT_FOUND'))
  const serverError = createHttpCatalogClient(async () => new Response('<html>Proxy error</html>', { status: 502 }))
  await assert.rejects(serverError.search('GPU', ''), hasCode('HTTP_ERROR'))
  const htmlSuccess = createHttpCatalogClient(async () => new Response('<html>Vite fallback</html>'))
  await assert.rejects(htmlSuccess.search('GPU', ''), hasCode('INVALID_RESPONSE'))
})

test('연결 실패·시간 초과·사용자 취소를 구분하고 자동 재전송하지 않는다', async () => {
  let calls = 0
  const offline = createHttpCatalogClient(async () => { calls++; throw new TypeError('offline') })
  await assert.rejects(offline.search('GPU', ''), hasCode('NETWORK_ERROR'))
  assert.equal(calls, 1)
  const waiting: typeof fetch = async (_url, init) => new Promise((_resolve, reject) => {
    init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true })
  })
  const timeout = createHttpCatalogClient(waiting, '/api/catalog/products', 5)
  await assert.rejects(timeout.search('GPU', ''), hasCode('REQUEST_TIMEOUT'))
  const client = createHttpCatalogClient(waiting)
  const controller = new AbortController()
  const pending = client.search('GPU', '', { signal: controller.signal })
  controller.abort()
  await assert.rejects(pending, (error: unknown) => error instanceof DOMException && error.name === 'AbortError')
})

test('헤더 수신 뒤 본문을 기다리는 중 취소해도 늦은 결과를 반환하지 않는다', async () => {
  const controller = new AbortController()
  const client = createHttpCatalogClient(async () => {
    const response = json(page(gpu))
    response.json = async () => { controller.abort(); return page(gpu) }
    return response
  })
  await assert.rejects(client.search('GPU', '', { signal: controller.signal }),
    (error: unknown) => error instanceof DOMException && error.name === 'AbortError')
})
