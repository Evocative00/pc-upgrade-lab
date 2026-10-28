import { findDatasetModel } from './benchmarkComparison.ts'
import { CPU_BLENDER_DATASET } from './cpuBlenderScores.ts'
import { GPU_RELATIVE_DATASET } from './gpuRelativeScores.ts'
import type { VideoResolution } from './plannerGoal.ts'

/**
 * Premiere Pro 편집 해상도(1080p·4K·8K)별 예상 내보내기 시간과 타임라인 재생 수준. 모두 추정치다.
 * 내보내기 기준점은 Techgage 2020 실측(Premiere 14.2, i9-10980XE + RTX 2060 SUPER)이라 최신 버전은 더 빠를 수 있다.
 * 하드웨어 인코딩을 켜면 GPU 영향이 작고(GPU 2배 → 30~50% 단축, Puget), 8K·RAW는 CPU 멀티코어에 거의 비례한다.
 */
export type PlaybackLevel = 'SMOOTH' | 'OCCASIONAL' | 'PROXY'

export type PremiereFacts = {
  cpuScore: number | null
  gpuRelative: number | null
  vramGiB: number | null
  quickSync: boolean | null
}

export type PremiereEstimate = {
  resolution: VideoResolution
  exportLow: number | null
  exportHigh: number | null
  exportDetail: string
  playback: PlaybackLevel | null
  playbackDetail: string
}

// 기준 시스템과 해상도별 내보내기 기준(원본 1초당 걸리는 초). H.264 출력, 하드웨어 인코딩 사용.
const ANCHOR = { cpu: 'Core i9-10980XE', gpu: 'GeForce RTX 2060 SUPER' }
const EXPORT_MODEL: Record<VideoResolution, { secondsPerSecond: number; cpuExponent: number; gpuExponent: number; note: string }> = {
  // 1080p는 깨끗한 실측이 없어 4K 값의 35%로 가정한다(픽셀 25% + 고정 처리 시간).
  FHD: { secondsPerSecond: (22 / 60) * 0.35, cpuExponent: 0.2, gpuExponent: 0.3, note: '4K 기준의 35%로 가정' },
  // 4K60 AVC 60초 → H.264 NVENC 22초.
  '4K': { secondsPerSecond: 22 / 60, cpuExponent: 0.2, gpuExponent: 0.5, note: '4K 60초 → 22초 실측 기준' },
  // 8K RED 22초 → 31초. GPU를 바꿔도 거의 차이가 없고 CPU가 좌우한다.
  '8K': { secondsPerSecond: 31 / 22, cpuExponent: 0.9, gpuExponent: 0.2, note: '8K 22초 → 31초 실측 기준' },
}
export const EXPORT_CLIP_SECONDS = 600
const EXPORT_SPREAD = 0.4
const QUICK_SYNC_FACTOR = 0.8

// 타임라인 재생 수준(팀 기준). CPU는 Blender 점수, GPU는 게임 성능 상대값(GTX 1060 6GB = 100), VRAM은 Puget 해상도별 최소값.
const PLAYBACK_RULES: Record<VideoResolution, {
  smooth: { cpu: number; gpu: number; vram: number }; occasional: { cpu: number; vram: number }
}> = {
  FHD: { smooth: { cpu: 100, gpu: 100, vram: 4 }, occasional: { cpu: 60, vram: 2 } },
  '4K': { smooth: { cpu: 200, gpu: 200, vram: 8 }, occasional: { cpu: 130, vram: 6 } },
  '8K': { smooth: { cpu: 450, gpu: 400, vram: 16 }, occasional: { cpu: 250, vram: 12 } },
}

function score(model: string, kind: 'CPU' | 'GPU'): number | null {
  const dataset = kind === 'CPU' ? CPU_BLENDER_DATASET : GPU_RELATIVE_DATASET
  const name = findDatasetModel(model, dataset)
  return name ? dataset.scores[name] ?? null : null
}

function formatMinutes(seconds: number): string {
  return seconds < 90 ? `${Math.round(seconds)}초` : `${Math.round(seconds / 60)}분`
}

