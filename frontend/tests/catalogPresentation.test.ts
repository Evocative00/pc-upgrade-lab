import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { renderToStaticMarkup } from 'react-dom/server'
import * as jsxRuntime from 'react/jsx-runtime'
import ts from 'typescript'
import * as presentation from '../src/features/catalog/catalogPresentation.ts'
import { catalogIdentityDescription, catalogIdentityLabel, catalogPriceStatusLabel, catalogRoleLabel, formatCatalogPrice, formatCurrentPrice, formatProjectPrice, formatPriceObservedAt, ramKitLabel, specificationRows } from '../src/features/catalog/catalogPresentation.ts'
import type { CatalogPriceStatus, CatalogProduct, CurrentPrice, ReferenceEstimate } from '../src/features/catalog/catalogTypes.ts'

const price: CurrentPrice = { amountKrw: 123456, sourceName: 'DANAWA', sourceUrl: 'https://shop.example/p/1', observedAt: '2026-10-06T01:00:00Z' }
const sharedStatus = (overrides: Partial<CatalogPriceStatus> = {}): CatalogPriceStatus => ({
  origin: 'SHARED', lookupStatus: 'OK', freshness: 'FRESH', catalogVersion: 'pilot-2026-10-10-v1',
  checkedAt: '2026-10-10T12:00:00Z', lastSuccessAt: '2026-10-10T11:00:00Z', includedInTotal: true, ...overrides,
})

function renderPriceSource(currentPrice: CurrentPrice | null, status: CatalogPriceStatus | null) {
  const code = ts.transpileModule(readFileSync(new URL('../src/features/catalog/CurrentPriceSource.tsx', import.meta.url), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX },
  }).outputText
  const module = { exports: {} as { CurrentPriceSource?: (props: { price: CurrentPrice | null; status: CatalogPriceStatus | null }) => ReturnType<typeof jsxRuntime.jsx> } }
  const require = (name: string) => name === 'react/jsx-runtime' ? jsxRuntime : presentation
  new Function('require', 'exports', 'module', code)(require, module.exports, module)
  return renderToStaticMarkup(module.exports.CurrentPriceSource!({ price: currentPrice, status }))
}

function renderReferenceSource(price: ReferenceEstimate, detailed = true) {
  const code = ts.transpileModule(readFileSync(new URL('../src/features/catalog/ReferencePriceSource.tsx', import.meta.url), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX },
  }).outputText
  const module = { exports: {} as { ReferencePriceSource?: (props: { price: ReferenceEstimate; detailed: boolean }) => ReturnType<typeof jsxRuntime.jsx> } }
  const require = (name: string) => name === 'react/jsx-runtime' ? jsxRuntime : presentation
  new Function('require', 'exports', 'module', code)(require, module.exports, module)
  return renderToStaticMarkup(module.exports.ReferencePriceSource!({ price, detailed }))
}

test('추정 참고가의 약 표시·범위·원 판매 단위·출처를 렌더링하고 현재가를 만들지 않는다', () => {
  const preview = JSON.parse(readFileSync(new URL('../../data/catalog-shared/reference-prices-preview-2026-10-10.json', import.meta.url), 'utf8'))
  const referenceEstimate = preview.products.find((p: { referenceEstimate: ReferenceEstimate }) => p.referenceEstimate.confidence === 'ESTIMATED').referenceEstimate as ReferenceEstimate
  const product = { currentPrice: null, referenceEstimate } as CatalogProduct
  assert.match(formatProjectPrice(product), /^추정 참고가 약 /)
  const html = renderReferenceSource(referenceEstimate)
  assert.match(html, /유사 부품 가격으로 추정/)
  assert.match(html, /중앙 · 추정 참고가/)
  assert.match(html, /참고 범위/)
  assert.match(html, /rel="noopener noreferrer"/)
  assert.ok(!html.includes('최근 관측'))
  assert.equal(product.currentPrice, null)
  assert.equal(formatProjectPrice({ ...product, currentPrice: price }), '123,456원')
  assert.equal(formatProjectPrice({ ...product, currentPrice: price,
    priceStatus: sharedStatus({ freshness: 'EXPIRED', includedInTotal: false }) }), '과거 관측 123,456원 (현재가 합계 제외)')
})

test('공식 출시 참고가는 원래 날짜를 표시하고 미확인 날짜를 추정 관측일로 바꾸지 않는다', () => {
  const preview = JSON.parse(readFileSync(new URL('../../data/catalog-shared/reference-prices-preview-2026-10-10.json', import.meta.url), 'utf8'))
  const launch = preview.products.find((p: { referenceEstimate: ReferenceEstimate }) => p.referenceEstimate.basis === 'LAUNCH_PRICE').referenceEstimate as ReferenceEstimate
  assert.match(formatProjectPrice({ currentPrice: null, referenceEstimate: launch } as CatalogProduct), /^출시 참고가 /)
  assert.match(renderReferenceSource(launch), new RegExp(`가격 기준 ${launch.sourceDate}`))
  assert.match(renderReferenceSource({ ...launch, sourceDate: null }), /가격 기준일 미확인/)
  assert.ok(!renderReferenceSource({ ...launch, sourceDate: null }).includes('유사 부품 가격으로 추정'))
})

