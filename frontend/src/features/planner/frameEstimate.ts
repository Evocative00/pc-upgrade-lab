import type { PartInput } from '../pc-scan/types.ts'
import { findDatasetModel, findPartModel } from './benchmarkComparison.ts'
import { CPU_BLENDER_DATASET } from './cpuBlenderScores.ts'
import {
  FRAME_SOURCES, GAME_FRAME_PROFILES, type FramePoint, type FramePreset, type FrameResolution, type FrameSourceKind,
} from './gameFrameData.ts'
import { GPU_RELATIVE_DATASET, GPU_RESOLUTION_SCALE } from './gpuRelativeScores.ts'
import { comparePartScore } from './partScores.ts'

/**
 * 기준점(공식 단계 또는 측정값)과 GPU 상대 성능으로 예상 프레임을 계산한다.
 * 예상 fps = 기준 fps × (내 GPU의 목표 해상도 성능 ÷ 기준 GPU의 기준점 해상도 성능)
 * 해상도별 성능 = 1080p 상대값 × 해상도 비율(Tom's Hardware 같은 표의 1440p·4K ÷ 1080p 프레임).
 * CPU 영향이 큰 게임은 측정 최댓값을 내 CPU 성능으로 환산한 상한을 둔다. 모두 추정치다.
 */
export type FrameEstimate = {
  preset: FramePreset
  resolution: FrameResolution
  status: 'ESTIMATED' | 'OUT_OF_RANGE' | 'UNKNOWN'
  // 예상 범위. OUT_OF_RANGE이면 한쪽 경계만 있다(예: 135fps 이상).
  low: number | null
  high: number | null
  target: 'MEETS' | 'BORDERLINE' | 'BELOW' | null
  // 목표 프레임에 필요한 GPU 성능 배율(현재 GPU 대비). CPU 상한에 걸리면 GPU만 바꿔서는 부족할 수 있다.
  requiredGpuMultiplier: number | null
  cpuLimited: boolean
  sourceIds: string[]
  reason: string
  cpuNote: string | null
}

// 기준점과 성능 차이가 이 범위를 넘으면 비례 가정이 약해 정확한 값 대신 한쪽 경계만 보여 준다.
const MIN_FACTOR = 0.4
const MAX_FACTOR = 2.5
// 출처 종류별 기본 오차. 기준점과 다른 해상도로 환산하면 더 넓힌다.
const SPREAD: Record<FrameSourceKind, number> = { OFFICIAL: 0.1, REVIEW: 0.15, COMMUNITY: 0.15, RETAILER: 0.2, VENDOR: 0.25 }
const CROSS_RESOLUTION_SPREAD = 0.1
// RTX 4090·5080·5090급(GTX 1060 6GB = 100 기준 600 이상). 이 GPU의 1080p 측정값은 CPU 한계에 가깝다.
const TOP_GPU_RELATIVE = 600
const RESOLUTION_LABEL: Record<FrameResolution, string> = { FHD: '1080p', QHD: '1440p', '4K': '4K' }

function performanceAt(name: string, resolution: FrameResolution): number | null {
  const relative = GPU_RELATIVE_DATASET.scores[name]
  const scale = resolution === 'FHD' ? 1
    : (resolution === 'QHD' ? GPU_RESOLUTION_SCALE[name]?.qhd : GPU_RESOLUTION_SCALE[name]?.uhd) ?? null
  return relative && scale ? relative * scale : null
}

// factor: 예상 fps 배율(GPU 차이 × 해상도 환산), gpuFactor: 같은 해상도에서의 순수 GPU 성능 차이(범위 검사용)
type Anchor = { point: FramePoint; model: string; factor: number; gpuFactor: number }

function cpuScore(model: string | undefined): number | null {
  if (!model) return null
  const name = findPartModel(model, CPU_BLENDER_DATASET)
  return name ? CPU_BLENDER_DATASET.scores[name] ?? null : null
}

