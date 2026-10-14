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

const check = (parts: PartInput[], label: string, game = '배틀그라운드') =>
  diagnoseGame(parts, GAME_REQUIREMENTS[game]).recommended.checks.find((item) => item.label === label)

test('모델명이 다르다는 이유만으로 CPU·GPU를 부족으로 판정하지 않는다', () => {
  const result = diagnoseTier([
    part('CPU', 'Ryzen 9 9999'), part('GPU', 'GeForce RTX 9999'),
    part('RAM', '16GB', { capacityBytes: 16 * GIB }),
  ], GAME_REQUIREMENTS['배틀그라운드'].recommended)
  assert.deepEqual(result.checks.map((item) => item.verdict), ['UNKNOWN', 'UNKNOWN', 'PASS', 'UNKNOWN'])
  assert.equal(result.verdict, 'UNKNOWN')
})

test('동일 모델과 같은 GPU의 VRAM 및 여러 RAM 모듈의 총 용량을 확인한다', () => {
  const result = diagnoseTier([
    part('CPU', 'AMD Ryzen 5 1600 Six-Core Processor'),
    part('GPU', 'NVIDIA GeForce GTX 1060', { vramBytes: 3 * GIB }),
    part('RAM', '8GB DDR4', { capacityBytes: 8 * GIB }, 2),
  ], GAME_REQUIREMENTS['배틀그라운드'].recommended)
  assert.deepEqual(result.checks.map((item) => item.verdict), ['PASS', 'PASS', 'PASS', 'PASS'])
  assert.equal(result.verdict, 'PASS')
})

test('Windows가 붙이는 Intel 상표 표기를 제거하고 동일 CPU 모델을 인식한다', () => {
  const result = diagnoseTier([part('CPU', 'Intel(R) Core(TM) i5-4430 CPU @ 3.00GHz')],
    GAME_REQUIREMENTS['배틀그라운드'].minimum)
  assert.equal(result.checks[0]?.verdict, 'PASS')
})

test('확인된 RAM 총량이 부족하면 부족, 미확인 모듈이 있으면 확인 불가', () => {
  assert.equal(check([part('RAM', '8GB', { capacityBytes: 8 * GIB })], 'RAM')?.verdict, 'FAIL')
  assert.equal(check([part('RAM', '8GB', { capacityBytes: 8 * GIB }), part('RAM', '용량 미상')], 'RAM')?.verdict, 'UNKNOWN')
  assert.equal(check([part('RAM', '16GB', { capacityBytes: 16 * GIB }), part('RAM', '용량 미상')], 'RAM')?.verdict, 'PASS')
})

test('분수·범위 밖·비유한 개수의 RAM을 합산해 충족으로 만들지 않는다', () => {
  for (const quantity of [1.5, 0, -1, 65, Number.NaN, Number.POSITIVE_INFINITY]) {
    const result = diagnoseTier([part('RAM', '8GB', { capacityBytes: 8 * GIB }, quantity)],
      GAME_REQUIREMENTS['사이버펑크 2077'].minimum)
    assert.equal(result.checks.find((item) => item.label === 'RAM')?.verdict, 'UNKNOWN', String(quantity))
  }
  assert.equal(check([part('RAM', '8GB', { capacityBytes: 8 * GIB }), part('RAM', '8GB', { capacityBytes: 8 * GIB }, 1.5)], 'RAM')?.verdict, 'UNKNOWN')
})

test('잘못된 bytes 값과 안전 범위를 넘는 RAM 합계는 확인 불가다', () => {
  for (const capacityBytes of [0, -1, 1.5, Number.NaN, Number.POSITIVE_INFINITY, Number.MAX_SAFE_INTEGER + 1]) {
    assert.equal(check([part('RAM', '용량 확인 필요', { capacityBytes })], 'RAM')?.verdict, 'UNKNOWN')
  }
  assert.equal(check([part('RAM', '합계 확인 필요', { capacityBytes: Number.MAX_SAFE_INTEGER }, 2)], 'RAM')?.verdict, 'UNKNOWN')
})

test('공식 모델명이어도 잘못된 장치 개수로 충족으로 만들지 않는다', () => {
  assert.equal(check([part('CPU', 'Ryzen 5 1600', {}, 1.5)], 'CPU')?.verdict, 'UNKNOWN')
  assert.equal(check([part('GPU', 'GeForce GTX 1060', { vramBytes: 3 * GIB }, 65)], 'GPU')?.verdict, 'UNKNOWN')
})

test('서로 다른 GPU의 VRAM은 더하지 않는다', () => {
  const result = diagnoseGame([
    part('GPU', 'GPU A', { vramBytes: 4 * GIB }),
    part('GPU', 'GPU B', { vramBytes: 4 * GIB }),
  ], GAME_REQUIREMENTS['사이버펑크 2077'])
  assert.equal(result.minimum.checks.find((item) => item.label === 'VRAM')?.verdict, 'FAIL')
})

