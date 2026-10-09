import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';

// Repository-only review. No database, network, seeds or private settings are used.
const root = fileURLToPath(new URL('../../', import.meta.url));
const auditDate = '2026-10-09';
const sources = new Map();
const normalizedHash = text => createHash('sha256').update(text.replaceAll('\r\n', '\n')).digest('hex');
async function read(relativePath) {
  const raw = await fs.readFile(path.join(root, relativePath), 'utf8');
  sources.set(relativePath, normalizedHash(raw));
  return JSON.parse(raw.replace(/^\uFEFF/, ''));
}
const batchCode = await fs.readFile(path.join(root,
  'backend/src/main/java/com/pcupgradelab/catalog/seed/CatalogSeedBatch.java'), 'utf8');
const batches = [...batchCode.matchAll(/\("(week2-[a-z0-9-]+)",\s*(\d+),/g)]
  .map(match => ({ name: match[1], expectedCount: Number(match[2]) }));
assert.equal(batches.length, 10, 'This review covers the ten approved historical batches.');
const seedRows = [];
for (const batch of batches) {
  const manifestPath = `backend/src/main/resources/catalog/seed/${batch.name}/manifest.json`;
  const manifest = await read(manifestPath);
  assert.equal(manifest.items.length, batch.expectedCount);
  for (const item of manifest.items) {
    const raw = await fs.readFile(path.join(root,
      `backend/src/main/resources/catalog/seed/${batch.name}/buildcores/${item.externalId}.json`), 'utf8');
    // Match CatalogSeedLoader: Windows checkout CRLF is normalized to LF before hashing.
    assert.equal(normalizedHash(raw), item.rawSha256, `Raw source mismatch: ${item.externalId}`);
    assert.ok(item.manufacturerSources.length > 0);
    seedRows.push({ ...item, manifestPath, seedName: batch.name });
  }
}
const seedsById = new Map(seedRows.map(item => [item.externalId, item]));
assert.equal(seedRows.length, 300);
assert.equal(seedsById.size, 300);
assert.equal(new Set(seedRows.map(item => `${item.product.type}|${item.product.manufacturer}|${item.product.modelName}`)).size, 300);
const pnRows = seedRows.filter(item => item.product.partNumber != null);
assert.equal(new Set(pnRows.map(item => `${item.product.manufacturer}|${item.product.partNumber}`)).size, pnRows.length);

function validateIdentity(product) {
  assert.equal(product.sourceName, 'BUILDCORES');
  const seed = seedsById.get(product.externalId);
  assert.ok(seed, `Unknown product: ${product.externalId}`);
  for (const field of ['manufacturer', 'modelName', 'partNumber']) {
    assert.equal(product[field], seed.product[field], `Identity mismatch: ${product.externalId}/${field}`);
  }
}
const priceFiles = ['observations-2026-10-06.json', 'observations-expansion50-2026-10-08.json'];
const mappingFiles = ['initial-danawa-mappings.json', 'expansion50-danawa-mappings-2026-10-08.json'];
const approved = new Map();
const mappings = new Map();
for (const file of mappingFiles) {
  for (const item of (await read(`data/catalog-current-prices/${file}`)).items) {
    validateIdentity(item.product);
    assert.ok(!mappings.has(item.product.externalId));
    mappings.set(item.product.externalId, item);
  }
}
for (const file of priceFiles) {
  for (const item of (await read(`data/catalog-current-prices/${file}`)).items) {
    validateIdentity(item.product);
    assert.ok(!approved.has(item.product.externalId));
    const mapping = mappings.get(item.product.externalId);
    assert.deepEqual(item.product, mapping.product);
    assert.deepEqual(item.offer, mapping.offer);
    assert.equal(item.price.condition, 'NEW');
    assert.equal(item.price.inStock, true);
    assert.ok(Number.isSafeInteger(item.price.amountKrw) && item.price.amountKrw > 0);
    assert.ok(Number.isFinite(Date.parse(item.price.observedAt)));
    approved.set(item.product.externalId, { ...item, observationFile: `data/catalog-current-prices/${file}` });
  }
}
assert.equal(approved.size, 72);
assert.equal(new Set([...approved.values()].map(item => item.offer.externalId)).size, 72);
const deferredDocument = await read('data/catalog-current-prices/deferred-expansion50-2026-10-08.json');
const deferred = new Map(deferredDocument.items.map(item => [item.product.externalId, item]));
const excludedDocument = await read('data/catalog-current-prices/excluded-candidates.json');
const excluded = new Map(excludedDocument.items.map(item => [item.product.externalId, item]));
assert.equal(deferred.size, 6);
assert.equal(excluded.size, 1);
for (const item of [...deferred.values(), ...excluded.values()]) {
  validateIdentity(item.product);
  assert.ok(!approved.has(item.product.externalId));
}
for (const id of deferred.keys()) assert.ok(!excluded.has(id));

// CPU memory profiles are disjoint additions. The latest board/CPU matrix is cumulative.
const cpuMemory = { items: [] };
for (const name of ['cpu-memory-v1', 'cpu-memory-expand100', 'cpu-memory-expand300']) {
  cpuMemory.items.push(...(await read(`backend/src/main/resources/catalog/enrichment/${name}.json`)).items);
}
const boardCpu = await read('backend/src/main/resources/catalog/enrichment/motherboard-cpu-expand300.json');
const cpuMemoryById = new Map(cpuMemory.items.map(item => [item.externalId, item]));
const boardCpuById = new Map(boardCpu.items.map(item => [item.board.externalId, item]));
assert.equal(cpuMemoryById.size, 70);
assert.equal(cpuMemory.items.length, cpuMemoryById.size);
assert.equal(boardCpuById.size, 70);
const allSupportEntries = boardCpu.items.flatMap(item => item.support.entries);
const supportPairCount = new Set(boardCpu.items.flatMap(item => item.support.entries
  .map(entry => `${item.board.externalId}|${entry.cpuExternalId}`))).size;
for (const item of cpuMemory.items) validateIdentity({ ...item, sourceName: 'BUILDCORES' });
for (const item of boardCpu.items) {
  validateIdentity({ ...item.board, sourceName: 'BUILDCORES' });
  for (const entry of item.support.entries) assert.equal(seedsById.get(entry.cpuExternalId)?.product.type, 'CPU');
}

const missing = (specification, fields) => fields.filter(field => specification[field] == null);
const compatibilityFields = {
  CPU: ['socketCode', 'hasIntegratedGraphics'],
  MOTHERBOARD: ['socketCode', 'formFactor', 'memoryType', 'memoryFormFactor', 'memorySlotCount', 'maxMemoryBytes', 'supportsEcc'],
  RAM: ['memoryType', 'moduleCapacityBytes', 'moduleCount', 'moduleFormFactor', 'pinCount', 'isEcc', 'bufferType', 'voltageV', 'heightMm'],
  GPU: ['vramBytes', 'pcieVersion', 'pcieConnectorLanes', 'pcieActiveLanes', 'lengthMm', 'heightMm', 'thicknessMm', 'slotWidth', 'cardPowerW', 'cardPowerBasis', 'psuRequirementW', 'psuRequirementBasis'],
  MONITOR: ['nativeWidthPx', 'nativeHeightPx', 'nativeStandardRefreshHz']
};
const conditionalFieldNotes = {
  supportsEcc: 'ECC RAM의 지원 여부를 판단할 때 필요',
  isEcc: 'ECC 여부를 포함한 RAM 호환성 판단에 필요',
  heightMm: '케이스 또는 쿨러 간섭을 판단할 때 필요',
  thicknessMm: '케이스 및 인접 슬롯 간섭 판단에 필요',
  slotWidth: '인접 슬롯 점유를 판단할 때 필요. 두께로 추정하지 않음',
  pcieConnectorLanes: '물리 PCIe 단자 크기 확인',
  pcieActiveLanes: '실제 동작 레인 수 확인. 물리 단자와 구분',
  cardPowerW: '카드 전력 추정에 필요. 시스템 PSU 권장 용량과 구분',
  cardPowerBasis: '카드 전력 공표값의 기준 확인',
  pinCount: '메모리 단자 규격 확인. DDR 종류만으로 임의 보충하지 않음',
  voltageV: '메모리 동작 조건 확인'
};
function specificationReview(seed) {
  const spec = seed.specification;
  const type = seed.product.type;
  const unknowns = missing(spec, compatibilityFields[type])
    .map(field => ({ field, condition: conditionalFieldNotes[field] ?? '해당 제원 추가 확인' }));
  const contextualNulls = [];
  if (type === 'CPU') {
    if (spec.hasIntegratedGraphics === false && spec.integratedGraphicsModel == null) contextualNulls.push('integratedGraphicsModel: 내장 그래픽 없음');
    if (spec.baseClockMhz == null && spec.performanceCoreBaseClockMhz != null && spec.efficientCoreBaseClockMhz != null) contextualNulls.push('baseClockMhz: P/E 기본 클럭으로 분리');
    if (spec.tdpW == null && spec.processorBasePowerW != null && spec.maximumTurboPowerW != null) contextualNulls.push('tdpW: Intel PBP/MTP를 별도로 기록');
    const support = cpuMemoryById.get(seed.externalId).support;
    for (const field of ['maxMemoryBytes', 'channelCount']) {
      if (support[field] == null) unknowns.push({ field: `cpuMemorySupport.${field}`, condition: '메모리 최대 용량 또는 구성 검토에 필요' });
    }
  }
  const otherUnknowns = type === 'MONITOR'
    ? missing(spec, ['hasRefreshOverclock', 'nativeOcRefreshHz', 'activePowerW', 'activePowerBasis', 'activePowerConditions'])
    : [];
  return {
    compatibilityUnknowns: unknowns,
    otherUnknowns,
    contextualNulls,
    nullIsNotAnError: true,
    additionalSchemaNeeds: ({ CPU: [], MOTHERBOARD: ['M.2/SATA/PCIe 슬롯 세부 구성'], RAM: ['모듈 rank', 'JEDEC/XMP/EXPO 프로필 구분'], GPU: [], MONITOR: ['영상 입력 단자 종류와 버전'] })[type]
  };
}
function identification(seed) {
  const type = seed.product.type;
  const labels = {
    CPU: ['CPU_MODEL_WITH_PACKAGE_VARIANT', 'CPU 모델. 이름만으로 정품 박스·벌크 판매 패키지 확정 불가'],
    MOTHERBOARD: ['MOTHERBOARD_RETAIL_MODEL', '보드 판매 모델. DDR/Wi-Fi/PCB 리비전 추가 확인'],
    RAM: seed.specification.moduleCount > 1
      ? ['RAM_RETAIL_KIT', '판매 키트. 수집된 개별 모듈 PN과 직접 동일시하지 않음']
      : ['RAM_SINGLE_MODULE', '단일 모듈 PN. 개별 장치 기준으로 확인'],
    GPU: ['GPU_RETAIL_CARD_MODEL', '실제 카드 판매 모델. Windows 칩셋명만으로 확정 불가'],
    MONITOR: ['MONITOR_RETAIL_MODEL', '모니터 판매 모델. 실제 모델명/PN 확인']
  };
  return { granularity: labels[type][0], pcMatchingNote: labels[type][1], partNumberKnown: seed.product.partNumber != null };
}
const categories = ['CPU', 'GPU', 'MOTHERBOARD', 'RAM', 'MONITOR'];
const items = seedRows.map(seed => {
  const id = seed.externalId;
  const observation = approved.get(id);
  const held = deferred.get(id);
  const revisionHeld = excluded.get(id);
  const mapping = mappings.get(id);
  const board = boardCpuById.get(id);
  const cpu = cpuMemoryById.get(id);
  const status = observation ? 'APPROVED_SNAPSHOT' : held ? 'QUOTE_REVIEW_PENDING'
    : revisionHeld ? 'REVISION_REVIEW_PENDING' : 'NO_APPROVED_OBSERVATION';
  return {
    externalId: id,
    product: seed.product,
    specification: seed.specification,
    identification: identification(seed),
    sourceEvidence: {
      seedName: seed.seedName,
      manifestPath: seed.manifestPath,
      rawSha256: seed.rawSha256,
      manufacturerSources: seed.manufacturerSources.map(source => ({ url: source.url, retrievedAt: source.retrievedAt, recordKind: source.payload.record_kind, reviewScope: source.payload.review_scope }))
    },
    demandEvidence: { status: 'NOT_COLLECTED', installedUserCount: null, domesticSalesRank: null },
    saleEvidence: {
      identityStatus: mapping ? 'HISTORICALLY_REVIEWED_MAPPING' : held ? 'HISTORICALLY_REVIEWED_DEFERRED_OFFER'
        : revisionHeld ? 'REVISION_NOT_CONFIRMED' : 'NOT_REVIEWED',
      availabilityNow: 'NOT_RECHECKED',
      offer: mapping?.offer ?? held?.offer ?? null,
      identityReview: mapping?.review ?? null
    },
    priceEvidence: {
      status,
      approvedAmountKrw: observation?.price.amountKrw ?? null,
      observedAt: observation?.price.observedAt ?? held?.price.observedAt ?? null,
      evidenceUrl: observation?.price.evidenceUrl ?? held?.price.evidenceUrl ?? revisionHeld?.sourceUrl ?? null,
      saleUnit: observation?.offer.saleUnit ?? held?.offer.saleUnit ?? null,
      wasInStockWhenObserved: observation?.price.inStock ?? held?.price.inStock ?? null,
      unapprovedObservedAmountKrw: held?.price.amountKrw ?? null,
      reviewReason: held?.reason ?? revisionHeld?.reason ?? null,
      observationFile: observation?.observationFile ?? null,
      priceIncludesShipping: false,
      freshnessPolicyApplied: false
    },
    specificationReview: specificationReview(seed),
    enrichmentReview: cpu ? { cpuMemorySupport: cpu.support, sourceUrl: cpu.sourceUrl, retrievedAt: cpu.retrievedAt }
      : board ? {
        revisionScope: board.support.revisionScope, hardwareRevision: board.support.hardwareRevision,
        supportEntryCount: board.support.entries.length,
        supportedCpuPairCount: new Set(board.support.entries.map(entry => entry.cpuExternalId)).size,
        unverifiedEntryCount: board.support.entries.filter(entry => entry.supportStatus === 'UNVERIFIED').length,
        unknownBiosEntryCount: board.support.entries.filter(entry => entry.biosRequirement === 'UNKNOWN').length,
        sourceUrl: board.sourceUrl, retrievedAt: board.retrievedAt, conditions: board.support.conditions
      } : null,
    selectionReview: {
      proposedDecision: 'RETAIN_PENDING_DEMAND_REVIEW',
      pcIdentificationRole: 'EXISTING_CATALOG_REFERENCE',
      purchaseRole: observation ? 'CANDIDATE_REQUIRES_FRESH_PRICE_AND_COMPATIBILITY_REVIEW'
        : 'CANDIDATE_REQUIRES_APPROVED_PRICE_AND_COMPATIBILITY_REVIEW',
      userDecision: null
    }
  };
}).sort((a, b) => categories.indexOf(a.product.type) - categories.indexOf(b.product.type)
  || a.product.manufacturer.localeCompare(b.product.manufacturer, 'en')
  || a.product.modelName.localeCompare(b.product.modelName, 'en'));
const countBy = (rows, value) => rows.reduce((counts, row) => {
  const key = value(row); counts[key] = (counts[key] ?? 0) + 1; return counts;
}, {});
const categorySummary = categories.map(category => {
  const rows = items.filter(item => item.product.type === category);
  const statusCounts = countBy(rows, row => row.priceEvidence.status);
  const specKeys = new Set(rows.flatMap(row => Object.keys(row.specification)));
  return {
    category, productCount: rows.length,
    approvedPriceCount: statusCounts.APPROVED_SNAPSHOT ?? 0,
    missingApprovedPriceCount: rows.length - (statusCounts.APPROVED_SNAPSHOT ?? 0),
    priceStatusCounts: statusCounts,
    manufacturerCounts: countBy(rows, row => row.product.manufacturer),
    platformCounts: countBy(rows, row => row.specification.socketCode ?? row.specification.chipVendor ?? row.specification.memoryType ?? 'EXTERNAL_MONITOR'),
    partNumberUnknownCount: rows.filter(row => !row.identification.partNumberKnown).length,
    compatibilityUnknownProductCount: rows.filter(row => row.specificationReview.compatibilityUnknowns.length > 0).length,
    nullSpecificationFieldCounts: Object.fromEntries([...specKeys].sort().map(field => [field, rows.filter(row => row.specification[field] == null).length]).filter(([, count]) => count > 0))
  };
});
assert.deepEqual(categorySummary.map(row => row.productCount), [70, 80, 70, 60, 20]);
assert.deepEqual(categorySummary.map(row => row.approvedPriceCount), [33, 12, 21, 2, 4]);
const supportStatusCounts = countBy(allSupportEntries, entry => entry.supportStatus);
const biosRequirementCounts = countBy(allSupportEntries, entry => entry.biosRequirement);
const report = {
  schemaVersion: 1, auditDate, intendedUsers: '국내 Windows 데스크톱 업그레이드 사용자',
  scope: '저장소의 승인된 300종 seed 및 enrichment와 가격 스냅샷 검토. 실제 로컬 DB와 현재 웹 판매 상태는 미점검.',
  conclusions: [
    '기존 300종은 유지 후보로 두고 실제 설치 수요와 국내 구매 수요 근거를 수집한 뒤 보강 우선순위를 정한다.',
    '300종 모두 제품별 실제 설치 수요 또는 국내 판매 순위 선정 근거가 아직 없다.',
    '가격 72종은 10월 6일 22종과 10월 8일 50종의 승인된 상품가 기록이다. 오늘의 재고나 가격을 보장하지 않는다.',
    '보류 6종은 수치 재검토 대상이며 오류 확정이나 품절로 분류하지 않는다. 보드 1종은 판매 리비전 확인이 필요하다.',
    'RAM_KIT 가격 2종은 키트 전체 금액이다. 장치 1개 용량과 판매 묶음 개수를 구분한다.',
    '조건부 null을 임의로 채우지 않는다. 제조사 일부 제원 근거는 완전한 PC 호환성 인증이나 국내 인기 근거가 아니다.'
  ],
  summary: {
    productCount: items.length, approvedPriceCount: approved.size, missingApprovedPriceCount: items.length - approved.size,
    priceStatusCounts: countBy(items, item => item.priceEvidence.status),
    demandEvidenceMissingCount: items.filter(item => item.demandEvidence.status === 'NOT_COLLECTED').length,
    availabilityNotRecheckedCount: items.filter(item => item.saleEvidence.availabilityNow === 'NOT_RECHECKED').length,
    partNumberUnknownCount: items.filter(item => !item.identification.partNumberKnown).length,
    manufacturerSourceRecordCount: items.reduce((sum, item) => sum + item.sourceEvidence.manufacturerSources.length, 0),
    categorySummary,
    absentCatalogCategories: ['STORAGE', 'PSU', 'CASE', 'COOLER'],
    ramSaleConfigurationCounts: countBy(items.filter(item => item.product.type === 'RAM'), item => String(item.specification.moduleCount)),
    boardCpuSupport: { boardCount: boardCpuById.size, cpuCount: boardCpu.cpus.length, entryCount: allSupportEntries.length, productPairCount: supportPairCount, supportStatusCounts, biosRequirementCounts },
    knownRepresentationGaps: [
      'CPU: Intel 31종은 모두 LGA1700. 다른 Intel 소켓의 기존 PC는 이 카탈로그에 없다.',
      'RAM: Kingston 32/60. Samsung 5종 및 SK Hynix 3종이며 실제 사용 비중 비교가 필요하다.',
      '보드: MSI 43/70. GPU 카드: MSI 41/80. 제조사 분포를 국내 사용자 표본과 비교해야 한다.',
      'STORAGE/PSU/CASE/COOLER: 카탈로그와 종류별 제원 모델 확장이 필요하다.'
    ]
  },
  proposedNextStep: {
    requiresUserConfirmation: true,
    purpose: '실제 설치 부품과 현재 구매 후보의 근거를 따로 모아 첫 보강 후보 목록을 작성한다.',
    researchFocus: ['기존 PC 식별의 빈 범위인 LGA1700 이전 Intel CPU와 보드', '삼성/SK Hynix 데스크톱 RAM의 모듈 PN', '첫 신규 범주인 SSD/STORAGE 후보와 필요한 제원'],
    selectionEvidence: ['동의받은 데스크톱 표본의 부품 이름과 모듈 PN. 제품 일련번호 등 개인 식별 정보 제외', '국내 판매 페이지의 정확한 SKU와 현재 구매 가능 여부', '제조사 제품 및 호환성 자료'],
    output: '제품별 추가 이유·식별 수준·공식 근거·국내 판매 근거가 있는 보강 후보 목록. 후보 확인 후 스키마 및 추가 묶음 범위를 결정한다.'
  },
  validation: { uniqueProductIds: 300, normalizedRawHashesMatched: 300, approvedPriceIdentitiesMatched: 72, duplicateApprovedOffers: 0, databaseAccessed: false, networkAccessed: false },
  sources: [...sources].map(([file, normalizedSha256]) => ({ file, normalizedSha256 })),
  items
};
const outputPath = path.join(root, 'data/catalog-review/catalog-selection-2026-10-09.json');
await fs.writeFile(outputPath, JSON.stringify(report, null, 2) + '\n', 'utf8');
console.log(JSON.stringify({ outputPath, summary: report.summary, validation: report.validation }, null, 2));
