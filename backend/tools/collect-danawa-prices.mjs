import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'

const MAX_PRICE_KRW = 999_999_999_999
const VOID_TAGS = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr'])
const NON_NEW = /해외\s*(?:구매|직구)|중고|리퍼|재생품|\b(?:refurbished|refurb|renewed|used)\b/i
const UNAVAILABLE = /품절|판매\s*종료|판매\s*중지|단종|재고\s*없|\b(?:sold[-_ ]?out|out[-_ ]?of[-_ ]?stock)\b/i
const CONDITIONAL_CLASS = /membership|coupon|discount|benefit|cash-price/i

export class CollectionError extends Error {
  constructor(code, message) {
    super(message)
    this.name = 'CollectionError'
    this.code = code
  }
}

function fail(code, message) { throw new CollectionError(code, message) }
function isObject(value) { return value !== null && typeof value === 'object' && !Array.isArray(value) }
function text(value, name, max) {
  if (typeof value !== 'string' || !value.trim() || value.length > max) fail('INVALID_MANIFEST', `${name} requires a nonempty string (max ${max})`)
}

function exactKeys(value, names, name) {
  if (!isObject(value) || Object.keys(value).some((key) => !names.includes(key))) fail('INVALID_MANIFEST', `${name} contains an unsupported field or is not an object`)
}

function httpsUrl(value, name) {
  text(value, name, 2048)
  try {
    const url = new URL(value)
    if (url.protocol !== 'https:' || url.username || url.password) throw new Error('Unsupported URL')
  } catch { fail('INVALID_MANIFEST', `${name} requires an absolute HTTPS URL without credentials`) }
}

function instant(value, name) {
  const parts = typeof value === 'string' && /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(?:Z|[+-](\d{2}):(\d{2}))$/.exec(value)
  const time = Date.parse(value)
  if (!parts || !Number.isFinite(time) || time < 0) fail('INVALID_MANIFEST', `${name} requires an ISO timestamp since 1970`)
  const [, year, month, day, hour, minute, second] = parts
  const lastDay = new Date(Date.UTC(Number(year), Number(month), 0)).getUTCDate()
  if (Number(month) < 1 || Number(month) > 12 || Number(day) < 1 || Number(day) > lastDay ||
    Number(hour) > 23 || Number(minute) > 59 || Number(second) > 59) fail('INVALID_MANIFEST', `${name} is not a valid calendar timestamp`)
  return time
}

function canonicalUrl(pcode) {
  if (typeof pcode !== 'string' || !/^[1-9]\d{0,15}$/.test(pcode)) fail('INVALID_MANIFEST', 'offer.externalId requires a positive numeric Danawa pcode')
  return `https://prod.danawa.com/info/?pcode=${pcode}`
}

