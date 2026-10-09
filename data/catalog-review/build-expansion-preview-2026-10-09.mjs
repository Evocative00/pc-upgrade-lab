import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

// Read-only research preparation. No private settings, database, network, seed or price collection.
const root = fileURLToPath(new URL('../../', import.meta.url));
const outputFile = 'data/catalog-review/catalog-expansion-preview-2026-10-09.json';
const reviewFile = 'data/catalog-review/catalog-expansion-candidates-2026-10-09.json';
const existingFile = 'data/catalog-review/catalog-selection-2026-10-09.json';
const date = '2026-10-09';
const namespace = 'pc-upgrade-lab/catalog/product/v1';
const sourcePaths = new Map();
const normalizedHash = raw => createHash('sha256').update(raw.replaceAll('\r\n', '\n')).digest('hex');
const args = process.argv.slice(2);
assert.ok(args.length === 0 || (args.length === 1 && args[0] === '--check'),
  'Only preview generation or --check is supported. Catalog adoption, --apply and price collection are not authorized.');

async function read(relativePath, expectedHash = null) {
  assert.match(relativePath, /^(data\/catalog-(review|current-prices)\/|backend\/src\/main\/resources\/catalog\/(seed|enrichment)\/)[A-Za-z0-9_./-]+\.json$/);
  assert.ok(!relativePath.split('/').includes('..'));
  const absolutePath = await fs.realpath(path.join(root, relativePath));
  assert.ok(absolutePath.startsWith((await fs.realpath(root)) + path.sep));
  const raw = await fs.readFile(absolutePath, 'utf8');
  const hash = normalizedHash(raw);
  if (expectedHash != null) assert.equal(hash, expectedHash, `Stale source hash: ${relativePath}`);
  if (sourcePaths.has(relativePath)) assert.equal(sourcePaths.get(relativePath), hash);
  sourcePaths.set(relativePath, hash);
  return JSON.parse(raw.replace(/^\uFEFF/, ''));
}

