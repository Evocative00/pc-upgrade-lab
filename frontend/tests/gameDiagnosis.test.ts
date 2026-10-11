import assert from 'node:assert/strict'
import test from 'node:test'
import type { PartInput, PartType } from '../src/features/pc-scan/types.ts'
import { diagnoseGame, diagnoseTier } from '../src/features/planner/gameDiagnosis.ts'
import { GAME_REQUIREMENTS } from '../src/features/planner/gameRequirements.ts'

const GIB = 1024 ** 3
function part(type: PartType, displayName: string, specs: PartInput['specs'] = {}, quantity = 1): PartInput {
  return { type, displayName, rawName: null, specs, quantity, source: 'MANUAL',
    catalogProductId: null, matchStatus: 'UNMATCHED' }
}

test('모델명이 다르다는 이유만으로 CPU·GPU를 부족으로 판정하지 않는다', () => {
  const result = diagnoseTier([
    part('CPU', 'Ryzen 9 9999'), part('GPU', 'GeForce RTX 9999'),
    part('RAM', '16GB', { capacityBytes: 16 * GIB }),
  ], GAME_REQUIREMENTS['배틀그라운드'].recommended)
  assert.deepEqual(result.checks.map((check) => check.verdict), ['UNKNOWN', 'UNKNOWN', 'PASS'])
  assert.equal(result.verdict, 'UNKNOWN')
})

test('동일 모델 및 여러 RAM 모듈의 총 용량을 확인한다', () => {
  const result = diagnoseTier([
    part('CPU', 'AMD Ryzen 5 1600 Six-Core Processor'),
    part('GPU', 'NVIDIA GeForce GTX 1060 6GB'),
    part('RAM', '8GB DDR4', { capacityBytes: 8 * GIB }, 2),
  ], GAME_REQUIREMENTS['배틀그라운드'].recommended)
  assert.deepEqual(result.checks.map((check) => check.verdict), ['PASS', 'PASS', 'PASS'])
  assert.equal(result.verdict, 'PASS')
})

test('Windows가 붙이는 Intel 상표 표기를 제거하고 동일 CPU 모델을 인식한다', () => {
  const result = diagnoseTier([part('CPU', 'Intel(R) Core(TM) i5-4430 CPU @ 3.00GHz')],
    GAME_REQUIREMENTS['배틀그라운드'].minimum)
  assert.equal(result.checks[0]?.verdict, 'PASS')
})

test('확인된 RAM 총량이 부족하면 부족, 미확인 모듈이 있으면 확인 불가', () => {
  const requirement = GAME_REQUIREMENTS['배틀그라운드'].recommended
  const low = diagnoseTier([part('RAM', '8GB', { capacityBytes: 8 * GIB })], requirement)
  assert.equal(low.checks.find((check) => check.label === 'RAM')?.verdict, 'FAIL')
  const incomplete = diagnoseTier([part('RAM', '8GB', { capacityBytes: 8 * GIB }), part('RAM', '용량 미상')], requirement)
  assert.equal(incomplete.checks.find((check) => check.label === 'RAM')?.verdict, 'UNKNOWN')
})

test('서로 다른 GPU의 VRAM은 더하지 않고 가장 큰 한 장치만 본다', () => {
  const game = GAME_REQUIREMENTS['사이버펑크 2077']
  const result = diagnoseGame([
    part('GPU', 'GPU A', { vramBytes: 4 * GIB }),
    part('GPU', 'GPU B', { vramBytes: 4 * GIB }),
  ], game)
  assert.equal(result.minimum.checks.find((check) => check.label === 'VRAM')?.verdict, 'FAIL')
})

test('대상 5개 게임은 모두 출처와 최소·권장 단계가 있다', () => {
  assert.equal(Object.keys(GAME_REQUIREMENTS).length, 5)
  for (const game of Object.values(GAME_REQUIREMENTS)) {
    assert.match(game.source, /^https:\/\//)
    assert.ok(game.minimum.ramGiB <= game.recommended.ramGiB)
    assert.ok(game.checkedOn)
  }
})
