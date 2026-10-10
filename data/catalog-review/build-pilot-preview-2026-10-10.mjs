import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import assert from 'node:assert/strict'

// Public review files only. This builder has no network, database or apply path.
const root = fileURLToPath(new URL('../../', import.meta.url))
const output = 'data/catalog-review/pilot-import-preview-2026-10-10.json'
const args = process.argv.slice(2)
assert.ok(args.length === 0 || args.join(' ') === '--check', 'Only --check or no arguments are supported')
const sources = []
async function read(file) {
  const raw = (await fs.readFile(path.join(root, file), 'utf8')).replace(/^\uFEFF/, '').replaceAll('\r\n', '\n')
  sources.push({ file, normalizedSha256: createHash('sha256').update(raw).digest('hex') })
  return JSON.parse(raw)
}
const selection = await read('data/catalog-review/pilot-selection-2026-10-10.json')
const existing = await read('data/catalog-review/catalog-selection-2026-10-09.json')
const platform = await read('data/catalog-review/pilot-platform-evidence-2026-10-10.json')
const ramGpu = await read('data/catalog-review/pilot-ram-gpu-evidence-2026-10-10.json')
const storage = await read('data/catalog-review/pilot-storage-evidence-2026-10-10.json')
const prices = await read('data/catalog-review/pilot-existing-prices-2026-10-10.json')
const priceReport = await read('data/catalog-review/pilot-existing-prices-2026-10-10.json.report.json')
const assessment = await read('data/catalog-review/pilot-existing-price-assessment-2026-10-10.json')
const modelPrices = await read('data/catalog-review/pilot-model-price-review-2026-10-10.json')
const storagePrices = await read('data/catalog-review/pilot-storage-price-review-2026-10-10.json')
assert.equal(selection.selectionApproval.databaseOrPriceApplyApproved, false)
assert.equal(selection.items.length, 14)
assert.equal(priceReport.succeeded, 6)
assert.equal(priceReport.rejectedCount, 0)
assert.equal(prices.items.length, 6)

