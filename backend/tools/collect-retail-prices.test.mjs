import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtemp, readFile, rmdir, unlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { CollectionError, collectManifest, parseRetailPrice, validateManifest, validatePreviewOutput, validateRetailUrl } from './collect-retail-prices.mjs'

const compuzoneHtml = await readFile(new URL('./fixtures/retail-compuzone-245k.html', import.meta.url), 'utf8')
const icodaHtml = await readFile(new URL('./fixtures/retail-icoda-250k-plus.html', import.meta.url), 'utf8')
const observedAt = '2026-10-10T12:00:00.000Z'
const reviewedAt = '2026-10-10T11:00:00.000Z'
const compuzone = { sourceName: 'COMPUZONE', externalId: '1183680', sourceUrl: 'https://www.compuzone.co.kr/product/product_detail.htm?DivNo=0&MediumDivNo=1012&ProductNo=1183680',
  expectedTitle: '[INTEL] 인텔 코어 Ultra 5 프로세서 245K (애로우 레이크/4.2GHz/26MB/쿨러미포함) [정품박스]', expectedModel: '245K', observedAt }
const icoda = { sourceName: 'ICODA', externalId: '1739432', sourceUrl: 'https://usr.icoda.co.kr/item/view/1739432',
  expectedTitle: '박스 인텔 코어 울트라 5 정품 250K PLUS CPU (애로우레이크 리프레시/LGA1851/쿨러미포함)', expectedModel: '250K PLUS', observedAt }
const hasCode = (code) => (error) => error instanceof CollectionError && error.code === code
function manifest(provider = 'COMPUZONE', status = 'APPROVED') {
  const page = provider === 'COMPUZONE' ? compuzone : icoda
  const partNumber = provider === 'COMPUZONE' ? 'BX80768245K' : 'BX80768250K'
  return { schemaVersion: 1, items: [{
    product: { sourceName: 'MANUFACTURER', externalId: `reviewed-test-box:${partNumber}`, manufacturer: 'Intel', modelName: `Core Ultra 5 ${page.expectedModel}`, partNumber: status === 'APPROVED' ? partNumber : null },
    offer: { sourceName: page.sourceName, externalId: page.externalId, sourceUrl: page.sourceUrl, saleSku: status === 'APPROVED' ? partNumber : '국내 대리점 정품 박스 단품 CPU', saleUnit: 'PRODUCT', verifiedAt: status === 'APPROVED' ? reviewedAt : null },
    // Synthetic approved mapping tests the gate only. The real two CPU mappings remain PENDING.
    review: { status, expectedTitle: page.expectedTitle, expectedModel: page.expectedModel, expectedPartNumber: partNumber, packageType: 'BOX', unitCount: 1, domesticDistribution: 'AUTHORIZED',
      manufacturerEvidenceUrl: 'https://www.intel.com/content/www/us/en/products/sku/test-box/specifications.html', domesticPnEvidenceUrl: page.sourceUrl, domesticPnVerified: status === 'APPROVED' },
  }] }
}
const response = (html, headers = {}) => new Response(html, { headers: { 'Content-Type': 'text/html; charset=utf-8', ...headers } })
const options = (extra = {}) => ({ now: () => new Date(observedAt), fetchImpl: async () => response(compuzoneHtml), ...extra })
const repositoryRoot = fileURLToPath(new URL('../../', import.meta.url))

test('컴퓨존 일반 상품가 330500만 읽고 숨겨진 328000·카드할인·배송·추천 가격은 제외한다', () => {
  assert.deepEqual(parseRetailPrice(compuzoneHtml, compuzone), { amountKrw: 330500, observedAt, evidenceUrl: compuzone.sourceUrl, condition: 'NEW', inStock: true })
  assert.equal(parseRetailPrice(compuzoneHtml.replace('328,000', '1'), compuzone).amountKrw, 330500)
})

test('아이코다는 primary main_pay 일반가만 읽고 회원혜택·무통장·배송·aside 가격은 제외한다', () => {
  assert.equal(parseRetailPrice(icodaHtml, icoda).amountKrw, 342100)
  assert.equal(parseRetailPrice(icodaHtml.replace('<aside><div class="view_price">342,100원', '<aside><div class="view_price">1원'), icoda).amountKrw, 342100)
})

