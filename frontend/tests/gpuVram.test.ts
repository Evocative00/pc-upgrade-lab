import assert from 'node:assert/strict'
import test from 'node:test'
import { clearStaleVram, gpuModelKey, resolveVram, vramOptions, withVramInput } from '../src/features/pc/gpuVram.ts'
import type { PartInput } from '../src/features/pc-scan/types.ts'

const GIB = 1024 ** 3
const gpu = (displayName: string, specs: PartInput['specs'] = {}): PartInput => ({ type: 'GPU', displayName, rawName: null, specs,
  quantity: 1, source: 'MANUAL', catalogProductId: null, matchStatus: 'UNMATCHED' })

test('판매명·Windows 이름에서 그래픽 칩 모델을 찾는다', () => {
  assert.equal(gpuModelKey('NVIDIA GeForce RTX 3070'), 'rtx 3070')
  assert.equal(gpuModelKey('MSI GeForce RTX 4070 Ti SUPER 16G VENTUS'), 'rtx 4070 ti super')
  assert.equal(gpuModelKey('Sapphire PULSE Radeon RX 7900 XTX 24GB'), 'rx 7900 xtx')
  assert.equal(gpuModelKey('AMD Radeon RX 6600'), 'rx 6600')
  assert.equal(gpuModelKey('ASRock Intel Arc B580 Challenger 12GB OC'), 'arc b580')
  assert.equal(gpuModelKey('NVIDIA GeForce RTX 4060 Laptop GPU'), null)
  assert.equal(gpuModelKey('Intel(R) UHD Graphics 630'), null)
})

test('VRAM이 하나뿐인 모델은 자동으로 채운다', () => {
  assert.deepEqual(resolveVram(gpu('NVIDIA GeForce RTX 3070')), { gib: 8, source: 'MODEL', options: [8] })
  assert.equal(resolveVram(gpu('AMD Radeon RX 7900 XT')).gib, 20)
})

test('VRAM이 여러 개인 모델은 이름에 적힌 값만 쓰고, 없으면 사용자가 고르게 한다', () => {
  assert.deepEqual(vramOptions('NVIDIA GeForce RTX 3060'), [8, 12])
  assert.deepEqual(resolveVram(gpu('NVIDIA GeForce RTX 3060')), { gib: null, source: null, options: [8, 12] })
  assert.deepEqual(resolveVram(gpu('MSI GeForce RTX 3060 VENTUS 2X 12G OC')), { gib: 12, source: 'NAME', options: [8, 12] })
  // 모델 선택지에 없는 숫자(예: 2X)는 VRAM으로 보지 않는다.
  assert.equal(resolveVram(gpu('GeForce RTX 4060 Ti VENTUS 2X')).gib, null)
})

test('사용자가 고른 VRAM을 가장 먼저 쓰고, 고른 값은 제원에 바이트로 저장한다', () => {
  const picked = withVramInput(gpu('NVIDIA GeForce RTX 3060'), 12)
  assert.equal(picked.specs.vramBytes, 12 * GIB)
  assert.deepEqual(resolveVram(picked), { gib: 12, source: 'INPUT', options: [8, 12] })
  assert.equal(withVramInput(picked, null).specs.vramBytes, null)
})

test('모르는 모델은 선택지 없이 미확인으로 둔다', () => {
  assert.deepEqual(resolveVram(gpu('Intel(R) UHD Graphics 630')), { gib: null, source: null, options: null })
})

test('모델을 바꿔 예전 선택이 새 모델 선택지에 없으면 지운다', () => {
  const before = withVramInput(gpu('NVIDIA GeForce RTX 3060'), 12)
  const renamed = clearStaleVram(before, { ...before, displayName: 'GeForce GTX 1060 GAMING X 6G' })
  assert.equal(renamed.specs.vramBytes, null)
  assert.equal(resolveVram(renamed).gib, 6)
  // 같은 모델의 이름만 바꾸면 고른 값을 유지한다.
  assert.equal(clearStaleVram(before, { ...before, displayName: 'MSI GeForce RTX 3060 VENTUS' }).specs.vramBytes, 12 * GIB)
})

test('같은 칩셋의 다른 판매 용량을 연결하면 이전 VRAM만 지우고 수집 원문과 다른 제원은 보존한다', () => {
  const before: PartInput = { ...gpu('GeForce RTX 3060 12GB', { vramBytes: 12 * GIB, driverVersion: '1.2.3' }),
    rawName: 'NVIDIA GeForce RTX 3060', source: 'AUTO', quantity: 2, catalogProductId: '3060-12', matchStatus: 'MATCHED' }
  const next = clearStaleVram(before, { ...before, displayName: 'GeForce RTX 3060 VENTUS 8G', catalogProductId: '3060-8' })
  assert.deepEqual(resolveVram(next), { gib: 8, source: 'NAME', options: [8, 12] })
  assert.deepEqual(next.specs, { vramBytes: null, driverVersion: '1.2.3' })
  assert.equal(next.rawName, before.rawName)
  assert.equal(next.source, before.source)
  assert.equal(next.quantity, before.quantity)
  assert.equal(next.catalogProductId, '3060-8')
  assert.equal(before.specs.vramBytes, 12 * GIB)
  const sameCapacity = clearStaleVram(before, { ...before, displayName: 'GeForce RTX 3060 VENTUS 12G', catalogProductId: '3060-other-12' })
  assert.equal(sameCapacity.specs.vramBytes, 12 * GIB)
})