test('PUBG GPU별 필수 VRAM은 미확인·부족·충분을 구분한다', () => {
  const missing = [part('GPU', 'GeForce GTX 1060')]
  assert.equal(check(missing, 'GPU')?.verdict, 'UNKNOWN')
  assert.equal(check(missing, 'VRAM')?.verdict, 'UNKNOWN')
  const low = [part('GPU', 'Radeon RX 580', { vramBytes: 3 * GIB })]
  assert.equal(check(low, 'GPU')?.verdict, 'FAIL')
  assert.equal(check(low, 'VRAM')?.verdict, 'FAIL')
  const enough = [part('GPU', 'GeForce GTX 1060', { vramBytes: 3 * GIB })]
  assert.equal(check(enough, 'GPU')?.verdict, 'PASS')
  assert.equal(check(enough, 'VRAM')?.verdict, 'PASS')
  const minimum = diagnoseTier([part('GPU', 'GeForce GTX 960', { vramBytes: 2 * GIB })],
    GAME_REQUIREMENTS['배틀그라운드'].minimum)
  assert.equal(minimum.checks.find((item) => item.label === 'GPU')?.verdict, 'PASS')
})

test('공식 GPU 모델과 다른 GPU의 VRAM을 합쳐 충족으로 만들지 않는다', () => {
  const parts = [part('GPU', 'GeForce GTX 1060', { vramBytes: 2 * GIB }),
    part('GPU', 'Radeon R9 390', { vramBytes: 8 * GIB })]
  assert.equal(check(parts, 'GPU')?.verdict, 'UNKNOWN')
  assert.equal(check(parts, 'VRAM')?.verdict, 'UNKNOWN')
})

test('대상 5개 게임은 모두 출처와 최소·권장 단계가 있다', () => {
  assert.equal(Object.keys(GAME_REQUIREMENTS).length, 5)
  for (const game of Object.values(GAME_REQUIREMENTS)) {
    assert.match(game.source, /^https:\/\//)
    assert.ok(game.minimum.ramGiB <= game.recommended.ramGiB)
    assert.ok(game.checkedOn)
  }
})

test('모델이 공식 예시와 다르면 벤치마크 배율로 충족·부족을 추정하고 근거를 밝힌다', () => {
  const parts = [part('CPU', '13th Gen Intel(R) Core(TM) i5-13600KF'), part('GPU', 'NVIDIA GeForce GTX 960')]
  const [cpu, gpu] = diagnoseTier(parts, GAME_REQUIREMENTS['배틀그라운드'].recommended).checks
  assert.equal(cpu.verdict, 'PASS')
  assert.equal(gpu.verdict, 'FAIL')
  assert.match(cpu.reason, /벤치마크.*공식 동일 모델 확인이 아닌 추정/)
  assert.match(gpu.reason, /GPU 게임 성능이 기준 .*배입니다/)
  assert.equal(check([part('GPU', 'Intel(R) Graphics')], 'GPU')?.verdict, 'UNKNOWN')
})

test('Blender 점수가 있어도 ARM CPU를 Windows 게임의 공식 충족으로 표시하지 않는다', () => {
  for (const model of ['Apple M1', 'Snapdragon(R) X Elite - X1E80100 - Qualcomm(R) Oryon(TM) CPU']) {
    const result = diagnoseTier([part('CPU', model)], GAME_REQUIREMENTS['발로란트'].recommended)
    assert.equal(result.checks.find((item) => item.label === 'CPU')?.verdict, 'UNKNOWN', model)
  }
})

test('VALORANT 권장 독립형 GPU의 VRAM은 부족·미확인·충분을 구분한다', () => {
  for (const [vramBytes, expected] of [[GIB / 2, 'FAIL'], [null, 'UNKNOWN'], [GIB, 'PASS']] as const) {
    const parts = [part('GPU', 'GeForce GT 730', { vramBytes })]
    assert.equal(check(parts, 'GPU', '발로란트')?.verdict, expected)
    assert.equal(check(parts, 'VRAM', '발로란트')?.verdict, expected)
  }
})

test('VALORANT 최소 사양은 독립형 GPU에만 전용 VRAM 1GiB를 요구한다', () => {
  const requirement = GAME_REQUIREMENTS['발로란트'].minimum
  for (const [vramBytes, expected] of [[GIB / 2, 'FAIL'], [null, 'UNKNOWN'], [GIB, 'PASS']] as const) {
    const result = diagnoseTier([part('GPU', 'Radeon R5 220', { vramBytes })], requirement)
    assert.equal(result.checks.find((item) => item.label === 'GPU')?.verdict, expected)
    assert.equal(result.checks.find((item) => item.label === 'VRAM')?.verdict, expected)
  }
  const integrated = diagnoseTier([
    part('CPU', 'Core i3-540'), part('GPU', 'Intel HD 4000', { vramBytes: null }),
    part('RAM', '4GB', { capacityBytes: 4 * GIB }),
  ], requirement)
  assert.equal(integrated.verdict, 'PASS')
  const memory = integrated.checks.find((item) => item.label === 'VRAM')
  assert.equal(memory?.verdict, 'PASS')
  assert.match(memory?.actual ?? '', /전용 VRAM 조건 없음/)
  assert.match(memory?.reason ?? '', /측정하거나 추정한 결과가 아닙니다/)
  const unknown = diagnoseTier([part('GPU', 'Intel Graphics', { vramBytes: 8 * GIB })], requirement)
  assert.equal(unknown.checks.find((item) => item.label === 'VRAM')?.verdict, 'UNKNOWN')
  const invalid = diagnoseTier([part('GPU', 'Intel HD 4000', {}, 1.5)], requirement)
  assert.equal(invalid.checks.find((item) => item.label === 'VRAM')?.verdict, 'UNKNOWN')
})
