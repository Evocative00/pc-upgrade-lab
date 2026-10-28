import type { PartInput } from '../pc-scan/types.ts'
import { findDatasetModel, findPartModel } from './benchmarkComparison.ts'
import { CPU_BLENDER_DATASET } from './cpuBlenderScores.ts'
import { GPU_BLENDER_DATASET } from './gpuBlenderScores.ts'
import { GPU_RELATIVE_DATASET } from './gpuRelativeScores.ts'
import type { VideoResolution, Workload } from './plannerGoal.ts'
import { estimatePremiere, EXPORT_CLIP_SECONDS, exportText, hasQuickSync, PLAYBACK_TEXT } from './premiereEstimate.ts'
import {
  BLENDER_RENDER_ANCHOR, COMPILE_ANCHORS, CONTAINER_ASSUMPTION, EMULATOR_ASSUMPTION, PROGRAM_PROFILES,
  type ProgramProfile, type Requirement,
} from './workloadData.ts'

/**
 * 영상 편집·개발 프로그램의 공식 사양 확인과 예상 수치. 예상 수치는 공개 벤치마크 기준점을 내 부품 점수 비율로 환산한
 * 추정치이며, 화면에 오차 범위와 출처를 함께 보여 준다.
 */
export type Verdict = 'PASS' | 'FAIL' | 'UNKNOWN'
export type RequirementCheck = { level: string; item: string; required: string; actual: string; verdict: Verdict }
export type WorkloadEstimate = { title: string; value: string; detail: string; known: boolean }
export type ProgramDiagnosis = {
  profile: ProgramProfile
  levels: { level: string; verdict: Verdict; checks: RequirementCheck[] }[]
  estimates: WorkloadEstimate[]
}

const GIB = 1024 ** 3

type PcFacts = {
  ramGiB: number | null
  vramGiB: number | null
  vramInferred: boolean
  cores: number | null
  threads: number | null
  cpuModel: string | null
  cpuScore: number | null
  gpuModel: string | null
  gpuRenderScore: number | null
  gpuRelative: number | null
  cpuName: string | null
}

function validCount(part: PartInput): boolean {
  return Number.isSafeInteger(part.quantity) && part.quantity >= 1 && part.quantity <= 64
}

// 판매명이나 Windows 이름에 적힌 VRAM(예: '16G', '12GB')을 읽는다. 제원에 VRAM이 없을 때만 쓴다.
function vramFromName(name: string): number | null {
  const match = name.match(/\b(\d{1,2})\s*G(?:B)?\b/i)
  const value = match ? Number(match[1]) : NaN
  return value >= 1 && value <= 48 ? value : null
}

export function pcFacts(parts: PartInput[]): PcFacts {
  const rams = parts.filter((part) => part.type === 'RAM' && part.displayName.trim())
  const ramBytes = rams.map((part) => {
    const value = part.specs.capacityBytes
    return validCount(part) && typeof value === 'number' && value > 0 ? value * part.quantity : null
  })
  const ramGiB = rams.length && ramBytes.every((value) => value !== null)
    ? ramBytes.reduce((sum: number, value) => sum + Number(value), 0) / GIB : null

  const gpu = parts.find((part) => part.type === 'GPU' && part.displayName.trim())
  const vramSpec = gpu?.specs.vramBytes
  const vramFromSpec = typeof vramSpec === 'number' && vramSpec > 0 ? vramSpec / GIB : null
  const vramGuess = gpu && vramFromSpec === null ? vramFromName(gpu.displayName) : null

  const cpu = parts.find((part) => part.type === 'CPU' && part.displayName.trim())
  const cores = typeof cpu?.specs.cores === 'number' && cpu.specs.cores > 0 ? cpu.specs.cores : null
  const threads = typeof cpu?.specs.logicalProcessors === 'number' && cpu.specs.logicalProcessors > 0
    ? cpu.specs.logicalProcessors : null
  const cpuModel = cpu ? findPartModel(cpu.displayName, CPU_BLENDER_DATASET) : null
  const gpuModel = gpu ? findPartModel(gpu.displayName, GPU_BLENDER_DATASET) : null
  const relativeModel = gpu ? findPartModel(gpu.displayName, GPU_RELATIVE_DATASET) : null
  return {
    ramGiB, vramGiB: vramFromSpec ?? vramGuess, vramInferred: vramFromSpec === null && vramGuess !== null, cores, threads,
    cpuModel, cpuScore: cpuModel ? CPU_BLENDER_DATASET.scores[cpuModel] ?? null : null,
    gpuModel, gpuRenderScore: gpuModel ? GPU_BLENDER_DATASET.scores[gpuModel] ?? null : null,
    gpuRelative: relativeModel ? GPU_RELATIVE_DATASET.scores[relativeModel] ?? null : null,
    cpuName: cpu?.displayName ?? null,
  }
}

