import assert from 'node:assert/strict'
import test from 'node:test'
import { formatCatalogPrice, ramKitLabel, specificationRows } from '../src/features/catalog/catalogPresentation.ts'

test('가격 미확정·자료 부족을 0원으로 표시하지 않는다', () => {
  assert.equal(formatCatalogPrice({ amountKrw: null, status: 'UNCONFIRMED', updatedAt: '' }), '가격 미확정')
  assert.equal(formatCatalogPrice({ amountKrw: null, status: 'INSUFFICIENT_HISTORY', updatedAt: '' }), '가격 자료 부족')
  assert.equal(formatCatalogPrice({ amountKrw: 123456.78, status: 'CONFIRMED', updatedAt: '' }), '123,456.78원')
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
