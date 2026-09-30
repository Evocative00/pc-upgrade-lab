import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { CatalogApiError, createHttpCatalogClient } from '../src/features/catalog/catalogClient.ts'
import type { CatalogDetail, CatalogPage, CatalogProduct, CatalogSpecification } from '../src/features/catalog/catalogTypes.ts'

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
      createdAt: timestamp, updatedAt: timestamp, referencePrice: { amountKrw: null, status: 'UNCONFIRMED', updatedAt: timestamp } },
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