function checkLevel(level: string, requirement: Requirement, facts: PcFacts): { level: string; verdict: Verdict; checks: RequirementCheck[] } {
  const checks: RequirementCheck[] = []
  const add = (item: string, required: number | undefined, actual: number | null, unit: string, suffix = '') => {
    if (required === undefined) return
    checks.push({ level, item, required: `${required}${unit} 이상`,
      actual: actual === null ? '미입력' : `${Math.round(actual * 10) / 10}${unit}${suffix}`,
      verdict: actual === null ? 'UNKNOWN' : actual >= required ? 'PASS' : 'FAIL' })
  }
  add('RAM', requirement.ramGiB, facts.ramGiB, 'GB')
  add('VRAM', requirement.vramGiB, facts.vramGiB, 'GB', facts.vramInferred ? ' (이름에서 추정)' : '')
  add('CPU 코어', requirement.cores, facts.cores, '개')
  const verdict: Verdict = checks.some((check) => check.verdict === 'FAIL') ? 'FAIL'
    : checks.length && checks.every((check) => check.verdict === 'PASS') ? 'PASS' : 'UNKNOWN'
  return { level, verdict, checks }
}

function cpuScoreOf(model: string): number | null {
  const name = findDatasetModel(model, CPU_BLENDER_DATASET)
  return name ? CPU_BLENDER_DATASET.scores[name] ?? null : null
}

const range = (value: number, spread: number) => [Math.round(value * (1 - spread)), Math.round(value * (1 + spread))]
const unknown = (title: string, detail: string): WorkloadEstimate => ({ title, value: '추정 불가', detail, known: false })

function blenderRender(facts: PcFacts): WorkloadEstimate {
  const title = '렌더 시간 (BMW27 장면)'
  const anchor = cpuScoreOf(BLENDER_RENDER_ANCHOR.cpu)
  if (!facts.cpuScore || !anchor) return unknown(title, 'CPU 점수 자료 없음')
  // 렌더 시간은 점수(분당 샘플 수)에 반비례한다. CPU와 GPU 점수는 같은 단위다.
  const cpuSeconds = BLENDER_RENDER_ANCHOR.seconds * anchor / facts.cpuScore
  const [cpuLow, cpuHigh] = range(cpuSeconds, 0.25)
  if (!facts.gpuRenderScore) {
    return { title, known: true, value: `CPU 약 ${cpuLow}~${cpuHigh}초`,
      detail: 'GPU 렌더링 미지원 · CPU 기준 · 오차 ±25%' }
  }
  const speedup = facts.gpuRenderScore / facts.cpuScore
  const [gpuLow, gpuHigh] = range(cpuSeconds / speedup, 0.25)
  return { title, known: true, value: `GPU 약 ${gpuLow}~${gpuHigh}초 · CPU 약 ${cpuLow}~${cpuHigh}초`,
    detail: `GPU가 CPU보다 약 ${speedup.toFixed(1)}배 빠름 · 오차 ±25%` }
}

function compileTime(facts: PcFacts): WorkloadEstimate {
  const title = '컴파일 시간 (리눅스 커널 기준)'
  if (!facts.cpuScore) return unknown(title, 'CPU 점수 자료 없음')
  const myScore = facts.cpuScore
  const anchors = COMPILE_ANCHORS.map((anchor) => ({ ...anchor, score: cpuScoreOf(anchor.cpu) }))
    .filter((anchor): anchor is typeof anchor & { score: number } => anchor.score !== null)
  if (!anchors.length) return unknown(title, '기준 자료 없음')
  // 내 CPU와 점수가 가장 가까운 기준점에서 점수 비율로 환산한다(멀티코어 작업이라 대체로 반비례).
  const nearest = anchors.reduce((best, anchor) =>
    Math.abs(Math.log(anchor.score / myScore)) < Math.abs(Math.log(best.score / myScore)) ? anchor : best)
  const [low, high] = range(nearest.seconds * nearest.score / myScore, 0.3)
  return { title, known: true, value: `약 ${low}~${high}초`,
    detail: `${nearest.cpu}(${nearest.seconds}초) 기준 환산 · 오차 ±30%` }
}

function emulators(facts: PcFacts): WorkloadEstimate {
  const title = '에뮬레이터 동시 실행'
  if (facts.ramGiB === null) return unknown(title, 'RAM 용량을 입력하세요')
  const count = Math.max(0, Math.floor((facts.ramGiB - EMULATOR_ASSUMPTION.studioGiB) / EMULATOR_ASSUMPTION.emulatorGiB))
  return { title, known: true, value: count ? `약 ${count}개` : '여유 없음',
    detail: `1개당 ${EMULATOR_ASSUMPTION.emulatorGiB}GB 기준 (Windows·Studio ${EMULATOR_ASSUMPTION.studioGiB}GB 제외)` }
}

