import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtemp, readFile, rmdir, unlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { CollectionError, collectManifest, parseDanawaPrice, validateManifest } from './collect-danawa-prices.mjs'

const title = '인텔 코어i5-12세대 12400F (엘더레이크) (정품)'
const observedAt = '2026-10-06T10:00:00.000Z'
const args = { expectedTitle: title, pcode: '16101353', observedAt }
const hasCode = (code) => (error) => error instanceof CollectionError && error.code === code
const bridge = (pcode = args.pcode) => `<a href="https://prod.danawa.com/bridge/loadingBridge.html?pcode=${pcode}&amp;cmpnyc=SHOP" class="link__full-cover" aria-label="상품보기"></a>`
const row = (amount = '215990', extra = '', attrs = '') => `<li class="list-item" ${attrs}>
  <div class="box__logo">판매처</div><div class="sell-price" data-base-price="${amount}" data-delivery-price="4000">
  <span class="text__num">219,990</span></div><div class="box__delivery">4,000원</div>${bridge()}${extra}</li>`
const page = (rows = row(), extras = '', productTitle = title) => `<!doctype html>
  <html><head><meta property="og:title" content="[다나와] ${productTitle}"></head><body>
  <h3 class="prod_tit"><span class="title">${productTitle}</span><a>상품비교</a></h3>
  ${extras}<div class="box__mall-price "><div class="box__section-header">쇼핑몰별 최저가 배송비 포함</div>
  <ul class="list__mall-price">${rows}</ul></div></body></html>`
const manifest = () => ({ schemaVersion: 1, items: [{
  product: { sourceName: 'BUILDCORES', externalId: 'intel-core-i5-12400f', manufacturer: 'Intel', modelName: 'Core i5-12400F', partNumber: null },
  offer: { sourceName: 'DANAWA', externalId: args.pcode, sourceUrl: `https://prod.danawa.com/info/?pcode=${args.pcode}`,
    saleSku: '정품', saleUnit: 'PRODUCT', verifiedAt: '2026-10-06T09:00:00Z' },
  review: { expectedTitle: title },
}] })

test('일반 판매처의 상품가만 읽고 배송비·배송비 포함 화면값은 합산하지 않는다', () => {
  assert.deepEqual(parseDanawaPrice(page(row()), args), { amountKrw: 215990, observedAt,
    evidenceUrl: 'https://prod.danawa.com/info/?pcode=16101353', condition: 'NEW', inStock: true })
  const multiple = row('230000') + row('215990')
  assert.equal(parseDanawaPrice(page(multiple), args).amountKrw, 215990)
})

test('회원·쿠폰·카드·현금 혜택 섹션과 광고·스크립트의 더 낮은 가격은 제외한다', () => {
  const extras = `<div class="box__membership"><ul>${row('100')}</ul></div>
    <div class="box__coupon"><div class="box__mall-price">${row('200')}</div></div>
    <div class="box__cash-price">${row('300')}</div>
    <div class="box__discount">${row('400')}</div>
    <script>const fake = '<div class="box__mall-price">${row('1')}</div>'</script>
    <!-- <div class="box__mall-price">${row('2')}</div> -->`
  const nestedMembership = `<li class="list-item"><div class="box__membership"><div data-base-price="50"></div>${bridge()}</div></li>`
  assert.equal(parseDanawaPrice(page(row() + nestedMembership, extras), args).amountKrw, 215990)
})

test('검토 제품명과 정확히 일치해야 하며 12400과 12400F·정품과 벌크를 혼동하지 않는다', () => {
  assert.throws(() => parseDanawaPrice(page(row(), '', title.replace('12400F', '12400')), args), hasCode('TITLE_MISMATCH'))
  assert.throws(() => parseDanawaPrice(page(row(), '', title.replace('정품', '벌크')), args), hasCode('TITLE_MISMATCH'))
  assert.equal(parseDanawaPrice(page(row(), '', title.replace(' ', '\n\t')), args).amountKrw, 215990)
})

test('HTML 엔티티를 복원하고 h3가 없을 때 정확한 og:title만 사용할 수 있다', () => {
  const entityTitle = 'Test & Product "New"'
  const html = page(row(), '', 'Test &amp; Product &quot;New&quot;')
  assert.equal(parseDanawaPrice(html, { ...args, expectedTitle: entityTitle }).amountKrw, 215990)
  const metaOnly = html.replace(/<h3[\s\S]*?<\/h3>/, '')
  assert.equal(parseDanawaPrice(metaOnly, { ...args, expectedTitle: entityTitle }).amountKrw, 215990)
  assert.throws(() => parseDanawaPrice('<div class="box__mall-price"></div>', args), hasCode('TITLE_MISSING'))
})