test('실제 페이지처럼 엔티티·공백·중복 class를 처리하지만 스크립트는 실행하지 않는다', () => {
  assert.equal(parseRetailPrice(compuzoneHtml.replace('인텔 코어 Ultra', '인텔\n 코어\tUltra'), compuzone).amountKrw, 330500)
  assert.equal(parseRetailPrice(icodaHtml.replace('250K PLUS CPU', '250K &#80;LUS CPU'), icoda).amountKrw, 342100)
})

test('모델·제목 변경과 다른 상품 ID의 페이지를 거절한다', () => {
  assert.throws(() => parseRetailPrice(compuzoneHtml.replaceAll('245K', '245KF'), compuzone), hasCode('TITLE_MISMATCH'))
  assert.throws(() => parseRetailPrice(icodaHtml.replaceAll('250K PLUS', '245K'), icoda), hasCode('TITLE_MISMATCH'))
  assert.throws(() => parseRetailPrice(compuzoneHtml.replace('value="1183680"', 'value="1183681"'), compuzone), hasCode('PRODUCT_ID_MISMATCH'))
  assert.throws(() => parseRetailPrice(icodaHtml.replace("set_copy('1739432')", "set_copy('1739433')"), icoda), hasCode('PRODUCT_ID_MISMATCH'))
})

test('5800X를 5800X3D로 확정하지 않으며 모델 경계를 검사한다', () => {
  const title = compuzone.expectedTitle.replace('245K', '5800X3D')
  assert.throws(() => parseRetailPrice(compuzoneHtml.replaceAll('245K', '5800X3D'), { ...compuzone, expectedTitle: title, expectedModel: '5800X' }), hasCode('MODEL_MISMATCH'))
  const invalid = manifest()
  invalid.items[0].product.modelName = 'Core Ultra 5 245KF'
  assert.throws(() => validateManifest(invalid, new Date(observedAt)), hasCode('INVALID_MANIFEST'))
})

test('정확 제목으로 검토했더라도 벌크·멀티팩·트레이·묶음·해외·리퍼는 가격으로 채택하지 않는다', () => {
  for (const suffix of ['멀티팩', '벌크', 'MPK', 'tray', 'CPU + 메인보드', '세트 상품']) {
    const title = `${compuzone.expectedTitle} ${suffix}`
    assert.throws(() => parseRetailPrice(compuzoneHtml.replace(compuzone.expectedTitle, title), { ...compuzone, expectedTitle: title }), hasCode('WRONG_PACKAGE'))
  }
  for (const suffix of ['해외배송', '해외 구매대행', '병행수입', '중고', '리퍼']) {
    const title = `${icoda.expectedTitle} ${suffix}`
    assert.throws(() => parseRetailPrice(icodaHtml.replace(icoda.expectedTitle, title), { ...icoda, expectedTitle: title }), hasCode('NOT_DOMESTIC_NEW'))
  }
})

test('아이코다 본문 포장·대리점 유통이 바뀌면 기존 제목과 가격이 있어도 거절한다', () => {
  assert.throws(() => parseRetailPrice(icodaHtml.replace('<span>박스</span>', '<span>멀티팩</span>'), icoda), hasCode('WRONG_PACKAGE'))
  assert.throws(() => parseRetailPrice(icodaHtml.replace('대리점 정품', '병행수입'), icoda), hasCode('WRONG_PACKAGE'))
  assert.throws(() => parseRetailPrice(icodaHtml.replace('<div class="price_info">', '<div class="price_info"><div>해외배송 상품</div>'), icoda), hasCode('NOT_DOMESTIC_NEW'))
  assert.throws(() => parseRetailPrice(compuzoneHtml.replace('<div class="info_header">', '<div class="info_header"><div>멀티팩 상품</div>'), compuzone), hasCode('WRONG_PACKAGE'))
  assert.throws(() => parseRetailPrice(compuzoneHtml.replace('<input type="hidden" id="ProductNo"', '<input class="pg_no" value="123"><input type="hidden" id="ProductNo"'), compuzone), hasCode('WRONG_PACKAGE'))
})

test('품절 문구는 오래된 InStock script·stock_cnt나 구매 버튼보다 우선한다', () => {
  for (const token of ['일시품절', '현재 판매중인 상품이 아닙니다', '구매불가', '재고 없음']) {
    assert.throws(() => parseRetailPrice(compuzoneHtml.replace('<div class="info_header">', `<div class="info_header"><span>${token}</span>`), compuzone), hasCode('OUT_OF_STOCK'))
    assert.throws(() => parseRetailPrice(icodaHtml.replace('<div class="price_info">', `<div class="price_info"><div>${token}</div>`), icoda), hasCode('OUT_OF_STOCK'))
  }
})

