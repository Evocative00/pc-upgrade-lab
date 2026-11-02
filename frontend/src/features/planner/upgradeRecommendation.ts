import type { PartInput } from '../pc-scan/types.ts'
import { findDatasetModel, findPartModel, type BenchmarkDataset } from './benchmarkComparison.ts'
import { CPU_GAMING_DATASET } from './cpuGamingScores.ts'
import { estimateGameFrames } from './frameEstimate.ts'
import { diagnoseGame } from './gameDiagnosis.ts'
import type { FrameResolution } from './gameFrameData.ts'
import { GAME_REQUIREMENTS } from './gameRequirements.ts'
import { comparePartScore, SCORE_DATASETS } from './partScores.ts'
import type { UpgradeGoal } from './plannerGoal.ts'
import { PROGRAM_PROFILES, type Requirement } from './workloadData.ts'
import { pcFacts } from './workloadEstimate.ts'

/**
 * 업그레이드 플랜: 목표에 부족한 부품을 한 예산 안에서 함께 바꾸고, 나머지 부품은 그대로 둔 최종 구성을 만든다. 모두 추정이다.
 * 성능 우선: 부족한 부품을 최대한 채운 뒤, 예산 안에서 성능 향상이 가장 큰 조합.
 * 예산 위주: 부족한 부품을 최대한 채우는 조합 중 가장 싼 조합.
 * CPU는 게임 목표면 게임 점수(Tom's Hardware 1080p), 작업 목표면 Blender 점수를 쓴다.
 * GPU는 게임 성능 상대값, RAM은 용량을 점수로 쓴다.
 * 지금 살 수 있는 판매가 제품을 먼저 고르고, 참고 추정가 제품은 그 종류에 판매가 후보가 없을 때만 고른다.
 * 데스크톱에서 메인보드는 그대로 두므로 CPU 소켓·RAM 규격이 현재 구성과 다른 제품은 뺀다.
 */
export type RecommendPriority = 'PERFORMANCE' | 'BUDGET'
export type UpgradePartKind = 'CPU' | 'GPU' | 'RAM'
// GAME: 게임 1080p 성능, WORK: 렌더링·컴파일 같은 멀티코어 작업 성능
export type CpuMetric = 'GAME' | 'WORK'

export const PRIORITY_LABEL: Record<RecommendPriority, string> = { PERFORMANCE: '성능 우선', BUDGET: '예산 위주' }
export const UPGRADE_PART_LABEL: Record<UpgradePartKind, string> = { CPU: 'CPU', GPU: '그래픽카드', RAM: '메모리' }
export const UPGRADE_PART_KINDS: UpgradePartKind[] = ['CPU', 'GPU', 'RAM']

export const isRecommendPriority = (value: unknown): value is RecommendPriority =>
  value === 'PERFORMANCE' || value === 'BUDGET'
export const isUpgradePartKind = (value: unknown): value is UpgradePartKind =>
  value === 'CPU' || value === 'GPU' || value === 'RAM'

export type PricedProduct = {
  id: string
  name: string
  priceKrw: number
  // CURRENT: 판매처 현재가, ESTIMATE: 과거 판매가·출시가 등으로 만든 참고 추정가
  priceKind: 'CURRENT' | 'ESTIMATE'
  priceSource: string | null
  priceUrl: string | null
}

export type ScoredProduct = PricedProduct & {
  score: number
  // 현재 부품 대비 배율. 현재 점수를 모르면 null.
  ratio: number | null
  vramGiB: number | null
  // 목표 기준을 넘는지. 목표가 없으면 null.
  meetsTarget: boolean | null
}

/** 부품 종류별 목표. needed: 지금 부품이 목표에 못 미치는지(모르면 null). */
export type KindTarget = { minScore: number | null; minVramGiB: number | null; needed: boolean | null }

export type PlanItem = {
  kind: UpgradePartKind
  currentLabel: string
  target: KindTarget
  choice: ScoredProduct | null
  // 교체(또는 유지) 후 목표 충족 여부. 목표가 없거나 모르면 null.
  meets: boolean | null
  // 유지할 때의 설명
  note: string | null
}

export type UpgradePlan = {
  priority: RecommendPriority
  items: PlanItem[]
  totalKrw: number
  budgetWon: number
  reason: string
  // 부족한 부품을 모두 채우는 가장 싼 조합이 예산을 넘을 때 그 금액
  fullFixKrw: number | null
}

