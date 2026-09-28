import assert from 'node:assert/strict'
import test from 'node:test'
import {
  getCapacityBytes,
  linkCatalog,
  planScanApply,
  renameDraft,
  setCapacityBytes,
  setCapacityInput,
  toDraft,
  toPartInputs,
  toPersistedDraft,
  validatePcRequest,
  withEmptyRows,
} from '../src/features/pc/partDraft.ts'
import type { PartInput, ScanResult } from '../src/features/pc-scan/types.ts'

function part(type: PartInput['type'], overrides: Partial<PartInput> = {}): PartInput {
  return {
    type,
    displayName: `TEST ${type}`,
    rawName: `Fixture ${type}`,
    quantity: 1,
    source: 'AUTO',
    catalogProductId: null,
    matchStatus: 'UNMATCHED',
    specs: {},
    ...overrides,
  }
}

function scan(parts: PartInput[]): ScanResult {
  return {
    schemaVersion: 1,
    collectorVersion: 'test',
    collectedAt: '2026-09-28T12:00:00Z',
    parts,
    warnings: [],
  }
}

test('저장 후 다시 연 AUTO 보완값은 덮어쓰기 확인 대상이다', () => {
  const edited = renameDraft(toDraft(part('CPU')), '사용자 보완 CPU')
  assert.equal(planScanApply([edited], scan([part('CPU')])).editedReplaced, 1)

  // API JSON 왕복으로 화면 전용 편집 표시가 사라져도 보완값을 보호해야 한다.
  const saved: PartInput[] = JSON.parse(JSON.stringify(toPartInputs([edited])))
  const reopened = saved.map(toPersistedDraft)
  assert.equal(reopened[0].edited, false)
  assert.equal(reopened[0].displayName, '사용자 보완 CPU')
  assert.equal(planScanApply(reopened, scan([part('CPU')])).editedReplaced, 1)
  // 계획 계산 자체는 원래 입력을 바꾸지 않는다. 확인 취소 시 이 배열을 유지한다.
  assert.equal(reopened[0].displayName, '사용자 보완 CPU')
})

test('저장된 AUTO 용량·수량 보완도 재인식 전에 확인한다', () => {
  const edited = { ...setCapacityBytes(toDraft(part('RAM')), 32 * 1024 ** 3), quantity: 2 }
  const reopened = toPartInputs([edited]).map(toPersistedDraft)
  const plan = planScanApply(reopened, scan([part('RAM', { specs: { capacityBytes: 16 * 1024 ** 3 } })]))
  assert.equal(plan.editedReplaced, 1)
  assert.equal(reopened[0].quantity, 2)
  assert.equal(reopened[0].specs.capacityBytes, 32 * 1024 ** 3)
})

test('새 스캔 반복 반영은 중복을 만들거나 불필요한 확인을 요구하지 않는다', () => {
  const result = scan([
    part('CPU'), part('RAM', { specs: { slot: 'DIMM A' } }),
    part('RAM', { specs: { slot: 'DIMM B' } }), part('STORAGE'), part('STORAGE'),
  ])
  const first = planScanApply(withEmptyRows([]), result)
  const second = planScanApply(first.next, result)
  assert.equal(second.editedReplaced, 0)
  assert.deepEqual(toPartInputs(second.next), result.parts)
})

test('부분 스캔은 빠진 종류와 MANUAL 값을 유지하고 AUTO 종류만 교체한다', () => {
  const manualCpu = part('CPU', { source: 'MANUAL', displayName: '직접 입력 CPU', rawName: null })
  const storedRam = part('RAM', { displayName: '저장된 RAM' })
  const initial = [manualCpu, storedRam, part('CPU')].map(toPersistedDraft)
  const plan = planScanApply(initial, scan([part('CPU', { displayName: '새 스캔 CPU' })]))
  const inputs = toPartInputs(plan.next)
  assert.equal(plan.keptManual, 1)
  assert.equal(plan.editedReplaced, 1)
  assert.deepEqual(inputs.find((item) => item.displayName === manualCpu.displayName), manualCpu)
  assert.deepEqual(inputs.find((item) => item.type === 'RAM'), storedRam)
  assert.equal(inputs.filter((item) => item.source === 'AUTO' && item.type === 'CPU').length, 1)
})

