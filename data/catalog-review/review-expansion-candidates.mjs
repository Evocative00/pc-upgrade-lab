import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

// Research records only. This script never applies a catalog seed or connects to a database.
const root = fileURLToPath(new URL('../../', import.meta.url));
const researchDate = '2026-10-09';
const sourceFiles = [
  'data/catalog-review/candidates-legacy-intel-2026-10-09.json',
  'data/catalog-review/candidates-desktop-ram-2026-10-09.json',
  'data/catalog-review/candidates-ssd-2026-10-09.json',
  'data/catalog-review/candidates-current-intel-2026-10-09.json',
  'data/catalog-review/candidates-current-amd-2026-10-09.json',
  'data/catalog-review/candidates-current-gpu-2026-10-09.json'
];
const existing = JSON.parse(await fs.readFile(path.join(root,
  'data/catalog-review/catalog-selection-2026-10-09.json'), 'utf8'));
assert.equal(existing.items.length, 300);
const normalize = value => String(value ?? '').trim().toLowerCase().replace(/\s+/g, ' ');
const manufacturers = value => normalize(value).replace(/^sk\s*hynix$/, 'sk hynix');
const candidates = [];
const groups = [];
const excludedPurchaseCandidates = [];
const heldAdditionalModels = [];
const ids = new Set();
const pnKeys = new Set();
const modelKeys = new Set();
const validRoles = new Set(['INSTALLED_PC_REFERENCE', 'PURCHASE_CANDIDATE', 'BOTH']);
const validReadiness = new Set(['RESEARCH_CANDIDATE', 'NEEDS_IDENTITY_REVIEW']);
const validSaleIdentity = new Set(['NOT_REVIEWED', 'PRODUCT_MODEL_PAGE_FOUND', 'EXACT_VARIANT_REVIEWED']);
const validDomesticStatuses = new Set(['PRODUCT_PAGE_FOUND', 'CURRENT_OFFER_CONFIRMED', 'IDENTITY_UNCONFIRMED']);
const sourceHashes = [];
for (const relativePath of sourceFiles) {
  const raw = await fs.readFile(path.join(root, relativePath), 'utf8');
  const group = JSON.parse(raw.replace(/^\uFEFF/, ''));
  assert.equal(group.schemaVersion, 1);
  assert.equal(group.researchDate, researchDate);
  assert.ok(Array.isArray(group.candidates) && group.candidates.length > 0);
  sourceHashes.push({ file: relativePath, normalizedSha256: createHash('sha256').update(raw.replaceAll('\r\n', '\n')).digest('hex') });
  assert.ok(group.categoryGroup && Array.isArray(group.scopeLimitations));
  groups.push({ categoryGroup: group.categoryGroup, candidateCount: group.candidates.length,
    coverage: group.coverage ?? null, scopeLimitations: group.scopeLimitations,
    schemaRequirements: group.schemaRequirements ?? [], excludedScope: group.excludedScope ?? [] });
  excludedPurchaseCandidates.push(...(group.excludedPurchaseCandidates ?? []));
  for (const held of group.heldProducts ?? []) {
    assert.equal(held.includedInCandidateCount, false);
    assert.equal(held.priceStatus, 'NOT_COLLECTED');
    assert.ok(held.reasonForHold && held.officialSources.length > 0);
    for (const source of held.officialSources) {
      assert.equal(new URL(source.url).protocol, 'https:');
      assert.equal(source.checkedDate, researchDate);
    }
    heldAdditionalModels.push({ ...held, researchGroup: group.categoryGroup, researchSourceFile: relativePath });
  }
  for (const candidate of group.candidates) {
    assert.ok(candidate.candidateKey && !ids.has(candidate.candidateKey), 'Candidate key must be unique');
    ids.add(candidate.candidateKey);
    assert.ok(['CPU', 'MOTHERBOARD', 'RAM', 'STORAGE', 'GPU'].includes(candidate.category));
    assert.ok(candidate.manufacturer && candidate.modelName);
    const modelKey = `${candidate.category}|${manufacturers(candidate.manufacturer)}|${normalize(candidate.modelName)}`;
    assert.ok(!modelKeys.has(modelKey), `Duplicate proposed model: ${modelKey}`);
    modelKeys.add(modelKey);
    assert.ok(validRoles.has(candidate.role));
    assert.ok(validReadiness.has(candidate.readiness));
    assert.ok(validSaleIdentity.has(candidate.saleIdentityStatus));
    assert.equal(candidate.priceStatus, 'NOT_COLLECTED');
    assert.equal(candidate.selectionEvidence.installedDemand, 'NOT_MEASURED');
    assert.equal(candidate.selectionEvidence.domesticDemand, 'NOT_MEASURED');
    assert.ok(candidate.officialSources.length > 0);
    for (const source of [...candidate.officialSources, ...candidate.domesticSources]) {
      const url = new URL(source.url);
      assert.equal(url.protocol, 'https:');
      assert.equal(source.checkedDate, researchDate);
      assert.ok(source.title);
    }
    for (const source of candidate.officialSources) {
      assert.ok(Array.isArray(source.supportedFacts) && source.supportedFacts.length > 0);
    }
    for (const source of candidate.domesticSources) assert.ok(validDomesticStatuses.has(source.status));
    const duplicateIds = existing.items.filter(row => {
      if (row.product.type !== candidate.category || manufacturers(row.product.manufacturer) !== manufacturers(candidate.manufacturer)) return false;
      return (candidate.partNumber != null && row.product.partNumber != null && normalize(row.product.partNumber) === normalize(candidate.partNumber))
        || normalize(row.product.modelName) === normalize(candidate.modelName);
    }).map(row => row.externalId);
    assert.deepEqual(duplicateIds, [], `Already in the approved catalog: ${candidate.candidateKey}`);
    if (candidate.partNumber != null) {
      const key = `${manufacturers(candidate.manufacturer)}|${normalize(candidate.partNumber)}`;
      assert.ok(!pnKeys.has(key), `Duplicate proposed part number: ${key}`);
      pnKeys.add(key);
    }
    if (candidate.category === 'RAM') {
      assert.equal(candidate.specification.moduleFormFactor, 'DIMM');
      assert.equal(candidate.specification.moduleCount, 1);
      assert.ok(Number.isSafeInteger(candidate.specification.moduleCapacityBytes) && candidate.specification.moduleCapacityBytes > 0);
    }
    if (candidate.category === 'STORAGE') {
      const spec = candidate.specification;
      assert.equal(spec.storageKind, 'SSD');
      assert.equal(spec.capacityBasis, 'DECIMAL_GB');
      assert.equal(spec.capacityBytes, spec.advertisedCapacityGb * 1_000_000_000);
      assert.ok(Number.isSafeInteger(spec.capacityBytes) && spec.capacityBytes > 0);
      assert.ok(spec.formFactor && spec.busInterface);
      if (spec.busInterface === 'PCIE') {
        assert.equal(spec.interfaceProtocol, 'NVME');
        assert.ok(spec.pcieVersion && spec.pcieLanes > 0);
      }
      if (spec.busInterface === 'SATA') {
        assert.equal(spec.pcieVersion, null);
        assert.equal(spec.pcieLanes, null);
        assert.equal(spec.nvmeVersion, null);
      }
    }
    if (candidate.category === 'GPU') {
      const spec = candidate.specification;
      assert.ok(spec.chipVendor && spec.chipset);
      assert.ok(Number.isSafeInteger(spec.vramBytes) && spec.vramBytes > 0);
      if (spec.pcieActiveLanes != null && spec.pcieConnectorLanes != null) {
        assert.ok(spec.pcieActiveLanes <= spec.pcieConnectorLanes);
      }
      assert.equal(spec.powerConnectorsKnown, true);
      assert.ok(spec.powerConnectors.every(connector => connector.connectorCount > 0));
    }
    candidates.push({ ...candidate, researchGroup: group.categoryGroup, researchSourceFile: relativePath, existingCatalogDuplicateIds: duplicateIds });
  }
}
const countBy = getter => candidates.reduce((counts, candidate) => {
  const key = getter(candidate); counts[key] = (counts[key] ?? 0) + 1; return counts;
}, {});
const report = {
  schemaVersion: 1, researchDate,
  goal: '국내 데스크톱 사용자의 현재 부품을 정확히 식별하고, 근거가 있는 호환 부품과 현재 국내 신품 상품가를 제시한다.',
  candidatePurpose: '설치 부품 식별의 공백과 구매 후보 범위를 보강하기 전에 모델 정체성·호환 조건·판매 구성·추가 이유를 확인한다.',
  stage: 'RESEARCH_ONLY_PENDING_ADOPTION_REVIEW',
  groups,
  excludedPurchaseCandidates,
  heldAdditionalModels,
  summary: {
    candidateCount: candidates.length,
    heldAdditionalModelCount: heldAdditionalModels.length,
    categoryCounts: countBy(candidate => candidate.category),
    researchGroupCounts: countBy(candidate => candidate.researchGroup),
    roleCounts: countBy(candidate => candidate.role),
    identityReviewPendingCount: candidates.filter(candidate => candidate.readiness === 'NEEDS_IDENTITY_REVIEW').length,
    partNumberUnknownCount: candidates.filter(candidate => candidate.partNumber == null).length,
    domesticProductPageCount: candidates.filter(candidate => candidate.domesticSources.some(source => source.status !== 'IDENTITY_UNCONFIRMED')).length,
    exactSaleVariantReviewedCount: candidates.filter(candidate => candidate.saleIdentityStatus === 'EXACT_VARIANT_REVIEWED').length,
    currentOfferEvidenceCount: candidates.filter(candidate => candidate.domesticSources.some(source => source.status === 'CURRENT_OFFER_CONFIRMED')).length,
    installedDemandMeasuredCount: 0,
    domesticDemandMeasuredCount: 0,
    priceObservationsCreated: 0,
    catalogProductsCreated: 0
  },
  proposedSuccessMeasures: [
    { measure: '설치 부품 모델 식별 범위', definition: '동의받은 실제 데스크톱 표본에서 원문만 남은 항목과 근거를 갖고 모델까지 확인한 항목을 종류별로 비교한다.', currentValue: null },
    { measure: '정확한 제품 또는 모듈 PN 식별', definition: '모델군 식별과 정확한 판매 SKU 또는 RAM 모듈 PN 식별을 따로 집계한다. 이름만 일치한 후보는 정확한 SKU 성공으로 세지 않는다.', currentValue: null },
    { measure: '구매 검토 자료의 충족 여부', definition: '판매 변형·호환 조건·현재 국내 판매 근거·승인된 상품가 및 수집 시각을 각각 확인한다.', currentValue: null },
    { measure: '잘못 확정한 제품 및 추천', definition: '칩셋 이름, 같은 소켓 이름, 키트/단품 또는 용량만으로 확정한 오식별을 별도로 기록한다.', currentValue: null }
  ],
  nextDecision: {
    requiresUserConfirmation: true,
    proposal: '작성한 설계안에 따라 기존 UUID·300종·가격 72종을 유지하며 모델/판매 구성과 공통 ID, SSD·보드 슬롯·메모리 조건을 구현하고 최신 후보부터 DB 없는 검증 묶음을 준비할지 컨펌받는다.',
    designDocument: 'docs/catalog-db-expansion-design-2026-10-09.md',
    pendingIdentityCandidates: candidates.filter(candidate => candidate.readiness === 'NEEDS_IDENTITY_REVIEW').map(candidate => candidate.candidateKey),
    databaseApplyAuthorized: false
  },
  validation: { uniqueCandidateKeys: ids.size, existingCatalogDuplicates: 0, databaseAccessed: false, seedExecuted: false, priceCollected: false },
  sources: sourceHashes,
  candidates
};
const outputPath = path.join(root, 'data/catalog-review/catalog-expansion-candidates-2026-10-09.json');
await fs.writeFile(outputPath, JSON.stringify(report, null, 2) + '\n', 'utf8');