export type PlanInput = {
  parts: PartInput[]
  productsByKind: Partial<Record<UpgradePartKind, PricedProduct[]>>
  kinds: UpgradePartKind[]
  targets: Partial<Record<UpgradePartKind, KindTarget>>
  priority: RecommendPriority
  budgetWon: number
  cpuMetric: CpuMetric
  // 호환성 검사에서 맞지 않은 제품 등 후보에서 뺄 카탈로그 상품 ID
  excludedIds?: string[]
}

// 이만큼은 빨라야 교체 의미가 있다고 본다.
const MIN_GAIN = 1.1

const CHIPSET_SOCKETS: [RegExp, string][] = [
  [/\b(?:a320|b350|x370|b450|x470|a520|b550|x570)\b/i, 'AM4'],
  [/\b(?:a620|b650e?|x670e?|b840|b850|x870e?)\b/i, 'AM5'],
  [/\b(?:h610|b660|h670|z690|b760|h770|z790)\b/i, 'LGA1700'],
  [/\b(?:h410|b460|h470|z490|h510|b560|h570|z590)\b/i, 'LGA1200'],
  [/\b(?:h310|b360|b365|h370|z370|z390)\b/i, 'LGA1151-2'],
  [/\b(?:h110|b150|h170|z170|b250|h270|z270)\b/i, 'LGA1151'],
  [/\b(?:b860|z890)\b/i, 'LGA1851'],
]

/** CPU 이름에서 데스크톱 소켓을 추정한다. 모르면 null. */
export function cpuSocket(name: string): string | null {
  const ryzen = name.match(/ryzen\s+(?:threadripper\s+)?[3579]\s+(\d)(\d{3})/i)
  if (ryzen) {
    const series = Number(ryzen[1])
    if (series >= 1 && series <= 5) return 'AM4'
    if (series >= 7) return 'AM5'
    return null
  }
  if (/core\s*(?:\(tm\)\s*)?ultra\s+[579]\s+2\d{2}/i.test(name)) return 'LGA1851'
  const core = name.match(/\bi[3579]-(\d{4,5})/i)
  if (!core) return null
  const digits = core[1]
  const generation = digits.length === 5 ? Number(digits.slice(0, 2)) : Number(digits[0])
  if (generation >= 12 && generation <= 14) return 'LGA1700'
  if (generation === 10 || generation === 11) return 'LGA1200'
  if (generation === 8 || generation === 9) return 'LGA1151-2'
  if (generation === 6 || generation === 7) return 'LGA1151'
  return null
}

/** 메인보드 칩셋을 먼저 보고, 없으면 현재 CPU 이름으로 소켓을 추정한다. */
export function currentSocket(parts: PartInput[]): string | null {
  for (const board of parts.filter((part) => part.type === 'MOTHERBOARD')) {
    const hit = CHIPSET_SOCKETS.find(([pattern]) => pattern.test(board.displayName))
    if (hit) return hit[1]
  }
  for (const cpu of parts.filter((part) => part.type === 'CPU')) {
    const socket = cpuSocket(cpu.displayName)
    if (socket) return socket
  }
  return null
}

export function ddrGeneration(name: string): number | null {
  const match = name.match(/\bDDR([345])\b/i) ?? name.match(/DDR([345])/i)
  return match ? Number(match[1]) : null
}

function currentDdr(parts: PartInput[]): number | null {
  for (const ram of parts.filter((part) => part.type === 'RAM')) {
    const generation = ddrGeneration(ram.displayName)
    if (generation) return generation
  }
  return null
}

// 판매명 '32GB (2x16GB)'의 앞쪽 총용량.
export function ramKitGiB(name: string): number | null {
  const match = name.match(/\b(\d{1,3})\s*GB\b/i)
  const value = match ? Number(match[1]) : NaN
  return value >= 4 && value <= 256 ? value : null
}

// 판매명·Windows 이름의 VRAM 표기(16G, 12GB).
export function vramFromName(name: string): number | null {
  const match = name.match(/\b(\d{1,2})\s*G(?:B)?\b/i)
  const value = match ? Number(match[1]) : NaN
  return value >= 1 && value <= 48 ? value : null
}

function datasetFor(kind: 'CPU' | 'GPU', cpuMetric: CpuMetric): BenchmarkDataset {
  return kind === 'CPU' && cpuMetric === 'GAME' ? CPU_GAMING_DATASET : SCORE_DATASETS[kind]
}

export function productScore(kind: UpgradePartKind, name: string, cpuMetric: CpuMetric = 'WORK'): number | null {
  if (kind === 'RAM') return ramKitGiB(name)
  const dataset = datasetFor(kind, cpuMetric)
  const model = findPartModel(name, dataset)
  return model ? dataset.scores[model] ?? null : null
}

