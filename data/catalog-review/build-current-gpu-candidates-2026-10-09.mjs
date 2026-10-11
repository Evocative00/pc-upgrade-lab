import fs from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

// These are reviewed research records, not a seed or a live price collector.
const date = '2026-10-09';
const rows = [
  {
    key: 'CURRENT-GPU-MSI-RTX5050-VENTUS2X-OC', manufacturer: 'MSI',
    model: 'GeForce RTX 5050 8G VENTUS 2X OC', pn: 'G5050-8V2C',
    chipVendor: 'NVIDIA', chipset: 'GeForce RTX 5050', vramGiB: 8,
    connectorLanes: 16, activeLanes: 8, dimensions: [197, 120, 41], slots: null,
    cardPower: 130, powerBasis: 'MANUFACTURER_POWER_CONSUMPTION', psu: 550, psuBasis: 'RECOMMENDED', connectors: 1,
    official: 'https://www.msi.com/Graphics-Card/GeForce-RTX-5050-8G-VENTUS-2X-oc/Specification',
    domestic: 'https://prod.danawa.com/info/?pcode=94203833',
    questions: ['공식 Model Name과 국내 판매점의 제품 번호·유통 구성이 일치하는지 추가 확인한다.'],
    officialFacts: ['Model Name G5050-8V2C', 'RTX 5050, 8GB GDDR6', 'PCIe 5.0 x16 단자, 사용 레인 x8', '197 x 120 x 41 mm', '130W, 8핀 1개, 권장 PSU 550W']
  },
  {
    key: 'CURRENT-GPU-SAPPHIRE-RX9070GRE-PULSE-OC-12G', manufacturer: 'Sapphire',
    model: 'PULSE Radeon RX 9070 GRE OC 12GB', pn: '11354-01-20G',
    chipVendor: 'AMD', chipset: 'Radeon RX 9070 GRE', vramGiB: 12,
    connectorLanes: 16, activeLanes: null, dimensions: [280, 120.25, 51.5], slots: 2.5,
    cardPower: 240, powerBasis: 'TYPICAL_BOARD_POWER', psu: 650, psuBasis: 'MINIMUM', connectors: 2,
    official: 'https://www.sapphiretech.com/en/consumer/pulse-radeon-rx-9070-gre-12g-gddr6',
    domestic: 'https://prod.danawa.com/info/?pcode=122684820',
    questions: ['국내 판매점에서 공식 SKU와 유통 구성을 확인한다.', '공식 x16 인터페이스 표기를 물리 단자/활성 레인 각각으로 승인하기 전에 추가 확인한다.'],
    officialFacts: ['SKU 11354-01-20G', 'RX 9070 GRE, 12GB GDDR6', 'PCI-Express 5.0 x16 인터페이스', '280 x 120.25 x 51.5 mm, 2.5 slot', '240W Typical Board Power, 8핀 2개, 최소 PSU 650W']
  },
  {
    key: 'CURRENT-GPU-SAPPHIRE-RX9060-PULSE-OC-8G', manufacturer: 'Sapphire',
    model: 'PULSE Radeon RX 9060 OC 8GB', pn: null,
    chipVendor: 'AMD', chipset: 'Radeon RX 9060', vramGiB: 8,
    connectorLanes: 16, activeLanes: null, dimensions: [200, 109.25, 40.6], slots: 2,
    cardPower: 136, powerBasis: 'TYPICAL_BOARD_POWER', psu: 450, psuBasis: 'MINIMUM', connectors: 1,
    official: 'https://www.sapphiretech.com/en/consumer/pulse-radeon-rx-9060-8g-gddr6',
    domestic: 'https://prod.danawa.com/info/?pcode=97470848',
    questions: ['공식 페이지에 11351-14-10G / 11351-24-18 두 SKU가 같이 표시된다. 국내 단품이 어느 SKU인지 확인하기 전 PN을 고르지 않는다.', '20개 벌크 별도 국내 페이지를 단품 가격에 연결하지 않는다.', '공식 x16 인터페이스 표기를 물리 단자/활성 레인 각각으로 승인하기 전에 추가 확인한다.'],
    officialFacts: ['공식 SKU 표기 11351-14-10G / 11351-24-18', 'RX 9060, 8GB GDDR6', 'PCI-Express 5.0 x16 인터페이스', '200 x 109.25 x 40.6 mm, 2 slot', '136W Typical Board Power, 8핀 1개, 최소 PSU 450W']
  }
];

