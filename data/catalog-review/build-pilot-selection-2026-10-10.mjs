import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';

// Selection proposal only. No database, private settings, network or adoption.
const root = fileURLToPath(new URL('../../', import.meta.url));
const date = '2026-10-10';
const outputFile = 'data/catalog-review/pilot-selection-2026-10-10.json';
const args = process.argv.slice(2);
assert.ok(args.length === 0 || (args.length === 1 && args[0] === '--check'));
const sources = [];
async function read(file) {
  const raw = await fs.readFile(path.join(root, file), 'utf8');
  sources.push({ file, normalizedSha256: createHash('sha256').update(raw.replaceAll('\r\n', '\n')).digest('hex') });
  return JSON.parse(raw.replace(/^\uFEFF/, ''));
}
const existing = await read('data/catalog-review/catalog-selection-2026-10-09.json');
const research = await read('data/catalog-review/catalog-expansion-candidates-2026-10-09.json');
const schema = await read('data/catalog-review/schema-applied-2026-10-09.json');
assert.equal(existing.items.length, 300);
assert.equal(existing.summary.approvedPriceCount, 72);
assert.equal(research.candidates.length, 78);
assert.equal(schema.migrationAfter, '14');

const choices = [
  { id: 'cc0a0e08-bc25-4539-af92-952cced58623', type: 'CPU', platform: 'AMD_AM5', reason: '기존 Ryzen 9000 6코어 자료와 가격 연결을 재사용한다.', checks: ['정품 판매 패키지와 공식 PN 대조', '선택 보드의 정확 CPU 지원표와 설치 BIOS'] },
  { id: '09086e21-4979-41ba-a451-3f8bb1c9cc55', type: 'CPU', platform: 'AMD_AM5', reason: '같은 AM5 플랫폼의 8코어 모델을 별도 식별하고 연결한다.', checks: ['정품 판매 패키지와 공식 PN 대조', '선택 보드의 정확 CPU 지원표와 설치 BIOS'] },
  { key: 'CURRENT-INTEL-CPU-U5-245K', type: 'CPU', platform: 'INTEL_LGA1851', reason: 'Core Ultra 200S 기본 모델과 Plus 모델을 구분한다.', checks: ['국내 정품 판매 구성과 제조사 주문 번호', '보드별 CPU 지원표와 최소 BIOS', 'B860 조합의 기본 동작만 검증; CPU 오버클럭 범위 제외'] },
  { key: 'CURRENT-INTEL-CPU-U5-250K-PLUS', type: 'CPU', platform: 'INTEL_LGA1851', reason: '최신 200S Plus 모델을 첫 묶음에 포함해 BIOS·OS 조건을 다룬다.', checks: ['국내 정품 판매 구성과 제조사 주문 번호', '보드별 Plus CPU 지원표와 최소 BIOS', '보드가 제시하는 OS 조건을 CPU 지원과 별도 확인'] },
  { id: 'b4a7cd9a-692d-4289-a9ca-f5f9a659db5d', type: 'MOTHERBOARD', platform: 'AMD_AM5', reason: '기존 가격·CPU 지원 근거를 재사용하고 슬롯 조건을 보강한다.', checks: ['보드 리비전과 국내 판매 구성', '과거 CPU 지원 근거와 최신 지원표 대조', 'M.2·SATA 포트별 연결 및 공유 조건'] },
  { key: 'CURRENT-AMD-MB-ASROCK-B850M-PRO-RS', type: 'MOTHERBOARD', platform: 'AMD_AM5', reason: 'B850과 M.2/PCIe 슬롯 공유 조건을 추가한다.', checks: ['WiFi 없는 정확 모델과 리비전·국내 유통 구성', 'CPU별 최소 BIOS는 아직 미확인', 'M2_3/PCIE2 공유 조건과 슬롯별 CPU 경로'] },
  { key: 'CURRENT-INTEL-BOARD-MSI-MAG-B860M-MORTAR-WIFI', type: 'MOTHERBOARD', platform: 'INTEL_LGA1851', reason: 'LGA1851 mATX 보드의 CPU·RAM·슬롯 조건을 검증한다.', checks: ['리비전·국내 유통 구성', '245K/250K Plus 최소 BIOS는 아직 미확인', 'DIMM 종류·QVL·슬롯 방열판 조건'] },
  { key: 'CURRENT-INTEL-BOARD-ASUS-TUF-GAMING-B860-PLUS-WIFI', type: 'MOTHERBOARD', platform: 'INTEL_LGA1851', reason: '다른 제조사의 ATX·슬롯 공유·Plus 조건을 비교한다.', checks: ['리비전·국내 유통 구성', '245K/250K Plus 최소 BIOS는 아직 미확인', 'Plus OS 조건·DIMM QVL·슬롯 공유'] },
  { id: 'a743f7bd-bdaf-4d25-820b-96e9b3293d34', type: 'RAM', platform: 'DDR5', reason: '정확한 DDR5 32GB(16GB×2) 키트와 기존 가격 매핑을 검증한다.', official: 'https://www.gskill.com/specification/165/393/1661410171/F5-6000J3038F16GX2-TZ5N-Specification', checks: ['키트 PN과 개별 모듈 PN 구분', 'SPD 기본 속도와 OC 6000·프로필 구분', 'CPU/보드별 QVL·rank·장착 수', 'Neo라는 이름만으로 AMD 전용으로 분류하지 않음'] },
  { id: '1555eede-7e03-4038-824d-91d8fb21a6e2', type: 'RAM', platform: 'DDR5', reason: '기존 정확 키트의 신규 가격 확보와 용량·타이밍 변형 구분을 검증한다.', official: 'https://www.gskill.com/specification/165/377/1664845977/F5-6000J3636F16GX2-RS5K-Specification', checks: ['국내 동일 PN 32GB(16GB×2) 판매 키트', '키트 PN과 개별 모듈 PN 구분', 'SPD/OC 속도와 CPU/보드별 QVL', 'Ripjaws라는 이름만으로 Intel 전용으로 분류하지 않음'] },
  { id: 'bb0cc615-e9f2-49e0-b555-192f5c573cc9', type: 'GPU', platform: 'PCIE', reason: '기존 NVIDIA 카드의 정확 변형과 가격 연결을 재사용한다.', official: 'https://storage-asset.msi.com/datasheet/vga/global/GeForce-RTX-5060-8G-VENTUS-2X-OC.pdf', checks: ['G5060-8V2C 정확 카드; MAX/WHITE 등 변형 제외', '케이스·PSU·전원 커넥터는 실제 PC와 별도 확인', 'GPU 칩 이름만으로 정확 카드 확정 금지'] },
  { id: 'bd3e1d44-97d6-4679-8e43-829730a90f18', type: 'GPU', platform: 'PCIE', reason: '기존 AMD 카드의 OC·VRAM 변형과 가격 연결을 재사용한다.', official: 'https://www.sapphiretech.com/en/consumer/pulse-radeon-rx-9060-xt-16g-gddr6', checks: ['11350-03-20G OC 16GB; 8GB/non-OC 변형 제외', '케이스·PSU·전원 커넥터는 실제 PC와 별도 확인', 'GPU 칩 이름만으로 정확 카드 확정 금지'] },
  { key: 'ssd-samsung-870-evo-1tb', type: 'STORAGE', platform: 'SATA', reason: '2.5형 SATA 업그레이드와 NVMe 비해당·포트 공유 처리를 검증한다.', checks: ['국내 박스 PN·유통·보증과 공식 MZ-77E1T0BW 대조', 'SATA 포트·데이터 케이블·전원·2.5형 장착 공간', '다른 용량과 벌크·병행·추가 구성 구분'] },
  { key: 'ssd-samsung-990-pro-1tb', type: 'STORAGE', platform: 'PCIE_NVME', reason: 'PCIe 4.0 NVMe·2280·십진 1TB와 방열판 변형 구분을 검증한다.', checks: ['국내 박스 PN·유통·보증과 공식 MZ-V9P1T0BW 대조', '방열판 미포함 BW와 CW/GW 변형 구분', '슬롯별 NVMe·2280·레인·공유·부팅 근거'] },
];
const items = choices.map((choice, index) => {
  const matches = choice.id ? existing.items.filter(x => x.externalId === choice.id) : research.candidates.filter(x => x.candidateKey === choice.key);
  assert.equal(matches.length, 1, `Selection must identify one reviewed record: ${choice.id ?? choice.key}`);
  const row = matches[0];
  const product = choice.id ? row.product : row;
  assert.equal(choice.id ? product.type : row.category, choice.type);
  const officialUrl = choice.official ?? (choice.id ? row.sourceEvidence.manufacturerSources[0].url : row.officialSources[0].url);
  assert.ok(officialUrl.startsWith('https://'));
  const historical = choice.id && row.priceEvidence.approvedAmountKrw != null ? {
    amountKrw: row.priceEvidence.approvedAmountKrw,
    observedAt: row.priceEvidence.observedAt,
    evidenceUrl: row.priceEvidence.evidenceUrl,
    saleUnit: row.priceEvidence.saleUnit,
    status: 'HISTORICAL_APPROVED_SNAPSHOT_NOT_CURRENT_PRICE',
  } : null;
  return {
    selectionNumber: index + 1, type: choice.type, platform: choice.platform,
    manufacturer: product.manufacturer, modelName: product.modelName, partNumber: product.partNumber ?? null,
    existingCatalogExternalId: choice.id ?? null, researchCandidateKey: choice.key ?? null,
    catalogPlan: choice.id ? 'REUSE_EXISTING_PRODUCT_ID_AFTER_REVIEW' : 'PROPOSE_NEW_PRODUCT_AFTER_REVIEW',
    reasonForSelection: choice.reason,
    officialIdentityReview: { url: officialUrl, checkedDate: date, scope: 'Selection identity/specification page only; not complete compatibility, domestic SKU or current-price approval' },
    domesticResearchUrls: choice.id ? [row.saleEvidence.offer?.sourceUrl ?? historical?.evidenceUrl].filter(Boolean) : row.domesticSources.map(s => s.url),
    historicalApprovedPrice: historical,
    priceWork: historical ? 'RECHECK_EXACT_SALE_VARIANT_AND_OBSERVE_AGAIN' : 'VERIFY_EXACT_SALE_VARIANT_AND_COLLECT',
    openChecks: choice.checks, adoptionStatus: 'PROPOSED_NOT_APPROVED',
  };
});
assert.equal(items.length, 14);
assert.equal(new Set(items.map(x => x.existingCatalogExternalId ?? x.researchCandidateKey)).size, 14);
assert.equal(items.filter(x => x.existingCatalogExternalId).length, 7);
assert.equal(items.filter(x => x.historicalApprovedPrice).length, 6);
for (const [type, count] of Object.entries({ CPU: 4, MOTHERBOARD: 4, RAM: 2, GPU: 2, STORAGE: 2 })) assert.equal(items.filter(x => x.type === type).length, count);
const result = {
  schemaVersion: 1, selectionDate: date, status: 'SELECTION_APPROVED_REVIEW_IN_PROGRESS',
  selectionApproval: { date, decision: '14종 선정 승인·상세 검토 진행', databaseOrPriceApplyApproved: false },
  purpose: 'Verify representative AMD/Intel upgrade data flows with a small reviewed batch before expanding 52 candidates',
  basis: 'Coverage and evidence reuse; domestic popularity or performance-per-price ranking is not established',
  sources,
  summary: { selectedParts: 14, reusedCatalogParts: 7, proposedNewParts: 7, historicalPriceEvidence: 6, withoutHistoricalPrice: 8, freshPricesCollected: 0, databaseAccessed: false, databaseWrites: 0 },
  approvalGates: ['Selection approved; complete detailed compatibility/SKU/price review and import preparation', 'Approve concrete data/price changes before actual MySQL application'],
  ramQuantityPolicy: { saleUnit: '32GB 2x16GB kit', pcQuantity: 2, capacityBytesPerDevice: 17179869184, modulePartNumberConfirmedByKitPartNumber: false },
  deferredScope: ['Remaining 52/78 research candidates', 'PCIe 5.0 SSD and Z890/CPU overclocking coverage', 'Global reclassification of existing 300 products', 'Central price service and scheduled collection'],
  items,
};
const rendered = JSON.stringify(result, null, 2) + '\n';
if (args[0] === '--check') {
  assert.deepStrictEqual(JSON.parse(await fs.readFile(path.join(root, outputFile), 'utf8')), result);
} else {
  await fs.writeFile(path.join(root, outputFile), rendered, 'utf8');
}
console.log(JSON.stringify({ file: outputFile, ...result.summary, status: result.status, checkOnly: args[0] === '--check' }));