export function currentPartScore(kind: UpgradePartKind, parts: PartInput[], cpuMetric: CpuMetric = 'WORK'): number | null {
  if (kind === 'RAM') return pcFacts(parts).ramGiB
  const scores = parts.filter((part) => part.type === kind)
    .map((part) => productScore(kind, part.displayName, cpuMetric)).filter((score): score is number => score !== null)
  return scores.length ? Math.max(...scores) : null
}

function compatible(kind: UpgradePartKind, product: PricedProduct, socket: string | null, ddr: number | null): boolean {
  if (kind === 'CPU' && socket) return cpuSocket(product.name) === socket
  if (kind === 'RAM' && ddr) return ddrGeneration(product.name) === ddr
  return true
}

function meetsTarget(target: KindTarget | undefined, score: number, vramGiB: number | null): boolean | null {
  if (!target || target.minScore === null && target.minVramGiB === null) return null
  const scoreOk = target.minScore === null || score >= target.minScore
  const vramOk = target.minVramGiB === null || (vramGiB !== null && vramGiB >= target.minVramGiB)
  return scoreOk && vramOk
}

type KindCandidates = { list: ScoredProduct[]; incompatible: number }

/** 한 종류의 교체 후보. 호환·점수·향상 폭을 거른 뒤, 판매가 제품이 있으면 그것만 남기고 가격 대비 우위 제품만 둔다. */
function candidatesFor(kind: UpgradePartKind, input: PlanInput): KindCandidates {
  const { parts, cpuMetric } = input
  const target = input.targets[kind]
  const currentScore = currentPartScore(kind, parts, cpuMetric)
  const socket = kind === 'CPU' ? currentSocket(parts) : null
  const ddr = kind === 'RAM' ? currentDdr(parts) : null
  let incompatible = 0
  const scored: ScoredProduct[] = []
  const excluded = new Set(input.excludedIds ?? [])
  for (const product of input.productsByKind[kind] ?? []) {
    if (excluded.has(product.id)) continue
    if (!compatible(kind, product, socket, ddr)) { incompatible += 1; continue }
    const score = productScore(kind, product.name, cpuMetric)
    if (score === null) continue
    if (currentScore !== null && score < currentScore * (kind === 'RAM' ? 1.01 : MIN_GAIN)) continue
    const vramGiB = kind === 'GPU' ? vramFromName(product.name) : null
    scored.push({ ...product, score, vramGiB, ratio: currentScore ? score / currentScore : null,
      meetsTarget: meetsTarget(target, score, vramGiB) })
  }
  const buyable = scored.filter((product) => product.priceKind === 'CURRENT')
  const pool = (buyable.length ? buyable : scored).sort((a, b) => a.priceKrw - b.priceKrw || b.score - a.score)
  // 더 싼 제품보다 (목표 충족, 점수)가 나아지는 제품만 남긴다. 조합 수를 줄인다.
  const list: ScoredProduct[] = []
  let best = -Infinity
  for (const product of pool) {
    const key = (product.meetsTarget ? 1e6 : 0) + product.score
    if (key > best) { list.push(product); best = key }
  }
  return { list: list.slice(-30), incompatible }
}

type Combo = { choices: (ScoredProduct | null)[]; total: number; met: number; gain: number }


