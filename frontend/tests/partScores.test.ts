import assert from 'node:assert/strict'
import test from 'node:test'
import type { PartInput, PartType } from '../src/features/pc-scan/types.ts'
import { normalizeHardwareModel } from '../src/features/planner/benchmarkComparison.ts'
import { CPU_BLENDER_DATASET } from '../src/features/planner/cpuBlenderScores.ts'
import { GAME_REQUIREMENTS } from '../src/features/planner/gameRequirements.ts'
import { comparePartScore } from '../src/features/planner/partScores.ts'

function part(type: PartType, displayName: string): PartInput {
  return { type, displayName, rawName: null, specs: {}, quantity: 1, source: 'MANUAL',
    catalogProductId: null, matchStatus: 'UNMATCHED' }
}

test('Windows가 보고한 CPU 이름을 Blender 점수표의 같은 모델과 연결한다', () => {
  const result = comparePartScore('CPU', [part('CPU', '13th Gen Intel(R) Core(TM) i5-13600KF')],
    GAME_REQUIREMENTS['사이버펑크 2077'].recommended.cpu)
  assert.equal(result.basis, 'BENCHMARK_SCORE')
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

test('GPU는 벤치마크 점수가 아닌 내부 등급으로 비교한다', () => {
  const result = comparePartScore('GPU', [part('GPU', 'GeForce GTX 960')], GAME_REQUIREMENTS['배틀그라운드'].recommended.gpu)
  assert.equal(result.basis, 'INTERNAL_SCORE')
  assert.equal(result.ratio, null)
  assert.equal(result.status, 'BELOW_BASELINE')
  assert.equal(result.gap, 1 / 8)
})

test('생성된 CPU 점수표는 정규화 후에도 모델이 겹치지 않고 표본 수를 함께 가진다', () => {
  const keys = Object.keys(CPU_BLENDER_DATASET.scores).map(normalizeHardwareModel)
  assert.equal(new Set(keys).size, keys.length)
  for (const [name, score] of Object.entries(CPU_BLENDER_DATASET.scores)) {
    assert.ok(Number.isFinite(score) && score > 0, name)
    assert.ok(Number(CPU_BLENDER_DATASET.sampleCounts?.[name]) >= 3, name)
  }
})