const cell = value => String(value ?? '미확인').replaceAll('|', '\\|').replaceAll('\n', ' ');
const roleLabels = { INSTALLED_PC_REFERENCE: '설치 PC 참조', PURCHASE_CANDIDATE: '구매 검토', BOTH: '설치 참조·구매 검토' };
const refs = candidate => {
  const official = candidate.officialSources.map((source, index) => `[공식${index + 1}](${source.url})`).join(' ');
  const domestic = candidate.domesticSources.map((source, index) => `[국내${index + 1}](${source.url})`).join(' ');
  return [official, domestic].filter(Boolean).join(' ');
};
const markdown = [
  '# 국내 데스크톱 부품 DB 보강 후보',
  '',
  '**목표는 현재 PC의 부품을 정확히 식별하고, 교체할 수 있는 부품과 현재 국내 신품 상품가를 근거와 함께 제시하는 것이다.** 사용자는 사양 확인에서 업그레이드 판단까지 이어지는 도움을 받아야 한다.',
  '',
  '조사 기준일은 2026-10-09다. Intel 6~11세대 설치 참조부터 현재 출시된 Core Ultra 200S·200S Plus, AMD 최신 AM5/X3D 모델·800 시리즈 보드, 누락 GPU 카드, 삼성·SK하이닉스 RAM과 SSD까지 범위를 확장했다. 기존 Intel 12~14세대 등 300종은 대조 자료로 유지한다. 후보는 실제 설치 비율이나 국내 판매 순위로 선정한 인기 제품 목록이 아니다.',
  '',
  '**이번 목록은 세대 범위를 최신까지 보강하는 조사 자료다. 모든 OEM·지역별 SKU·제조사 보드/카드를 전수 적재하거나 국내 신품 판매를 승인한 목록은 아니다.** 모델별 추가 후보와 보류 범위를 아래와 원자료에 구분한다. 설계는 [DB 확장 설계안](catalog-db-expansion-design-2026-10-09.md)을 따른다.',
  '',
  '## 후보를 검토하는 이유',
  '',
  '설치 부품 참조 자료는 사용자가 이미 가진 부품을 알아보는 데 필요하다. 구형 CPU·보드는 이 역할로 유지할 수 있다. 구매 검토 자료는 정확한 판매 구성, 호환 조건, 현재 판매 상태와 상품가가 필요하다. 두 역할을 구분하면 과거 부품을 신품 구매 대상으로 잘못 안내하거나 다른 변형의 가격을 연결하는 일을 줄일 수 있다.',
  '',
  '예를 들어 Intel 6·7세대와 8·9세대는 LGA1151이라는 소켓 이름을 공유하지만 메인보드 칩셋 계열이 서로 호환되지 않는다. 소켓 이름 일치만으로 교체 가능하다고 안내하면 안 된다. [Intel 공식 호환 안내](https://www.intel.com/content/www/us/en/support/articles/000025694/processors/intel-core-processors.html)',
  '',
  'M.2는 저장장치 형태이고 슬롯의 키·버스·지원 프로토콜은 별도 조건이다. NVMe SSD 후보를 추가할 때 보드의 실제 슬롯 지원도 함께 준비해야 한다. [Samsung SSD 설치 안내](https://semiconductor.samsung.com/consumer-storage/support/faqs/internalssd-installation/)',
  '',
  '## 목표 사용자 흐름',
  '',
  '1. 사용자의 PC 사양을 읽고 CPU 모델, 보드 모델, 개별 RAM 모듈과 저장장치를 확인한다.',
  '2. 모델군까지만 확인한 항목과 정확한 제품·모듈 번호까지 확인한 항목을 구분한다. 미확인 원문을 보존하고 필요한 확인을 안내한다.',
  '3. 보드·CPU·메모리·저장장치 연결 조건을 확인해 교체 가능한 후보와 추가 확인 사항을 제시한다.',
  '4. 구매 후보의 국내 신품 상품가, 판매 단위, 출처와 확인 시각을 보여준다.',
  '',
  '이는 앞으로 완성할 사용자 경험이다. 현재 수집기는 결과를 AUTO/UNMATCHED로 만들고, 카탈로그 검색과 CPU·보드·RAM의 근거 기반 호환 검사 일부가 구현돼 있다. 모델 자동 식별·SSD 슬롯 검사·전체 업그레이드 추천은 추가 구현 대상이다. [PC 공통 규격](week1-contract.md), [카탈로그 조회 서비스](../backend/src/main/java/com/pcupgradelab/catalog/CatalogQueryService.java), [현재 호환 규칙](../backend/src/main/java/com/pcupgradelab/compatibility/CompatibilityRules.java)',
  '',
  '가격 운영은 중앙 카탈로그·가격 API와 정기 수집을 통해 같은 제품을 보는 팀원과 기기에 같은 관측 결과를 제공하는 방향으로 이어간다. 현재 구현은 각 PC DB의 승인된 관측을 읽는 방식이며 새로고침 자체가 판매처를 재수집하지 않는다. [현재 가격 로직과 반영 절차](catalog-current-prices.md)',
  '',
  '## 조사 결과',
  '',
  '| 범위 | 후보 수 | 보강할 부분 |',
  '| --- | ---: | --- |',
  `| CPU | ${report.summary.categoryCounts.CPU} | Intel 6~11세대와 Core Ultra 200S·Plus, AMD AM5/X3D·AM4 XT 누락 모델 |`,
  `| 메인보드 | ${report.summary.categoryCounts.MOTHERBOARD} | 구형 플랫폼과 LGA1851 H810/B860/Z890, AM5 800 계열 |`,
  `| GPU 카드 | ${report.summary.categoryCounts.GPU} | 누락 RTX 5050·RX 9060·RX 9070 GRE 카드 |`,
  `| 삼성·SK하이닉스 RAM | ${report.summary.categoryCounts.RAM} | DDR4-3200 및 DDR5-4800·5600 개별 모듈과 용량 범위 |`,
  `| SSD | ${report.summary.categoryCounts.STORAGE} | SATA 2.5형과 M.2 NVMe PCIe 3·4·5세대 |`,
  `| 합계 | ${report.summary.candidateCount} | 기존 300종과 모델·확인된 PN의 직접 중복 없음 |`,
  '',
  `국내 모델 페이지를 확인한 후보는 ${report.summary.domesticProductPageCount}종이다. 정확한 국내 판매 변형을 승인한 후보와 현재 판매점의 신품 구매 가능성을 확인한 후보는 각각 ${report.summary.exactSaleVariantReviewedCount}종, ${report.summary.currentOfferEvidenceCount}종이다. 이번 후보에는 가격 관측을 생성하지 않았다.`,
  '',
  'RAM은 공식 QVL 또는 시험 구성에서 정확한 모듈 번호를 찾은 4종과 PN 미확정 구간 후보 6종을 구분했다. QVL은 특정 보드·CPU·메모리 구성에서 확인한 자료이며 모든 PC의 호환성을 보장하지 않는다. PN 미확정 구간은 정확한 제품 행으로 바로 적재하지 않는다.',
  '',
  '## Intel·AMD CPU와 보드 후보'
];
const groupLabels = { LEGACY_INTEL: 'Intel 6~11세대 설치 참조', CURRENT_INTEL: 'Intel 최신 데스크톱', CURRENT_AMD: 'AMD 데스크톱 최신·누락 모델' };
for (const group of groups.filter(value => candidates.some(item => item.researchGroup === value.categoryGroup && ['CPU', 'MOTHERBOARD'].includes(item.category)))) {
  markdown.push('', `### ${groupLabels[group.categoryGroup] ?? group.categoryGroup}`, '',
    '| 제품 | 확인한 범위 | 역할 | 근거 |', '| --- | --- | --- | --- |');
  for (const candidate of candidates.filter(item => item.researchGroup === group.categoryGroup && ['CPU', 'MOTHERBOARD'].includes(item.category))) {
    const spec = candidate.specification;
    const series = spec.generationLabel ?? spec.seriesLabel ?? spec.processorSeries ?? (spec.generation != null ? `${spec.generation}세대` : '제조사 모델 기준');
    const memoryTypes = (spec.memorySupport ?? []).map(type => type.memoryType);
    const coverage = candidate.category === 'CPU'
      ? `${series} / ${spec.socketCode} / ${memoryTypes.join('·') || (spec.memoryTypes ?? []).join('·') || '메모리 조건 추가 확인'}`
      : `${spec.chipset ?? spec.chipsetCode ?? '칩셋 추가 확인'} / ${spec.socketCode} / ${(spec.memoryTypes ?? [spec.memoryType].filter(Boolean)).join('·')}`;
    markdown.push(`| ${cell(candidate.manufacturer + ' ' + candidate.modelName)} | ${cell(coverage)} | ${roleLabels[candidate.role]} | ${refs(candidate)} |`);
  }
}
markdown.push('',
  'LGA1151은 100·200 시리즈와 300 시리즈 플랫폼을 구분한다. LGA1200도 보드·CPU 세대·정확한 지원 목록과 BIOS 조건을 확인한다. 보드의 DDR3 표기와 CPU의 DDR3L 지원 전압을 동일 조건으로 가정하지 않는다. 제조사 리비전·R2.0 접미사는 식별 과정에서 제거하지 않는다.',
  '',
  'Core Ultra Series 2는 임의의 Core i 15세대로 바꾸지 않는다. UDIMM/CUDIMM·DPC·rank별 속도 조건, 보드 CPU 지원표·BIOS와 슬롯 공유 조건을 추가 확인한다. AMD 9000·7000/8000 누락 모델은 기존 모델과 중복 없이 구분한다. OEM/저전력/신제품 출시는 아래 보류 범위와 원자료의 coverage를 따른다.',
  '', '## 최신 GPU 카드 보강', '',
  '| 카드 모델 | 메모리·형태 | 공식 PN | 근거 |', '| --- | --- | --- | --- |');