export function validateManifest(manifest, now = new Date()) {
  exactKeys(manifest, ['schemaVersion', 'items'], 'manifest')
  if (manifest.schemaVersion !== 1 || !Array.isArray(manifest.items) || manifest.items.length < 1 || manifest.items.length > 1000) {
    fail('INVALID_MANIFEST', 'manifest requires schemaVersion=1 and 1..1000 items')
  }
  const providerIds = new Set()
  for (const [index, item] of manifest.items.entries()) {
    const name = `items[${index}]`
    exactKeys(item, ['product', 'offer', 'review'], name)
    exactKeys(item.product, ['sourceName', 'externalId', 'manufacturer', 'modelName', 'partNumber'], `${name}.product`)
    if (item.product.sourceName !== 'BUILDCORES') fail('INVALID_MANIFEST', `${name}.product.sourceName must be BUILDCORES`)
    for (const [key, max] of [['externalId', 128], ['manufacturer', 100], ['modelName', 255]]) text(item.product[key], `${name}.product.${key}`, max)
    if (item.product.partNumber != null) text(item.product.partNumber, `${name}.product.partNumber`, 128)
    exactKeys(item.offer, ['sourceName', 'externalId', 'sourceUrl', 'saleSku', 'saleUnit', 'moduleCount', 'verifiedAt'], `${name}.offer`)
    if (item.offer.sourceName !== 'DANAWA' || item.offer.sourceUrl !== canonicalUrl(item.offer.externalId)) fail('INVALID_MANIFEST', `${name}.offer requires DANAWA and its canonical HTTPS pcode URL`)
    text(item.offer.saleSku, `${name}.offer.saleSku`, 255)
    if (item.offer.saleUnit === 'PRODUCT') {
      if (item.offer.moduleCount != null) fail('INVALID_MANIFEST', `${name}.offer.PRODUCT must not specify moduleCount`)
    } else if (item.offer.saleUnit !== 'RAM_KIT' || !Number.isSafeInteger(item.offer.moduleCount) || item.offer.moduleCount < 1) {
      fail('INVALID_MANIFEST', `${name}.offer requires PRODUCT or RAM_KIT with positive moduleCount`)
    }
    if (instant(item.offer.verifiedAt, `${name}.offer.verifiedAt`) > now.getTime()) fail('INVALID_MANIFEST', `${name}.offer.verifiedAt cannot be in the future`)
    exactKeys(item.review, ['expectedTitle', 'identityEvidenceUrls', 'reason', 'domesticPnEvidenceUrl', 'domesticPnReview', 'timingCheck'], `${name}.review`)
    text(item.review.expectedTitle, `${name}.review.expectedTitle`, 512)
    if (item.review.identityEvidenceUrls !== undefined) {
      if (!Array.isArray(item.review.identityEvidenceUrls) || item.review.identityEvidenceUrls.length < 1 || item.review.identityEvidenceUrls.length > 30) fail('INVALID_MANIFEST', `${name}.review.identityEvidenceUrls requires 1..30 HTTPS URLs`)
      for (const url of item.review.identityEvidenceUrls) httpsUrl(url, `${name}.review.identityEvidenceUrls`)
    }
    if (item.review.domesticPnEvidenceUrl !== undefined) httpsUrl(item.review.domesticPnEvidenceUrl, `${name}.review.domesticPnEvidenceUrl`)
    for (const [key, max] of [['reason', 4000], ['domesticPnReview', 4000], ['timingCheck', 512]]) {
      if (item.review[key] !== undefined) text(item.review[key], `${name}.review.${key}`, max)
    }
    if (NON_NEW.test(item.review.expectedTitle) || UNAVAILABLE.test(item.review.expectedTitle)) fail('INVALID_MANIFEST', `${name}.review.expectedTitle must describe an available domestic new product`)
    const key = `${item.offer.sourceName}:${item.offer.externalId}`
    if (providerIds.has(key)) fail('DUPLICATE_PROVIDER_ID', `Duplicate reviewed provider identity: ${key}`)
    providerIds.add(key)
  }
  return manifest
}

function decodeEntities(value) {
  const named = { amp: '&', quot: '"', apos: "'", lt: '<', gt: '>', nbsp: ' ' }
  return value.replace(/&(#x[\da-f]+|#\d+|amp|quot|apos|lt|gt|nbsp);/gi, (entity, key) => {
    if (key.startsWith('#')) {
      const code = key[1].toLowerCase() === 'x' ? Number.parseInt(key.slice(2), 16) : Number(key.slice(1))
      return code >= 0 && code <= 0x10ffff ? String.fromCodePoint(code) : entity
    }
    return named[key.toLowerCase()] ?? entity
  })
}

function normalizeText(value) { return decodeEntities(value).replace(/\s+/g, ' ').trim() }

