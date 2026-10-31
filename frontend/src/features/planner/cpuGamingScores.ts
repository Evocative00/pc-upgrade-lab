import type { BenchmarkDataset } from './benchmarkComparison.ts'

/**
 * 게임용 CPU 상대 성능(1080p, Ryzen 7 9850X3D = 100). 게임 목표의 CPU 추천에만 쓴다. 모두 추정 단계가 섞여 있다.
 * 출처: Tom's Hardware CPU Benchmarks and Hierarchy 2026 (확인 2026-10-31)
 *   1) 표에 1080p 게임 점수가 있으면 그대로 쓴다.
 *   2) 없고 단일 스레드 점수가 있으면 × 0.82 (표에 둘 다 있는 X3D 아닌 CPU 9종의 평균 비율). X3D는 × 1.107.
 *   3) 둘 다 없으면 같은 구조의 'GHz당 게임 점수' × 최대 부스트 클럭으로 추정한다.
 *      표에 없는 구 세대는 제조사 공식 IPC 향상 폭(Zen 3 +19%, Zen 2 +15%, Zen+ +3%,
 *      Alder Lake +19%, Rocket Lake +19%)으로 거슬러 계산하고, Haswell은 Skylake보다 5% 낮다고 가정한다.
 *   4코어 이하는 최근 게임에서 코어 부족을 반영해 × 0.9 (팀 가정).
 * 같은 칩의 F·KF(내장 그래픽 없음)는 같은 값이다.
 */
const TOMS_1080P: Record<string, number> = {
  'Ryzen 7 9850X3D': 100, 'Ryzen 7 9800X3D': 97, 'Ryzen 9 9950X3D': 95.7, 'Ryzen 9 9900X3D': 86.9,
  'Ryzen 7 7800X3D': 85.6, 'Ryzen 9 7950X3D': 83.9, 'Ryzen 5 7600X3D': 80.6, 'Core i9-14900K': 78.2,
  'Core Ultra 7 270K Plus': 77.5, 'Ryzen 9 7900X3D': 77.1, 'Ryzen 9 9950X': 76.9, 'Core i9-13900K': 76.8,
  'Core i7-14700K': 76.4, 'Core i7-13700K': 75.8, 'Ryzen 9 9900X': 73.9, 'Core Ultra 5 250K Plus': 73.3,
  'Core i5-14600K': 72.8, 'Ryzen 5 9600X': 72.6, 'Core Ultra 9 285K': 71.8, 'Ryzen 9 7950X': 71,
  'Core i5-13600K': 70.9, 'Ryzen 7 7700X': 70.6, 'Core Ultra 7 265K': 70.3, 'Ryzen 9 7900X': 69.2,
  'Ryzen 5 7600X': 67.3, 'Core Ultra 5 245K': 67.1, 'Core i7-12700K': 65.8, 'Core Ultra 5 225': 62.5,
  'Core i5-12600K': 60.8, 'Core i5-14400': 58,
}

const TOMS_SINGLE: Record<string, number> = {
  'Ryzen 7 5700X': 67, 'Ryzen 7 5800X3D': 65.9, 'Ryzen 5 5500': 60, 'Core i5-12400F': 69.5,
  'Core i3-12100F': 67.3, 'Core i3-13100F': 71, 'Core i5-13400F': 74,
}
const GAME_PER_SINGLE = 0.82
const X3D_GAME_PER_SINGLE = 1.107
const FEW_CORES = 0.9

const per = (name: string, ghz: number) => TOMS_1080P[name] / ghz
const mean = (values: number[]) => values.reduce((sum, value) => sum + value, 0) / values.length