for (const candidate of candidates.filter(item => item.category === 'GPU')) {
  const spec = candidate.specification;
  markdown.push(`| ${cell(candidate.manufacturer + ' ' + candidate.modelName)} | ${cell(`${spec.vramBytes / (2 ** 30)} GiB / ${spec.lengthMm}mm / PCIe ${spec.pcieVersion}`)} | ${cell(candidate.partNumber)} | ${refs(candidate)} |`);
}
markdown.push('',
  'GPU 칩 모델과 이 카드의 제조사·VRAM·냉각기·정확 SKU는 별도 식별이다. Sapphire RX 9060 공식 페이지는 두 SKU를 함께 표시하므로 국내 단품 PN을 확정하지 않았다. 국내 20개 벌크 페이지를 단품 구매가에 연결하지 않는다.',
  '', '## 데스크톱 RAM 후보', '',
  '| 제조사 | 모듈 번호 또는 미확정 범위 | 용량·속도 | 확인 수준 | 근거 |',
  '| --- | --- | --- | --- | --- |');
for (const candidate of candidates.filter(item => item.category === 'RAM')) {
  const spec = candidate.specification;
  const level = candidate.partNumber == null ? 'PN 추가 확인' : '공식 자료에 PN 등장';
  markdown.push(`| ${cell(candidate.manufacturer)} | ${cell(candidate.partNumber ?? '정확 PN 미확정')} | ${cell(`${spec.memoryType} / ${spec.moduleCapacityBytes / (2 ** 30)} GiB / ${spec.dataRateMts} MT/s`)} | ${level} | ${refs(candidate)} |`);
}
markdown.push('',
  '각 후보의 moduleCount는 1이며 용량은 한 모듈 기준이다. 설치된 두 모듈과 2개 판매 키트를 동일시하지 않는다. 공식 자료에서 PN이 확인돼도 동일 용량·속도의 국내 모델 페이지가 그 PN만 판매하는지는 별도 확인한다.',
  '', '## SSD 후보', '',
  '| 제품·용량 | 형태·버스 | 공식 PN | 국내 판매 구성 | 근거 |',
  '| --- | --- | --- | --- | --- |');