// 최상위 GPU의 1080p 측정값을 내 CPU 성능 비율로 낮춘 값. CPU 한계는 해상도와 관계없어 모든 해상도에 같은 상한을 쓴다.
// Blender 멀티코어 점수로 환산하므로 게임의 단일 코어 성능과는 차이가 있다.
function cpuCeiling(parts: PartInput[], preset: FramePreset): number | null {
  if (!preset.cpuLimited) return null
  const mine = parts.filter((part) => part.type === 'CPU').map((part) => cpuScore(part.displayName))
    .filter((score): score is number => score !== null)
  if (mine.length === 0) return null
  const myScore = Math.max(...mine)
  // 최상위 GPU로 잰 값만 CPU 한계로 본다. 중급 GPU 측정값은 GPU가 한계라 CPU 상한으로 쓰면 너무 낮다.
  const ceilings = preset.points.filter((point) => point.resolution === 'FHD').filter((point) => {
    const model = findDatasetModel(point.gpu, GPU_RELATIVE_DATASET)
    return model !== null && (GPU_RELATIVE_DATASET.scores[model] ?? 0) >= TOP_GPU_RELATIVE
  }).map((point) => {
    const reference = cpuScore(point.cpu)
    return reference ? point.fps * Math.min(1, Math.max(0.3, myScore / reference)) : null
  }).filter((value): value is number => value !== null)
  return ceilings.length ? Math.round(Math.max(...ceilings)) : null
}

export function estimateFrame(parts: PartInput[], preset: FramePreset, resolution: FrameResolution,
  targetFps: number): FrameEstimate {
  const base = { preset, resolution, low: null, high: null, target: null, requiredGpuMultiplier: null,
    cpuLimited: false, cpuNote: null }
  const gpus = parts.filter((part) => part.type === 'GPU' && part.displayName.trim())
  if (gpus.length === 0) return { ...base, status: 'UNKNOWN', sourceIds: [], reason: '내 GPU 모델을 먼저 입력해 주세요.' }
  const mine = gpus.map((part) => findPartModel(part.displayName, GPU_RELATIVE_DATASET))
    .filter((name): name is string => name !== null)
  if (mine.length === 0) return { ...base, status: 'UNKNOWN', sourceIds: [], reason: '내 GPU의 성능 자료가 없습니다.' }
  const best = mine.map((name) => ({ name, performance: performanceAt(name, resolution) }))
    .filter((item): item is { name: string; performance: number } => item.performance !== null)
    .sort((a, b) => b.performance - a.performance)[0]
  if (!best) {
    return { ...base, status: 'UNKNOWN', sourceIds: [],
      reason: `내 GPU(${mine[0]})는 ${RESOLUTION_LABEL[resolution]} 측정 자료가 없어 이 해상도의 프레임을 추정할 수 없습니다.` }
  }

  // 같은 해상도의 기준점을 우선 쓰고, 없으면 다른 해상도 기준점을 해상도 비율로 환산한다.
  const usable = (points: FramePoint[]): Anchor[] => points.map((point) => {
    const model = findDatasetModel(point.gpu, GPU_RELATIVE_DATASET)
    const performance = model ? performanceAt(model, point.resolution) : null
    const mineAtPoint = performanceAt(best.name, point.resolution)
    return model && performance && mineAtPoint
      ? { point, model, factor: best.performance / performance, gpuFactor: mineAtPoint / performance } : null
  }).filter((anchor): anchor is Anchor => anchor !== null)
  // 같은 해상도·비례 범위 안의 기준점 → 다른 해상도·범위 안의 기준점 → 범위 밖이라도 가장 가까운 기준점 순으로 쓴다.
  const inRange = (anchor: Anchor) => anchor.gpuFactor >= MIN_FACTOR && anchor.gpuFactor <= MAX_FACTOR
  const all = usable(preset.points)
  const sameResolution = all.filter((anchor) => anchor.point.resolution === resolution)
  const anchors = sameResolution.some(inRange) ? sameResolution.filter(inRange)
    : all.some(inRange) ? all.filter(inRange) : sameResolution.length ? sameResolution : all
  if (anchors.length === 0) {
    return { ...base, status: 'UNKNOWN', sourceIds: [], reason: '기준점 GPU의 성능 자료가 없어 프레임을 추정할 수 없습니다.' }
  }
  // 공식 단계는 예시 중 가장 낮은 GPU를, 측정값은 내 GPU와 성능이 가장 가까운 두 기준점을 쓴다.
  const chosen = preset.mode === 'OFFICIAL_TIER'
    ? [anchors.reduce((low, anchor) => anchor.gpuFactor > low.gpuFactor ? anchor : low)]
    : [...anchors].sort((a, b) => Math.abs(Math.log(a.gpuFactor)) - Math.abs(Math.log(b.gpuFactor))).slice(0, 2)
  const crossResolution = chosen.some((anchor) => anchor.point.resolution !== resolution)
  const spread = Math.max(...chosen.map((anchor) => SPREAD[FRAME_SOURCES[anchor.point.sourceId].kind]))
    + (crossResolution ? CROSS_RESOLUTION_SPREAD : 0)
  const sourceIds = [...new Set(chosen.map((anchor) => anchor.point.sourceId))]
  const nearest = chosen[0]
  const anchorText = chosen.map((anchor) => `${anchor.model} ${RESOLUTION_LABEL[anchor.point.resolution]} ${anchor.point.fps}fps`).join(', ')

  let cpuNote: string | null = null
  if (preset.cpu) {
    const cpu = comparePartScore('CPU', parts, preset.cpu)
    cpuNote = cpu.status === 'ABOVE_BASELINE' ? null : cpu.status === 'BELOW_BASELINE'
      ? `CPU가 이 단계의 공식 CPU(${cpu.baselineModel})보다 낮아 실제 프레임이 더 낮을 수 있습니다.`
      : '이 단계의 공식 CPU와 비교할 수 없어 CPU 병목 여부는 확인하지 못했습니다.'
  }
  const ceiling = cpuCeiling(parts, preset)
  if (preset.cpuLimited && ceiling === null) cpuNote = '내 CPU 성능 자료가 없어 CPU 한계는 반영하지 못했습니다.'

  if (nearest.gpuFactor > MAX_FACTOR || nearest.gpuFactor < MIN_FACTOR) {
    const above = nearest.gpuFactor > MAX_FACTOR
    // 경계값도 해상도 환산을 반영한다: 기준 fps × (경계 GPU 배율) × (해상도 비율)
    const resolutionRatio = nearest.factor / nearest.gpuFactor
    const bound = Math.round(nearest.point.fps * resolutionRatio * (above ? MAX_FACTOR * (1 - spread) : MIN_FACTOR * (1 + spread)))
    const low = above ? (ceiling !== null ? Math.min(bound, ceiling) : bound) : null
    const high = above ? null : bound
    return { ...base, status: 'OUT_OF_RANGE', low, high, sourceIds, cpuNote,
      target: low !== null && low >= targetFps ? 'MEETS' : high !== null && high < targetFps ? 'BELOW' : null,
      reason: `기준점(${anchorText})과 GPU 성능 차이가 ${nearest.gpuFactor.toFixed(1)}배라 정확한 값 대신 ${above ? '하한' : '상한'}만 표시합니다.` }
  }

  const mid = chosen.reduce((sum, anchor) => sum + anchor.point.fps * anchor.factor, 0) / chosen.length
  let low = Math.round(mid * (1 - spread))
  let high = Math.round(mid * (1 + spread))
  const limited = ceiling !== null && high > ceiling
  if (limited) {
    high = ceiling
    low = Math.min(low, Math.round(ceiling * (1 - spread)))
    cpuNote = `CPU 성능 기준 약 ${ceiling}fps 근처에서 프레임이 제한될 수 있습니다(CPU 점수로 환산한 추정).`
  }
  const target = low >= targetFps ? 'MEETS' : high < targetFps ? 'BELOW' : 'BORDERLINE'
  return { ...base, status: 'ESTIMATED', low, high, target, cpuLimited: limited, sourceIds, cpuNote,
    requiredGpuMultiplier: target === 'MEETS' ? null : targetFps / mid,
    reason: `기준점(${anchorText})과 GPU 성능 비율로 계산한 추정치입니다${crossResolution ? '(해상도 환산 포함)' : ''}.` }
}