// GHz당 게임 점수. 표의 같은 구조 CPU 평균에서 시작한다.
const ZEN5 = mean([per('Ryzen 5 9600X', 5.4), per('Ryzen 9 9900X', 5.6), per('Ryzen 9 9950X', 5.7)])
const ZEN4 = mean([per('Ryzen 5 7600X', 5.3), per('Ryzen 7 7700X', 5.4), per('Ryzen 9 7900X', 5.6), per('Ryzen 9 7950X', 5.7)])
const ZEN3 = TOMS_SINGLE['Ryzen 7 5700X'] * GAME_PER_SINGLE / 4.6
// L3가 작은 APU 계열(5500·5600G 등)은 5500 실측으로 비율을 정하고 Zen 2·Zen 4 APU에도 같은 비율을 쓴다.
const ZEN3_APU = TOMS_SINGLE['Ryzen 5 5500'] * GAME_PER_SINGLE / 4.2
const APU_RATIO = ZEN3_APU / ZEN3
const ZEN2 = ZEN3 / 1.19
const ZEN_PLUS = ZEN2 / 1.15
const ZEN1 = ZEN_PLUS / 1.03
const RAPTOR_K = mean([per('Core i9-14900K', 6.0), per('Core i9-13900K', 5.8), per('Core i7-14700K', 5.6),
  per('Core i7-13700K', 5.4), per('Core i5-14600K', 5.3), per('Core i5-13600K', 5.1)])
const RAPTOR_MAIN = per('Core i5-14400', 4.7)
const ALDER_K = mean([per('Core i5-12600K', 4.9), per('Core i7-12700K', 5.0)])
const ROCKET = ALDER_K / 1.19
const SKYLAKE = ROCKET / 1.19
const HASWELL = SKYLAKE / 1.05

// [모델, GHz당 점수, 최대 부스트 GHz, 코어 수]
const BY_CLOCK: [string, number, number, number][] = [
  ['Ryzen 7 9700X', ZEN5, 5.5, 8],
  ['Ryzen 5 7500F', ZEN4, 5.0, 6], ['Ryzen 5 7600', ZEN4, 5.1, 6], ['Ryzen 7 7700', ZEN4, 5.3, 8], ['Ryzen 9 7900', ZEN4, 5.4, 12],
  ['Ryzen 5 8500G', ZEN4 * APU_RATIO, 5.0, 6], ['Ryzen 5 8600G', ZEN4 * APU_RATIO, 5.0, 6], ['Ryzen 7 8700G', ZEN4 * APU_RATIO, 5.1, 8],
  ['Ryzen 5 5600', ZEN3, 4.4, 6], ['Ryzen 5 5600X', ZEN3, 4.6, 6], ['Ryzen 7 5800X', ZEN3, 4.7, 8],
  ['Ryzen 9 5900X', ZEN3, 4.8, 12], ['Ryzen 9 5950X', ZEN3, 4.9, 16],
  ['Ryzen 5 5600G', ZEN3_APU, 4.4, 6], ['Ryzen 7 5700G', ZEN3_APU, 4.6, 8],
  ['Ryzen 3 3100', ZEN2, 3.9, 4], ['Ryzen 3 3300X', ZEN2, 4.3, 4], ['Ryzen 5 3600', ZEN2, 4.2, 6], ['Ryzen 5 3600X', ZEN2, 4.4, 6],
  ['Ryzen 7 3700X', ZEN2, 4.4, 8], ['Ryzen 7 3800X', ZEN2, 4.5, 8], ['Ryzen 9 3900X', ZEN2, 4.6, 12], ['Ryzen 9 3950X', ZEN2, 4.7, 16],
  ['Ryzen 5 4500', ZEN2 * APU_RATIO, 4.1, 6], ['Ryzen 5 4600G', ZEN2 * APU_RATIO, 4.2, 6],
  ['Ryzen 5 2600', ZEN_PLUS, 3.9, 6], ['Ryzen 5 2600X', ZEN_PLUS, 4.2, 6], ['Ryzen 7 2700X', ZEN_PLUS, 4.3, 8],
  ['Ryzen 3 1200', ZEN1, 3.4, 4], ['Ryzen 5 1600', ZEN1, 3.6, 6], ['Ryzen 7 1700', ZEN1, 3.7, 8], ['Ryzen 7 1800X', ZEN1, 4.0, 8],
  ['Core i7-14700', RAPTOR_K, 5.4, 20], ['Core i7-13700', RAPTOR_K, 5.2, 16], ['Core i9-14900', RAPTOR_K, 5.8, 24],
  ['Core i5-14500', RAPTOR_MAIN, 5.0, 14], ['Core i5-13500', RAPTOR_MAIN, 4.8, 14],
  ['Core i3-14100', TOMS_SINGLE['Core i3-13100F'] * GAME_PER_SINGLE / 4.5, 4.7, 4],
  ['Core i7-12700', ALDER_K, 4.9, 12], ['Core i9-12900K', ALDER_K, 5.2, 16], ['Core i9-12900', ALDER_K, 5.1, 16],
  ['Core i5-11400', ROCKET, 4.4, 6], ['Core i5-11600K', ROCKET, 4.9, 6], ['Core i7-11700K', ROCKET, 5.0, 8], ['Core i9-11900K', ROCKET, 5.3, 8],
  ['Core i5-10400', SKYLAKE, 4.3, 6], ['Core i5-10600K', SKYLAKE, 4.8, 6], ['Core i7-10700', SKYLAKE, 4.8, 8],
  ['Core i7-10700K', SKYLAKE, 5.1, 8], ['Core i9-10900K', SKYLAKE, 5.3, 10], ['Core i3-10100', SKYLAKE, 4.3, 4],
  ['Core i5-9400', SKYLAKE, 4.1, 6], ['Core i5-9600K', SKYLAKE, 4.6, 6], ['Core i7-9700K', SKYLAKE, 4.9, 8], ['Core i9-9900K', SKYLAKE, 5.0, 8],
  ['Core i5-8400', SKYLAKE, 4.0, 6], ['Core i7-8700', SKYLAKE, 4.6, 6], ['Core i7-8700K', SKYLAKE, 4.7, 6],
  ['Core i7-7700K', SKYLAKE, 4.5, 4], ['Core i5-7500', SKYLAKE, 3.8, 4],
  ['Core i7-6700', SKYLAKE, 4.0, 4], ['Core i7-6700K', SKYLAKE, 4.2, 4], ['Core i5-6600K', SKYLAKE, 3.9, 4],
  ['Core i5-4430', HASWELL, 3.2, 4], ['Core i3-4150', HASWELL, 3.5, 2], ['Core i7-4790K', HASWELL, 4.4, 4],
]