for (const candidate of candidates.filter(item => item.category === 'STORAGE')) {
  const spec = candidate.specification;
  const physical = spec.formFactor === '2.5_INCH' ? '2.5형' : 'M.2 2280';
  const link = spec.busInterface === 'SATA' ? `SATA ${spec.sataVersion}` : `NVMe / PCIe ${spec.pcieVersion} x${spec.pcieLanes}`;
  markdown.push(`| ${cell(candidate.manufacturer + ' ' + candidate.modelName)} | ${cell(physical + ' / ' + link)} | ${cell(candidate.partNumber)} | 정확 SKU 추가 확인 | ${refs(candidate)} |`);
}
markdown.push('',
  'SSD 명목 1TB는 1,000,000,000,000 bytes로 기록한다. Windows에서 보고한 실제 장치 용량·파일시스템 용량과 구분하며 RAM GiB 방식으로 환산하지 않는다. 제조사 지역별 박스 PN, 방열판·용량·유통 구성은 정확한 국내 판매 상품과 연결할 때 다시 확인한다.',
  '', '## 구매 후보·범위 보류', '',
  ...excludedPurchaseCandidates.map(candidate => `${candidate.manufacturer} ${candidate.modelName}은 해당 국내 모델 페이지의 상태 때문에 이번 신품 구매 검토 목록에서 보류했다. 제조사의 모든 지역 단종이나 모든 국내 재고 소진을 뜻하지 않는다. ${candidate.domesticSources.map(source => `[국내 페이지](${source.url})`).join(' ')}`),
  '',
  'Intel T/A/TA·공식 제품번호 문서만 확인된 모델, Core Ultra Series 3 모바일, AMD AI/OEM 데스크톱과 전문 플랫폼은 조사 근거·국내 단품 판매 여부에 따라 별도 보류한다. 최신 보드라도 해당 제조사 페이지가 판매 중단을 표시하면 설치 참조 역할로 분류한다. 공식 출시 사실을 당일 국내 신품 재고 승인으로 해석하지 않는다.',
  '',
  `Ryzen AI 400 AM5 별도 검토 보류 ${heldAdditionalModels.length}종: ${heldAdditionalModels.map(model => model.modelName).join(', ')}. 위 ${report.summary.candidateCount}개 후보 집계에는 포함하지 않았다. 설치 참조 자료로 연결할 때도 정확한 보드 BIOS·OEM 조건을 확인한다.`,
  '',
  ...groups.filter(group => group.coverage != null).map(group => `- [${groupLabels[group.categoryGroup] ?? group.categoryGroup} 세대·모델별 coverage 원자료](../${sourceFiles.find(file => candidates.some(item => item.researchSourceFile === file && item.researchGroup === group.categoryGroup))})`),
  '', '## 작성한 설계안과 다음 컨펌', '',
  '[DB 확장 설계안](catalog-db-expansion-design-2026-10-09.md)은 아래 구조, 기존 ID 유지·공통 ID 매핑, 중앙 가격 API·매일 수집·48시간/7일 신선도와 단계별 승인 범위를 제안한다. 다음 컨펌은 실제 DB 적용 전 코드·스키마 구현과 검증용 추가 묶음 미리보기 준비까지다.', '',
  '1. CPU·보드의 모델 식별, RAM의 개별 모듈 식별, 정확한 판매 SKU를 구분한다. 구형 모델의 설치 참조 역할과 신품 구매 검토 역할도 별도로 표현한다.',
  '2. SSD 제원에 종류·명목 용량·형태·연결 버스·프로토콜·PCIe 레인·크기·방열판 구성과 출처를 기록한다. 조건부 비해당과 미확인 값을 구분한다.',
  '3. 보드의 슬롯별 M.2 길이·키·지원 버스/프로토콜·레인 경로·SATA 공유·CPU/BIOS 조건을 담는다. 슬롯 지원 자료가 없으면 SSD 호환을 확정하지 않는다.',
  '4. 기존 300종과 72종 가격 자료는 유지하면서 새 추가 묶음·안정적인 식별자·자료 검증 경로를 준비한다. 후보 키는 조사용이며 DB 제품 ID가 아니다.',
  '',
  '현재 카탈로그 종류별 제원 모델에는 STORAGE가 없어 SSD 후보를 현행 seed에 그대로 넣을 수 없다. [제원 모델](../backend/src/main/java/com/pcupgradelab/catalog/CatalogSpecification.java), [승인 seed 범위](../backend/src/main/java/com/pcupgradelab/catalog/seed/CatalogSeedBatch.java)',
  '', '## 효과를 확인하는 기준', '',
  '동의받은 실제 데스크톱 표본에서 종류별 모델 식별 범위를 측정한다. 모델군 확인과 정확한 제품·모듈 PN 확인은 따로 센다. 개인 식별 정보·일련번호·스캔 토큰을 조사 기록에 저장하지 않는다. 현재 실제 표본 수와 식별률은 미측정이며, 후보 수를 늘린 것만으로 성능 개선을 선언하지 않는다.',
  '',
  '구매 검토는 판매 변형, 호환 근거, 신품 구매 가능성, 승인된 상품가와 관측 시각의 충족 여부로 평가한다. 이름·소켓·용량만으로 잘못 확정한 결과를 별도로 기록한다.',
  '', '## 검토 자료와 재생성', '',
  '[통합 후보 원자료](../data/catalog-review/catalog-expansion-candidates-2026-10-09.json)에 제품별 추가 이유·식별 주의·공식 근거·국내 모델 페이지·미확인 사항을 보존한다. 기존 범위는 [300종 선정 검토 자료](../data/catalog-review/catalog-selection-2026-10-09.json)를 따른다.',
  '',
  '프로젝트 루트에서 아래 명령은 조사 자료의 중복·근거 URL·단일 모듈 및 용량 단위를 검사하고 통합 원자료와 이 문서를 재생성한다. DB·Flyway·seed·가격 수집을 실행하지 않는다.',
  '', '```powershell', 'node data/catalog-review/review-expansion-candidates.mjs', '```', ''
);
const documentPath = path.join(root, 'docs/catalog-expansion-candidates-2026-10-09.md');
await fs.writeFile(documentPath, markdown.join('\n'), 'utf8');
console.log(JSON.stringify({ outputPath, documentPath, summary: report.summary, validation: report.validation }, null, 2));