test('설치 모델 참고·판매 키트·정확 상품 자료·미분류를 별도로 표시한다', () => {
  assert.equal(catalogIdentityLabel('MODEL_REFERENCE'), '설치 모델 참고')
  assert.equal(catalogIdentityLabel('RETAIL_KIT'), '판매 키트')
  assert.equal(catalogIdentityLabel('PHYSICAL_VARIANT'), '정확 상품 자료')
  assert.equal(catalogIdentityLabel('LEGACY_UNCLASSIFIED'), '식별 범위 미확인')
  assert.match(catalogIdentityDescription('MODEL_REFERENCE'), /판매 구성이나 보드 리비전은 별도로 확인/)
  assert.match(catalogIdentityDescription('RETAIL_KIT'), /장착한 모듈 수와 모듈 1개당 용량/)
  assert.match(catalogIdentityDescription('PHYSICAL_VARIANT'), /내 PC 실물과 같은 구성인지 확인/)
})

test('상품의 자료 용도를 구매 추천 여부와 구분해 표시한다', () => {
  assert.equal(catalogRoleLabel('INSTALLED_PC_REFERENCE'), '설치 모델 참고용')
  assert.equal(catalogRoleLabel('PURCHASE_CANDIDATE'), '구매 후보 자료')
  assert.equal(catalogRoleLabel('BOTH'), '설치·구매 참고용')
  assert.equal(catalogRoleLabel('UNASSIGNED'), '용도 미분류')
})

test('이전 응답에 식별·용도 필드가 없으면 제품 확인 수준을 추정하지 않는다', () => {
  assert.equal(catalogIdentityLabel(undefined), catalogIdentityLabel('LEGACY_UNCLASSIFIED'))
  assert.equal(catalogRoleLabel(undefined), catalogRoleLabel('UNASSIGNED'))
  assert.equal(catalogIdentityDescription(undefined), catalogIdentityDescription('LEGACY_UNCLASSIFIED'))
})

test('SSD 십진 광고 용량·실제 공표 치수와 SATA 비해당을 구분한다', () => {
  const sata = Object.fromEntries(specificationRows('STORAGE', { storageKind: 'SSD', advertisedCapacityGb: 1000,
    capacityBytes: 1_000_000_000_000, capacityBasis: 'DECIMAL_GB', formFactor: 'TWO_POINT_FIVE_INCH',
    busInterface: 'SATA', pcieVersion: null, pcieLanes: null, nvmeVersion: null, heatsinkIncluded: false }))
  assert.equal(sata['제조사 표시 용량'], '1,000 GB')
  assert.equal(sata['명목 용량'], '1,000,000,000,000 bytes')
  assert.equal(sata['PCIe 세대'], '해당 없음')
  assert.equal(sata['M.2 키'], '해당 없음')
  assert.equal(sata['SATA 버전'], '미확인')
  assert.equal(sata['방열판 포함'], '미포함')
  const nvme = Object.fromEntries(specificationRows('STORAGE', { busInterface: 'PCIE', formFactor: 'M2',
    lengthMm: 80.15, widthMm: 22.15, dimensionsBasis: 'MANUFACTURER_MAXIMUM', heatsinkIncluded: null }))
  assert.equal(nvme['길이'], '80.15 mm')
  assert.equal(nvme['SATA 버전'], '해당 없음')
  assert.equal(nvme['방열판 포함'], '미확인')
})

test('가격 미확정·자료 부족을 0원으로 표시하지 않는다', () => {
  assert.equal(formatCatalogPrice({ amountKrw: null, status: 'UNCONFIRMED', updatedAt: '' }), '가격 미확정')
  assert.equal(formatCatalogPrice({ amountKrw: null, status: 'INSUFFICIENT_HISTORY', updatedAt: '' }), '가격 자료 부족')
  assert.equal(formatCatalogPrice({ amountKrw: 123456.78, status: 'CONFIRMED', updatedAt: '' }), '123,456.78원')
})

test('현재 상품가는 원 단위로 표시하고 없는 가격은 미확인으로 표시한다', () => {
  assert.equal(formatCurrentPrice(null), '현재 상품가 미확인')
  assert.equal(formatCurrentPrice({ amountKrw: 123456, sourceName: '판매처', sourceUrl: 'https://shop.example/p/1',
    observedAt: '2026-10-06T01:00:00Z' }), '123,456원')
})

