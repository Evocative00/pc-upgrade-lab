import assert from 'node:assert/strict'
import test from 'node:test'
import type { PartInput, PartType } from '../src/features/pc-scan/types.ts'
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

test('용량이 충분할 때는 시연용 등급에서 낮은 부품을 하나 고른다', () => {
  const focus = recommendPrototypeFocus([
    part('CPU', 'Ryzen 5 3600'), part('GPU', 'GeForce GTX 960'),
    part('RAM', '16GB', { capacityBytes: 16 * GIB }),
  ], ['배틀그라운드'])
  assert.equal(focus.status, 'FOCUS')
  assert.equal(focus.part, 'GPU')
  assert.equal(focus.basis, 'INTERNAL_SCORE')
})

test('점수표에 없는 부품만 있으면 임의 추천을 만들지 않는다', () => {
  const focus = recommendPrototypeFocus([
    part('CPU', 'Ryzen 9 9999'), part('GPU', 'GeForce RTX 9999'),
    part('RAM', '16GB', { capacityBytes: 16 * GIB }),
  ], ['배틀그라운드'])
  assert.equal(focus.status, 'UNKNOWN')
  assert.equal(focus.part, null)
})

test('비교 가능한 항목이 모두 내부 기준 이상이면 교체를 꾸며내지 않는다', () => {
  const focus = recommendPrototypeFocus([
    part('CPU', 'Ryzen 7 7800X3D'), part('GPU', 'GeForce RTX 4070'),
    part('RAM', '16GB', { capacityBytes: 16 * GIB }),
  ], ['배틀그라운드'])
  assert.equal(focus.status, 'NO_DEFICIT')
})