test('정확 구매 버튼이 없거나 비활성 또는 다른 ProductNo이면 구매 가능으로 취급하지 않는다', () => {
  for (const attr of ['hidden', 'disabled', 'aria-disabled="true"', 'style="display:none"']) {
    assert.throws(() => parseRetailPrice(compuzoneHtml.replace('<a class="buy"', `<a ${attr} class="buy"`), compuzone), hasCode('NO_ACTIVE_PURCHASE'))
    assert.throws(() => parseRetailPrice(icodaHtml.replace('<span type="바로구매"', `<span ${attr} type="바로구매"`), icoda), hasCode('NO_ACTIVE_PURCHASE'))
  }
  assert.throws(() => parseRetailPrice(compuzoneHtml.replaceAll("buy_direct('1183680'", "buy_direct('1183681'"), compuzone), hasCode('NO_ACTIVE_PURCHASE'))
  assert.throws(() => parseRetailPrice(icodaHtml.replace('onclick="바로구매();"', 'onclick="품절안내();"'), icoda), hasCode('NO_ACTIVE_PURCHASE'))
})

test('선택 수량은 한 개이며 아이코다 단가와 일반 판매가는 같아야 한다', () => {
  assert.throws(() => parseRetailPrice(compuzoneHtml.replace('title="수량" value="1"', 'title="수량" value="2"'), compuzone), hasCode('UNIT_COUNT_MISMATCH'))
  assert.throws(() => parseRetailPrice(icodaHtml.replace('value="1" price="342100"', 'value="2" price="342100"'), icoda), hasCode('UNIT_COUNT_MISMATCH'))
  assert.throws(() => parseRetailPrice(icodaHtml.replace('price="342100"', 'price="342101"'), icoda), hasCode('UNIT_COUNT_MISMATCH'))
})

test('일반가 필드 자체가 할인·쿠폰·회원조건이면 거절하고 혜택 필드로 대체하지 않는다', () => {
  for (const token of ['회원가', '쿠폰 적용가', '카드 결제 할인', '현금 전용', '프로모션']) {
    assert.throws(() => parseRetailPrice(compuzoneHtml.replace('<h3>판매가</h3>', `<h3>${token}</h3>`), compuzone), hasCode('CONDITIONAL_PRICE'))
    assert.throws(() => parseRetailPrice(icodaHtml.replace('<span class="no_margin"></span>', `<span class="no_margin">${token}</span>`), icoda), hasCode('CONDITIONAL_PRICE'))
  }
  assert.throws(() => parseRetailPrice(icodaHtml.replace('class="price_info"', 'class="price_info membership"'), icoda), hasCode('CONDITIONAL_PRICE'))
})

test('일반가 없음·영역 중복은 회원가·배송 포함 합계로 보완하지 않는다', () => {
  assert.throws(() => parseRetailPrice(compuzoneHtml.replace('class="price_real"', 'class="coupon_price"'), compuzone), hasCode('AMBIGUOUS_PRICE'))
  assert.throws(() => parseRetailPrice(icodaHtml.replace('class="view_price"', 'class="coupon_price"'), icoda), hasCode('REGULAR_PRICE_SECTION_MISSING'))
  assert.throws(() => parseRetailPrice(compuzoneHtml.replace('<div class="ct price_inner">', '<div class="ct price_inner"><div class="price_real">200,000원</div>'), compuzone), hasCode('AMBIGUOUS_PRICE'))
})

test('0·음수·소수·잘못된 콤마·통화·과도한 가격을 거절한다', () => {
  for (const raw of ['0', '-1', '10.5', '342,10', '₩342,100', '1e6', '1,000,000,000,000']) {
    const html = icodaHtml.replace('<span>342,100</span><span>원</span>', `<span>${raw}</span><span>원</span>`)
    assert.throws(() => parseRetailPrice(html, icoda), hasCode('INVALID_PRICE'))
  }
})

