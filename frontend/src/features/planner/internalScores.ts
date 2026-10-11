import type { PartInput } from '../pc-scan/types.ts'
import { isSpecificHardwareModel, normalizeHardwareModel } from './benchmarkComparison.ts'

/**
 * GPU 게임 성능의 팀 내부 등급(1~8)이다. CPU는 cpuBlenderScores.ts의 벤치마크 점수로 비교한다.
 * 공개 벤치마크의 상대 순서를 근거로 팀이 나눈 구간이며, 성능 배수나 예상 FPS가 아니다.
 * 데스크톱 업그레이드 대상이므로 데스크톱 GPU만 넣는다. 이 표에 없는 정확한 모델은 판단하지 않는다.
 */
export const INTERNAL_SCORE_VERSION = 'gpu-v2'
export const INTERNAL_SCORE_MAX = 8

export type ScoredPart = 'CPU' | 'GPU'
export type InternalScoreComparison = {
  status: 'ABOVE_BASELINE' | 'BELOW_BASELINE' | 'UNKNOWN'
  actualGrade: number | null
  baselineGrade: number | null
  baselineModel: string | null
  reason: string
}

// 등급을 나눌 때 참고한 자료. 자료의 점수는 옮겨 오지 않고 순서와 구간만 등급에 반영한다.
// - PassMark G3D Mark 목록: 모든 모델을 같은 합성 3D 점수로 비교한다. 게임 실측이 아니며 VRAM 부족을 반영하지 않는다.
// - Tom's Hardware GPU 계층표: 최신 NVIDIA 모델의 1080p 게임 실측 순서를 교차 확인했다.
export const GPU_GRADE_SOURCES = [
  { name: 'PassMark G3D Mark GPU 목록', url: 'https://www.videocardbenchmark.net/gpu_list.php', checkedOn: '2026-10-11' },
  { name: "Tom's Hardware GPU Benchmarks Hierarchy", url: 'https://www.tomshardware.com/reviews/gpu-hierarchy,4388.html', checkedOn: '2026-10-11' },
] as const

// 등급 4는 GeForce GTX 1060 6GB 수준이다. note는 같은 이름의 변형 때문에 실제 성능이 달라질 수 있는 경우만 적는다.
const GPU_GRADES: Record<string, { grade: number; note?: string }> = {
  'Intel HD 4000': { grade: 1 }, 'Radeon R5 220': { grade: 1 }, 'Radeon HD 6570': { grade: 1 },
  'Intel HD 4600': { grade: 2 }, 'Radeon R7 240': { grade: 2 },
  'GeForce GT 730': { grade: 2, note: 'DDR3·GDDR5 판의 성능 차이가 약 2배다.' },
  'Intel UHD 630': { grade: 2 },
  'GeForce 560': { grade: 3, note: '공식 사양 표기를 GTX 560(Ti 아님)으로 본다.' }, 'Radeon HD 6950': { grade: 3 },
  'Radeon R7 370': { grade: 3 }, 'GeForce GTX 960': { grade: 3 }, 'Radeon R9 380': { grade: 3 },
  'GeForce GTX 1650': { grade: 4 },
  'Radeon RX 6400': { grade: 4, note: 'PCIe 4.0 x4 연결이라 PCIe 3.0 메인보드에서는 더 느리다.' },
  'GeForce GTX 1060': { grade: 4, note: '3GB 판은 VRAM 부족으로 게임에서 더 낮을 수 있다.' },
  'Radeon RX 580': { grade: 4 }, 'Intel Arc A380': { grade: 4 },
  'GeForce GTX 1660 SUPER': { grade: 5 },
  'GeForce RTX 2060 SUPER': { grade: 5 }, 'Radeon RX 5700 XT': { grade: 5 },
  'Intel Arc A770': { grade: 5 }, 'GeForce RTX 3060': { grade: 5 },
  'GeForce RTX 4060': { grade: 6 },
  'GeForce RTX 5060 Ti': { grade: 7, note: '8GB·16GB 판을 구분하지 않는다.' }, 'GeForce RTX 4070': { grade: 7 },
}

function gradeFor(name: string): number | null {
  if (!isSpecificHardwareModel(name)) return null
  const key = normalizeHardwareModel(name)
  if (!key) return null
  const entries = Object.entries(GPU_GRADES).filter(([model]) => normalizeHardwareModel(model) === key)
  // 이름 정규화가 서로 다른 제품을 합쳐 버리면 조용히 점수를 사용하지 않는다.
  return entries.length === 1 ? entries[0][1].grade : null
}

export function compareInternalScore(parts: PartInput[], requirementModels: string[]): InternalScoreComparison {
  const unknown = (reason: string, actualGrade: number | null = null): InternalScoreComparison => ({
    status: 'UNKNOWN', actualGrade, baselineGrade: null, baselineModel: null, reason,
  })
  if (requirementModels.length === 0 || requirementModels.some((model) => !isSpecificHardwareModel(model))) {
    return unknown('공식 예시가 특정 모델이 아닌 계열로 적혀 있어 내부 기준을 정할 수 없습니다.')
  }
  const baselines = requirementModels.map((model) => ({ model, grade: gradeFor(model) }))
  if (baselines.some(({ grade }) => grade === null)) {
    return unknown('공식 예시 중 내부 등급표에 없는 모델이 있어 기준을 정할 수 없습니다.')
  }
  const baseline = baselines.reduce((lowest, current) => Number(current.grade) < Number(lowest.grade) ? current : lowest)
  const available = parts.filter((part) => part.type === 'GPU' && part.displayName.trim() && part.quantity > 0)
  if (available.length === 0) return unknown('내 GPU 모델을 먼저 입력해 주세요.')
  const grades = available.map((part) => gradeFor(part.displayName))
  const known = grades.filter((grade): grade is number => grade !== null)
  const actualGrade = known.length ? Math.max(...known) : null
  if (actualGrade === null) return unknown('내 GPU 모델이 내부 등급표에 없습니다.')
  if (actualGrade < Number(baseline.grade) && known.length !== available.length) {
    return unknown('등급을 모르는 GPU가 있어 부족하다고 단정할 수 없습니다.', actualGrade)
  }
  return {
    status: actualGrade >= Number(baseline.grade) ? 'ABOVE_BASELINE' : 'BELOW_BASELINE',
    actualGrade, baselineGrade: Number(baseline.grade), baselineModel: baseline.model,
    reason: '공개 벤치마크 순서를 근거로 팀이 정한 등급입니다. 공식 충족 판정, 성능 배수 또는 예상 FPS가 아닙니다.',
  }
}
