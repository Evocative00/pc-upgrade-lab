import { readFile, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

// Research only. Search candidates do not establish an exact sale SKU or a price.
const options = {}
for (let i = 2; i < process.argv.length; i += 2) {
  if (!['--inventory', '--output', '--limit'].includes(process.argv[i]) || !process.argv[i + 1] || options[process.argv[i]])
    throw new Error('Use --inventory <public-inventory.json> --output <candidate-review.json>')
  options[process.argv[i]] = process.argv[i] === '--limit' ? Number(process.argv[i + 1]) : resolve(process.argv[i + 1])
}
if (!options['--inventory'] || !options['--output'] || options['--inventory'] === options['--output'])
  throw new Error('Separate input and output paths are required')
const inventory = JSON.parse(await readFile(options['--inventory'], 'utf8'))
if (inventory.schemaVersion !== 1 || !Array.isArray(inventory.items) || inventory.items.length !== 307)
  throw new Error('Use the reviewed 307-product public inventory')
const missing = inventory.items.filter(item => item.currentPrice === null)
if (missing.length !== 233) throw new Error('Initial discovery scope must contain 233 unpriced products')
const selected = options['--limit'] === undefined ? missing : missing.slice(0, options['--limit'])
if (options['--limit'] !== undefined && (!Number.isInteger(options['--limit']) || options['--limit'] < 1 || options['--limit'] > 233))
  throw new Error('Limit must be 1..233')

function text(html) {
  return html.replace(/<[^>]*>/g, ' ').replace(/&#(\d+);/g, (_, code) => String.fromCodePoint(Number(code)))
    .replace(/&#x([\da-f]+);/gi, (_, code) => String.fromCodePoint(parseInt(code, 16)))
    .replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'")
    .replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim()
}
function candidates(html) {
  const found = new Map()
  for (const match of html.matchAll(/<p\b[^>]*class=["'][^"']*\bprod_name\b[^"']*["'][^>]*>([\s\S]*?)<\/p>/gi)) {
    const code = /(?:prod\.danawa\.com\/info\/\?pcode=|data-product-code=["'])([1-9]\d{0,15})/.exec(match[1])?.[1]
    const title = text(match[1])
    if (code && title && !found.has(code)) found.set(code, { externalId: code,
      sourceUrl: `https://prod.danawa.com/info/?pcode=${code}`, title })
    if (found.size === 12) break
  }
  return [...found.values()]
}
const report = { schemaVersion: 1, stage: 'SEARCH_CANDIDATES_ONLY', databaseWrites: 0, deployments: 0,
  priceImportApproved: false, sourceInventory: 'all-catalog-live-inventory-2026-10-10.json',
  rules: 'Search results are leads only. Review exact manufacturer PN, revision, RAM count, domestic NEW sale configuration and the live regular product price before price import. A search miss does not prove unavailability.',
  requested: selected.length, searchedAt: new Date().toISOString(), items: [] }
let previousStart = 0
let consecutiveFailures = 0
async function search(query) {
  const wait = 1100 - (Date.now() - previousStart)
  if (previousStart && wait > 0) await delay(wait)
  previousStart = Date.now()
  const sourceUrl = `https://search.danawa.com/dsearch.php?query=${encodeURIComponent(query)}`
  const response = await fetch(sourceUrl, { redirect: 'error', credentials: 'omit', cache: 'no-store',
    signal: AbortSignal.timeout(15000), headers: { Accept: 'text/html', 'User-Agent': 'pc-upgrade-lab-price-review/1.0' } })
  if (!response.ok || !response.headers.get('content-type')?.includes('text/html')) throw new Error(`HTTP ${response.status}`)
  const html = await response.text()
  if (html.length > 5_000_000) throw new Error('Response is too large')
  return { query, sourceUrl, observedAt: new Date().toISOString(),
    responseSha256: createHash('sha256').update(html).digest('hex'),
    pageTitle: text(/<title>([\s\S]*?)<\/title>/i.exec(html)?.[1] || ''), candidates: candidates(html) }
}
for (const item of selected) {
  const query = item.partNumber || `${item.manufacturer} ${item.modelName}`
  const sourceUrl = `https://search.danawa.com/dsearch.php?query=${encodeURIComponent(query)}`
  const result = { localProductId: item.id, canonicalId: item.canonicalId, type: item.type,
    manufacturer: item.manufacturer, modelName: item.modelName, partNumber: item.partNumber,
    identityKind: item.identityKind, query, sourceUrl, observedAt: null, candidates: [] }
  try {
    const primary = await search(query)
    Object.assign(result, primary)
    result.searches = [primary]
    if (item.partNumber && primary.candidates.length === 0) {
      const alternative = await search(`${item.manufacturer} ${item.modelName}`)
      result.searches.push(alternative)
      result.candidates = alternative.candidates
    }
    result.status = result.candidates.length ? 'EXACT_SKU_REVIEW_REQUIRED' : 'NO_SEARCH_CANDIDATES_REVIEW_REQUIRED'
  } catch (error) { result.status = 'REQUEST_FAILED_REVIEW_REQUIRED'; result.error = error.message;
    result.errorCode = error.cause?.code ?? null;
    result.errorName = error.name;
    result.errorCause = typeof error.cause?.message === 'string' ? error.cause.message.slice(0, 300) : null;
    result.errorCauseName = error.cause?.name ?? null;
    result.errorNestedCodes = Array.isArray(error.cause?.errors)
      ? error.cause.errors.slice(0, 4).map(nested => nested?.code ?? null) : null }
  report.items.push(result)
  consecutiveFailures = result.status === 'REQUEST_FAILED_REVIEW_REQUIRED' ? consecutiveFailures + 1 : 0
  if (consecutiveFailures >= 3) report.stoppedReason = 'THREE_CONSECUTIVE_REQUEST_FAILURES'
  if (report.items.length % 25 === 0 || report.items.length === selected.length || report.stoppedReason) {
    report.completed = report.items.length
    report.withCandidates = report.items.filter(item => item.candidates.length > 0).length
    await writeFile(options['--output'], `${JSON.stringify(report, null, 2)}\n`, 'utf8')
    console.log(`Search review ${report.completed}/${report.requested}; candidate products=${report.withCandidates}; price approvals=0`)
  }
  if (report.stoppedReason) { process.exitCode = 2; break }
}
