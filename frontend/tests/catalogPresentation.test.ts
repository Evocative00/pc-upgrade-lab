import assert from 'node:assert/strict'
import test from 'node:test'
import { formatCatalogPrice, formatCurrentPrice, formatPriceObservedAt, ramKitLabel, specificationRows } from '../src/features/catalog/catalogPresentation.ts'

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