function canonicalProductId(externalId) {
  assert.match(externalId, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
  const bytes = createHash('md5').update(`${namespace}\0BUILDCORES\0${externalId}`).digest();
  bytes[6] = (bytes[6] & 0x0f) | 0x30;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function nullPaths(value, prefix = 'specification') {
  if (value === null) return [prefix];
  if (typeof value !== 'object') return [];
  return Object.entries(value).flatMap(([key, child]) => nullPaths(child, `${prefix}.${key}`));
}

function validateUnits(candidate) {
  const spec = candidate.specification;
  const positiveInteger = value => assert.ok(Number.isSafeInteger(value) && value > 0);
  if (candidate.category === 'STORAGE') {
    assert.equal(spec.capacityBasis, 'DECIMAL_GB');
    positiveInteger(spec.advertisedCapacityGb);
    positiveInteger(spec.capacityBytes);
    assert.equal(spec.capacityBytes, spec.advertisedCapacityGb * 1_000_000_000);
    if (spec.busInterface === 'SATA') {
      for (const field of ['pcieVersion', 'pcieLanes', 'nvmeVersion']) assert.equal(spec[field], null);
    } else {
      assert.equal(spec.busInterface, 'PCIE');
      assert.equal(spec.interfaceProtocol, 'NVME');
      positiveInteger(spec.pcieLanes);
      assert.ok(spec.pcieVersion);
    }
    return { status: 'RECORDED_UNITS_VALIDATED', capacityBasis: 'DECIMAL_GB', note: '광고 용량의 십진 bytes다. 실제 스캔 장치 용량·슬롯 호환·판매 SKU 승인을 뜻하지 않는다.' };
  }
  if (candidate.category === 'RAM') {
    positiveInteger(spec.moduleCapacityBytes);
    positiveInteger(spec.moduleCount);
    assert.equal(spec.moduleCount, 1);
    assert.equal(spec.moduleCapacityBytes % (1024 ** 3), 0);
    return { status: 'RECORDED_UNITS_VALIDATED', capacityBasis: 'BINARY_MODULE_BYTES', note: '용량은 한 모듈 기준이다. 장착 개수와 판매 키트 개수는 별개이며 정확 모듈 PN은 미확인이다.' };
  }
  if (candidate.category === 'GPU') {
    positiveInteger(spec.vramBytes);
    assert.equal(spec.vramBytes % (1024 ** 3), 0);
    return { status: 'RECORDED_UNITS_VALIDATED', capacityBasis: 'BINARY_VRAM_BYTES', note: '연구 자료의 VRAM bytes를 보존하며 칩 모델을 특정 카드 SKU로 확정하지 않는다.' };
  }
  return { status: 'NO_RECORDED_DEVICE_CAPACITY_TO_CONVERT', capacityBasis: null, note: 'CPU/보드의 제조사 GB 표기와 기존 bytes를 그대로 보존한다. 미확인 숫자와 메모리 보장 속도를 새로 계산하지 않는다.' };
}

const research = await read(reviewFile);
const existing = await read(existingFile);
assert.equal(research.schemaVersion, 1);
assert.equal(research.researchDate, date);
assert.equal(existing.auditDate, date);
assert.equal(research.candidates.length, 78);
assert.equal(research.heldAdditionalModels.length, 6);
assert.equal(existing.items.length, 300);
assert.equal(existing.summary.approvedPriceCount, 72);
for (const source of [...research.sources, ...existing.sources]) await read(source.file, source.normalizedSha256);

// Reject a stale aggregate even if its listed raw hashes remain correct.
const rawCandidates = new Map();
const rawHeld = [];
for (const source of research.sources) {
  const group = await read(source.file, source.normalizedSha256);
  for (const item of group.candidates) {
    assert.ok(!rawCandidates.has(item.candidateKey));
    rawCandidates.set(item.candidateKey, { ...item, researchGroup: group.categoryGroup,
      researchSourceFile: source.file, existingCatalogDuplicateIds: [] });
  }
  rawHeld.push(...(group.heldProducts ?? []).map(item => ({ ...item,
    researchGroup: group.categoryGroup, researchSourceFile: source.file })));
}
for (const item of research.candidates) assert.deepEqual(item, rawCandidates.get(item.candidateKey));
assert.equal(rawCandidates.size, 78);
assert.deepEqual(research.heldAdditionalModels, rawHeld);

const existingMappings = [];
const externalIds = new Set();
for (const item of existing.items) {
  const id = item.externalId;
  assert.ok(!externalIds.has(id), `Duplicate reviewed external identity: ${id}`);
  externalIds.add(id);
  const manifest = await read(item.sourceEvidence.manifestPath);
  const row = manifest.items.find(entry => entry.externalId === id);
  assert.ok(row, `Missing reviewed source identity: ${id}`);
  assert.deepEqual(item.product, row.product);
  assert.deepEqual(item.specification, row.specification);
  assert.equal(row.rawSha256, item.sourceEvidence.rawSha256);
  await read(`backend/src/main/resources/catalog/seed/${item.sourceEvidence.seedName}/buildcores/${id}.json`, row.rawSha256);
  existingMappings.push({ sourceName: 'BUILDCORES', sourceExternalId: id,
    canonicalProductId: canonicalProductId(id), localProductId: null,
    expectedProduct: { category: item.product.type, manufacturer: item.product.manufacturer,
      modelName: item.product.modelName, partNumber: item.product.partNumber },
    lookupStrategy: 'EXACT_REVIEWED_SOURCE_IDENTITY', typeConflictBehavior: 'REJECT_AND_REVIEW',
    mappingReviewStatus: 'NEEDS_LOCAL_LOOKUP', mappingApproved: false,
    sourceManifestFile: item.sourceEvidence.manifestPath,
    sourceRecordSha256: row.rawSha256, approvedPriceStatus: item.priceEvidence.status });
}
assert.equal(new Set(existingMappings.map(item => item.canonicalProductId)).size, 300);

const phaseOrder = ['SSD', 'CURRENT_INTEL', 'CURRENT_AMD', 'CURRENT_GPU', 'LEGACY_INTEL', 'DESKTOP_RAM'];
const phases = new Map(phaseOrder.map((group, index) => [group, index]));
const candidates = [...research.candidates].sort((a, b) => phases.get(a.researchGroup) - phases.get(b.researchGroup))
  .map((item, index) => {
    assert.ok(phases.has(item.researchGroup));
    assert.equal(item.priceStatus, 'NOT_COLLECTED');
    assert.deepEqual(item.existingCatalogDuplicateIds, []);
    const gate = (code, status, detail) => ({ code, status, detail });
    return { candidateKey: item.candidateKey, category: item.category, manufacturer: item.manufacturer,
      modelName: item.modelName, researchPartNumber: item.partNumber, role: item.role,
      researchGroup: item.researchGroup, researchSourceFile: item.researchSourceFile,
      selectionReason: item.reasonForSelection, selectionEvidence: item.selectionEvidence,
      researchReadiness: item.readiness, lifeCycleStatus: item.lifeCycleStatus ?? null,
      lifeCycleDateRaw: item.lifeCycleDateRaw ?? null,
      reviewOrder: index + 1, reviewPhase: phases.get(item.researchGroup) < 4 ? 'LATEST_FIRST' : 'FOLLOWUP',
      eligibility: 'NEEDS_REVIEW', adoptionDecision: 'NOT_APPROVED',
      proposedCanonicalProductId: null, localProductId: null,
      userConfirmationRequired: true, databaseApplyAuthorized: false,
      saleIdentityStatus: item.saleIdentityStatus, priceStatus: 'NOT_COLLECTED',
      compatibilityReviewStatus: 'NOT_REVIEWED',
      identificationLevel: item.category === 'RAM' && item.partNumber == null ? 'BRAND_SPEED_CAPACITY_RANGE' : 'RESEARCH_MODEL_ONLY',
      knownSpecificationFacts: item.specification, unconfirmedFactPaths: nullPaths(item.specification),
      unitValidation: validateUnits(item),
      reviewGates: [gate('USER_ADOPTION', 'PENDING', '구현·미리보기 승인과 제품 채택 승인은 별개다.'),
        gate('PRODUCT_IDENTITY', 'PENDING', '정확 모델·판매 변형·리비전·제조사 PN 관계를 확인한다.'),
        gate('MANUFACTURER_PART_NUMBER', item.partNumber == null ? 'UNKNOWN' : 'RESEARCH_RECORDED_UNAPPROVED', '연구 PN은 국내 신품 판매 SKU 승인과 다르다.'),
        gate('DOMESTIC_NEW_SALE', 'NOT_VERIFIED', '국내 비교 페이지는 현재 개별 판매자의 신품 재고 근거가 아니다.'),
        gate('COMPATIBILITY', 'NOT_REVIEWED', '제원·지원 소개·사례는 해당 PC의 최종 호환 판정이 아니다.'),
        gate('MINIMUM_BIOS', ['CPU', 'MOTHERBOARD', 'STORAGE'].includes(item.category) ? 'NOT_REVIEWED' : 'NOT_APPLICABLE_TO_RESEARCH_TYPE', '정확 보드·CPU·부팅 지원·리비전별 조건은 후속 확인이다.'),
        gate('STORAGE_SLOT_RULES', ['MOTHERBOARD', 'STORAGE'].includes(item.category) ? 'NOT_REVIEWED' : 'NOT_APPLICABLE_TO_RESEARCH_TYPE', '슬롯·프로토콜·CPU별 활성화·공유 조건은 별도 검토한다.'),
        gate('SALE_UNIT', 'NOT_REVIEWED', '단품·키트·정품·벌크·방열판 등 판매 단위를 확인한다.'),
        gate('PRICE', 'NOT_COLLECTED', '상품가 관측을 만들거나 기존 가격의 날짜를 갱신하지 않는다.')],
      officialSources: item.officialSources, domesticSources: item.domesticSources,
      compatibilityNotes: item.compatibilityNotes, identificationNotes: item.identificationNotes,
      openQuestions: item.openQuestions };
  });

const preview = {
  schemaVersion: 1, previewDate: date, stage: 'DB_FREE_PREVIEW_PENDING_PRODUCT_ADOPTION',
  authorization: { implementationPreviewApproved: true, catalogAdoptionApproved: false,
    databaseApplyAuthorized: false, priceCollectionAuthorized: false,
    requiresUserConfirmationBeforeApply: true },
  summary: { existingProductCount: 300, retainedApprovedPriceCount: 72, researchCandidateCount: 78,
    latestFirstCandidateCount: candidates.filter(item => item.reviewPhase === 'LATEST_FIRST').length,
    followupCandidateCount: candidates.filter(item => item.reviewPhase === 'FOLLOWUP').length,
    heldAdditionalModelCount: 6, partNumberUnknownCount: candidates.filter(item => item.researchPartNumber == null).length,
    newProductsCreated: 0, newPriceObservationsCreated: 0 },
  canonicalIdPlan: { algorithm: 'JAVA_NAME_UUID_MD5_V3', namespace,
    identitySeedFields: ['sourceName', 'sourceExternalId'], nameBasedMatchingAllowed: false,
    localProductIdsPreserved: true, status: 'PROPOSED_MAPPING_NOT_APPLIED' },
  sourceHashes: [...sourcePaths].map(([file, normalizedSha256]) => ({ file, normalizedSha256 })),
  existingProductMappings: existingMappings, candidates,
  heldAdditionalModels: research.heldAdditionalModels.map(item => ({ modelName: item.modelName,
    category: item.category, manufacturer: item.manufacturer, researchGroup: item.researchGroup,
    researchSourceFile: item.researchSourceFile, status: item.status, reasonForHold: item.reasonForHold,
    proposedCanonicalProductId: null, localProductId: null, eligibility: 'NEEDS_REVIEW',
    userConfirmationRequired: true, databaseApplyAuthorized: false, priceStatus: 'NOT_COLLECTED',
    knownSpecificationFacts: { processorSeries: item.processorSeries, socketCode: item.socketCode,
      coreCount: item.coreCount, threadCount: item.threadCount, tdpW: item.tdpW },
    officialSources: item.officialSources, openQuestions: item.openQuestions }))
};
assert.equal(preview.summary.latestFirstCandidateCount, 52);
assert.equal(preview.summary.followupCandidateCount, 26);
const serialized = JSON.stringify(preview, null, 2) + '\n';
if (args[0] === '--check') {
  assert.ok((await fs.readFile(path.join(root, outputFile), 'utf8')).replaceAll('\r\n', '\n') === serialized,
    'Preview is stale; regenerate with the reviewed sources.');
} else {
  await fs.writeFile(path.join(root, outputFile), serialized, 'utf8');
}
console.log(JSON.stringify({ previewFile: outputFile, ...preview.summary,
  verifiedSourceHashCount: preview.sourceHashes.length, databaseAccessed: false,
  applyImplemented: false, checkOnly: args[0] === '--check' }));
