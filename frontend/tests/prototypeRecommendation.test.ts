import assert from 'node:assert/strict'
import test from 'node:test'
import type { PartInput, PartType } from '../src/features/pc-scan/types.ts'
import { GAME_REQUIREMENTS } from '../src/features/planner/gameRequirements.ts'
import { recommendPrototypeFocus } from '../src/features/planner/prototypeRecommendation.ts'

const GIB = 1024 ** 3
function part(type: PartType, displayName: string, specs: PartInput['specs'] = {}): PartInput {
  return { type, displayName, rawName: null, specs, quantity: 1, source: 'MANUAL',
    catalogProductId: null, matchStatus: 'UNMATCHED' }
}

test('확인된 권장 용량 부족을 내부 등급 부족보다 먼저 점검한다', () => {
  const focus = recommendPrototypeFocus([
    part('CPU', 'Ryzen 5 3600'), part('GPU', 'GeForce GTX 960'),
    part('RAM', '8GB', { capacityBytes: 8 * GIB }),
  ], ['배틀그라운드'])
  assert.equal(focus.status, 'FOCUS')
  assert.equal(focus.part, 'RAM')
  assert.equal(focus.basis, 'OFFICIAL_CAPACITY')
})

test('용량이 충분할 때는 같은 시험의 CPU 비교에서 낮은 부품을 하나 고른다', () => {
  const focus = recommendPrototypeFocus([
    part('CPU', 'Core i3-4150'), part('GPU', 'GeForce RTX 4070'),
    part('RAM', '16GB', { capacityBytes: 16 * GIB }),
  ], ['배틀그라운드'])
  assert.equal(focus.status, 'FOCUS')
  assert.equal(focus.part, 'CPU')
  assert.equal(focus.basis, 'BENCHMARK_SCORE')
})

test('점수표에 없는 부품만 있으면 임의 추천을 만들지 않는다', () => {
  const focus = recommendPrototypeFocus([
    part('CPU', 'Ryzen 9 9999'), part('GPU', 'GeForce RTX 9999'),
    part('RAM', '16GB', { capacityBytes: 16 * GIB }),
  ], ['배틀그라운드'])
  assert.equal(focus.status, 'UNKNOWN')
  assert.equal(focus.part, null)
})

test('비교 가능한 항목이 모두 기준 이상이면 교체를 꾸며내지 않는다', () => {
  const focus = recommendPrototypeFocus([
    part('CPU', 'Core i3-4150'), part('GPU', 'GeForce GT 730', { vramBytes: GIB }),
    part('RAM', '4GB', { capacityBytes: 4 * GIB }),
  ], ['발로란트'])
  assert.equal(focus.status, 'NO_DEFICIT')
})

test('분수 RAM 개수로 권장 용량을 채워 부족 없음으로 만들지 않는다', () => {
  const ram = part('RAM', '2GB', { capacityBytes: 2 * GIB })
  const focus = recommendPrototypeFocus([
    part('CPU', 'Core i3-4150'), part('GPU', 'GeForce GT 730', { vramBytes: GIB }),
    { ...ram, quantity: 1.5 },
  ], ['발로란트'])
  assert.equal(focus.status, 'UNKNOWN')
  assert.equal(focus.part, null)
})

test('VRAM이 미확인인 공식 PUBG GPU는 추천에서도 부족 없음으로 만들지 않는다', () => {
  const focus = recommendPrototypeFocus([
    part('CPU', 'Ryzen 5 1600'), part('GPU', 'GeForce GTX 1060'),
    part('RAM', '16GB', { capacityBytes: 16 * GIB }),
  ], ['배틀그라운드'])
  assert.equal(focus.status, 'UNKNOWN')
  assert.equal(focus.part, null)
})

test('서로 다른 GPU의 높은 점수와 큰 VRAM을 빌려 부족 없음으로 만들지 않는다', () => {
  // 단일 모델·동일 시험의 fixture 사양으로 장치별 점수와 VRAM 연결을 검증한다.
  const name = '동일 장치 검증 fixture'
  const tier = { cpu: ['Core i3-4150'], gpu: ['GeForce GT 730'], ramGiB: 4, vramGiB: 2,
    storage: 'fixture', os: 'fixture', context: 'fixture' }
  GAME_REQUIREMENTS[name] = { name, source: 'https://example.test/requirements', sourceName: 'fixture',
    checkedOn: '2026-10-13', minimum: tier, recommended: tier }
  try {
    const focus = recommendPrototypeFocus([
      part('CPU', 'Core i3-4150'), part('RAM', '4GB', { capacityBytes: 4 * GIB }),
      part('GPU', 'GeForce GT 730', { vramBytes: GIB }),
      part('GPU', 'Radeon R5 220', { vramBytes: 2 * GIB }),
    ], [name])
    assert.equal(focus.status, 'UNKNOWN')
    assert.equal(focus.part, null)
  } finally {
    delete GAME_REQUIREMENTS[name]
  }
})
