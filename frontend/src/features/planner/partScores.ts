import type { PartInput } from '../pc-scan/types.ts'
import { compareBenchmarkScores, type BenchmarkDataset, type ScoreComparison } from './benchmarkComparison.ts'
import { CPU_BLENDER_DATASET } from './cpuBlenderScores.ts'
import { GPU_RELATIVE_DATASET } from './gpuRelativeScores.ts'

/**
 * CPU는 Blender Open Data의 렌더링 점수, GPU는 같은 출처·시험판의 점수끼리만 공식 예시와 비교한다.
 * GPU에 Blender 점수를 쓰지 않는 이유: 레이트레이싱 하드웨어·제조사별 렌더 방식 차이로 게임 성능과 크게 어긋난다.
 * 두 자료 모두 점수의 절대값이 아니라 비교 대상 대비 배율만 의미가 있다.
 */
export type ScoredPart = 'CPU' | 'GPU'

export const SCORE_DATASETS: Record<ScoredPart, BenchmarkDataset> = { CPU: CPU_BLENDER_DATASET, GPU: GPU_RELATIVE_DATASET }

export type PartScoreComparison = ScoreComparison & {
  // 부족 정도. 1 - (내 점수 ÷ 기준 점수), 기준 이상이거나 판단 보류면 0이다.
  gap: number
}

export function comparePartScore(kind: ScoredPart, parts: PartInput[], requirementModels: string[]): PartScoreComparison {
  const withGap = (result: ScoreComparison): PartScoreComparison => ({ ...result,
    gap: result.status === 'BELOW_BASELINE' && result.ratio !== null ? 1 - result.ratio : 0 })
  const available = parts.filter((part) => part.type === kind)
  if (available.some((part) => !part.displayName.trim() || !Number.isSafeInteger(part.quantity) ||
    part.quantity < 1 || part.quantity > 64)) {
    return withGap({ status: 'UNKNOWN', actualScore: null, baselineScore: null, ratio: null,
      baselineModel: null, reason: `내 ${kind} 모델과 수량을 확인해 주세요.` })
  }
  if (available.length === 0) {
    return withGap({ status: 'UNKNOWN', actualScore: null, baselineScore: null, ratio: null,
      baselineModel: null, reason: `내 ${kind} 모델을 먼저 입력해 주세요.` })
  }
  const results = available.map((part) =>
    compareBenchmarkScores(kind, part.displayName, requirementModels, SCORE_DATASETS[kind]))
  const known = results.filter((result) => result.actualScore !== null && result.baselineScore !== null)
  // 점수를 아는 부품이 없으면 첫 결과의 사유(공식 예시 문제 또는 내 모델 없음)를 그대로 보여 준다.
  if (known.length === 0) return withGap(results[0])
  const best = known.reduce((top, result) => Number(result.actualScore) > Number(top.actualScore) ? result : top)
  if (best.status === 'BELOW_BASELINE' && known.length !== available.length) {
    return { ...best, status: 'UNKNOWN', gap: 0, reason: `점수를 모르는 ${kind}가 있어 부족하다고 단정할 수 없습니다.` }
  }
  return withGap(best)
}

// 기준이 아주 낮은 모델(내장 그래픽 등)이면 수십 배가 나오므로 10배부터는 묶어서 보여 준다.
export function formatRatio(ratio: number): string {
  return ratio >= 10 ? '10배 이상' : `${ratio.toFixed(2)}배`
}
