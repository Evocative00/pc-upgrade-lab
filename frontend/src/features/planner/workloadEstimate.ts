import type { PartInput } from '../pc-scan/types.ts'
import { findDatasetModel, findPartModel } from './benchmarkComparison.ts'
import { CPU_BLENDER_DATASET } from './cpuBlenderScores.ts'
import { GPU_BLENDER_DATASET } from './gpuBlenderScores.ts'
import { GPU_RELATIVE_DATASET } from './gpuRelativeScores.ts'
import type { Workload } from './plannerGoal.ts'
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
  const title = 'Blender 렌더링 (BMW27 장면)'
  const anchor = cpuScoreOf(BLENDER_RENDER_ANCHOR.cpu)
  if (!facts.cpuScore || !anchor) return unknown(title, '내 CPU의 렌더링 점수 자료가 없습니다.')
  // 렌더 시간은 점수(분당 샘플 수)에 반비례한다. CPU와 GPU 점수는 같은 단위다.
  const cpuSeconds = BLENDER_RENDER_ANCHOR.seconds * anchor / facts.cpuScore
  const [cpuLow, cpuHigh] = range(cpuSeconds, 0.25)
  if (!facts.gpuRenderScore) {
    return { title, known: true, value: `CPU 약 ${cpuLow}~${cpuHigh}초`,
      detail: 'GPU 렌더링 자료가 없거나 Blender GPU 렌더링(OptiX·HIP·oneAPI)을 지원하지 않는 GPU라 CPU 렌더링 기준입니다.' }
  }
  const speedup = facts.gpuRenderScore / facts.cpuScore
  const [gpuLow, gpuHigh] = range(cpuSeconds / speedup, 0.25)
  return { title, known: true, value: `GPU 약 ${gpuLow}~${gpuHigh}초 · CPU 약 ${cpuLow}~${cpuHigh}초`,
    detail: `GPU(${facts.gpuModel}) 렌더링이 CPU보다 약 ${speedup.toFixed(1)}배 빠릅니다. 기준: ${BLENDER_RENDER_ANCHOR.cpu} CPU ${BLENDER_RENDER_ANCHOR.seconds}초, 오차 ±25%.` }
}

function compileTime(facts: PcFacts): WorkloadEstimate {
  const title = '코드 컴파일 (리눅스 커널 빌드 기준)'
  if (!facts.cpuScore) return unknown(title, '내 CPU의 멀티코어 점수 자료가 없습니다.')
  const myScore = facts.cpuScore
  const anchors = COMPILE_ANCHORS.map((anchor) => ({ ...anchor, score: cpuScoreOf(anchor.cpu) }))
    .filter((anchor): anchor is typeof anchor & { score: number } => anchor.score !== null)
  if (!anchors.length) return unknown(title, '기준 CPU 자료가 없습니다.')
  // 내 CPU와 점수가 가장 가까운 기준점에서 점수 비율로 환산한다(멀티코어 작업이라 대체로 반비례).
  const nearest = anchors.reduce((best, anchor) =>
    Math.abs(Math.log(anchor.score / myScore)) < Math.abs(Math.log(best.score / myScore)) ? anchor : best)
  const [low, high] = range(nearest.seconds * nearest.score / myScore, 0.3)
  return { title, known: true, value: `약 ${low}~${high}초`,
    detail: `기준: ${nearest.cpu} ${nearest.seconds}초에서 CPU 멀티코어 점수 비율로 환산, 오차 ±30%. 프로젝트 크기에 따라 실제 시간은 크게 달라집니다.` }
}

function emulators(facts: PcFacts): WorkloadEstimate {
  const title = 'Android 에뮬레이터 동시 실행'
  if (facts.ramGiB === null) return unknown(title, 'RAM 용량을 입력하면 계산합니다.')
  const count = Math.max(0, Math.floor((facts.ramGiB - EMULATOR_ASSUMPTION.studioGiB) / EMULATOR_ASSUMPTION.emulatorGiB))
  return { title, known: true, value: count ? `약 ${count}개` : '여유 없음',
    detail: `RAM ${Math.round(facts.ramGiB)}GB 중 Android Studio·Windows ${EMULATOR_ASSUMPTION.studioGiB}GB를 빼고 공식 안내의 에뮬레이터 1개당 ${EMULATOR_ASSUMPTION.emulatorGiB}GB로 나눈 값입니다.` }
}