test('중앙 최근 관측·오래된 관측·만료와 로컬 범위 밖 상태를 별도로 표시한다', () => {
  assert.match(catalogPriceStatusLabel(sharedStatus()), /중앙 가격 · 최근 관측/)
  assert.match(catalogPriceStatusLabel(sharedStatus({ freshness: 'STALE' })), /오래된 관측 · 구매 전 확인/)
  const expired = sharedStatus({ freshness: 'EXPIRED', includedInTotal: false })
  assert.match(catalogPriceStatusLabel(expired), /관측 만료 · 합계 제외/)
  assert.equal(formatCurrentPrice(price, expired), '과거 관측 123,456원 (합계 제외)')
  assert.equal(formatCurrentPrice(price, sharedStatus({ freshness: 'STALE' })), '123,456원')
  assert.equal(catalogPriceStatusLabel(null), '로컬 가격')
  assert.match(catalogPriceStatusLabel(sharedStatus({ origin: 'LOCAL', lookupStatus: 'NOT_IN_SCOPE',
    freshness: null, catalogVersion: null, checkedAt: null, lastSuccessAt: null })), /로컬 가격 · 중앙 대상 범위 밖/)
})

test('가격 미확인·중앙 미등록·연결 실패를 같은 빈 가격으로 숨기지 않는다', () => {
  const noPrice = sharedStatus({ lookupStatus: 'NO_PRICE', freshness: 'NO_PRICE', includedInTotal: false })
  const unknown = { ...noPrice, lookupStatus: 'UNKNOWN_PRODUCT' as const }
  const unavailable = sharedStatus({ lookupStatus: 'UNAVAILABLE', includedInTotal: false })
  assert.equal(formatCurrentPrice(null, noPrice), '현재 상품가 미확인')
  assert.equal(formatCurrentPrice(null, unknown), '중앙 카탈로그 미등록')
  assert.equal(formatCurrentPrice(null, unavailable), '중앙 가격 연결 실패 · 확인한 가격 없음')
  assert.equal(formatCurrentPrice(price, unavailable), '마지막 확인 가격 123,456원 (합계 제외)')
  for (const status of [noPrice, unknown, unavailable]) assert.ok(!formatCurrentPrice(null, status).includes('0원'))
})

test('중앙 연결 실패 시 마지막 가격의 원관측 시각·출처와 마지막 성공 조회를 함께 표시한다', () => {
  const status = sharedStatus({ lookupStatus: 'UNAVAILABLE', includedInTotal: false })
  const html = renderPriceSource(price, status)
  assert.match(html, /중앙 가격 · 연결 실패 · 현재가 합계 제외/)
  assert.match(html, /href="https:\/\/shop.example\/p\/1"/)
  assert.match(html, /datetime="2026-10-06T01:00:00Z"/i)
  assert.match(html, /마지막 성공 조회/)
  assert.match(html, /datetime="2026-10-10T11:00:00Z"/i)
  assert.match(html, /한국 시간/)
  const empty = renderPriceSource(null, sharedStatus({ lookupStatus: 'UNAVAILABLE', freshness: null,
    lastSuccessAt: null, includedInTotal: false }))
  assert.match(empty, /연결 실패/)
  assert.ok(!empty.includes('마지막 성공 조회'))
  assert.ok(!empty.includes('0원'))
})

test('가격 없는 중앙 미확인·미등록 상태도 출처 컴포넌트에 표시한다', () => {
  for (const lookupStatus of ['NO_PRICE', 'UNKNOWN_PRODUCT'] as const) {
    const html = renderPriceSource(null, sharedStatus({ lookupStatus, freshness: 'NO_PRICE', includedInTotal: false }))
    assert.match(html, lookupStatus === 'NO_PRICE' ? /현재 상품가 미확인/ : /카탈로그 미등록/)
    assert.match(html, /datetime="2026-10-10T12:00:00Z"/i)
  }
})

test('가격 확인 시각은 실행 환경의 시간대와 관계없이 한국 시간으로 표시한다', () => {
  const formatted = formatPriceObservedAt('2026-10-05T15:30:00Z')
  assert.match(formatted, /2026\. 10\. 06\./)
  assert.match(formatted, /00:30/)
  assert.match(formatted, /한국 시간/)
  assert.equal(formatPriceObservedAt('invalid'), '확인 시각 미확인')
})