export function estimateGameFrames(parts: PartInput[], game: string, resolution: FrameResolution,
  targetFps: number): FrameEstimate[] {
  const profile = Object.hasOwn(GAME_FRAME_PROFILES, game) ? GAME_FRAME_PROFILES[game] : undefined
  return (profile?.presets ?? []).map((preset) => estimateFrame(parts, preset, resolution, targetFps))
}

export function supportsFrameEstimate(game: string): boolean {
  return Object.hasOwn(GAME_FRAME_PROFILES, game)
}

// 화면 표기: 범위면 '약 24~37fps', 한쪽 경계만 있으면 '135fps 이상'·'12fps 미만'.
export function frameText(estimate: FrameEstimate): string {
  if (estimate.low !== null && estimate.high !== null) return `약 ${estimate.low}~${estimate.high}fps`
  if (estimate.low !== null) return `${estimate.low}fps 이상`
  if (estimate.high !== null) return `${estimate.high}fps 미만`
  return '추정 불가'
}

export const SOURCE_KIND_LABEL: Record<FrameSourceKind, string> = {
  OFFICIAL: '게임사 공식', REVIEW: '리뷰 실측', COMMUNITY: '커뮤니티 실측', RETAILER: '판매점 자료', VENDOR: '제조사 홍보 수치',
}