// A small, non-executing tree for this reviewed HTML layout. Scripts, styles and comments never become offers.
function parseHtml(html) {
  const root = { tag: 'root', attrs: {}, children: [], parent: null }
  const stack = [root]
  const clean = html.replace(/<!--[\s\S]*?-->/g, '').replace(/<(script|style)\b[^>]*>[\s\S]*?<\/\1\s*>/gi, '')
  const tags = /<\/?([a-z][\w:-]*)\b(?:"[^"]*"|'[^']*'|[^'">])*?>/gi
  let end = 0
  for (const match of clean.matchAll(tags)) {
    const parent = stack.at(-1)
    if (match.index > end) parent.children.push({ text: clean.slice(end, match.index), parent })
    end = match.index + match[0].length
    const tag = match[1].toLowerCase()
    if (match[0].startsWith('</')) {
      const position = stack.findLastIndex((node) => node.tag === tag)
      if (position > 0) stack.length = position
      continue
    }
    const attrs = {}
    const source = match[0].slice(match[0].indexOf(match[1]) + match[1].length, -1)
    for (const attribute of source.matchAll(/([^\s=/'"<>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s'"=<>`]+)))?/g)) {
      attrs[attribute[1].toLowerCase()] = decodeEntities(attribute[2] ?? attribute[3] ?? attribute[4] ?? '')
    }
    const node = { tag, attrs, children: [], parent }
    parent.children.push(node)
    if (!VOID_TAGS.has(tag) && !match[0].endsWith('/>')) stack.push(node)
  }
  if (end < clean.length) stack.at(-1).children.push({ text: clean.slice(end), parent: stack.at(-1) })
  return root
}

function descendants(node, predicate) {
  const matches = []
  for (const child of node.children ?? []) {
    if (child.tag && predicate(child)) matches.push(child)
    matches.push(...descendants(child, predicate))
  }
  return matches
}
function hasClass(node, value) { return (node.attrs.class ?? '').split(/\s+/).includes(value) }
function nodeText(node) { return node.text ?? (node.children ?? []).map(nodeText).join(' ') }
function ancestors(node, predicate) {
  for (let current = node; current; current = current.parent) if (predicate(current)) return true
  return false
}
function inactive(node) {
  return ancestors(node, (ancestor) => ancestor.attrs && ('hidden' in ancestor.attrs || 'disabled' in ancestor.attrs ||
    ancestor.attrs['aria-disabled'] === 'true' || /display\s*:\s*none|visibility\s*:\s*hidden/i.test(ancestor.attrs.style ?? '') ||
    /(?:^|\s)(?:disabled|sold[-_]?out|out[-_]?of[-_]?stock|no[-_]?stock)(?:\s|$)/i.test(ancestor.attrs.class ?? '')))
}

export function parseDanawaPrice(html, { expectedTitle, pcode, observedAt }) {
  const evidenceUrl = canonicalUrl(pcode)
  instant(observedAt, 'observedAt')
  if (typeof html !== 'string' || html.length > 5_000_000) fail('INVALID_HTML', 'Expected an HTML page no larger than 5 MB')
  const root = parseHtml(html)
  const headings = descendants(root, (node) => node.tag === 'h3' && hasClass(node, 'prod_tit'))
  let title
  if (headings.length === 1) {
    const titleSpans = descendants(headings[0], (node) => hasClass(node, 'title'))
    title = normalizeText(nodeText(titleSpans.length === 1 ? titleSpans[0] : headings[0]))
  } else if (headings.length === 0) {
    const metas = descendants(root, (node) => node.tag === 'meta' && node.attrs.property === 'og:title')
    if (metas.length === 1) title = normalizeText(metas[0].attrs.content ?? '').replace(/^\[다나와\]\s*/, '')
  }
  if (!title) fail('TITLE_MISSING', 'The reviewed Danawa product title is missing or ambiguous')
  if (title !== normalizeText(expectedTitle)) fail('TITLE_MISMATCH', `Expected title does not match the fetched product: ${title}`)
  if (NON_NEW.test(title)) fail('NOT_DOMESTIC_NEW', `Product is not a domestic new product: ${title}`)
  if (UNAVAILABLE.test(title)) fail('OUT_OF_STOCK', 'Product title indicates it is unavailable')
  const sections = descendants(root, (node) => node.tag === 'div' && hasClass(node, 'box__mall-price') &&
    !ancestors(node, (ancestor) => CONDITIONAL_CLASS.test(ancestor.attrs?.class ?? '')))
  if (sections.length !== 1 || inactive(sections[0])) fail('REGULAR_PRICE_SECTION_MISSING', 'Exactly one active regular mall-price section is required')
  const prices = []
  for (const row of descendants(sections[0], (node) => node.tag === 'li' && hasClass(node, 'list-item'))) {
    if (inactive(row) || UNAVAILABLE.test(normalizeText(nodeText(row))) || NON_NEW.test(normalizeText(nodeText(row))) ||
      ancestors(row, (ancestor) => CONDITIONAL_CLASS.test(ancestor.attrs?.class ?? ''))) continue
    const links = descendants(row, (node) => node.tag === 'a' && !inactive(node) && typeof node.attrs.href === 'string' &&
      !ancestors(node, (ancestor) => CONDITIONAL_CLASS.test(ancestor.attrs?.class ?? '')))
    const activeBridge = links.some((link) => {
      try {
        const url = new URL(link.attrs.href, evidenceUrl)
        return url.protocol === 'https:' && url.hostname === 'prod.danawa.com' && !url.username && !url.password &&
          url.pathname === '/bridge/loadingBridge.html' && url.searchParams.get('pcode') === pcode
      } catch { return false }
    })
    if (!activeBridge) continue
    const values = descendants(row, (node) => 'data-base-price' in node.attrs && !inactive(node) &&
      !ancestors(node, (ancestor) => CONDITIONAL_CLASS.test(ancestor.attrs?.class ?? '')))
    if (values.length > 1) fail('AMBIGUOUS_PRICE', 'An active regular offer contains multiple base prices')
    if (values.length === 0) continue
    const raw = values[0].attrs['data-base-price']
    const amount = Number(raw)
    if (!/^[1-9]\d*$/.test(raw) || !Number.isSafeInteger(amount) || amount > MAX_PRICE_KRW) fail('INVALID_PRICE', 'An active regular offer has an invalid integer KRW base price')
    prices.push(amount)
  }
  if (prices.length === 0) fail('NO_ACTIVE_OFFER', 'No active regular offer with an explicit base price and matching purchase bridge was found')
  return { amountKrw: Math.min(...prices), observedAt, evidenceUrl, condition: 'NEW', inStock: true }
}

export async function collectManifest(manifest, { fetchImpl = globalThis.fetch, now = () => new Date(),
  sleep = delay, intervalMs = 1100, timeoutMs = 15_000 } = {}) {
  validateManifest(manifest, now())
  if (intervalMs < 1001 || timeoutMs < 1 || timeoutMs > 15_000) fail('INVALID_OPTIONS', 'Request interval must exceed 1000 ms and timeout must be 1..15000 ms')
  const successful = []
  const rejected = []
  let previousStart = 0
  for (const [index, item] of manifest.items.entries()) {
    const waitMs = intervalMs - (Date.now() - previousStart)
    if (previousStart && waitMs > 0) await sleep(waitMs)
    previousStart = Date.now()
    try {
      const signal = AbortSignal.timeout(timeoutMs)
      const response = await fetchImpl(item.offer.sourceUrl, { method: 'GET', redirect: 'error', credentials: 'omit',
        cache: 'no-store', signal, headers: { Accept: 'text/html', 'User-Agent': 'pc-upgrade-lab-price-collector/1.0' } })
      if (!response.ok) fail('HTTP_ERROR', `Danawa returned HTTP ${response.status}`)
      if (!response.headers.get('content-type')?.toLowerCase().includes('text/html')) fail('INVALID_CONTENT_TYPE', 'Danawa did not return an HTML page')
      const html = await response.text()
      signal.throwIfAborted()
      const observedAt = now().toISOString()
      const price = parseDanawaPrice(html, { expectedTitle: item.review.expectedTitle, pcode: item.offer.externalId, observedAt })
      successful.push({ product: structuredClone(item.product), offer: structuredClone(item.offer), price })
    } catch (error) {
      rejected.push({ index, externalId: item.offer.externalId, sourceUrl: item.offer.sourceUrl,
        code: error instanceof CollectionError ? error.code : error?.name === 'TimeoutError' ? 'REQUEST_TIMEOUT' : 'NETWORK_ERROR',
        message: error instanceof Error ? error.message : 'Collection failed' })
    }
  }
  return { payload: { schemaVersion: 1, items: successful },
    report: { schemaVersion: 1, collectedAt: now().toISOString(), requested: manifest.items.length,
      succeeded: successful.length, rejectedCount: rejected.length, rejected }, exitCode: rejected.length ? 2 : 0 }
}

async function main(args) {
  const options = {}
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index]
    if (!['--manifest', '--output'].includes(key) || !args[index + 1] || options[key]) fail('USAGE', 'Usage: node backend/tools/collect-danawa-prices.mjs --manifest reviewed.json --output prices.json')
    options[key] = args[index + 1]
  }
  if (!options['--manifest'] || !options['--output']) fail('USAGE', 'Both --manifest and --output are required')
  const manifestPath = resolve(options['--manifest'])
  const outputPath = resolve(options['--output'])
  if (manifestPath === outputPath || manifestPath === `${outputPath}.report.json`) fail('USAGE', 'Output must not overwrite the reviewed manifest')
  let manifest
  let result
  try {
    manifest = JSON.parse((await readFile(manifestPath, 'utf8')).replace(/^\uFEFF/, ''))
    result = await collectManifest(manifest)
  } catch (error) {
    result = { payload: { schemaVersion: 1, items: [] }, report: { schemaVersion: 1, collectedAt: new Date().toISOString(),
      requested: Array.isArray(manifest?.items) ? manifest.items.length : 0, succeeded: 0, rejectedCount: 1,
      rejected: [{ code: error instanceof CollectionError ? error.code : 'COLLECTION_ERROR', message: error.message }] }, exitCode: 2 }
  }
  await mkdir(dirname(outputPath), { recursive: true })
  await writeFile(outputPath, `${JSON.stringify(result.payload, null, 2)}\n`, 'utf8')
  await writeFile(`${outputPath}.report.json`, `${JSON.stringify(result.report, null, 2)}\n`, 'utf8')
  console.log(`Collected ${result.report.succeeded}/${result.report.requested}; rejected ${result.report.rejectedCount}. Report: ${outputPath}.report.json`)
  process.exitCode = result.exitCode
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch((error) => { console.error(error.message); process.exitCode = 2 })
}