test('컴퓨존 숫자 ProductNo와 선택 DivNo·MediumDivNo만 허용한다', () => {
  for (const url of [compuzone.sourceUrl, 'https://www.compuzone.co.kr/product/product_detail.htm?ProductNo=1183680', 'https://www.compuzone.co.kr/product/product_detail.htm?ProductNo=1183680&MediumDivNo=1012&DivNo=0']) assert.equal(validateRetailUrl('COMPUZONE', '1183680', url), url)
  for (const url of [compuzone.sourceUrl + '&coupon=1', compuzone.sourceUrl + '&ProductNo=1183680', compuzone.sourceUrl + '&DivNo=0', compuzone.sourceUrl.replace('DivNo=0', 'DivNo='), compuzone.sourceUrl.replace('DivNo=0', 'DivNo=-1'), compuzone.sourceUrl.replace('ProductNo=', 'Product%4eo='), compuzone.sourceUrl.replace('1183680', '1183681'), compuzone.sourceUrl.replace('DivNo=0', 'DivNo=12345678901234567'), compuzone.sourceUrl.replace('MediumDivNo=1012', 'MediumDivNo=12345678901234567')]) assert.throws(() => validateRetailUrl('COMPUZONE', '1183680', url), hasCode('INVALID_URL'))
  assert.equal(validateRetailUrl('COMPUZONE', '1183680', compuzone.sourceUrl.replace('DivNo=0', 'DivNo=1234567890123456')), compuzone.sourceUrl.replace('DivNo=0', 'DivNo=1234567890123456'))
})

test('아이코다 정확 숫자 경로만 허용하고 두 제공자 모두 443포트·userinfo·fragment·다른 host를 거절한다', () => {
  for (const page of [compuzone, icoda]) {
    const url = new URL(page.sourceUrl)
    const invalid = [page.sourceUrl.replace('https:', 'http:'), page.sourceUrl.replace(url.host, `${url.host}:443`), page.sourceUrl.replace(url.host, `user@${url.host}`), `${page.sourceUrl}#`, page.sourceUrl.replace(url.host, 'example.com'), page.sourceUrl.replace('https://', 'https://user:secret@')]
    if (page === icoda) invalid.push(`${page.sourceUrl}?coupon=1`, `${page.sourceUrl}/`, page.sourceUrl.replace('1739432', '01739432'))
    for (const value of invalid) assert.throws(() => validateRetailUrl(page.sourceName, page.externalId, value), hasCode('INVALID_URL'))
  }
  for (const id of ['0', '-1', '1.5', '12345678901234567']) assert.throws(() => validateRetailUrl('ICODA', id, `https://usr.icoda.co.kr/item/view/${id}`), hasCode('INVALID_URL'))
})

test('APPROVED는 non-null 정확 PN·공식/국내 근거·완료 검토시간을 모두 요구한다', () => {
  for (const mutate of [(x) => { x.product.partNumber = null }, (x) => { x.review.expectedPartNumber = 'BXOTHER' }, (x) => { x.review.domesticPnVerified = false }, (x) => { delete x.review.manufacturerEvidenceUrl }, (x) => { delete x.review.domesticPnEvidenceUrl }]) {
    const invalid = manifest(); mutate(invalid.items[0]); assert.throws(() => validateManifest(invalid, new Date(observedAt)), hasCode('UNREVIEWED_MAPPING'))
  }
  for (const time of [null, '2026-02-30T10:00:00Z', '2026-10-11T00:00:00Z']) {
    const invalid = manifest(); invalid.items[0].offer.verifiedAt = time; assert.throws(() => validateManifest(invalid, new Date(observedAt)), hasCode('INVALID_MANIFEST'))
  }
})

test('APPROVED CPU saleSku는 정확 BOX PN이어야 하고 설명형·다른 PN·다른 포장은 거절한다', () => {
  for (const saleSku of ['국내 대리점 정품 박스 단품 CPU', 'BX80768250K', 'BX80768245K MULTIPACK']) {
    const invalid = manifest(); invalid.items[0].offer.saleSku = saleSku
    assert.throws(() => validateManifest(invalid, new Date(observedAt)), hasCode('UNREVIEWED_MAPPING'))
  }
  const pending = manifest('COMPUZONE', 'PENDING')
  assert.equal(validateManifest(pending, new Date(observedAt)), pending)
  assert.equal(pending.items[0].offer.saleSku, '국내 대리점 정품 박스 단품 CPU')
})

