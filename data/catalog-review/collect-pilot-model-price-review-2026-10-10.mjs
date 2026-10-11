// Explicit public-page collection. These model-level observations are quarantined, not price imports.
import { readFile, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { parseDanawaPrice } from '../../backend/tools/collect-danawa-prices.mjs'

if (process.argv.slice(2).join(' ') !== '--collect') {
  throw new Error('Only explicit --collect is supported. This review never connects to a DB.')
}
const selection = JSON.parse(await readFile(new URL('./pilot-selection-2026-10-10.json', import.meta.url), 'utf8'))
const pages = [
  [3, '69059753', '인텔 코어 울트라5 시리즈2 245K (애로우레이크) (정품)'],
  [4, '108424451', '인텔 코어 울트라5 시리즈2 250K Plus (애로우레이크 리프레시) (정품)'],
  [6, '75538394', 'ASRock B850M Pro RS 대원씨티에스'],
  [7, '74340266', 'MSI MAG B860M 박격포 WIFI'],
  [8, '74255378', 'ASUS TUF Gaming B860-PLUS WIFI 인텍앤컴퍼니'],
]
const items = []
for (const [selectionNumber, pcode, expectedTitle] of pages) {
  const part = selection.items.find(item => item.selectionNumber === selectionNumber)
  if (!part || part.existingCatalogExternalId !== null) throw new Error('Unexpected selection identity')
  const sourceUrl = `https://prod.danawa.com/info/?pcode=${pcode}`
  const item = { selectionNumber, candidateKey: part.researchCandidateKey, pcode, expectedTitle, sourceUrl,
    matchScope: 'MODEL_AND_ADVERTISED_DISTRIBUTION_NOT_EXACT_PHYSICAL_VARIANT',
    priceImportEligible: false,
    holdReason: selectionNumber === 3
      ? 'Global boxed ordering code is retired; exact domestic ordering code and installed-reference role require review.'
      : selectionNumber === 4
        ? 'Domestic boxed ordering code has not been matched to the manufacturer ordering code.'
        : 'Hardware revision is unknown; model-level page does not establish an exact physical sale variant.' }
  try {
    const response = await fetch(sourceUrl, { method: 'GET', redirect: 'error', credentials: 'omit', cache: 'no-store',
      signal: AbortSignal.timeout(15000), headers: { Accept: 'text/html', 'User-Agent': 'pc-upgrade-lab-price-collector/1.0' } })
    if (!response.ok || !response.headers.get('content-type')?.includes('text/html')) throw new Error(`Unexpected HTTP/content type (${response.status})`)
    const html = await response.text()
    item.responseSha256 = createHash('sha256').update(html).digest('hex')
    item.price = parseDanawaPrice(html, { pcode, expectedTitle, observedAt: new Date().toISOString() })
    item.status = 'OBSERVED_MODEL_PRICE_QUARANTINED'
  } catch (error) {
    item.price = null
    item.status = 'COLLECTION_REJECTED'
    item.errorCode = error.code ?? error.name
    item.errorMessage = error.message
  }
  items.push(item)
  await new Promise(resolve => setTimeout(resolve, 1100))
}
const review = { schemaVersion: 1, reviewDate: '2026-10-10', collectedAt: new Date().toISOString(),
  purpose: 'Quarantined live model-level item prices for the approved first-14 review; not an import payload',
  pricePolicy: { shippingIncluded: false, conditionalDiscountsIncluded: false, cachePricesUsed: false },
  databaseAccessed: false, databaseWrites: 0, items }
await writeFile(new URL('./pilot-model-price-review-2026-10-10.json', import.meta.url), `${JSON.stringify(review, null, 2)}\n`)
console.log(`Model prices observed ${items.filter(item => item.price).length}/${items.length}; import eligible=0; DB writes=0`)