const round = (value: number) => Math.round(value * 10) / 10
const scores: Record<string, number> = { ...TOMS_1080P }
for (const [name, value] of Object.entries(TOMS_SINGLE)) {
  if (scores[name] !== undefined) continue
  const cores = /i3-/.test(name) ? 4 : 0
  scores[name] = round(value * (/X3D/.test(name) ? X3D_GAME_PER_SINGLE : GAME_PER_SINGLE) * (cores && cores <= 4 ? FEW_CORES : 1))
}
scores['Ryzen 7 5700X3D'] = round(scores['Ryzen 7 5800X3D'] * 4.1 / 4.5)
for (const [name, perGhz, ghz, cores] of BY_CLOCK) {
  scores[name] = round(perGhz * ghz * (cores <= 4 ? FEW_CORES : 1))
}
// F·KF(내장 그래픽 없음) 변형과 기본 모델은 같은 값으로 둔다.
for (const name of Object.keys(scores)) {
  if (/^Core i\d-\d{4,5}K?$/.test(name)) scores[`${name}F`] ??= scores[name]
  if (/^Core i\d-\d{4,5}K?F$/.test(name)) scores[name.slice(0, -1)] ??= scores[name]
}

export const CPU_GAMING_DATASET: BenchmarkDataset = {
  kind: 'CPU',
  metric: 'CPU_GAMING',
  publisher: "Tom's Hardware",
  edition: 'CPU Benchmarks Hierarchy 2026 · 1080p 게임 점수(일부 추정)',
  sourceUrl: 'https://www.tomshardware.com/reviews/cpu-hierarchy,4312.html',
  checkedOn: '2026-10-31',
  scores,
}