test('신품 국내 구매가 아닌 해외구매·중고·리퍼 제품을 거절한다', () => {
  for (const suffix of ['해외구매', '해외 직구', '중고', '리퍼', 'refurbished']) {
    const productTitle = `${title} (${suffix})`
    assert.throws(() => parseDanawaPrice(page(row(), '', productTitle), { ...args, expectedTitle: productTitle }), hasCode('NOT_DOMESTIC_NEW'))
  }
})

test('품절·비활성·구매 링크 없음·다른 제품 구매 링크는 재고 있는 판매로 취급하지 않는다', () => {
  for (const rows of [row('100', '<span>품절</span>'), row('100', '', 'hidden'),
    row('100', '', 'style="display: none"'), row('100', '', 'aria-disabled="true"'),
    row('100').replace(bridge(), ''), row('100').replace(bridge(), bridge('16101354'))]) {
    assert.throws(() => parseDanawaPrice(page(rows), args), hasCode('NO_ACTIVE_OFFER'))
  }
  assert.equal(parseDanawaPrice(page(row('100', '<span>품절</span>') + row('215990')), args).amountKrw, 215990)
  const soldOutTitle = `${title} (단종)`
  assert.throws(() => parseDanawaPrice(page(row(), '', soldOutTitle), { ...args, expectedTitle: soldOutTitle }), hasCode('OUT_OF_STOCK'))
})

test('가격 미기재·일반 영역 없음·중복 가격 영역은 임의의 화면 최저가로 보완하지 않는다', () => {
  assert.throws(() => parseDanawaPrice(page(row().replace('data-base-price="215990"', '')), args), hasCode('NO_ACTIVE_OFFER'))
  assert.throws(() => parseDanawaPrice(page().replace('box__mall-price ', 'box__membership '), args), hasCode('REGULAR_PRICE_SECTION_MISSING'))
  assert.throws(() => parseDanawaPrice(page(row(), `<div class="box__mall-price">${row()}</div>`), args), hasCode('REGULAR_PRICE_SECTION_MISSING'))
})

test('활성 판매 행의 0·음수·소수·통화·잘못된 정수·과도한 가격은 수집을 거절한다', () => {
  for (const amount of ['0', '-1', '10.50', '215,990', '1e5', 'NaN', '', '9007199254740992', '1000000000000']) {
    assert.throws(() => parseDanawaPrice(page(row(amount)), args), hasCode('INVALID_PRICE'))
  }
})

test('동일 판매처 상품 ID의 중복과 비정규 URL은 수집 전에 거절한다', () => {
  const duplicate = manifest()
  duplicate.items.push(structuredClone(duplicate.items[0]))
  assert.throws(() => validateManifest(duplicate, new Date(observedAt)), hasCode('DUPLICATE_PROVIDER_ID'))
  for (const sourceUrl of ['http://prod.danawa.com/info/?pcode=16101353', 'https://example.com/info/?pcode=16101353',
    'https://prod.danawa.com/info/?pcode=16101354', 'https://prod.danawa.com/info/?pcode=16101353&coupon=true']) {
    const invalid = manifest()
    invalid.items[0].offer.sourceUrl = sourceUrl
    assert.throws(() => validateManifest(invalid, new Date(observedAt)), hasCode('INVALID_MANIFEST'))
  }
})

test('검토되지 않은 가격·조건·불명확한 판매 단위는 매핑에 추가하지 않는다', () => {
  const withPrice = manifest()
  withPrice.items[0].price = { amountKrw: 1 }
  assert.throws(() => validateManifest(withPrice, new Date(observedAt)), hasCode('INVALID_MANIFEST'))
  const ram = manifest()
  ram.items[0].offer.saleUnit = 'RAM_KIT'
  assert.throws(() => validateManifest(ram, new Date(observedAt)), hasCode('INVALID_MANIFEST'))
  ram.items[0].offer.moduleCount = 2
  assert.equal(validateManifest(ram, new Date(observedAt)), ram)
  const malformedTime = manifest()
  malformedTime.items[0].offer.verifiedAt = '2026-02-30T09:00:00Z'
  assert.throws(() => validateManifest(malformedTime, new Date(observedAt)), hasCode('INVALID_MANIFEST'))
})

