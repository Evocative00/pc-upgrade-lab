import type { PartInput } from '../pc-scan/types.ts'
import { isSpecificHardwareModel, normalizeHardwareModel } from './benchmarkComparison.ts'

/**
 * 캡스톤 시연용 수동 등급이다. 실측 벤치마크, 성능 배수 또는 FPS가 아니다.
 * CPU와 GPU는 서로 비교하지 않으며, 이 표에 없는 정확한 모델은 판단하지 않는다.
 */
export const INTERNAL_SCORE_VERSION = 'prototype-v1'
export const INTERNAL_SCORE_MAX = 8

export type ScoredPart = 'CPU' | 'GPU'
export type InternalScoreComparison = {
  status: 'ABOVE_BASELINE' | 'BELOW_BASELINE' | 'UNKNOWN'
  actualGrade: number | null
  baselineGrade: number | null
  baselineModel: string | null
  reason: string
}

const CPU_GRADES: Record<string, number> = {
  'Core i3-530': 1, 'Core i3-540': 1, 'A6-3650': 1, 'Phenom X3 8650': 1,
  'Athlon 200GE': 2, 'FX-6300': 2, 'Core i3-4150': 2,
  'Core i5-4430': 3, 'Core i5-8250': 3, 'Ryzen 3 1200': 3,
  'Core i5-6600K': 4, 'Ryzen 5 1600': 4, 'Core i7-6700': 4,
  'Ryzen 5 3600': 5,
  'Ryzen 5 5600': 6, 'Core i5-11400': 6, 'Core i5-12400': 6,
  'Core i7-12700': 7, 'Core i5-13400': 7, 'Ryzen 7 5700X3D': 7,
  'Ryzen 7 7800X3D': 8,
}

const GPU_GRADES: Record<string, number> = {
  'Intel HD 4000': 1, 'Radeon R5 220': 1, 'Radeon HD 6570': 1,
  'Intel HD 4600': 2, 'GeForce GT 730': 2, 'Radeon R7 240': 2,
  'GeForce 560': 2, 'Radeon HD 6950': 2, 'Intel UHD 630': 2,
  'Radeon R7 370': 3, 'GeForce GTX 960': 3, 'GeForce GTX 1650': 3,
  'Radeon R9 380': 3, 'Radeon RX 6400': 3,
  'GeForce GTX 1060': 4, 'Radeon RX 580': 4, 'Intel Arc A380': 4,
  'GeForce GTX 1660 SUPER': 4,
  'GeForce RTX 2060 SUPER': 5, 'Radeon RX 5700 XT': 5,
  'Intel Arc A770': 5, 'GeForce RTX 3060': 5,
  'GeForce RTX 4060': 6, 'GeForce RTX 4070': 7,
}

function gradeFor(kind: ScoredPart, name: string): number | null {
  if (!isSpecificHardwareModel(name)) return null
  const key = normalizeHardwareModel(name)
  if (!key) return null
  const entries = Object.entries(kind === 'CPU' ? CPU_GRADES : GPU_GRADES)
    .filter(([model]) => normalizeHardwareModel(model) === key)
  // 이름 정규화가 서로 다른 제품을 합쳐 버리면 조용히 점수를 사용하지 않는다.
  return entries.length === 1 ? entries[0][1] : null
}

export function compareInternalScore(kind: ScoredPart, parts: PartInput[], requirementModels: string[]): InternalScoreComparison {
  const unknown = (reason: string, actualGrade: number | null = null): InternalScoreComparison => ({
    status: 'UNKNOWN', actualGrade, baselineGrade: null, baselineModel: null, reason,
  })
  if (requirementModels.length === 0 || requirementModels.some((model) => !isSpecificHardwareModel(model))) {
    return unknown('공식 예시가 특정 모델이 아닌 계열로 적혀 있어 내부 기준을 정할 수 없습니다.')
  }
  const baselines = requirementModels.map((model) => ({ model, grade: gradeFor(kind, model) }))
  if (baselines.some(({ grade }) => grade === null)) {
    return unknown('공식 예시 중 내부 등급표에 없는 모델이 있어 기준을 정할 수 없습니다.')
  }
  const baseline = baselines.reduce((lowest, current) => Number(current.grade) < Number(lowest.grade) ? current : lowest)
  const available = parts.filter((part) => part.type === kind && part.displayName.trim() && part.quantity > 0)
  if (available.length === 0) return unknown(`내 ${kind} 모델을 먼저 입력해 주세요.`)
  const grades = available.map((part) => gradeFor(kind, part.displayName))
  const known = grades.filter((grade): grade is number => grade !== null)
  const actualGrade = known.length ? Math.max(...known) : null
  if (actualGrade === null) return unknown(`내 ${kind} 모델이 내부 등급표에 없습니다.`)
  if (actualGrade < Number(baseline.grade) && known.length !== available.length) {
    return unknown(`등급을 모르는 ${kind}가 있어 부족하다고 단정할 수 없습니다.`, actualGrade)
  }
  return {
    status: actualGrade >= Number(baseline.grade) ? 'ABOVE_BASELINE' : 'BELOW_BASELINE',
    actualGrade, baselineGrade: Number(baseline.grade), baselineModel: baseline.model,
    reason: '팀이 정한 시연용 순서 등급입니다. 공식 충족 판정, 성능 배수 또는 예상 FPS가 아닙니다.',
  }
}