test('RAM은 모듈당 용량·묶음 개수·합계를 구분한다', () => {
  const specs = { moduleCapacityBytes: 16 * 1024 ** 3, moduleCount: 2 }
  assert.equal(ramKitLabel(specs), '16 GiB × 2개 = 총 32 GiB')
  const rows = Object.fromEntries(specificationRows('RAM', specs))
  assert.equal(rows['모듈 1개당 용량'], '16 GiB')
  assert.equal(rows['제품 구성 모듈 수'], '2 개')
  assert.equal(ramKitLabel({ moduleCapacityBytes: null, moduleCount: 2 }), '제품 구성 미확인')
})

test('모니터 OC·기본 주사율·미확인과 미지원 값을 구분한다', () => {
  const rows = Object.fromEntries(specificationRows('MONITOR', { nativeWidthPx: 2560, nativeHeightPx: 1440,
    nativeStandardRefreshHz: 165, hasRefreshOverclock: true, nativeOcRefreshHz: 180, activePowerW: null }))
  assert.equal(rows['기본 해상도'], '2560 × 1440')
  assert.equal(rows['기본 해상도 최대 주사율'], '165 Hz')
  assert.equal(rows['오버클록 최대 주사율'], '180 Hz')
  assert.equal(rows['사용 중 전력'], '미확인')
  for (const [value, label] of [[false, '미지원'], [null, '미확인']] as const) {
    assert.equal(Object.fromEntries(specificationRows('MONITOR', { hasRefreshOverclock: value }))['주사율 오버클록 지원'], label)
  }
})

test('GPU 보조전원 미확인·없음·알려진 규격을 구분한다', () => {
  const format = (specs: Parameters<typeof specificationRows>[1]) => Object.fromEntries(specificationRows('GPU', specs))['보조전원 단자']
  assert.equal(format({ powerConnectorsKnown: false, powerConnectors: [] }), '미확인')
  assert.equal(format({ powerConnectorsKnown: true, powerConnectors: [] }), '없음')
  assert.equal(format({ powerConnectorsKnown: true, powerConnectors: [{ connectorType: 'PCIE_12VHPWR', connectorCount: 1 }] }), '12VHPWR 16핀 × 1')
})

test('CPU는 총 코어·P/E 코어·각 클럭과 TDP·PBP·MTP를 구분한다', () => {
  const rows = Object.fromEntries(specificationRows('CPU', {
    coreCount: 14, threadCount: 20, baseClockMhz: null, boostClockMhz: 5100,
    performanceCoreCount: 6, efficientCoreCount: 8,
    performanceCoreBaseClockMhz: 3500, efficientCoreBaseClockMhz: 2600,
    performanceCoreBoostClockMhz: 5100, efficientCoreBoostClockMhz: 3900,
    tdpW: null, processorBasePowerW: 125, maximumTurboPowerW: 181,
  }))
  assert.equal(rows['총 코어'], '14 개')
  assert.equal(rows['스레드'], '20 개')
  assert.equal(rows['P코어 (성능 코어)'], '6 개')
  assert.equal(rows['E코어 (효율 코어)'], '8 개')
  assert.equal(rows['기본 클럭'], undefined)
  assert.equal(rows['최대 부스트'], '5,100 MHz')
  assert.equal(rows['P코어 기본 클럭'], '3,500 MHz')
  assert.equal(rows['E코어 기본 클럭'], '2,600 MHz')
  assert.equal(rows['P코어 최대 터보'], '5,100 MHz')
  assert.equal(rows['E코어 최대 터보'], '3,900 MHz')
  assert.equal(rows['TDP'], '미확인')
  assert.equal(rows['기본 전력 (PBP)'], '125 W')
  assert.equal(rows['최대 터보 전력 (MTP)'], '181 W')
})

test('기존 CPU와 코어 유형 없음·미확인을 표시할 때 추가 수치를 추정하지 않는다', () => {
  const legacy = Object.fromEntries(specificationRows('CPU', { coreCount: 6, baseClockMhz: 3600, tdpW: 65 }))
  assert.equal(legacy['기본 클럭'], '3,600 MHz')
  assert.equal(legacy['TDP'], '65 W')
  assert.equal(legacy['기본 전력 (PBP)'], '미확인')
  assert.equal(legacy['최대 터보 전력 (MTP)'], '미확인')
  assert.equal(legacy['P코어 (성능 코어)'], '미확인')
  assert.equal(legacy['E코어 (효율 코어)'], '미확인')
  const noEfficient = Object.fromEntries(specificationRows('CPU', {
    performanceCoreCount: 6, efficientCoreCount: 0, baseClockMhz: 2500,
    efficientCoreBaseClockMhz: null, efficientCoreBoostClockMhz: null,
  }))
  assert.equal(noEfficient['E코어 (효율 코어)'], '0 개')
  assert.equal(noEfficient['기본 클럭'], '2,500 MHz')
  assert.equal(noEfficient['E코어 기본 클럭'], '해당 없음')
  assert.equal(noEfficient['E코어 최대 터보'], '해당 없음')
})