test('새 경로는 CPU BOX 단품으로 한정하고 임의 필드·중복 제공자 ID를 수집 전 거절한다', async () => {
  for (const mutate of [(x) => { x.offer.saleUnit = 'RAM_KIT' }, (x) => { x.offer.moduleCount = 1 }, (x) => { x.review.unitCount = 2 }, (x) => { x.review.packageType = 'MPK' }, (x) => { x.review.domesticDistribution = 'PARALLEL' }, (x) => { x.price = { amountKrw: 1 } }]) {
    const invalid = manifest(); mutate(invalid.items[0]); assert.throws(() => validateManifest(invalid, new Date(observedAt)), hasCode('INVALID_MANIFEST'))
  }
  const invalid = manifest(); invalid.items.push(structuredClone(invalid.items[0])); let calls = 0
  await assert.rejects(collectManifest(invalid, options({ fetchImpl: async () => { calls++ } })), hasCode('DUPLICATE_PROVIDER_ID'))
  assert.equal(calls, 0)
})

test('실제 CPU 2종 같은 PENDING·PN null 입력은 일반가 관측 후보만 만들고 가격 payload는 비운다', async () => {
  const reviewed = manifest('COMPUZONE', 'PENDING'); reviewed.items.push(manifest('ICODA', 'PENDING').items[0]); const before = structuredClone(reviewed)
  const result = await collectManifest(reviewed, options({ sleep: async () => {}, fetchImpl: async (url) => response(url.includes('compuzone') ? compuzoneHtml : icodaHtml) }))
  assert.deepEqual(result.payload, { schemaVersion: 1, items: [] })
  assert.equal(result.report.candidateCount, 2)
  assert.deepEqual(result.report.candidates.map((x) => x.currentOrdinaryPriceCandidate.amountKrw), [330500, 342100])
  assert.ok(result.report.candidates.every((x) => x.importEligible === false && /^[a-f0-9]{64}$/.test(x.evidence.responseSha256)))
  assert.equal(result.report.databaseWrites, 0); assert.equal(result.report.publications, 0)
  assert.deepEqual(reviewed, before)
  assert.equal(result.exitCode, 2)
})

test('완료된 정확 SKU 입력은 계약 DTO만 내보내고 원문 SHA는 별도 보고서에 보존한다', async () => {
  const reviewed = manifest(), calls = []
  const result = await collectManifest(reviewed, options({ fetchImpl: async (url, opts) => { calls.push({ url, opts }); return response(compuzoneHtml) } }))
  assert.deepEqual(result.payload.items[0].product, reviewed.items[0].product)
  assert.deepEqual(result.payload.items[0].offer, reviewed.items[0].offer)
  assert.deepEqual(Object.keys(result.payload.items[0]).sort(), ['offer', 'price', 'product'])
  assert.equal(result.payload.items[0].price.amountKrw, 330500)
  assert.ok(/^[a-f0-9]{64}$/.test(result.report.evidence[0].responseSha256))
  assert.equal(calls[0].opts.method, 'GET'); assert.equal(calls[0].opts.redirect, 'error'); assert.equal(calls[0].opts.credentials, 'omit'); assert.equal(calls[0].opts.cache, 'no-store')
  assert.equal(result.exitCode, 0)
})

test('검토 전에 관측된 가격은 시계가 뒤로 바뀌어도 미리보기에 넣지 않는다', async () => {
  let clock = 0
  const result = await collectManifest(manifest(), options({ now: () => new Date(clock++ === 0 ? observedAt : '2026-10-10T10:59:59Z') }))
  assert.equal(result.payload.items.length, 0)
  assert.equal(result.report.rejected[0].code, 'OBSERVATION_BEFORE_REVIEW')
})

test('밀리초 아래 정밀도의 검토 시각도 절삭하지 않고 관측 순서를 검사한다', async () => {
  const reviewed = manifest(); reviewed.items[0].offer.verifiedAt = '2026-10-10T11:00:00.000001Z'; let clock = 0
  const result = await collectManifest(reviewed, options({ now: () => new Date(clock++ === 0 ? observedAt : '2026-10-10T11:00:00.000Z') }))
  assert.equal(result.report.rejected[0].code, 'OBSERVATION_BEFORE_REVIEW')
  const future = manifest(); future.items[0].offer.verifiedAt = '2026-10-10T12:00:00.000001Z'
  assert.throws(() => validateManifest(future, new Date(observedAt)), hasCode('INVALID_MANIFEST'))
})