test('예시 모델명 입력은 카탈로그 연결을 해제하면서 AUTO 원문을 보존한다', () => {
  const original = part('CPU')
  const linked = linkCatalog(toDraft(original), { id: 'real-id', name: '실제 카탈로그 CPU' })
  const renamed = renameDraft(linked, '예시 CPU 모델명')
  assert.equal(renamed.catalogProductId, null)
  assert.equal(renamed.matchStatus, 'UNMATCHED')
  assert.equal(renamed.source, original.source)
  assert.equal(renamed.rawName, original.rawName)
  assert.equal(planScanApply([renamed], scan([original])).editedReplaced, 1)
})

test('API 요청에는 화면 전용 key·edited·persisted·capacityText를 보내지 않는다', () => {
  const draft = setCapacityInput(toPersistedDraft(part('RAM')), '16')
  const [request] = toPartInputs([draft])
  assert.deepEqual(Object.keys(request).sort(), Object.keys(part('RAM')).sort())
  assert.equal(request.specs.capacityBytes, 16 * 1024 ** 3)
})

test('반올림되어 0바이트가 되는 작은 용량은 입력을 유지하고 저장을 막는다', () => {
  const draft = setCapacityInput(toDraft(part('STORAGE')), '0.0000000001')
  assert.equal(draft.capacityText, '0.0000000001')
  assert.equal(getCapacityBytes(draft), 0)
  assert.equal(validatePcRequest('검증 PC', toPartInputs([draft])).length, 1)
})

test('빈 용량만 null이고 0·음수·숫자 오류·무한대는 거절한다', () => {
  const original = toDraft(part('RAM'))
  const blank = setCapacityInput(original, '')
  assert.equal(blank.specs.capacityBytes, null)
  assert.deepEqual(validatePcRequest('검증 PC', toPartInputs([blank])), [])

  for (const input of ['0', '-1', 'abc', '1e309']) {
    const invalid = setCapacityInput(original, input)
    assert.equal(validatePcRequest('검증 PC', toPartInputs([invalid])).length, 1, input)
  }
  // 브라우저가 숫자 입력 오류를 빈 value로 전달해도 미확인(null)으로 바꾸지 않는다.
  const badNumber = setCapacityInput(original, '', true)
  assert.ok(Number.isNaN(badNumber.specs.capacityBytes))
  assert.equal(validatePcRequest('검증 PC', toPartInputs([badNumber])).length, 1)
})

test('백엔드와 같이 두 용량 필드의 알려진 값은 유한한 양수여야 한다', () => {
  for (const field of ['capacityBytes', 'vramBytes']) {
    for (const value of [0, -1, NaN, Infinity, -Infinity, '16', false]) {
      assert.equal(validatePcRequest('검증 PC', [part('GPU', { specs: { [field]: value } })]).length, 1)
    }
    for (const value of [null, 1, 16 * 1024 ** 3]) {
      assert.deepEqual(validatePcRequest('검증 PC', [part('GPU', { specs: { [field]: value } })]), [])
    }
  }
})

test('PC 이름·부품 수·수량의 기존 입력 제한도 유지한다', () => {
  assert.equal(validatePcRequest('', [part('CPU')]).length, 1)
  assert.equal(validatePcRequest('a'.repeat(101), [part('CPU')]).length, 1)
  assert.equal(validatePcRequest('검증 PC', []).length, 1)
  assert.equal(validatePcRequest('검증 PC', Array.from({ length: 65 }, () => part('CPU'))).length, 1)
  for (const quantity of [0, 65, 1.5, NaN]) {
    assert.equal(validatePcRequest('검증 PC', [part('CPU', { quantity })]).length, 1)
  }
})