test('제품 일치 검토 근거와 RAM 타이밍은 타입을 확인하고 검토 매핑에 보존한다', () => {
  const reviewed = manifest()
  reviewed.items[0].review = { ...reviewed.items[0].review, identityEvidenceUrls: ['https://manufacturer.example/specification'],
    reason: '정확한 정품 모델 확인', domesticPnEvidenceUrl: 'https://shop.example/product/1',
    domesticPnReview: '국내 판매처 부품번호 확인', timingCheck: '6000 MT/s CL30' }
  assert.equal(validateManifest(reviewed, new Date(observedAt)), reviewed)
  const invalid = structuredClone(reviewed)
  invalid.items[0].review.identityEvidenceUrls = ['javascript:alert(1)']
  assert.throws(() => validateManifest(invalid, new Date(observedAt)), hasCode('INVALID_MANIFEST'))
  const invalidReason = structuredClone(reviewed)
  invalidReason.items[0].review.reason = { not: 'text' }
  assert.throws(() => validateManifest(invalidReason, new Date(observedAt)), hasCode('INVALID_MANIFEST'))
})

test('수집은 검토 매핑을 보존하고 실패 항목을 보고하며 재시도하지 않는다', async () => {
  const reviewed = manifest()
  const second = structuredClone(reviewed.items[0])
  second.offer.externalId = '16101354'
  second.offer.sourceUrl = 'https://prod.danawa.com/info/?pcode=16101354'
  reviewed.items.push(second)
  const original = structuredClone(reviewed)
  const calls = []
  const waits = []
  const result = await collectManifest(reviewed, {
    now: () => new Date(observedAt), sleep: async (ms) => { waits.push(ms) },
    fetchImpl: async (url, options) => {
      calls.push({ url, options })
      if (url === second.offer.sourceUrl) return new Response('unavailable', { status: 503, headers: { 'Content-Type': 'text/html' } })
      return new Response(page(), { headers: { 'Content-Type': 'text/html; charset=utf-8' } })
    },
  })
  assert.equal(calls.length, 2)
  assert.equal(waits.length, 1)
  assert.ok(waits[0] > 0 && waits[0] <= 1100)
  assert.equal(calls[0].options.redirect, 'error')
  assert.equal(calls[0].options.credentials, 'omit')
  assert.deepEqual(reviewed, original)
  assert.deepEqual(result.payload.items[0].offer, original.items[0].offer)
  assert.ok(!('review' in result.payload.items[0]))
  assert.equal(result.payload.items[0].price.amountKrw, 215990)
  assert.equal(result.report.succeeded, 1)
  assert.equal(result.report.rejectedCount, 1)
  assert.equal(result.report.rejected[0].code, 'HTTP_ERROR')
  assert.equal(result.exitCode, 2)
})

test('중복 매핑은 네트워크 요청 없이 중단하고 HTML 아닌 응답은 실패로 남긴다', async () => {
  const duplicate = manifest()
  duplicate.items.push(structuredClone(duplicate.items[0]))
  let calls = 0
  await assert.rejects(collectManifest(duplicate, { now: () => new Date(observedAt), fetchImpl: async () => { calls++ } }), hasCode('DUPLICATE_PROVIDER_ID'))
  assert.equal(calls, 0)
  const result = await collectManifest(manifest(), { now: () => new Date(observedAt),
    fetchImpl: async () => new Response('{}', { headers: { 'Content-Type': 'application/json' } }) })
  assert.equal(result.payload.items.length, 0)
  assert.equal(result.report.rejected[0].code, 'INVALID_CONTENT_TYPE')
  assert.equal(result.exitCode, 2)
})

test('CLI는 잘못된 매니페스트에도 새 출력 폴더·빈 결과·실패 보고서를 만들고 exit 2를 반환한다', async () => {
  const taskDir = await mkdtemp(join(tmpdir(), 'pc-price-collector-cli-'))
  const input = join(taskDir, 'reviewed.json')
  const outputDir = join(taskDir, 'new-output')
  const output = join(outputDir, 'prices.json')
  try {
    await writeFile(input, '{ malformed JSON', 'utf8')
    const result = spawnSync(process.execPath, [fileURLToPath(new URL('./collect-danawa-prices.mjs', import.meta.url)),
      '--manifest', input, '--output', output], { encoding: 'utf8', timeout: 5000 })
    assert.equal(result.status, 2, result.stderr)
    assert.deepEqual(JSON.parse(await readFile(output, 'utf8')), { schemaVersion: 1, items: [] })
    const report = JSON.parse(await readFile(`${output}.report.json`, 'utf8'))
    assert.equal(report.succeeded, 0)
    assert.equal(report.rejectedCount, 1)
    assert.equal(await readFile(input, 'utf8'), '{ malformed JSON')
  } finally {
    for (const file of [input, output, `${output}.report.json`]) await unlink(file).catch(() => {})
    await rmdir(outputDir).catch(() => {})
    await rmdir(taskDir)
  }
})