// Same algorithm as UUID.nameUUIDFromBytes; model and product namespaces remain distinct.
function uuid(key) {
  const bytes = createHash('md5').update(key).digest()
  bytes[6] = (bytes[6] & 0x0f) | 0x30
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = bytes.toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
const newIdentities = {
  3: 'intel:ark:241067:model', 4: 'intel:ark:245694:model',
  6: 'asrock:B850M Pro RS:model', 7: 'msi:MAG B860M MORTAR WIFI:model',
  8: 'asus:TUF GAMING B860-PLUS WIFI:model',
  13: 'samsung:MZ-77E1T0BW', 14: 'samsung:MZ-V9P1T0BW',
}
const extractPreparedAt = '2026-10-09T16:33:34Z'
function manufacturerSource(part, evidenceFile, officialUrl) {
  return { sourceName: 'MANUFACTURER', externalId: newIdentities[part.selectionNumber] ?? `pilot:${part.existingCatalogExternalId}`,
    sourceRevision: 'pilot-review-2026-10-10', sourceUrl: officialUrl,
    rawPayload: { recordKind: 'REVIEWED_SPEC_EXTRACT', evidenceFile, selectionNumber: part.selectionNumber,
      pageCheckDate: '2026-10-10', pageCheckTimePrecision: 'DATE_ONLY',
      retrievedAtMeaning: 'Curated review-extract preparation time, not a precise original page fetch time' },
    retrievedAt: extractPreparedAt }
}

const models = new Map()
const products = selection.items.map(part => {
  const number = part.selectionNumber
  const old = part.existingCatalogExternalId ? existing.items.find(row => row.externalId === part.existingCatalogExternalId) : null
  const detail = platform.items.find(row => row.selectionNumber === number)
    ?? ramGpu.items.find(row => row.selectionNumber === number)
    ?? storage.candidates.find(row => row.selectionNumber === number)
  assert.ok(detail, `Missing detailed evidence for selection ${number}`)
  const spec = old?.specification ?? detail.spec ?? detail.storageSpecification
  assert.ok(spec, `Missing specification for selection ${number}`)
  if (old && detail.spec) assert.deepEqual(detail.spec, old.specification, `Existing specification changed: ${number}`)
  if (old && detail.preservedCatalogSpecification) assert.deepEqual(detail.preservedCatalogSpecification, old.specification)
  const product = old?.product ?? { type: part.type, manufacturer: part.manufacturer, modelName: part.modelName,
    partNumber: number >= 13 ? detail.selectedRetailPartNumber : null }
  const evidenceFile = number <= 8 ? 'data/catalog-review/pilot-platform-evidence-2026-10-10.json'
    : number <= 12 ? 'data/catalog-review/pilot-ram-gpu-evidence-2026-10-10.json'
      : 'data/catalog-review/pilot-storage-evidence-2026-10-10.json'
  const officialUrl = number >= 13
    ? storage.sources.find(source => source.sourceKey === detail.domesticSaleIdentityReview.manufacturerIdentitySourceKey).url
    : part.officialIdentityReview.url
  const source = manufacturerSource(part, evidenceFile, officialUrl)
  const sourceIdentity = old ? { sourceName: 'BUILDCORES', externalId: old.externalId }
    : { sourceName: source.sourceName, externalId: source.externalId }
  const modelKind = { CPU: 'CPU_MODEL', MOTHERBOARD: 'BOARD_MODEL', RAM: 'RAM_SPEC_GROUP', GPU: 'GPU_CHIP_MODEL', STORAGE: 'STORAGE_MODEL' }[part.type]
  const modelName = number === 9 || number === 10 ? 'DDR5 DIMM 16GB non-ECC unbuffered specification group'
    : number === 11 ? 'GeForce RTX 5060' : number === 12 ? 'Radeon RX 9060 XT' : part.modelName
  const manufacturer = number === 11 ? 'NVIDIA' : number === 12 ? 'AMD' : part.manufacturer
  const modelKey = `${modelKind}:${manufacturer}:${modelName}`
  const modelSource = { source, reviewScope: number === 9 || number === 10
    ? 'Only shared DDR5 DIMM 16GB non-ECC unbuffered facts. Kit PN is not module PN; rank, IC and exact module identity remain unknown.'
    : 'Reviewed manufacturer model identity only; no exact physical variant, full PC compatibility or current-price certification.' }
  if (!models.has(modelKey)) {
    models.set(modelKey, { modelKey, registration: {
      canonicalId: uuid(`pc-upgrade-lab/catalog/model/v1\0${modelKey}`), type: part.type, manufacturer, modelName,
      kind: modelKind, role: 'INSTALLED_PC_REFERENCE', family: null, series: null, sources: [], aliases: [],
    } })
  }
  models.get(modelKey).registration.sources.push(modelSource)
  const identityKind = part.type === 'RAM' ? 'RETAIL_KIT' : part.type === 'MOTHERBOARD' || (!old && number <= 8) ? 'MODEL_REFERENCE' : 'PHYSICAL_VARIANT'
  const role = number === 3 || part.type === 'MOTHERBOARD' || (!old && number <= 8) ? 'INSTALLED_PC_REFERENCE'
    : part.type === 'RAM' ? 'PURCHASE_CANDIDATE' : 'BOTH'
  const observation = prices.items.find(item => item.product.externalId === part.existingCatalogExternalId) ?? null
  const modelQuote = modelPrices.items.find(item => item.selectionNumber === number) ?? null
  return { selectionNumber: number, operation: old ? 'REUSE_EXISTING' : 'CREATE_PROPOSAL',
    sourceIdentity, proposedCanonicalId: uuid(`pc-upgrade-lab/catalog/product/v1\0${sourceIdentity.sourceName}\0${sourceIdentity.externalId}`),
    localProductId: null, product, specification: spec, manufacturerEvidence: source,
    modelKey, identityKind, role, bindingReviewScope: modelSource.reviewScope,
    memorySupport: detail.memorySupport ?? null, storageSupport: detail.storageSupport ?? null,
    priceReview: observation ? { status: 'REOBSERVED_PENDING_USER_APPROVAL', priceImportItem: observation,
      historicalAmountKrw: part.historicalApprovedPrice.amountKrw, deltaKrw: observation.price.amountKrw - part.historicalApprovedPrice.amountKrw }
      : { status: number === 10 ? 'HELD_EXACT_DOMESTIC_KIT_MAPPING_MISSING'
        : number >= 13 ? 'EXACT_SELLER_QUOTE_REVIEWED_PENDING_CONTRACT_AND_USER_APPROVAL'
          : 'HELD_EXACT_PHYSICAL_SALE_VARIANT_UNCONFIRMED', priceImportItem: null, modelQuote },
    evidenceFile, evidenceSelectionNumber: number, openChecks: detail.openChecks ?? detail.unknowns ?? part.openChecks }
})
assert.equal(products.filter(item => item.operation === 'REUSE_EXISTING').length, 7)
assert.equal(products.filter(item => item.operation === 'CREATE_PROPOSAL').length, 7)
assert.equal(models.size, 13)
assert.equal(new Set(products.map(item => item.proposedCanonicalId)).size, 14)
assert.equal(products.filter(item => item.priceReview.priceImportItem).length, 6)
assert.equal(storagePrices.observations.length, 2)
for (const quote of storagePrices.observations) {
  const product = products.find(item => item.selectionNumber === quote.selectionNumber)
  assert.equal(quote.partNumber, product.product.partNumber)
  assert.equal(quote.amountKrw, quote.evidenceChecks.visibleItemPriceKrw)
  assert.equal(quote.amountKrw, quote.evidenceChecks.hiddenSetGoodsPriceKrw)
  assert.equal(quote.evidenceChecks.showsOrderable, true)
  assert.equal(quote.evidenceChecks.selectedAddonCount, 0)
  assert.equal(quote.priceObservationProposal.shippingIncluded, false)
  assert.equal(quote.priceObservationProposal.conditionalDiscountApplied, false)
}
const preview = {
  schemaVersion: 1, previewDate: '2026-10-10', stage: 'DB_FREE_DETAILED_REVIEW_PENDING_APPLY_APPROVAL',
  approvalScope: { selectionAndDetailedReviewApproved: true, databaseApplyApproved: false, priceApplyApproved: false },
  sourceHashes: sources,
  summary: { reviewedParts: products.length, reusedProducts: 7, proposedNewProducts: 7,
    proposedModelRecords: models.size, proposedModelSources: [...models.values()].reduce((sum, model) => sum + model.registration.sources.length, 0),
    proposedProductBindings: products.length, proposedStorageProfiles: products.filter(item => item.storageSupport).length,
    proposedStorageSlots: products.reduce((sum, item) => sum + (item.storageSupport?.slots.length ?? 0), 0),
    reobservedExistingPriceCandidates: 6, exactNewStorageSellerQuotes: storagePrices.observations.length,
    quarantinedModelPriceQuotes: modelPrices.items.filter(item => item.price).length,
    databaseReads: 0, databaseWrites: 0, priceCoverageIncreaseFromReobservations: 0 },
  executionPolicy: { applyImplemented: false, resolveExistingIdsByExactSource: true, preserveExistingSpecsAndPrices: true,
    doNotPromoteVerificationOrActivation: true, pcRowsChanged: 0, migrationsRequired: 0,
    futureApplyMustRecheckDbStateAndBeIdempotent: true },
  models: [...models.values()], products, cpuSupportPairs: platform.cpuSupportPairs,
  ramQvlReviews: ramGpu.items.filter(item => item.product.type === 'RAM').map(item => ({ selectionNumber: item.selectionNumber, evidence: item.qvlEvidence })),
  existingPriceAssessment: assessment, exactStorageSellerQuoteReview: storagePrices,
  requiredBeforeApply: [
    'User approval of the concrete catalog and price scope',
    'DB-aware exact source/local ID and existing identity conflict preview before any writes',
    'Transactional idempotent apply implementation; no auto-seed on boot or pull',
    'MANUFACTURER product-source support in the price importer before new SSD quotes can be imported',
    'Recheck stock/identity/freshness of price quotes at apply time; preserve held quotes as review only',
  ],
}
if (args.length) {
  const stored = JSON.parse(await fs.readFile(path.join(root, output), 'utf8'))
  assert.deepEqual(stored, preview, 'Preview differs from reviewed public sources')
} else {
  await fs.writeFile(path.join(root, output), `${JSON.stringify(preview, null, 2)}\n`)
}
console.log(`DB-free pilot preview: parts=14; reuse=7; new proposals=7; models=13; bindings=14; slots=${preview.summary.proposedStorageSlots}; reobserved prices=6; DB writes=0`)
