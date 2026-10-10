import { readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { createHash } from 'node:crypto'
import { setTimeout as delay } from 'node:timers/promises'
import { parseDanawaPrice } from './collect-danawa-prices.mjs'

// Research only: a displayed price does not verify that a candidate is the exact catalog SKU.
const options = {}
for (let i = 2; i < process.argv.length; i += 2) {
  if (!['--discovery', '--output'].includes(process.argv[i]) || !process.argv[i + 1] || options[process.argv[i]])
    throw new Error('Use --discovery <candidate-review.json> --output <price-review.json>')
  options[process.argv[i]] = resolve(process.argv[i + 1])
}
if (!options['--discovery'] || !options['--output'] || options['--discovery'] === options['--output'])
  throw new Error('Separate discovery and output files are required')
const discovery = JSON.parse(await readFile(options['--discovery'], 'utf8'))
if (discovery.schemaVersion !== 1 || discovery.completed !== 233 || discovery.priceImportApproved !== false)
  throw new Error('Use the completed, unapproved 233-product discovery')
const selected = discovery.items.filter(item => item.candidates?.length)
const report = { schemaVersion: 1, stage: 'UNVERIFIED_SALE_SKU_PRICE_CANDIDATES_ONLY',
  priceImportApproved: false, databaseWrites: 0, deployments: 0, requested: selected.length,
  rules: 'The first search candidate may be a different SKU. All prices remain research only until exact manufacturer PN, domestic sale configuration, revision and RAM module count have been reviewed. A missing price section is not global stock evidence.', items: [] }
const plain = value => value.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '')
  .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, '').replace(/<[^>]*>/g, ' ')
  .replace(/&amp;/g, '&').replace(/&nbsp;/g, ' ').replace(/&quot;/g, '"')
  .replace(/&#39;|&apos;/g, "'").replace(/\s+/g, ' ').trim()
let lastStarted = 0
let consecutiveFailures = 0
for (const item of selected) {
  const candidate = item.candidates[0]
  if (!/^https:\/\/prod\.danawa\.com\/info\/\?pcode=[1-9]\d{0,15}$/.test(candidate.sourceUrl))
    throw new Error('Only public canonical Danawa product URLs are accepted')
  const result = { localProductId: item.localProductId, type: item.type, manufacturer: item.manufacturer,
    modelName: item.modelName, partNumber: item.partNumber, saleIdentityVerified: false,
    candidate: structuredClone(candidate), priceCandidate: null }
  const pause = 1100 - (Date.now() - lastStarted)
  if (lastStarted && pause > 0) await delay(pause)
  lastStarted = Date.now()
  try {
    const signal = AbortSignal.timeout(15000)
    const response = await fetch(candidate.sourceUrl, { redirect: 'error', credentials: 'omit',
      cache: 'no-store', signal, headers: { Accept: 'text/html', 'User-Agent': 'pc-upgrade-lab-price-review/1.0' } })
    if (!response.ok || !response.headers.get('content-type')?.toLowerCase().includes('text/html'))
      throw new Error(`HTTP ${response.status}`)
    const html = await response.text()
    signal.throwIfAborted()
    if (html.length > 5_000_000) throw new Error('Response exceeds review limit')
    result.observedAt = new Date().toISOString()
    result.responseSha256 = createHash('sha256').update(html).digest('hex')
    result.pageTitle = plain(/<title>([\s\S]*?)<\/title>/i.exec(html)?.[1] ?? '')
    result.candidateProductTitle = plain(/<h3\b[^>]*class=["'][^"']*\bprod_tit\b[^"']*["'][^>]*>[\s\S]*?<span\b[^>]*class=["'][^"']*\btitle\b[^"']*["'][^>]*>([\s\S]*?)<\/span>/i.exec(html)?.[1] ?? '')
    // Text occurrence is only a review hint: it may belong to a longer SKU or a related-product section.
    result.partNumberTextOccurrenceHint = Boolean(item.partNumber && plain(html).includes(item.partNumber))
    try {
      result.priceCandidate = parseDanawaPrice(html, { expectedTitle: result.candidateProductTitle,
        pcode: candidate.externalId, observedAt: result.observedAt })
      result.status = 'REGULAR_PRICE_FOUND_EXACT_SALE_SKU_REVIEW_REQUIRED'
    } catch (error) {
      result.status = 'NO_VERIFIED_PRICE_REVIEW_REQUIRED'
      result.reasonCode = error.code ?? 'PRICE_PARSE_FAILED'
    }
    consecutiveFailures = 0
  } catch (error) {
    result.status = 'REQUEST_FAILED_REVIEW_REQUIRED'
    result.error = error.message
    consecutiveFailures++
  }
  report.items.push(result)
  if (consecutiveFailures >= 3) report.stoppedReason = 'THREE_CONSECUTIVE_REQUEST_FAILURES'
  if (report.items.length % 25 === 0 || report.items.length === selected.length || report.stoppedReason) {
    report.completed = report.items.length
    report.regularPriceCandidates = report.items.filter(item => item.priceCandidate).length
    report.pnTextHintsWithPrice = report.items.filter(item => item.priceCandidate && item.partNumberTextOccurrenceHint).length
    report.requestFailures = report.items.filter(item => item.status === 'REQUEST_FAILED_REVIEW_REQUIRED').length
    await writeFile(options['--output'], `${JSON.stringify(report, null, 2)}\n`, 'utf8')
    console.log(`Unverified price review ${report.completed}/${report.requested}; regularPrices=${report.regularPriceCandidates}; pnTextHints=${report.pnTextHintsWithPrice}; approvals=0`)
  }
  if (report.stoppedReason) { process.exitCode = 2; break }
}
