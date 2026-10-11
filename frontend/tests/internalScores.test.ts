import assert from 'node:assert/strict'
import test from 'node:test'
import type { PartInput, PartType } from '../src/features/pc-scan/types.ts'
import { compareInternalScore, INTERNAL_SCORE_MAX } from '../src/features/planner/internalScores.ts'
import { GAME_REQUIREMENTS } from '../src/features/planner/gameRequirements.ts'

function part(type: PartType, displayName: string): PartInput {
  return { type, displayName, rawName: null, specs: {}, quantity: 1, source: 'MANUAL',
    catalogProductId: null, matchStatus: 'UNMATCHED' }
}

test('서로 다른 모델도 같은 시연용 GPU 등급표 안에서 비교한다', () => {
  const result = compareInternalScore('GPU', [part('GPU', 'NVIDIA GeForce RTX 4060')],
    GAME_REQUIREMENTS['배틀그라운드'].recommended.gpu)
  assert.equal(result.status, 'ABOVE_BASELINE')
  assert.equal(result.actualGrade, 6)
  assert.equal(result.baselineGrade, 4)
  assert.equal(INTERNAL_SCORE_MAX, 8)
  assert.match(result.reason, /시연용/)
})

test('권장 예시보다 낮은 내부 등급은 공식 실행 불가가 아닌 점검 대상으로만 둔다', () => {
  const result = compareInternalScore('GPU', [part('GPU', 'GeForce GTX 960')],
    GAME_REQUIREMENTS['배틀그라운드'].recommended.gpu)
  assert.equal(result.status, 'BELOW_BASELINE')
  assert.equal(result.actualGrade, 3)
  assert.match(result.reason, /공식 충족 판정.*아닙니다/)
})

test('같은 GPU도 최소 예시 이상이고 권장 예시보다 낮을 수 있다', () => {
  const gpu = [part('GPU', 'GeForce GTX 960')]
  const game = GAME_REQUIREMENTS['배틀그라운드']
  const minimum = compareInternalScore('GPU', gpu, game.minimum.gpu)
  const recommended = compareInternalScore('GPU', gpu, game.recommended.gpu)
  assert.equal(minimum.status, 'ABOVE_BASELINE')
  assert.equal(minimum.actualGrade, 3)
  assert.equal(minimum.baselineGrade, 3)
  assert.equal(recommended.status, 'BELOW_BASELINE')
  assert.equal(recommended.baselineGrade, 4)
})

test('자동 인식 표기 변형을 같은 정확 모델로 인식한다', () => {
  const result = compareInternalScore('CPU', [part('CPU', 'AMD Ryzen 5 3600 6-Core Processor')],
    GAME_REQUIREMENTS['배틀그라운드'].recommended.cpu)
  assert.equal(result.status, 'ABOVE_BASELINE')
  assert.equal(result.actualGrade, 5)
})

test('없는 모델 또는 공식 제품군 표기는 부족 판정을 만들지 않는다', () => {
  assert.equal(compareInternalScore('GPU', [part('GPU', 'GeForce RTX 9999')],
    GAME_REQUIREMENTS['배틀그라운드'].recommended.gpu).status, 'UNKNOWN')
  assert.equal(compareInternalScore('CPU', [part('CPU', 'Core i7-12700')],
    GAME_REQUIREMENTS['오버워치 2'].recommended.cpu).status, 'UNKNOWN')
})

test('여러 GPU 중 미확인 모델이 있으면 낮은 점수만 보고 부족으로 단정하지 않는다', () => {
  const result = compareInternalScore('GPU', [part('GPU', 'GeForce GTX 960'), part('GPU', 'GeForce RTX 9999')],
    GAME_REQUIREMENTS['배틀그라운드'].recommended.gpu)
  assert.equal(result.status, 'UNKNOWN')
})