const report = {
  schemaVersion: 1, researchDate: date, categoryGroup: 'CURRENT_GPU',
  scopeLimitations: [
    '기존 80개 카드가 RTX 50/RX 9000/Arc B를 포함하지만 빠진 RTX 5050·RX 9070 GRE·RX 9060의 카드 모델 후보를 보강한다. 국내 인기 순위는 측정하지 않았다.',
    '제조사 카드 제원과 국내 모델 페이지 본문을 확인했다. 웹 도구의 본문은 캐시일 수 있으며 당일 판매점의 정확한 신품 SKU·구매 가능성은 승인하지 않았다.',
    '추가 OEM·지역 한정 모델은 국내 정확한 카드·소매 판매 근거를 확보하기 전 구매 후보로 확정하지 않는다. 전문용 그래픽카드와 노트북 GPU는 현재 일반 데스크톱 업그레이드 범위에서 별도로 검토한다.',
    '상품가를 생성하거나 실제 DB에 반영하지 않았다. 국내 모델 페이지의 배송료 포함 최저가·프로모션 표시는 이번 연구 데이터에 저장하지 않는다.',
    'Sapphire의 PCIe x16 표기는 원문 인터페이스로 보존하며 활성 레인 수는 추가 확인까지 null로 둔다. 물리 단자 수 역시 적재 전 근거를 검토한다.'
  ],
  schemaRequirements: ['GPU 칩 모델 확인과 제조사 카드·SKU 확인을 구분한다.', '카드 전력 기준 enum의 TYPICAL_BOARD_POWER 지원을 검토하며 임의로 TDP나 TOTAL_BOARD_POWER로 바꾸지 않는다.'],
  candidates: rows.map(row => ({
    candidateKey: row.key, category: 'GPU', manufacturer: row.manufacturer,
    modelName: row.model, partNumber: row.pn, role: 'BOTH',
    reasonForSelection: `기존 GPU 목록에 ${row.chipset} 카드가 없어 최신 데스크톱 GPU 모델 범위를 보강한다.`,
    selectionEvidence: { installedDemand: 'NOT_MEASURED', domesticDemand: 'NOT_MEASURED', basis: 'MODEL_COVERAGE_GAP' },
    specification: {
      chipVendor: row.chipVendor, chipset: row.chipset, vramBytes: row.vramGiB * 2 ** 30,
      memoryType: 'GDDR6', pcieVersion: '5.0',
      pcieInterfacePublished: row.activeLanes === 8 ? 'PCIe 5.0 x16 (uses x8)' : 'PCI-Express 5.0 x16',
      pcieConnectorLanes: row.activeLanes === 8 ? row.connectorLanes : null,
      pcieActiveLanes: row.activeLanes,
      lengthMm: row.dimensions[0], heightMm: row.dimensions[1], thicknessMm: row.dimensions[2], slotWidth: row.slots,
      cardPowerW: row.cardPower, cardPowerBasis: row.powerBasis, psuRequirementW: row.psu, psuRequirementBasis: row.psuBasis,
      powerConnectorsKnown: true, powerConnectors: [{ connectorType: 'PCIE_8PIN', connectorCount: row.connectors }]
    },
    compatibilityNotes: ['GPU 슬롯·활성 레인·UEFI 조건, 케이스 공간, 실제 PSU와 케이블을 확인하기 전 전체 장착 호환성을 확정하지 않는다.'],
    identificationNotes: ['Windows GPU 칩 이름만으로 이 카드의 제조사·냉각기·메모리 변형·PN을 확정하지 않는다.'],
    officialSources: [{ url: row.official, title: `${row.manufacturer} ${row.model} official specification`, checkedDate: date, supportedFacts: row.officialFacts }],
    domesticSources: [{ url: row.domestic, title: `${row.manufacturer} ${row.model} 국내 모델 페이지`, checkedDate: date, status: 'PRODUCT_PAGE_FOUND', notes: '모델명·용량·카드 형태 확인만 수행. 정확한 판매점 SKU·신품 상태·상품가 관측은 별도 단계다.' }],
    saleIdentityStatus: 'PRODUCT_MODEL_PAGE_FOUND', priceStatus: 'NOT_COLLECTED',
    readiness: row.pn == null ? 'NEEDS_IDENTITY_REVIEW' : 'RESEARCH_CANDIDATE',
    openQuestions: row.questions, notInExistingCatalog: true
  }))
};
await fs.writeFile(fileURLToPath(new URL('./candidates-current-gpu-2026-10-09.json', import.meta.url)), JSON.stringify(report, null, 2) + '\n', 'utf8');
console.log(JSON.stringify({ candidateCount: report.candidates.length, databaseAccessed: false, priceCollected: false }));
