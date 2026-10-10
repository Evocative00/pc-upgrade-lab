import { createHash } from 'node:crypto'
import { mkdir, readFile, realpath, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { basename, dirname, isAbsolute, relative, resolve, sep } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { CollectionError } from './collect-danawa-prices.mjs'

export { CollectionError }
const MAX_BODY_BYTES = 5_000_000
const MAX_PRICE_KRW = 999_999_999_999
const NON_NEW = /해외\s*(?:구매|직구|배송)|구매\s*대행|병행\s*수입|중고|리퍼|재생품|\b(?:refurbished|refurb|renewed|used)\b/i
const WRONG_PACKAGE = /멀티\s*팩|벌크|트레이|\b(?:MPK|multipack|bulk|tray)\b|(?:CPU|프로세서)\s*\+|(?:세트|패키지)\s*상품/i
const UNAVAILABLE = /품절|판매\s*(?:종료|중지)|판매\s*중인\s*상품이\s*아닙|판매\s*중이\s*아닙|구매\s*(?:불가|불가능)|재고\s*없|\b(?:sold[-_ ]?out|out[-_ ]?of[-_ ]?stock)\b/i
const CONDITIONAL = /회원|쿠폰|혜택|할인|프로모션|현금|무통장|카드\s*결제|조건부|\b(?:coupon|discount|benefit|membership|promotion|cash)\b/i
const CONDITIONAL_CLASS = /coupon|discount|benefit|membership|promotion|cash|info_card|view_point/i
const VOID_TAGS = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr'])
const REPOSITORY_ROOT = fileURLToPath(new URL('../../', import.meta.url))

function fail(code, message) { throw new CollectionError(code, message) }
function object(value) { return value !== null && typeof value === 'object' && !Array.isArray(value) }
function keys(value, allowed, name) {
  if (!object(value) || Object.keys(value).some((key) => !allowed.includes(key))) fail('INVALID_MANIFEST', `${name} has unsupported fields`)
}
function string(value, name, max = 255) {
  if (typeof value !== 'string' || !value.trim() || value.length > max || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(value)) fail('INVALID_MANIFEST', `${name} requires nonempty text (max ${max})`)
}
function instant(value, name) {
  const match = typeof value === 'string' && /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(?:Z|[+-](\d{2}):(\d{2}))$/.exec(value)
  if (!match || !Number.isFinite(Date.parse(value)) || Date.parse(value) < 0) fail('INVALID_MANIFEST', `${name} requires an ISO timestamp since 1970`)
  const [, y, m, d, h, minute, second, offsetH, offsetM] = match
  if (+m < 1 || +m > 12 || +d < 1 || +d > new Date(Date.UTC(+y, +m, 0)).getUTCDate() || +h > 23 || +minute > 59 || +second > 59 || +(offsetH ?? 0) > 23 || +(offsetM ?? 0) > 59) fail('INVALID_MANIFEST', `${name} is not a valid calendar timestamp`)
  return Date.parse(value)
}
function epochNanos(value, name) {
  const time = instant(value, name)
  const fraction = /\.([0-9]{1,9})(?:Z|[+-])/.exec(value)?.[1] ?? ''
  return BigInt(Math.floor(time / 1000)) * 1_000_000_000n + BigInt(fraction.padEnd(9, '0'))
}
function evidenceUrl(value, name) {
  string(value, name, 2048)
  let url
  try { url = new URL(value) } catch { fail('INVALID_MANIFEST', `${name} requires an HTTPS URL`) }
  if (url.protocol !== 'https:' || url.username || url.password || url.port || url.hash) fail('INVALID_MANIFEST', `${name} requires HTTPS without credentials, port or fragment`)
}

// Only two reviewed public detail endpoints are fetchable. No search, cart, login or redirect endpoint is allowed.
export function validateRetailUrl(provider, externalId, value) {
  string(value, 'offer.sourceUrl', 2048)
  if (typeof externalId !== 'string' || !/^[1-9]\d{0,15}$/.test(externalId)) fail('INVALID_URL', 'Retail externalId must be a positive numeric product ID')
  let url
  try { url = new URL(value) } catch { fail('INVALID_URL', 'Malformed retail URL') }
  if (url.username || url.password || url.port || value.includes('#') || value.includes('\\')) fail('INVALID_URL', 'Credentials, ports, fragments and backslashes are not supported')
  if (provider === 'COMPUZONE') {
    if (!value.startsWith('https://www.compuzone.co.kr/product/product_detail.htm?') || url.origin !== 'https://www.compuzone.co.kr' || url.pathname !== '/product/product_detail.htm') fail('INVALID_URL', 'Only the Compuzone HTTPS detail endpoint is allowed')
    const fields = value.slice(value.indexOf('?') + 1).split('&')
    const seen = new Set()
    for (const field of fields) {
      const match = /^(ProductNo|DivNo|MediumDivNo)=(\d{1,16})$/.exec(field)
      if (!match || seen.has(match[1])) fail('INVALID_URL', 'Compuzone query allows unique numeric ProductNo, DivNo and MediumDivNo only')
      seen.add(match[1])
    }
    if (!seen.has('ProductNo') || url.searchParams.get('ProductNo') !== externalId) fail('INVALID_URL', 'Compuzone ProductNo does not match the reviewed ID')
  } else if (provider === 'ICODA') {
    if (value !== `https://usr.icoda.co.kr/item/view/${externalId}`) fail('INVALID_URL', 'Only the exact Icoda HTTPS detail URL without query is allowed')
  } else fail('INVALID_URL', 'Only COMPUZONE and ICODA are supported')
  return value
}

export function validateManifest(manifest, now = new Date()) {
  keys(manifest, ['schemaVersion', 'items'], 'manifest')
  if (manifest.schemaVersion !== 1 || !Array.isArray(manifest.items) || manifest.items.length < 1 || manifest.items.length > 1000 || !Number.isFinite(now.getTime())) fail('INVALID_MANIFEST', 'Expected schemaVersion=1 and 1..1000 reviewed CPU items')
  const providerIds = new Set()
  for (const [index, item] of manifest.items.entries()) {
    const name = `items[${index}]`
    keys(item, ['product', 'offer', 'review'], name)
    keys(item.product, ['sourceName', 'externalId', 'manufacturer', 'modelName', 'partNumber'], `${name}.product`)
    if (!['BUILDCORES', 'MANUFACTURER'].includes(item.product.sourceName)) fail('INVALID_MANIFEST', 'CPU product sourceName must use an existing BUILDCORES or MANUFACTURER identity')
    for (const [key, max] of [['sourceName', 100], ['externalId', 128], ['manufacturer', 100], ['modelName', 255]]) string(item.product[key], `${name}.product.${key}`, max)
    if (item.product.partNumber != null) string(item.product.partNumber, `${name}.product.partNumber`, 128)
    keys(item.offer, ['sourceName', 'externalId', 'sourceUrl', 'saleSku', 'saleUnit', 'moduleCount', 'verifiedAt'], `${name}.offer`)
    validateRetailUrl(item.offer.sourceName, item.offer.externalId, item.offer.sourceUrl)
    string(item.offer.saleSku, `${name}.offer.saleSku`)
    if (item.offer.saleUnit !== 'PRODUCT' || item.offer.moduleCount != null) fail('INVALID_MANIFEST', 'Boxed CPU saleUnit must be PRODUCT without moduleCount')
    keys(item.review, ['status', 'expectedTitle', 'expectedModel', 'expectedPartNumber', 'packageType', 'unitCount', 'domesticDistribution', 'manufacturerEvidenceUrl', 'domesticPnEvidenceUrl', 'domesticPnVerified', 'reason'], `${name}.review`)
    const review = item.review
    if (!['APPROVED', 'PENDING'].includes(review.status) || review.packageType !== 'BOX' || review.unitCount !== 1 || review.domesticDistribution !== 'AUTHORIZED') fail('INVALID_MANIFEST', 'Review must explicitly identify a single domestic authorized BOX CPU')
    string(review.expectedTitle, `${name}.review.expectedTitle`, 512)
    string(review.expectedModel, `${name}.review.expectedModel`, 128)
    const productModel = normalize(item.product.modelName).toUpperCase()
    const reviewedModel = normalize(review.expectedModel).toUpperCase()
    const modelPosition = productModel.indexOf(reviewedModel)
    if (modelPosition < 0 || /[A-Z0-9]/.test(productModel[modelPosition - 1] ?? '') || /[A-Z0-9]/.test(productModel[modelPosition + reviewedModel.length] ?? '')) fail('INVALID_MANIFEST', 'Reviewed model must exactly identify the product model token')
    if (review.expectedPartNumber != null) string(review.expectedPartNumber, `${name}.review.expectedPartNumber`, 128)
    if (review.reason != null) string(review.reason, `${name}.review.reason`, 4000)
    for (const key of ['manufacturerEvidenceUrl', 'domesticPnEvidenceUrl']) if (review[key] != null) evidenceUrl(review[key], `${name}.review.${key}`)
    if (review.domesticPnVerified !== undefined && typeof review.domesticPnVerified !== 'boolean') fail('INVALID_MANIFEST', 'domesticPnVerified must be boolean')
    if (NON_NEW.test(review.expectedTitle) || WRONG_PACKAGE.test(review.expectedTitle) || UNAVAILABLE.test(review.expectedTitle)) fail('INVALID_MANIFEST', 'Reviewed title must describe a domestic new BOX CPU')
    if (review.status === 'APPROVED') {
      if (!item.product.partNumber || review.expectedPartNumber !== item.product.partNumber || review.domesticPnVerified !== true || !review.manufacturerEvidenceUrl || !review.domesticPnEvidenceUrl) fail('UNREVIEWED_MAPPING', 'Approved CPU requires an exact known factory PN and manufacturer/domestic PN evidence')
      if (item.offer.saleSku !== item.product.partNumber) fail('UNREVIEWED_MAPPING', 'Approved CPU offer.saleSku must equal the exact reviewed factory BOX part number')
      if (epochNanos(item.offer.verifiedAt, `${name}.offer.verifiedAt`) > BigInt(now.getTime()) * 1_000_000n) fail('INVALID_MANIFEST', 'verifiedAt cannot be in the future')
    } else if (item.offer.verifiedAt != null && epochNanos(item.offer.verifiedAt, `${name}.offer.verifiedAt`) > BigInt(now.getTime()) * 1_000_000n) fail('INVALID_MANIFEST', 'verifiedAt cannot be in the future')
    const id = `${item.offer.sourceName}:${item.offer.externalId}`
    if (providerIds.has(id)) fail('DUPLICATE_PROVIDER_ID', 'A reviewed retailer product ID is repeated')
    providerIds.add(id)
  }
  return manifest
}

function decodeEntities(value) {
  const names = { amp: '&', quot: '"', apos: "'", lt: '<', gt: '>', nbsp: ' ' }
  return value.replace(/&(#x[\da-f]+|#\d+|amp|quot|apos|lt|gt|nbsp);/gi, (original, key) => {
    if (!key.startsWith('#')) return names[key.toLowerCase()]
    const code = key[1].toLowerCase() === 'x' ? Number.parseInt(key.slice(2), 16) : Number(key.slice(1))
    return code >= 0 && code <= 0x10ffff ? String.fromCodePoint(code) : original
  })
}
function normalize(value) { return decodeEntities(value).replace(/\s+/g, ' ').trim() }

// A non-executing tree. Script prices and recommendations outside the product scope cannot be offers.
function parseHtml(html) {
  const root = { tag: 'root', attrs: {}, children: [], parent: null }
  const stack = [root]
  const clean = html.replace(/<!--[\s\S]*?-->/g, '').replace(/<(script|style)\b[^>]*>[\s\S]*?<\/\1\s*>/gi, '')
  let end = 0
  let count = 0
  for (const match of clean.matchAll(/<\/?([a-z][\w:-]*)\b(?:"[^"]*"|'[^']*'|[^'">])*?>/gi)) {
    const parent = stack.at(-1)
    if (match.index > end) parent.children.push({ text: clean.slice(end, match.index), parent })
    end = match.index + match[0].length
    const tag = match[1].toLowerCase()
    if (match[0].startsWith('</')) { const position = stack.findLastIndex((node) => node.tag === tag); if (position > 0) stack.length = position; continue }
    if (++count > 100_000 || stack.length > 256) fail('INVALID_HTML', 'HTML tree exceeds supported limits')
    const attrs = {}
    for (const a of match[0].slice(match[0].indexOf(match[1]) + match[1].length, -1).matchAll(/([^\s=/'"<>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s'"=<>`]+)))?/g)) {
      const key = a[1].toLowerCase()
      // Real Compuzone buttons contain duplicate class attributes; keep the first browser-visible value.
      if (!(key in attrs)) attrs[key] = decodeEntities(a[2] ?? a[3] ?? a[4] ?? '')
    }
    const node = { tag, attrs, children: [], parent }
    parent.children.push(node)
    if (!VOID_TAGS.has(tag) && !match[0].endsWith('/>')) stack.push(node)
  }
  if (end < clean.length) stack.at(-1).children.push({ text: clean.slice(end), parent: stack.at(-1) })
  return root
}
function all(node, predicate) {
  const result = []
  const pending = [...(node.children ?? [])].reverse()
  while (pending.length) { const current = pending.pop(); if (current.tag && predicate(current)) result.push(current); if (current.children) pending.push(...[...current.children].reverse()) }
  return result
}
function cls(node, name) { return (node.attrs.class ?? '').split(/\s+/).includes(name) }
function ancestors(node, predicate) { for (let current = node; current; current = current.parent) if (predicate(current)) return true; return false }
function inactive(node) {
  return ancestors(node, (n) => n.attrs && ('hidden' in n.attrs || 'disabled' in n.attrs || n.attrs['aria-disabled'] === 'true' || /display\s*:\s*none|visibility\s*:\s*hidden/i.test(n.attrs.style ?? '') || /(?:^|\s)(?:disabled|disable|sold[-_]?out|out[-_]?of[-_]?stock|dsp_no\d*)(?:\s|$)/i.test(n.attrs.class ?? '')))
}
function visibleText(node) {
  const parts = []
  const pending = [node]
  while (pending.length) {
    const current = pending.pop()
    if (inactive(current)) continue
    if ('text' in current) parts.push(current.text)
    else pending.push(...[...(current.children ?? [])].reverse())
  }
  return normalize(parts.join(' '))
}
function one(values, code, message) { if (values.length !== 1 || inactive(values[0])) fail(code, message); return values[0] }
function integerPrice(value) {
  const compact = value.replace(/\s+/g, '')
  const match = /^([1-9]\d*|[1-9]\d{0,2}(?:,\d{3})+)원$/.exec(compact)
  const amount = match && Number(match[1].replaceAll(',', ''))
  if (!Number.isSafeInteger(amount) || amount < 1 || amount > MAX_PRICE_KRW) fail('INVALID_PRICE', 'Ordinary price must be a single positive integer KRW amount')
  return amount
}
function assertTitle(title, expectedTitle, expectedModel) {
  if (title !== normalize(expectedTitle)) fail('TITLE_MISMATCH', 'The current own product title differs from the reviewed title')
  const model = normalize(expectedModel).toUpperCase()
  const text = title.toUpperCase()
  const position = text.indexOf(model)
  if (position < 0 || /[A-Z0-9]/.test(text[position - 1] ?? '') || /[A-Z0-9]/.test(text[position + model.length] ?? '')) fail('MODEL_MISMATCH', 'The exact reviewed model token is missing')
  if (NON_NEW.test(title)) fail('NOT_DOMESTIC_NEW', 'The own product title is not domestic new stock')
  if (WRONG_PACKAGE.test(title)) fail('WRONG_PACKAGE', 'Multipack, bulk, tray and bundle products are excluded')
  if (UNAVAILABLE.test(title)) fail('OUT_OF_STOCK', 'The own product title indicates unavailability')
}

export function parseRetailPrice(html, { sourceName, externalId, sourceUrl, expectedTitle, expectedModel, observedAt }) {
  validateRetailUrl(sourceName, externalId, sourceUrl)
  instant(observedAt, 'observedAt')
  if (typeof html !== 'string' || Buffer.byteLength(html, 'utf8') > MAX_BODY_BYTES) fail('INVALID_HTML', 'Expected HTML within the 5 MB limit')
  const tree = parseHtml(html)
  let scope, title, amountKrw
  if (sourceName === 'COMPUZONE') {
    title = visibleText(one(all(tree, (n) => n.tag === 'h2' && cls(n, 'tit_p_name')), 'TITLE_MISSING', 'Exactly one Compuzone own product heading is required'))
    assertTitle(title, expectedTitle, expectedModel)
    if (!/정품\s*박스/.test(title)) fail('WRONG_PACKAGE', 'Compuzone must explicitly advertise an authorized BOX product')
    const ids = all(tree, (n) => n.tag === 'input' && n.attrs.id === 'ProductNo')
    if (ids.length !== 1 || ids[0].attrs.value !== externalId) fail('PRODUCT_ID_MISMATCH', 'The own ProductNo does not match the reviewed detail URL')
    if (all(tree, (n) => n.tag === 'input' && cls(n, 'pg_no')).some((n) => n.attrs.value !== '0')) fail('WRONG_PACKAGE', 'Grouped product configurations are not single boxed CPUs')
    scope = one(all(tree, (n) => cls(n, 'pdtl_col_rgt')), 'PRODUCT_SCOPE_MISSING', 'Exactly one active Compuzone product purchase scope is required')
    const section = one(all(scope, (n) => cls(n, 'info_price')), 'REGULAR_PRICE_SECTION_MISSING', 'Exactly one active ordinary selling-price section is required')
    if (ancestors(section, (n) => CONDITIONAL_CLASS.test(n.attrs?.class ?? '')) || CONDITIONAL.test(visibleText(section))) fail('CONDITIONAL_PRICE', 'Conditional benefit or promotion price cannot be an ordinary price')
    amountKrw = integerPrice(visibleText(one(all(section, (n) => cls(n, 'price_real')), 'AMBIGUOUS_PRICE', 'Exactly one visible ordinary price is required')))
    const quantity = all(scope, (n) => n.tag === 'input' && n.attrs.id === 'last_ea1')
    if (quantity.length !== 1 || quantity[0].attrs.value !== '1') fail('UNIT_COUNT_MISMATCH', 'The base selected CPU quantity must be one')
    const buys = all(scope, (n) => n.tag === 'a' && cls(n, 'buy') && !inactive(n) && visibleText(n) === '구매하기')
    const expectedBuy = new RegExp(`^buy_direct\\(\\s*['"]${externalId}['"]\\s*,`)
    if (!buys.some((n) => expectedBuy.test(n.attrs.onclick ?? ''))) fail('NO_ACTIVE_PURCHASE', 'No active purchase control for the exact ProductNo was found')
  } else {
    scope = one(all(tree, (n) => n.tag === 'article' && n.attrs.id === 'head_info'), 'PRODUCT_SCOPE_MISSING', 'Exactly one active Icoda own product scope is required')
    const pay = one(all(scope, (n) => cls(n, 'main_pay')), 'PRODUCT_SCOPE_MISSING', 'Exactly one Icoda primary purchase block is required')
    title = visibleText(one(all(pay, (n) => cls(n, 'view_name')), 'TITLE_MISSING', 'Exactly one Icoda own product heading is required'))
    assertTitle(title, expectedTitle, expectedModel)
    const specs = all(scope, (n) => n.tag === 'li').map((n) => visibleText(n))
    if (!specs.includes('포장 박스') || !specs.includes('유통 대리점 정품')) fail('WRONG_PACKAGE', 'Icoda own specifications must explicitly confirm BOX and authorized distribution')
    const ownIds = all(pay, (n) => /^set_copy\(['"]\d+['"]\)$/.test(n.attrs.onclick ?? ''))
    if (ownIds.length !== 1 || ownIds[0].attrs.onclick !== `set_copy('${externalId}')` && ownIds[0].attrs.onclick !== `set_copy("${externalId}")`) fail('PRODUCT_ID_MISMATCH', 'The own Icoda product ID does not match the reviewed URL')
    const price = one(all(pay, (n) => cls(n, 'view_price')), 'REGULAR_PRICE_SECTION_MISSING', 'Exactly one ordinary Icoda selling price is required')
    if (ancestors(price, (n) => CONDITIONAL_CLASS.test(n.attrs?.class ?? '')) || CONDITIONAL.test(visibleText(price))) fail('CONDITIONAL_PRICE', 'Conditional price cannot replace the ordinary selling price')
    amountKrw = integerPrice(visibleText(price))
    const quantity = all(pay, (n) => n.tag === 'input' && n.attrs.name === 'item_cnt')
    if (quantity.length !== 1 || quantity[0].attrs.value !== '1' || quantity[0].attrs.price !== String(amountKrw)) fail('UNIT_COUNT_MISMATCH', 'Icoda must select one CPU with the same explicit unit price')
    const buys = all(pay, (n) => n.attrs.type === '바로구매' && n.attrs.onclick === '바로구매();' && !inactive(n) && visibleText(n) === '바로구매' && ancestors(n, (p) => p.attrs && cls(p, 'btn_pay')))
    if (!buys.length) fail('NO_ACTIVE_PURCHASE', 'No active own product purchase control was found')
  }
  const currentScopeText = visibleText(scope)
  if (UNAVAILABLE.test(currentScopeText)) fail('OUT_OF_STOCK', 'Current product purchase scope says sold out or purchase unavailable')
  if (NON_NEW.test(currentScopeText)) fail('NOT_DOMESTIC_NEW', 'Current own product scope indicates overseas, used or parallel-import stock')
  if (WRONG_PACKAGE.test(currentScopeText)) fail('WRONG_PACKAGE', 'Current own purchase scope indicates multipack, bulk, tray or bundled stock')
  return { amountKrw, observedAt, evidenceUrl: sourceUrl, condition: 'NEW', inStock: true }
}

async function limitedBody(response, signal, maximum) {
  const contentLength = response.headers.get('content-length')
  if (contentLength !== null && (!/^\d+$/.test(contentLength) || Number(contentLength) > maximum)) fail('BODY_TOO_LARGE', 'Response exceeds the allowed body size')
  if (!response.body) fail('INVALID_HTML', 'The response body is missing')
  const reader = response.body.getReader()
  const abort = () => { void reader.cancel().catch(() => {}) }
  signal.addEventListener('abort', abort, { once: true })
  const chunks = []
  let total = 0
  try {
    for (;;) {
      signal.throwIfAborted()
      const { value, done } = await reader.read()
      if (done) break
      total += value.byteLength
      if (total > maximum) { await reader.cancel(); fail('BODY_TOO_LARGE', 'Response exceeds the allowed body size') }
      chunks.push(Buffer.from(value))
    }
  } finally { signal.removeEventListener('abort', abort); reader.releaseLock() }
  signal.throwIfAborted()
  return Buffer.concat(chunks, total)
}
function decodeBody(bytes, contentType, provider) {
  const ascii = bytes.subarray(0, 10_000).toString('latin1')
  let charset = (contentType.match(/charset\s*=\s*["']?([a-z\d_-]+)/i)?.[1] ?? ascii.match(/charset\s*=\s*["']?([a-z\d_-]+)/i)?.[1] ?? (provider === 'COMPUZONE' ? 'euc-kr' : 'utf-8')).toLowerCase()
  if (['ks_c_5601-1987', 'cp949', 'windows-949'].includes(charset)) charset = 'euc-kr'
  if (!['euc-kr', 'utf-8', 'utf8'].includes(charset)) fail('UNSUPPORTED_CHARSET', 'Only the reviewed UTF-8 and EUC-KR layouts are supported')
  try { return { html: new TextDecoder(charset, { fatal: true }).decode(bytes), charset } } catch { fail('INVALID_ENCODING', 'Retail page cannot be decoded without replacement characters') }
}

export async function collectManifest(manifest, { fetchImpl = globalThis.fetch, now = () => new Date(), monotonicNow = () => performance.now(), sleep = delay, intervalMs = 1100, timeoutMs = 15_000, maxBodyBytes = MAX_BODY_BYTES } = {}) {
  validateManifest(manifest, now())
  if (!Number.isSafeInteger(intervalMs) || intervalMs < 1001 || !Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 15_000 || !Number.isSafeInteger(maxBodyBytes) || maxBodyBytes < 1 || maxBodyBytes > MAX_BODY_BYTES) fail('INVALID_OPTIONS', 'Interval must exceed 1000 ms; timeout <=15000 ms; body <=5 MB')
  const successful = [], candidates = [], rejected = [], blockedHosts = new Set()
  let previousStart
  for (const [index, item] of manifest.items.entries()) {
    const host = new URL(item.offer.sourceUrl).hostname
    if (blockedHosts.has(host)) { rejected.push({ index, externalId: item.offer.externalId, code: 'HOST_BLOCKED', message: 'Further requests to this host stopped after HTTP 403/429' }); continue }
    if (previousStart !== undefined) { const wait = intervalMs - (monotonicNow() - previousStart); if (wait > 0) await sleep(wait) }
    previousStart = monotonicNow()
    try {
      const signal = AbortSignal.timeout(timeoutMs)
      const response = await fetchImpl(item.offer.sourceUrl, { method: 'GET', redirect: 'error', credentials: 'omit', cache: 'no-store', signal, headers: { Accept: 'text/html', 'User-Agent': 'pc-upgrade-lab-price-collector/1.0' } })
      if ([403, 429].includes(response.status)) blockedHosts.add(host)
      if (!response.ok) fail([403, 429].includes(response.status) ? 'ACCESS_BLOCKED' : 'HTTP_ERROR', `Retailer returned HTTP ${response.status}`)
      const contentType = response.headers.get('content-type') ?? ''
      if (!/^text\/html(?:\s*;|$)/i.test(contentType)) fail('INVALID_CONTENT_TYPE', 'Retailer did not return an HTML page')
      const bytes = await limitedBody(response, signal, maxBodyBytes)
      const { html, charset } = decodeBody(bytes, contentType, item.offer.sourceName)
      const observedAt = now().toISOString()
      const price = parseRetailPrice(html, { ...item.offer, ...item.review, observedAt })
      const evidence = { sourceUrl: item.offer.sourceUrl, observedAt, responseSha256: createHash('sha256').update(bytes).digest('hex'), responseHashScope: 'RAW_HTTP_RESPONSE_BYTES', charset }
      if (item.review.status !== 'APPROVED') {
        candidates.push({ product: structuredClone(item.product), offer: structuredClone(item.offer), review: structuredClone(item.review), currentOrdinaryPriceCandidate: price, evidence, importEligible: false, code: 'UNREVIEWED_MAPPING' })
        continue
      }
      if (epochNanos(observedAt, 'observedAt') < epochNanos(item.offer.verifiedAt, 'offer.verifiedAt')) fail('OBSERVATION_BEFORE_REVIEW', 'The new observation predates the completed SKU review')
      successful.push({ product: structuredClone(item.product), offer: structuredClone(item.offer), price })
      // Response hashes belong in the report; import DTOs retain their existing strict fields.
      evidence.index = index
      evidence.externalId = item.offer.externalId
      candidates.push({ evidence, importEligible: true })
    } catch (error) {
      rejected.push({ index, externalId: item.offer.externalId, sourceUrl: item.offer.sourceUrl, code: error instanceof CollectionError ? error.code : error?.name === 'TimeoutError' ? 'REQUEST_TIMEOUT' : 'NETWORK_ERROR', message: error instanceof CollectionError ? error.message : 'The public page request failed; no retry was attempted' })
    }
  }
  const pending = candidates.filter((item) => !item.importEligible)
  return { payload: { schemaVersion: 1, items: successful }, report: { schemaVersion: 1, collectedAt: now().toISOString(), mode: 'PREVIEW_ONLY', databaseWrites: 0, publications: 0, requested: manifest.items.length, succeeded: successful.length, candidateCount: pending.length, candidates: pending, evidence: candidates.filter((item) => item.importEligible).map((item) => item.evidence), rejectedCount: rejected.length, rejected, blockedHosts: [...blockedHosts] }, exitCode: rejected.length || pending.length ? 2 : 0 }
}

function within(root, path) {
  const part = relative(root, path)
  return part === '' || part !== '..' && !part.startsWith(`..${sep}`) && !isAbsolute(part)
}

// Resolve existing ancestors before creating files, so junctions/symlinks cannot redirect previews to live inputs.
async function physicalDestination(path) {
  let current = path
  const missing = []
  for (;;) {
    try { return resolve(await realpath(current), ...missing.reverse()) }
    catch (error) {
      if (error.code !== 'ENOENT' || dirname(current) === current) fail('USAGE', 'Preview path cannot be resolved safely')
      missing.push(basename(current))
      current = dirname(current)
    }
  }
}

export async function validatePreviewOutput(manifestPath, outputPath) {
  const repository = resolve(REPOSITORY_ROOT)
  const build = resolve(repository, 'backend/build')
  const temporary = resolve(tmpdir())
  const physicalRepository = await realpath(repository)
  const physicalBuild = resolve(physicalRepository, 'backend/build')
  const physicalTemporary = await realpath(temporary)
  const input = await physicalDestination(resolve(manifestPath))
  const output = resolve(outputPath)
  if (!output.toLowerCase().endsWith('.json')) fail('USAGE', 'Preview output must be a JSON file under backend/build or the system temporary directory')
  for (const target of [output, `${output}.report.json`]) {
    const physical = await physicalDestination(target)
    if (relative(input, physical) === '') fail('USAGE', 'Neither output nor report may overwrite the input manifest')
    const inBuild = within(build, target) && within(physicalBuild, physical)
    const inTemporary = within(temporary, target) && within(physicalTemporary, physical)
    if (!inBuild && !inTemporary) fail('USAGE', 'Preview output is restricted to backend/build or the system temporary directory; reviewed and production inputs cannot be overwritten')
    // A checkout inside TEMP still keeps its authoritative inputs protected.
    for (const reserved of ['data', 'workers', 'backend/src', 'backend/tools'])
      if (within(resolve(physicalRepository, reserved), physical)) fail('USAGE', 'Reviewed and production inputs cannot be overwritten')
  }
  return output
}

async function main(args) {
  const options = {}
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index]
    if (!['--manifest', '--output'].includes(key) || !args[index + 1] || options[key]) fail('USAGE', 'Usage: node backend/tools/collect-retail-prices.mjs --manifest reviewed.json --output preview.json')
    options[key] = args[index + 1]
  }
  if (!options['--manifest'] || !options['--output']) fail('USAGE', '--manifest and --output are required; this tool only writes preview files')
  const manifestPath = resolve(options['--manifest']), outputPath = resolve(options['--output'])
  await validatePreviewOutput(manifestPath, outputPath)
  let manifest, result
  try { manifest = JSON.parse((await readFile(manifestPath, 'utf8')).replace(/^\uFEFF/, '')); result = await collectManifest(manifest) }
  catch (error) { result = { payload: { schemaVersion: 1, items: [] }, report: { schemaVersion: 1, mode: 'PREVIEW_ONLY', databaseWrites: 0, publications: 0, collectedAt: new Date().toISOString(), requested: manifest?.items?.length ?? 0, succeeded: 0, candidateCount: 0, rejectedCount: 1, rejected: [{ code: error instanceof CollectionError ? error.code : 'INVALID_MANIFEST', message: error instanceof CollectionError ? error.message : 'Reviewed manifest could not be read' }] }, exitCode: 2 } }
  await mkdir(dirname(outputPath), { recursive: true })
  await writeFile(outputPath, `${JSON.stringify(result.payload, null, 2)}\n`, 'utf8')
  await writeFile(`${outputPath}.report.json`, `${JSON.stringify(result.report, null, 2)}\n`, 'utf8')
  console.log(`Preview: ${result.report.succeeded} eligible; ${result.report.candidateCount} unreviewed candidates; ${result.report.rejectedCount} rejected.`)
  process.exitCode = result.exitCode
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main(process.argv.slice(2)).catch((error) => { console.error(error instanceof CollectionError ? error.message : 'Preview collection failed'); process.exitCode = 2 })