function containers(facts: PcFacts): WorkloadEstimate {
  const title = '컨테이너·가상 머신'
  if (facts.ramGiB === null) return unknown(title, 'RAM 용량을 입력하세요')
  const dockerGiB = facts.ramGiB * CONTAINER_ASSUMPTION.dockerShare
  const light = Math.floor(dockerGiB / CONTAINER_ASSUMPTION.lightContainerGiB)
  const vms = Math.max(0, Math.floor((facts.ramGiB - CONTAINER_ASSUMPTION.hostReserveGiB) / CONTAINER_ASSUMPTION.vmGiB))
  return { title, known: true, value: `컨테이너 약 ${light}개 · VM 약 ${vms}대`,
    detail: `컨테이너 ${CONTAINER_ASSUMPTION.lightContainerGiB}GB·VM ${CONTAINER_ASSUMPTION.vmGiB}GB 가정 · Docker는 RAM 절반(${Math.round(dockerGiB)}GB) 사용`
      + (facts.threads ? ` · 논리 프로세서 ${facts.threads}개 공유` : facts.cores ? ` · 코어 ${facts.cores}개 공유` : '') }
}

// 영상 편집의 성능 수준. 팀이 정한 '4K 편집 원활' 기준 부품 대비 배율이다(공식 기준 아님).
const VIDEO_REFERENCE: Record<string, { cpu: string; gpu: string }> = {
  'Premiere Pro': { cpu: 'Core i7-12700', gpu: 'GeForce RTX 3060 12GB' },
  'DaVinci Resolve': { cpu: 'Core i5-12400', gpu: 'GeForce RTX 4070' },
}

function videoLevel(program: string, facts: PcFacts): WorkloadEstimate {
  const title = '편집 성능 (기준 부품 대비)'
  const reference = VIDEO_REFERENCE[program]
  const refCpu = cpuScoreOf(reference.cpu)
  const refGpuName = findDatasetModel(reference.gpu, GPU_RELATIVE_DATASET)
  const refGpu = refGpuName ? GPU_RELATIVE_DATASET.scores[refGpuName] : undefined
  const parts: string[] = []
  if (facts.cpuScore && refCpu) parts.push(`CPU ${(facts.cpuScore / refCpu).toFixed(2)}배`)
  if (facts.gpuRelative && refGpu) parts.push(`GPU ${(facts.gpuRelative / refGpu).toFixed(2)}배`)
  if (!parts.length) return unknown(title, 'CPU·GPU 점수 자료 없음')
  // Premiere의 H.264·HEVC 하드웨어 디코딩은 내장 그래픽이 있는 Intel CPU(Quick Sync)가 유리하다.
  const sync = program === 'Premiere Pro' ? hasQuickSync(facts.cpuName) : null
  const quickSync = sync === null ? '' : sync ? ' · Quick Sync 있음(H.264·HEVC 유리)' : ' · Quick Sync 없음(GPU로 디코딩)'
  return { title, known: true, value: parts.join(' · '),
    detail: `기준: ${reference.cpu.replace(/^Core /, '')} · ${reference.gpu.replace(/^GeForce /, '')} (4K 편집 원활, 팀 설정)${quickSync}` }
}

function premiereEstimates(facts: PcFacts, resolution: VideoResolution): WorkloadEstimate[] {
  const result = estimatePremiere(resolution, { cpuScore: facts.cpuScore, gpuRelative: facts.gpuRelative,
    vramGiB: facts.vramGiB, quickSync: hasQuickSync(facts.cpuName) })
  const label = resolution === 'FHD' ? '1080p' : resolution
  return [
    { title: `내보내기 시간 (${label} ${EXPORT_CLIP_SECONDS / 60}분 영상 → H.264)`, value: exportText(result),
      detail: result.exportDetail, known: result.exportLow !== null },
    { title: `타임라인 재생 (${label})`, value: result.playback ? PLAYBACK_TEXT[result.playback] : '추정 불가',
      detail: result.playbackDetail, known: result.playback !== null },
  ]
}

export function diagnoseProgram(program: string, parts: PartInput[], workload: Workload,
  videoResolution: VideoResolution = '4K'): ProgramDiagnosis | null {
  const profile = Object.hasOwn(PROGRAM_PROFILES, program) ? PROGRAM_PROFILES[program] : undefined
  if (!profile) return null
  const facts = pcFacts(parts)
  const levels = [checkLevel('공식 최소', profile.minimum, facts), checkLevel('공식 권장', profile.recommended, facts)]
  if (profile.byResolution) {
    levels.push(checkLevel(profile.byResolution[videoResolution].label, profile.byResolution[videoResolution], facts))
  } else if (profile.comfortable) {
    levels.push(checkLevel(profile.comfortable[workload].label, profile.comfortable[workload], facts))
  }
  const estimates = profile.estimates.flatMap((kind) => kind === 'PREMIERE' ? premiereEstimates(facts, videoResolution)
    : kind === 'BLENDER_RENDER' ? blenderRender(facts)
    : kind === 'COMPILE' ? compileTime(facts) : kind === 'EMULATORS' ? emulators(facts)
      : kind === 'CONTAINERS' ? containers(facts) : videoLevel(program, facts))
  return { profile, levels, estimates }
}