export function buildUpgradePlan(input: PlanInput): UpgradePlan {
  const { parts, kinds, priority, budgetWon } = input
  const kindData = kinds.map((kind) => ({ kind, target: input.targets[kind], ...candidatesFor(kind, input) }))
  const neededMet = (index: number, choice: ScoredProduct | null) =>
    kindData[index].target?.needed === true && choice?.meetsTarget === true ? 1 : 0

  // 종류별 후보(유지 포함)의 모든 조합. 종류 3개·후보 30개 이하라 계산량이 작다.
  let combos: Combo[] = [{ choices: [], total: 0, met: 0, gain: 0 }]
  kindData.forEach((data, index) => {
    combos = combos.flatMap((combo) => [null, ...data.list].map((choice) => ({
      choices: [...combo.choices, choice],
      total: combo.total + (choice?.priceKrw ?? 0),
      met: combo.met + neededMet(index, choice),
      gain: combo.gain + (choice ? Math.log(choice.ratio ?? 1.2) : 0),
    })))
  })
  const neededCount = kindData.filter((data) => data.target?.needed === true).length
  const compare = (a: Combo, b: Combo) => b.met - a.met || (priority === 'PERFORMANCE'
    ? b.gain - a.gain || a.total - b.total
    : a.total - b.total || b.gain - a.gain)
  const best = combos.filter((combo) => combo.total <= budgetWon).sort(compare)[0] ?? combos[0]
  const fullFix = neededCount
    ? combos.filter((combo) => combo.met === neededCount).sort((a, b) => a.total - b.total)[0] : undefined

  const items: PlanItem[] = kindData.map((data, index) => {
    const choice = best.choices[index] ?? null
    const target = data.target ?? { minScore: null, minVramGiB: null, needed: null }
    const currentLabel = parts.filter((part) => part.type === data.kind).map((part) => part.displayName).join(', ') || '입력 없음'
    const note = choice ? null : data.list.length === 0
      ? data.incompatible ? '같은 메인보드에 맞는 더 나은 제품 없음' : '더 나은 제품 없음'
      : target.needed === true ? '예산 부족으로 유지' : null
    return { kind: data.kind, currentLabel, target, choice,
      meets: choice ? choice.meetsTarget : target.needed === null ? null : !target.needed, note }
  })

  const changed = items.filter((item) => item.choice).length
  const reason = changed === 0
    ? kinds.length === 0 ? '바꿀 부품을 골라 주세요.'
      : neededCount === 0 ? '선택한 부품은 이미 목표를 충족합니다.'
        : items.some((item) => item.note === '예산 부족으로 유지') ? '예산 안에서 바꿀 수 있는 부품이 없습니다.'
          : '지금보다 나은 호환 제품이 없습니다.'
    : best.met < neededCount ? `부족한 부품 ${neededCount}개 중 ${best.met}개 해결.`
      : '부족한 부품을 모두 해결합니다.'
  return { priority, items, totalKrw: best.total, budgetWon, reason,
    fullFixKrw: fullFix && fullFix.total > budgetWon ? fullFix.total : null }
}

/**
 * 게임 목표에 필요한 점수. CPU는 공식 권장 예시의 게임 점수, GPU는 권장 예시 점수와 목표 FPS에 필요한 배율(현재 GPU × 배율) 중
 * 높은 값을 쓴다. 게임마다 가장 쉬운 품질 단계 기준이며, 모르면 null.
 */
export function gameTargetScore(kind: UpgradePartKind, parts: PartInput[], games: string[],
  resolution: FrameResolution, targetFps: number): number | null {
  const targets: number[] = []
  for (const name of games) {
    const game = Object.hasOwn(GAME_REQUIREMENTS, name) ? GAME_REQUIREMENTS[name] : undefined
    if (!game) continue
    if (kind === 'RAM') { targets.push(game.recommended.ramGiB); continue }
    if (kind === 'CPU') {
      // 공식 권장 예시(Intel 또는 AMD)는 서로 대체 가능한 예라서 게임 점수가 낮은 쪽을 기준으로 둔다.
      const known = game.recommended.cpu.map((model) => findDatasetModel(model, CPU_GAMING_DATASET))
        .filter((model): model is string => model !== null).map((model) => CPU_GAMING_DATASET.scores[model])
      if (known.length) targets.push(Math.min(...known))
      continue
    }
    const baseline = comparePartScore(kind, parts, game.recommended.gpu).baselineScore
    if (baseline !== null) targets.push(baseline)
    const current = currentPartScore('GPU', parts)
    const multipliers = estimateGameFrames(parts, name, resolution, targetFps)
      .filter((estimate) => estimate.status !== 'UNKNOWN' && !estimate.cpuLimited)
      .map((estimate) => estimate.requiredGpuMultiplier ?? 1)
    if (current !== null && multipliers.length) targets.push(current * Math.min(...multipliers))
  }
  return targets.length ? Math.max(...targets) : null
}

const maxOf = (values: (number | undefined)[]) => {
  const known = values.filter((value): value is number => typeof value === 'number')
  return known.length ? Math.max(...known) : null
}

