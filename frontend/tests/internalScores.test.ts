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
  const result = compareInternalScore([part('GPU', 'NVIDIA GeForce RTX 4060')],
    GAME_REQUIREMENTS['배틀그라운드'].recommended.gpu)
  assert.equal(result.status, 'ABOVE_BASELINE')
  assert.equal(result.actualGrade, 6)
  assert.equal(result.baselineGrade, 4)
  assert.equal(INTERNAL_SCORE_MAX, 8)
  assert.match(result.reason, /공개 벤치마크/)
})

test('권장 예시보다 낮은 내부 등급은 공식 실행 불가가 아닌 점검 대상으로만 둔다', () => {
  const result = compareInternalScore([part('GPU', 'GeForce GTX 960')],
    GAME_REQUIREMENTS['배틀그라운드'].recommended.gpu)
  assert.equal(result.status, 'BELOW_BASELINE')
  assert.equal(result.actualGrade, 3)
  assert.match(result.reason, /공식 충족 판정.*아닙니다/)
})

test('같은 GPU도 최소 예시 이상이고 권장 예시보다 낮을 수 있다', () => {
  const gpu = [part('GPU', 'GeForce GTX 960')]
  const game = GAME_REQUIREMENTS['배틀그라운드']
  const minimum = compareInternalScore(gpu, game.minimum.gpu)
  const recommended = compareInternalScore(gpu, game.recommended.gpu)
  assert.equal(minimum.status, 'ABOVE_BASELINE')
  assert.equal(minimum.actualGrade, 3)
  assert.equal(minimum.baselineGrade, 3)
  assert.equal(recommended.status, 'BELOW_BASELINE')
  assert.equal(recommended.baselineGrade, 4)
})


test('없는 모델 또는 공식 제품군 표기는 부족 판정을 만들지 않는다', () => {
  assert.equal(compareInternalScore([part('GPU', 'GeForce RTX 9999')],
    GAME_REQUIREMENTS['배틀그라운드'].recommended.gpu).status, 'UNKNOWN')
  assert.equal(compareInternalScore([part('GPU', 'GeForce RTX 4070')],
    GAME_REQUIREMENTS['오버워치 2'].minimum.gpu).status, 'UNKNOWN')
})

test('여러 GPU 중 미확인 모델이 있으면 낮은 점수만 보고 부족으로 단정하지 않는다', () => {
  const result = compareInternalScore([part('GPU', 'GeForce GTX 960'), part('GPU', 'GeForce RTX 9999')],
    GAME_REQUIREMENTS['배틀그라운드'].recommended.gpu)
  assert.equal(result.status, 'UNKNOWN')
})

test('출처 검토로 조정한 등급과 새 모델을 반영한다', () => {
  const gtx1650 = compareInternalScore([part('GPU', 'NVIDIA GeForce GTX 1650')],
    GAME_REQUIREMENTS['배틀그라운드'].recommended.gpu)
  assert.equal(gtx1650.actualGrade, 4)
  assert.equal(gtx1650.status, 'ABOVE_BASELINE')
  // 데스크톱 업그레이드 대상이라 노트북 GPU는 등급표에 없다.
  assert.equal(compareInternalScore([part('GPU', 'NVIDIA GeForce RTX 3050 Laptop GPU')],
    GAME_REQUIREMENTS['사이버펑크 2077'].recommended.gpu).status, 'UNKNOWN')
  assert.equal(compareInternalScore([part('GPU', 'NVIDIA GeForce RTX 5060 Ti')],
    GAME_REQUIREMENTS['사이버펑크 2077'].recommended.gpu).actualGrade, 7)
})

test('CPU 부품은 GPU 등급 비교에 쓰지 않는다', () => {
  assert.equal(compareInternalScore([part('CPU', 'GeForce RTX 4070')],
    GAME_REQUIREMENTS['배틀그라운드'].recommended.gpu).status, 'UNKNOWN')
})