function containers(facts: PcFacts): WorkloadEstimate {
  const title = 'Docker 컨테이너·가상 머신'
  if (facts.ramGiB === null) return unknown(title, 'RAM 용량을 입력하면 계산합니다.')
  const dockerGiB = facts.ramGiB * CONTAINER_ASSUMPTION.dockerShare
  const light = Math.floor(dockerGiB / CONTAINER_ASSUMPTION.lightContainerGiB)
  const vms = Math.max(0, Math.floor((facts.ramGiB - CONTAINER_ASSUMPTION.hostReserveGiB) / CONTAINER_ASSUMPTION.vmGiB))
  return { title, known: true, value: `가벼운 컨테이너 약 ${light}개 · 가상 머신 약 ${vms}대`,
    detail: `Docker 기본 할당(RAM의 50%, ${Math.round(dockerGiB)}GB)에 컨테이너 1개 ${CONTAINER_ASSUMPTION.lightContainerGiB}GB, `
      + `VM 1대 ${CONTAINER_ASSUMPTION.vmGiB}GB(Windows용 ${CONTAINER_ASSUMPTION.hostReserveGiB}GB 제외)를 가정한 값입니다.`
      + (facts.threads ? ` CPU 논리 프로세서 ${facts.threads}개를 나눠 씁니다.` : facts.cores ? ` CPU 코어 ${facts.cores}개를 나눠 씁니다.` : '') }
}

// 영상 편집의 성능 수준. 팀이 정한 '4K 편집 원활' 기준 부품 대비 배율이다(공식 기준 아님).
const VIDEO_REFERENCE: Record<string, { cpu: string; gpu: string }> = {
  'Premiere Pro': { cpu: 'Core i7-12700', gpu: 'GeForce RTX 3060 12GB' },
  'DaVinci Resolve': { cpu: 'Core i5-12400', gpu: 'GeForce RTX 4070' },
}

function videoLevel(program: string, facts: PcFacts): WorkloadEstimate {
  const title = '편집 성능 수준 (4K 편집 원활 기준 대비)'
  const reference = VIDEO_REFERENCE[program]
  const refCpu = cpuScoreOf(reference.cpu)
  const refGpuName = findDatasetModel(reference.gpu, GPU_RELATIVE_DATASET)
  const refGpu = refGpuName ? GPU_RELATIVE_DATASET.scores[refGpuName] : undefined
  const parts: string[] = []
  if (facts.cpuScore && refCpu) parts.push(`CPU ${reference.cpu}의 ${(facts.cpuScore / refCpu).toFixed(2)}배`)
  if (facts.gpuRelative && refGpu) parts.push(`GPU ${reference.gpu}의 ${(facts.gpuRelative / refGpu).toFixed(2)}배`)
  if (!parts.length) return unknown(title, 'CPU·GPU 성능 자료가 없습니다.')
  // Premiere의 H.264·HEVC 하드웨어 디코딩은 내장 그래픽이 있는 Intel CPU(Quick Sync)가 유리하다.
  const quickSync = program === 'Premiere Pro' && facts.cpuName
    ? /intel|core/i.test(facts.cpuName) && !/\d{4,5}K?F\b/i.test(facts.cpuName)
      ? ' Intel 내장 그래픽(Quick Sync)으로 H.264·HEVC 디코딩이 유리합니다.'
      : ' Quick Sync가 없어 H.264·HEVC 디코딩은 GPU(NVENC 등)에 의존합니다.' : ''
  return { title, known: true, value: parts.join(' · '),
    detail: `팀이 정한 '4K 편집 원활' 기준 부품 대비 배율입니다(CPU는 Blender 멀티코어, GPU는 게임 성능 상대값으로 대신 비교).${quickSync}` }
}

export function diagnoseProgram(program: string, parts: PartInput[], workload: Workload): ProgramDiagnosis | null {
  const profile = Object.hasOwn(PROGRAM_PROFILES, program) ? PROGRAM_PROFILES[program] : undefined
  if (!profile) return null
  const facts = pcFacts(parts)
  const levels = [checkLevel('공식 최소', profile.minimum, facts), checkLevel('공식 권장', profile.recommended, facts)]
  if (profile.comfortable) levels.push(checkLevel(profile.comfortable[workload].label, profile.comfortable[workload], facts))
  const estimates = profile.estimates.map((kind) => kind === 'BLENDER_RENDER' ? blenderRender(facts)
    : kind === 'COMPILE' ? compileTime(facts) : kind === 'EMULATORS' ? emulators(facts)
      : kind === 'CONTAINERS' ? containers(facts) : videoLevel(program, facts))
  return { profile, levels, estimates }
}