/** 목표별 부품 기준. 게임은 권장 사양·목표 FPS, 작업은 프로그램의 가장 높은 단계(작업 규모·편집 해상도) 기준. */
export function planTargets(parts: PartInput[], goal: UpgradeGoal): Partial<Record<UpgradePartKind, KindTarget>> {
  const facts = pcFacts(parts)
  // 기준이 없거나 지금 값을 모르면 null.
  const needed = (current: number | null, min: number | null) => min === null || current === null ? null : current < min
  const anyNeeded = (checks: (boolean | null)[]) => checks.includes(true) ? true
    : checks.length && checks.every((check) => check === false) ? false : null
  if (goal.purpose === 'GAME') {
    const cpu = gameTargetScore('CPU', parts, goal.games, goal.resolution, goal.targetFps)
    const gpu = gameTargetScore('GPU', parts, goal.games, goal.resolution, goal.targetFps)
    const ram = gameTargetScore('RAM', parts, goal.games, goal.resolution, goal.targetFps)
    const vram = maxOf(goal.games.map((name) =>
      Object.hasOwn(GAME_REQUIREMENTS, name) ? GAME_REQUIREMENTS[name].recommended.vramGiB : undefined))
    // 3단계 진단에서 권장 사양 '부족'으로 나온 항목은 추천 플랜에서도 반드시 부족으로 본다.
    // (진단은 공식 예시와 Blender 점수, 플랜은 게임 점수·목표 FPS를 쓰므로 둘 중 하나라도 부족이면 바꿀 대상이다.)
    const failed = new Set(goal.games.flatMap((name) => {
      const game = Object.hasOwn(GAME_REQUIREMENTS, name) ? GAME_REQUIREMENTS[name] : undefined
      return game ? diagnoseGame(parts, game).recommended.checks.filter((check) => check.verdict === 'FAIL').map((check) => check.label) : []
    }))
    const gpuChecks = [
      ...gpu === null ? [] : [needed(currentPartScore('GPU', parts), gpu)],
      ...vram === null ? [] : [needed(facts.vramGiB, vram)],
      ...failed.has('GPU') || failed.has('VRAM') ? [true] : [],
    ]
    return {
      CPU: { minScore: cpu, minVramGiB: null,
        needed: failed.has('CPU') ? true : needed(currentPartScore('CPU', parts, 'GAME'), cpu) },
      GPU: { minScore: gpu, minVramGiB: vram, needed: anyNeeded(gpuChecks) },
      RAM: { minScore: ram, minVramGiB: null, needed: failed.has('RAM') ? true : needed(facts.ramGiB, ram) },
    }
  }
  if (goal.purpose === 'CREATION' || goal.purpose === 'DEVELOPMENT') {
    const levels: Requirement[] = goal.programs.flatMap((program) => {
      const profile = Object.hasOwn(PROGRAM_PROFILES, program) ? PROGRAM_PROFILES[program] : undefined
      if (!profile) return []
      return [profile.recommended, profile.byResolution?.[goal.videoResolution] ?? profile.comfortable?.[goal.workload] ?? {}]
    })
    const ram = maxOf(levels.map((level) => level.ramGiB))
    const vram = maxOf(levels.map((level) => level.vramGiB))
    const cores = maxOf(levels.map((level) => level.cores))
    return {
      // 판매명에서 코어 수를 알 수 없어 CPU는 코어 부족 여부만 보고, 후보는 작업 성능(Blender 점수)으로 고른다.
      CPU: { minScore: null, minVramGiB: null, needed: needed(facts.cores, cores) },
      GPU: { minScore: null, minVramGiB: vram, needed: needed(facts.vramGiB, vram) },
      RAM: { minScore: ram, minVramGiB: null, needed: needed(facts.ramGiB, ram) },
    }
  }
  return {}
}

const GIB = 1024 ** 3

/**
 * 한 종류의 부품을 후보 제품으로 바꾼 가상 구성. 같은 종류의 기존 부품(RAM 여러 개 등)은 모두 빠진다.
 * 카탈로그 상품 ID를 주면 연결된 부품으로 두어 호환성 검사에 쓸 수 있다.
 */
export function withReplacedPart(parts: PartInput[], kind: UpgradePartKind, name: string, productId: string | null = null): PartInput[] {
  const index = parts.findIndex((part) => part.type === kind)
  const capacity = kind === 'RAM' ? ramKitGiB(name) : null
  const replacement: PartInput = { type: kind, displayName: name, rawName: null, quantity: 1, source: 'MANUAL',
    catalogProductId: productId, matchStatus: productId ? 'MATCHED' : 'UNMATCHED',
    specs: capacity ? { capacityBytes: capacity * GIB } : {} }
  const others = parts.filter((part) => part.type !== kind)
  return index < 0 ? [...parts, replacement] : [...others.slice(0, index), replacement, ...others.slice(index)]
}

/** 플랜의 교체 부품을 모두 반영한 최종 구성. */
export function applyPlan(parts: PartInput[], plan: UpgradePlan): PartInput[] {
  return plan.items.reduce((next, item) => item.choice ? withReplacedPart(next, item.kind, item.choice.name, item.choice.id) : next, parts)
}