test('403·429 후 동일 host 요청을 중단하고 재시도하지 않으며 다른 승인 host만 계속한다', async () => {
  for (const status of [403, 429]) {
    const reviewed = manifest(); const second = structuredClone(reviewed.items[0]); second.offer.externalId = '1183681'; second.offer.sourceUrl = second.offer.sourceUrl.replace('1183680', '1183681'); reviewed.items.push(second, manifest('ICODA').items[0]); const calls = []
    const result = await collectManifest(reviewed, options({ sleep: async () => {}, fetchImpl: async (url) => { calls.push(url); return url.includes('compuzone') ? new Response('blocked', { status }) : response(icodaHtml) } }))
    assert.equal(calls.length, 2); assert.equal(result.payload.items.length, 1)
    assert.deepEqual(result.report.rejected.map((x) => x.code), ['ACCESS_BLOCKED', 'HOST_BLOCKED'])
    assert.deepEqual(result.report.blockedHosts, ['www.compuzone.co.kr'])
  }
})

test('요청 간격을 1000ms 이상 지키고 interval 축소와 timeout 확대를 거절한다', async () => {
  const reviewed = manifest(); reviewed.items.push(manifest('ICODA').items[0]); let current = 0; const starts = [], waits = []
  await collectManifest(reviewed, options({ monotonicNow: () => current, sleep: async (ms) => { waits.push(ms); current += ms }, fetchImpl: async (url) => { starts.push(current); return response(url.includes('compuzone') ? compuzoneHtml : icodaHtml) } }))
  assert.deepEqual(waits, [1100]); assert.deepEqual(starts, [0, 1100])
  await assert.rejects(collectManifest(manifest(), options({ intervalMs: 1000 })), hasCode('INVALID_OPTIONS'))
  await assert.rejects(collectManifest(manifest(), options({ timeoutMs: 15001 })), hasCode('INVALID_OPTIONS'))
})

test('HTML 아닌 응답·본문 크기 초과·안전하지 않은 인코딩은 가격으로 처리하지 않는다', async () => {
  for (const [returned, code] of [[new Response('{}', { headers: { 'Content-Type': 'application/json' } }), 'INVALID_CONTENT_TYPE'], [response(compuzoneHtml, { 'Content-Length': '5000001' }), 'BODY_TOO_LARGE'], [response(compuzoneHtml, { 'Content-Type': 'text/html; charset=utf-16' }), 'UNSUPPORTED_CHARSET'], [new Response(new Uint8Array([0xff, 0xff]), { headers: { 'Content-Type': 'text/html; charset=utf-8' } }), 'INVALID_ENCODING']]) {
    const result = await collectManifest(manifest(), options({ fetchImpl: async () => returned }))
    assert.equal(result.payload.items.length, 0); assert.equal(result.report.rejected[0].code, code)
  }
  const streamed = await collectManifest(manifest(), options({ maxBodyBytes: 100, fetchImpl: async () => response(compuzoneHtml) }))
  assert.equal(streamed.report.rejected[0].code, 'BODY_TOO_LARGE')
})

test('본문 스트림이 멈춰도 timeout에 취소하며 네트워크·redirect 오류 원문은 출력하지 않는다', async () => {
  let cancelled = false
  const stream = new ReadableStream({ start(controller) { controller.enqueue(new TextEncoder().encode('<html>')) }, cancel() { cancelled = true } })
  const keepAlive = setTimeout(() => {}, 500)
  try {
    const result = await collectManifest(manifest(), options({ timeoutMs: 20, fetchImpl: async () => new Response(stream, { headers: { 'Content-Type': 'text/html' } }) }))
    assert.equal(result.report.rejected[0].code, 'REQUEST_TIMEOUT'); assert.equal(cancelled, true)
  } finally { clearTimeout(keepAlive) }
  const network = await collectManifest(manifest(), options({ fetchImpl: async () => { throw new Error('redirect failed with secret-private-value') } }))
  assert.equal(network.report.rejected[0].code, 'NETWORK_ERROR'); assert.ok(!network.report.rejected[0].message.includes('secret-private-value'))
})