function exportTime(resolution: VideoResolution, facts: PremiereFacts): Pick<PremiereEstimate, 'exportLow' | 'exportHigh' | 'exportDetail'> {
  const anchorCpu = score(ANCHOR.cpu, 'CPU')
  const anchorGpu = score(ANCHOR.gpu, 'GPU')
  if (!facts.cpuScore || !facts.gpuRelative || !anchorCpu || !anchorGpu) {
    return { exportLow: null, exportHigh: null, exportDetail: 'CPU·GPU 점수 자료 없음' }
  }
  const model = EXPORT_MODEL[resolution]
  const seconds = EXPORT_CLIP_SECONDS * model.secondsPerSecond
    * (anchorCpu / facts.cpuScore) ** model.cpuExponent
    * (anchorGpu / facts.gpuRelative) ** model.gpuExponent
    * (facts.quickSync ? QUICK_SYNC_FACTOR : 1)
  return { exportLow: Math.round(seconds * (1 - EXPORT_SPREAD)), exportHigh: Math.round(seconds * (1 + EXPORT_SPREAD)),
    exportDetail: `${model.note} · 오차 ±40%${facts.quickSync ? ' · Quick Sync 반영' : ''}` }
}

function playback(resolution: VideoResolution, facts: PremiereFacts): Pick<PremiereEstimate, 'playback' | 'playbackDetail'> {
  if (!facts.cpuScore) return { playback: null, playbackDetail: 'CPU 점수 자료 없음' }
  const rule = PLAYBACK_RULES[resolution]
  const vram = facts.vramGiB
  // Quick Sync가 있는 Intel CPU는 H.264·HEVC 디코딩을 맡아 GPU 조건을 대신한다.
  const gpuOk = facts.quickSync === true || (facts.gpuRelative ?? 0) >= rule.smooth.gpu
  const short: string[] = []
  if (facts.cpuScore < rule.smooth.cpu) short.push('CPU')
  if (!gpuOk) short.push('GPU')
  if (vram !== null && vram < rule.smooth.vram) short.push(`VRAM(${rule.smooth.vram}GB 필요)`)
  const vramNote = vram === null ? ' · VRAM 확인 필요' : ''
  if (!short.length) return { playback: 'SMOOTH', playbackDetail: `팀 기준 충족${vramNote}` }
  const occasional = facts.cpuScore >= rule.occasional.cpu && (vram === null || vram >= rule.occasional.vram)
  return { playback: occasional ? 'OCCASIONAL' : 'PROXY', playbackDetail: `부족: ${short.join('·')}${vramNote}` }
}

export function estimatePremiere(resolution: VideoResolution, facts: PremiereFacts): PremiereEstimate {
  return { resolution, ...exportTime(resolution, facts), ...playback(resolution, facts) }
}

export function exportText(estimate: PremiereEstimate): string {
  return estimate.exportLow !== null && estimate.exportHigh !== null
    ? `약 ${formatMinutes(estimate.exportLow)}~${formatMinutes(estimate.exportHigh)}` : '추정 불가'
}

export const PLAYBACK_TEXT: Record<PlaybackLevel, string> = { SMOOTH: '원활', OCCASIONAL: '가끔 끊김', PROXY: '프록시 권장' }

// Premiere의 H.264·HEVC 하드웨어 디코딩에 쓰는 Intel 내장 그래픽(Quick Sync) 유무. F·KF 모델은 내장 그래픽이 없다.
export function hasQuickSync(cpuName: string | null): boolean | null {
  if (!cpuName) return null
  // AMD 이름에도 '8-Core'처럼 Core가 들어가므로 제조사부터 구분한다.
  if (/\b(?:amd|ryzen|athlon|threadripper)\b/i.test(cpuName)) return false
  if (!/\bintel\b|\bcore(?:\(tm\))?\s+(?:i[3579]|ultra)\b/i.test(cpuName)) return false
  return !/\d{4,5}K?F\b/i.test(cpuName)
}
