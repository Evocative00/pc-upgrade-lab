import type { PartInput } from '../pc-scan/types.ts'
import { compareBenchmarkScores, type ScoreComparison } from './benchmarkComparison.ts'
import { CPU_BLENDER_DATASET } from './cpuBlenderScores.ts'
import { compareInternalScore, INTERNAL_SCORE_MAX, type ScoredPart } from './internalScores.ts'

/**
 * CPU는 Blender Open Data의 실측 렌더링 점수, GPU는 근거를 단 팀 내부 등급으로 비교한다.
 * GPU의 렌더링 점수는 레이트레이싱 하드웨어·제조사별 렌더 방식 차이로 게임 성능과 크게 어긋나 쓰지 않는다.
 */
export type ScoreBasis = 'BENCHMARK_SCORE' | 'INTERNAL_SCORE'

export type PartScoreComparison = {
  basis: ScoreBasis
  status: ScoreComparison['status']
  // CPU는 벤치마크 점수, GPU는 1~INTERNAL_SCORE_MAX 등급이다.
  actual: number | null
  baseline: number | null
  // CPU만 있다. 내 점수 ÷ 기준 점수.
  ratio: number | null
  baselineModel: string | null
  reason: string
  // 부족 정도를 0~1로 맞춘 값. 서로 다른 부품의 부족 항목을 정렬할 때만 쓴다.
  gap: number
}

export const SCORE_BASIS: Record<ScoredPart, ScoreBasis> = { CPU: 'BENCHMARK_SCORE', GPU: 'INTERNAL_SCORE' }

function compareCpu(parts: PartInput[], requirementModels: string[]): PartScoreComparison {
  const toResult = (result: ScoreComparison): PartScoreComparison => ({
    basis: 'BENCHMARK_SCORE', status: result.status, actual: result.actualScore, baseline: result.baselineScore,
    ratio: result.ratio, baselineModel: result.baselineModel, reason: result.reason,
    gap: result.status === 'BELOW_BASELINE' && result.ratio !== null ? 1 - result.ratio : 0,
  })
  const available = parts.filter((part) => part.type === 'CPU' && part.displayName.trim() && part.quantity > 0)
  if (available.length === 0) {
    return toResult({ status: 'UNKNOWN', actualScore: null, baselineScore: null, ratio: null,
      baselineModel: null, reason: '내 CPU 모델을 먼저 입력해 주세요.' })
  }
  const results = available.map((part) =>
    compareBenchmarkScores('CPU', part.displayName, requirementModels, CPU_BLENDER_DATASET))
  const known = results.filter((result) => result.actualScore !== null && result.baselineScore !== null)
  // 점수를 아는 CPU가 없으면 첫 결과의 사유(공식 예시 문제 또는 내 모델 없음)를 그대로 보여 준다.
  if (known.length === 0) return toResult(results[0])
  const best = known.reduce((top, result) => Number(result.actualScore) > Number(top.actualScore) ? result : top)
  if (best.status === 'BELOW_BASELINE' && known.length !== available.length) {
    return { ...toResult(best), status: 'UNKNOWN', gap: 0,
      reason: '점수를 모르는 CPU가 있어 부족하다고 단정할 수 없습니다.' }
  }
  return toResult(best)
}

function compareGpu(parts: PartInput[], requirementModels: string[]): PartScoreComparison {
  const result = compareInternalScore(parts, requirementModels)
  return {
    basis: 'INTERNAL_SCORE', status: result.status, actual: result.actualGrade, baseline: result.baselineGrade,
    ratio: null, baselineModel: result.baselineModel, reason: result.reason,
    gap: result.status === 'BELOW_BASELINE'
      ? (Number(result.baselineGrade) - Number(result.actualGrade)) / INTERNAL_SCORE_MAX : 0,
  }
}

export function comparePartScore(kind: ScoredPart, parts: PartInput[], requirementModels: string[]): PartScoreComparison {
  return kind === 'CPU' ? compareCpu(parts, requirementModels) : compareGpu(parts, requirementModels)
}