test('CLI는 잘못된 입력에도 빈 preview·실패 보고서를 만들고 원본을 보존한다', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'pc-retail-price-test-')); const input = join(dir, 'input.json'); const output = join(dir, 'output', 'preview.json')
  try {
    await writeFile(input, '{broken JSON', 'utf8')
    const result = spawnSync(process.execPath, [fileURLToPath(new URL('./collect-retail-prices.mjs', import.meta.url)), '--manifest', input, '--output', output], { encoding: 'utf8', timeout: 5000 })
    assert.equal(result.status, 2, result.stderr)
    assert.deepEqual(JSON.parse(await readFile(output, 'utf8')), { schemaVersion: 1, items: [] })
    const report = JSON.parse(await readFile(`${output}.report.json`, 'utf8')); assert.equal(report.mode, 'PREVIEW_ONLY'); assert.equal(report.databaseWrites, 0); assert.equal(report.publications, 0)
    assert.equal(await readFile(input, 'utf8'), '{broken JSON')
  } finally {
    for (const file of [input, output, `${output}.report.json`]) await unlink(file).catch(() => {})
    await rmdir(join(dir, 'output')).catch(() => {})
    await rmdir(dir)
  }
})

test('CLI 출력은 운영 가격·승인 pilot 원본·generated Worker와 저장소 review 파일을 덮어쓸 수 없다', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'pc-retail-output-guard-')); const input = join(dir, 'input.json')
  const protectedFiles = [
    join(repositoryRoot, 'data/catalog-shared/active-approved-prices.json'),
    join(repositoryRoot, 'data/catalog-shared/all-catalog-identities-2026-10-10.json'),
    join(repositoryRoot, 'data/catalog-current-prices/retail-guard-never-created.json'),
    join(repositoryRoot, 'data/catalog-review/pilot-approved-prices-2026-10-10.json'),
    join(repositoryRoot, 'data/catalog-review/pilot-apply-approval-2026-10-10.json'),
    join(repositoryRoot, 'data/catalog-review/retail-guard-never-created.json'),
    join(repositoryRoot, 'workers/catalog-prices/generated/catalog-v2.json'),
  ]
  try {
    await writeFile(input, JSON.stringify(manifest('COMPUZONE', 'PENDING')), 'utf8')
    for (const output of protectedFiles) {
      const before = await readFile(output).catch((error) => error.code === 'ENOENT' ? null : Promise.reject(error))
      await assert.rejects(validatePreviewOutput(input, output), hasCode('USAGE'))
      const result = spawnSync(process.execPath, [fileURLToPath(new URL('./collect-retail-prices.mjs', import.meta.url)), '--manifest', input, '--output', output], { encoding: 'utf8', timeout: 5000 })
      assert.equal(result.status, 2); assert.match(result.stderr, /restricted|cannot be overwritten/)
      const after = await readFile(output).catch((error) => error.code === 'ENOENT' ? null : Promise.reject(error))
      assert.deepEqual(after, before, `protected output changed: ${output}`)
    }
  } finally { await unlink(input); await rmdir(dir) }
})

test('일반 출력·report의 입력 충돌을 모두 차단하고 backend/build·TEMP JSON만 허용한다', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'pc-retail-collision-')); const input = join(dir, 'input.json'); const collision = join(dir, 'preview.json.report.json')
  try {
    await writeFile(input, '{input}', 'utf8'); await writeFile(collision, '{report-collision-input}', 'utf8')
    await assert.rejects(validatePreviewOutput(input, input), hasCode('USAGE'))
    await assert.rejects(validatePreviewOutput(collision, join(dir, 'preview.json')), hasCode('USAGE'))
    await assert.rejects(validatePreviewOutput(input, join(dir, 'preview.properties')), hasCode('USAGE'))
    await assert.rejects(validatePreviewOutput(input, join(repositoryRoot, 'backend/build/../../data/catalog-shared/active-approved-prices.json')), hasCode('USAGE'))
    assert.equal(await validatePreviewOutput(input, join(dir, 'preview.json')), join(dir, 'preview.json'))
    const buildOutput = join(repositoryRoot, 'backend/build/retail-safety-tests/preview.json')
    assert.equal(await validatePreviewOutput(input, buildOutput), buildOutput)
    const cli = spawnSync(process.execPath, [fileURLToPath(new URL('./collect-retail-prices.mjs', import.meta.url)), '--manifest', collision, '--output', join(dir, 'preview.json')], { encoding: 'utf8', timeout: 5000 })
    assert.equal(cli.status, 2); assert.match(cli.stderr, /overwrite the input manifest/)
    assert.equal(await readFile(collision, 'utf8'), '{report-collision-input}')
  } finally { await unlink(input); await unlink(collision); await rmdir(dir) }
})
