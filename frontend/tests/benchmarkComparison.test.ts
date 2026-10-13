import assert from 'node:assert/strict'
import test from 'node:test'
import { compareBenchmarkScores, normalizeHardwareModel, type BenchmarkDataset } from '../src/features/planner/benchmarkComparison.ts'

// 실제 제조사 점수가 아닌 알고리즘 검증용 가상 데이터다.
const gpuFixture: BenchmarkDataset = {
  kind: 'GPU', metric: 'GPU_3D', publisher: '테스트 전용', edition: 'fixture-v1',
  sourceUrl: 'https://example.invalid/fixture', checkedOn: '2026-10-11',
  scores: { 'GeForce GTX 1060': 100, 'Radeon RX 580': 110, 'GeForce RTX 9999': 180,
    'GeForce GTX 999': 80 },
}

test('서로 다른 GPU라도 같은 시험의 점수가 높으면 기준 이상으로 비교한다', () => {
  const result = compareBenchmarkScores('GPU', 'NVIDIA GeForce RTX 9999',
    ['GeForce GTX 1060', 'Radeon RX 580'], gpuFixture)
  assert.equal(result.status, 'ABOVE_BASELINE')
  assert.equal(result.baselineModel, 'GeForce GTX 1060')
  assert.equal(result.ratio, 1.8)
})

test('낮은 점수도 게임 실행 불가가 아니라 점수 기준 미만으로만 표현한다', () => {
  const result = compareBenchmarkScores('GPU', 'GeForce GTX 999', ['GeForce GTX 1060'], gpuFixture)
  assert.equal(result.status, 'BELOW_BASELINE')
  assert.match(result.reason, /실제 게임 FPS나 실행 가능 판정은 아닙니다/)
})

test('자료가 없거나 공식 사양이 제품군이면 점수를 꾸며내지 않는다', () => {
  assert.equal(compareBenchmarkScores('GPU', 'GeForce RTX 9999', ['GeForce GTX 1060'], null).status, 'UNKNOWN')
  assert.equal(compareBenchmarkScores('GPU', 'GeForce RTX 9999', ['GeForce GTX 600 Series'], gpuFixture).status, 'UNKNOWN')
  assert.equal(compareBenchmarkScores('GPU', 'GeForce RTX 8888', ['GeForce GTX 1060'], gpuFixture).status, 'UNKNOWN')
  assert.equal(compareBenchmarkScores('GPU', 'GeForce RTX 9999', ['GeForce GTX 1060', 'GeForce RTX 8888'], gpuFixture).status, 'UNKNOWN')
})

test('CPU 점수와 GPU 점수를 섞어 비교하지 않는다', () => {
  const result = compareBenchmarkScores('CPU', 'Core i5-4430', ['Core i3-540'], gpuFixture)
  assert.equal(result.status, 'UNKNOWN')
})

test('CPU도 같은 판의 같은 지표 점수로만 비교한다', () => {
  const cpuFixture: BenchmarkDataset = {
    kind: 'CPU', metric: 'CPU_SINGLE_THREAD', publisher: '테스트 전용',
    edition: 'fixture-v1', sourceUrl: 'https://example.invalid/fixture', checkedOn: '2026-10-11',
    scores: { 'Intel Core i5-4430': 120, 'Intel Core i3-540': 80 },
  }
  const result = compareBenchmarkScores('CPU', 'Intel(R) Core(TM) i5-4430 CPU @ 3.00GHz',
    ['Intel Core i3-540'], cpuFixture)
  assert.equal(result.status, 'ABOVE_BASELINE')
  assert.equal(result.ratio, 1.5)
})

test('중복 이름·잘못된 점수는 조용히 사용하지 않는다', () => {
  const duplicated = { ...gpuFixture, scores: { 'GeForce GTX 1060': 100, 'NVIDIA GeForce GTX 1060': 200,
    'GeForce RTX 9999': 180 } }
  assert.equal(compareBenchmarkScores('GPU', 'GeForce RTX 9999', ['GeForce GTX 1060'], duplicated).status, 'UNKNOWN')
  const invalid = { ...gpuFixture, scores: { ...gpuFixture.scores, 'GeForce GTX 1060': -1 } }
  assert.equal(compareBenchmarkScores('GPU', 'GeForce RTX 9999', ['GeForce GTX 1060'], invalid).status, 'UNKNOWN')
})

