import assert from 'node:assert/strict'
import test from 'node:test'
import type { PartInput, PartType } from '../src/features/pc-scan/types.ts'
import { normalizeHardwareModel } from '../src/features/planner/benchmarkComparison.ts'
import { CPU_BLENDER_DATASET } from '../src/features/planner/cpuBlenderScores.ts'
import { GPU_RELATIVE_DATASET } from '../src/features/planner/gpuRelativeScores.ts'
import { GAME_REQUIREMENTS } from '../src/features/planner/gameRequirements.ts'
import { comparePartScore, formatRatio } from '../src/features/planner/partScores.ts'

function part(type: PartType, displayName: string): PartInput {
  return { type, displayName, rawName: null, specs: {}, quantity: 1, source: 'MANUAL',
    catalogProductId: null, matchStatus: 'UNMATCHED' }
}

test('Windows가 보고한 CPU 이름을 Blender 점수표의 같은 모델과 연결한다', () => {
  const result = comparePartScore('CPU', [part('CPU', '13th Gen Intel(R) Core(TM) i5-13600KF')],
    GAME_REQUIREMENTS['사이버펑크 2077'].recommended.cpu)
  assert.equal(result.status, 'ABOVE_BASELINE')
  assert.ok(result.ratio !== null && result.ratio > 1)
  assert.equal(result.gap, 0)
})

test('권장 예시보다 낮은 CPU는 점수 비율과 부족 정도를 함께 돌려준다', () => {
  const result = comparePartScore('CPU', [part('CPU', 'AMD Ryzen 5 1600 Six-Core Processor')],
    GAME_REQUIREMENTS['사이버펑크 2077'].recommended.cpu)
  assert.equal(result.status, 'BELOW_BASELINE')
  assert.ok(result.ratio !== null && result.ratio < 1)
  assert.ok(Math.abs(result.gap - (1 - Number(result.ratio))) < 1e-9)
  // 공식 대안 중 점수가 낮은 예시를 기준으로 삼는다.
  assert.equal(result.baselineModel, 'Core i7-12700')
})

test('점수표에 없는 CPU나 계열 표기는 판단을 보류한다', () => {
  const missing = comparePartScore('CPU', [part('CPU', 'Ryzen 9 9999')], GAME_REQUIREMENTS['배틀그라운드'].recommended.cpu)
  assert.equal(missing.status, 'UNKNOWN')
  assert.equal(comparePartScore('CPU', [part('CPU', 'Ryzen 7 7800X3D')],
    GAME_REQUIREMENTS['오버워치 2'].recommended.cpu).status, 'UNKNOWN')
  assert.equal(comparePartScore('CPU', [], GAME_REQUIREMENTS['배틀그라운드'].recommended.cpu).status, 'UNKNOWN')
})

test('점수를 모르는 CPU가 섞여 있으면 낮은 점수만으로 부족하다고 단정하지 않는다', () => {
  const result = comparePartScore('CPU', [part('CPU', 'Core i7-6700'), part('CPU', 'Ryzen 9 9999')],
    GAME_REQUIREMENTS['사이버펑크 2077'].recommended.cpu)
  assert.equal(result.status, 'UNKNOWN')
})

test('GPU는 게임 성능 상대값으로 공식 예시 중 낮은 모델과 비교한다', () => {
  const pubg = GAME_REQUIREMENTS['배틀그라운드']
  const below = comparePartScore('GPU', [part('GPU', 'NVIDIA GeForce GTX 960')], pubg.recommended.gpu)
  assert.equal(below.status, 'BELOW_BASELINE')
  assert.equal(below.baselineModel, 'GeForce GTX 1060')
  assert.ok(Math.abs(below.gap - (1 - Number(below.ratio))) < 1e-9)
  const above = comparePartScore('GPU', [part('GPU', 'NVIDIA GeForce RTX 4060')], pubg.recommended.gpu)
  assert.equal(above.status, 'ABOVE_BASELINE')
  assert.ok(Number(above.ratio) > 2)
})

test('Windows의 AMD 표기와 공식 사양의 약식 표기도 같은 GPU로 찾는다', () => {
  assert.equal(comparePartScore('GPU', [part('GPU', 'AMD Radeon RX 580 Series')],
    GAME_REQUIREMENTS['배틀그라운드'].recommended.gpu).status, 'ABOVE_BASELINE')
  // 롤 권장 사양의 'GeForce 560'은 GTX 560 값으로 비교한다.
  assert.notEqual(comparePartScore('GPU', [part('GPU', 'NVIDIA GeForce RTX 4060')],
    GAME_REQUIREMENTS['리그 오브 레전드'].recommended.gpu).status, 'UNKNOWN')
})

test('계열 표기, 노트북·내장 그래픽처럼 자료에 없는 GPU는 판단을 보류한다', () => {
  const ow2 = GAME_REQUIREMENTS['오버워치 2']
  assert.equal(comparePartScore('GPU', [part('GPU', 'GeForce RTX 4070')], ow2.minimum.gpu).status, 'UNKNOWN')
  const pubg = GAME_REQUIREMENTS['배틀그라운드'].recommended.gpu
  assert.equal(comparePartScore('GPU', [part('GPU', 'NVIDIA GeForce RTX 3050 Laptop GPU')], pubg).status, 'UNKNOWN')
  assert.equal(comparePartScore('GPU', [part('GPU', 'Intel(R) Graphics')], pubg).status, 'UNKNOWN')
  assert.equal(comparePartScore('GPU', [part('GPU', 'GeForce GTX 960'), part('GPU', 'GeForce RTX 9999')], pubg).status, 'UNKNOWN')
})

test('생성된 GPU 점수표는 정규화 후에도 모델이 겹치지 않는다', () => {
  const keys = Object.keys(GPU_RELATIVE_DATASET.scores).map(normalizeHardwareModel)
  assert.equal(new Set(keys).size, keys.length)
  assert.equal(GPU_RELATIVE_DATASET.scores['GeForce GTX 1060'], 100)
})

test('생성된 CPU 점수표는 정규화 후에도 모델이 겹치지 않고 표본 수를 함께 가진다', () => {
  const keys = Object.keys(CPU_BLENDER_DATASET.scores).map(normalizeHardwareModel)
  assert.equal(new Set(keys).size, keys.length)
  for (const [name, score] of Object.entries(CPU_BLENDER_DATASET.scores)) {
    assert.ok(Number.isFinite(score) && score > 0, name)
    assert.ok(Number(CPU_BLENDER_DATASET.sampleCounts?.[name]) >= 3, name)
  }
})

test('배율은 소수 둘째 자리까지, 10배부터는 묶어서 보여 준다', () => {
  assert.equal(formatRatio(0.654), '0.65배')
  assert.equal(formatRatio(54.4), '10배 이상')
})