test('제품 접미사는 판매명 설명과 달리 기본 CPU·GPU 모델로 떨어뜨리지 않는다', () => {
  const gpu = { ...gpuFixture, scores: { ...gpuFixture.scores,
    'GeForce GTX 980': 90, 'Radeon RX 6800': 385, 'GeForce RTX 5090': 750 } }
  for (const name of ['GeForce GTX 980M', 'Radeon RX 6800M', 'GeForce RTX 5090D',
    'GeForce RTX 5090 D', 'GeForce GTX 1060 Ti', 'GeForce GTX 10600', 'GeForce GTX 980 Mobile']) {
    assert.equal(compareBenchmarkScores('GPU', name, ['GeForce GTX 1060'], gpu).status, 'UNKNOWN', name)
  }
  const cpu: BenchmarkDataset = { ...gpuFixture, kind: 'CPU', metric: 'CPU_MULTI_THREAD',
    scores: { 'Core i5-4430': 100 } }
  assert.equal(compareBenchmarkScores('CPU', 'Core i5-4430Q', ['Core i5-4430'], cpu).status, 'UNKNOWN')
})

test('알려진 칩셋 뒤의 판매명은 허용하되 명시된 메모리 변형은 지우지 않는다', () => {
  const gpu = { ...gpuFixture, scores: { 'GeForce GTX 1660 SUPER': 146, 'GeForce GTX 1060 6GB': 100 } }
  const result = compareBenchmarkScores('GPU', 'MSI GeForce GTX 1660 SUPER VENTUS XS OC',
    ['GeForce GTX 1060 6GB'], gpu)
  assert.equal(result.actualScore, 146)
  assert.equal(result.status, 'ABOVE_BASELINE')
  assert.equal(compareBenchmarkScores('GPU', 'GeForce GTX 1060 3GB', ['GeForce GTX 1060 6GB'], gpu).status, 'UNKNOWN')
  assert.equal(compareBenchmarkScores('GPU', 'GeForce GTX 1060 GAMING 6G OC',
    ['GeForce GTX 1060 6GB'], gpu).ratio, 1)
  assert.notEqual(normalizeHardwareModel('GeForce GTX 1650 GDDR5'), normalizeHardwareModel('GeForce GTX 1650 GDDR6'))
  assert.notEqual(normalizeHardwareModel('Ryzen 5 5600G'), normalizeHardwareModel('Ryzen 5 5600'))
})

test('발행처·시험판이 다른 모델은 공통 기준 점수로 환산돼도 비교하지 않는다', () => {
  const gpu = { ...gpuFixture, scoreGroups: { 'GeForce GTX 1060': 'raster-old',
    'Radeon RX 580': 'raster-old', 'GeForce RTX 9999': 'raster-new', 'GeForce GTX 999': 'synthetic' } }
  assert.equal(compareBenchmarkScores('GPU', 'GeForce RTX 9999', ['GeForce GTX 1060'], gpu).status, 'UNKNOWN')
  assert.equal(compareBenchmarkScores('GPU', 'GeForce GTX 999', ['GeForce GTX 1060'], gpu).status, 'UNKNOWN')
  assert.equal(compareBenchmarkScores('GPU', 'Radeon RX 580', ['GeForce GTX 1060', 'GeForce GTX 999'], gpu).status, 'UNKNOWN')
  assert.equal(compareBenchmarkScores('GPU', 'Radeon RX 580', ['GeForce GTX 1060'], gpu).ratio, 1.1)
  const missingGroup = { ...gpu, scoreGroups: { 'GeForce GTX 1060': 'raster-old' } }
  assert.equal(compareBenchmarkScores('GPU', 'Radeon RX 580', ['GeForce GTX 1060'], missingGroup).status, 'UNKNOWN')
})
