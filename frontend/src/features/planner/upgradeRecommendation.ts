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
import type { CatalogSpecification } from '../catalog/catalogTypes.ts'

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
  // CURRENT: 판매처 현재가, PAST: 기간이 지난 판매처 관측가(지난 판매가), ESTIMATE: 비슷한 제품·출시가 등으로 만든 참고 추정가
  priceKind: 'CURRENT' | 'PAST' | 'ESTIMATE'
  // 판매처 관측 시각(CURRENT·PAST)
  observedAt?: string | null
  priceSource: string | null
  priceUrl: string | null
  specification?: CatalogSpecification
  attributions?: import('../catalog/catalogTypes.ts').CatalogAttribution[]
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
export type KindTarget = { minScore: number | null; minVramGiB: number | null; needed: boolean | null;
  minCores?: number | null; games?: string[] }

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

// 추천 안 3개: 가성비(가장 싸게 목표 달성) · 추천(가격 대비 향상이 좋은 조합) · 최고 성능(예산 안 최대 향상)
export type PlanOption = 'VALUE' | 'BALANCED' | 'BEST'
export const PLAN_OPTIONS: PlanOption[] = ['VALUE', 'BALANCED', 'BEST']
export const PLAN_OPTION_LABEL: Record<PlanOption, string> = { VALUE: '가성비', BALANCED: '추천', BEST: '최고 성능' }
export const isPlanOption = (value: unknown): value is PlanOption => value === 'VALUE' || value === 'BALANCED' || value === 'BEST'

export type UpgradePlan = {
  option: PlanOption
  items: PlanItem[]
  totalKrw: number
  budgetWon: number
  reason: string
  // 부족한 부품을 모두 채우는 가장 싼 조합이 예산을 넘을 때 그 금액
  fullFixKrw: number | null
  // 사용자가 부품을 직접 바꿨는지
  custom: boolean
}

// 부품 종류별 직접 고른 카탈로그 상품 ID. KEEP이면 지금 부품을 유지한다.
export const KEEP = 'KEEP'
export type PlanOverrides = Partial<Record<UpgradePartKind, string>>
export const isPlanOverrides = (value: unknown): value is PlanOverrides =>
  typeof value === 'object' && value !== null && !Array.isArray(value) && Object.entries(value)
    .every(([key, id]) => isUpgradePartKind(key) && typeof id === 'string' && id.trim().length > 0 && id.length <= 128)

export type UpgradeOptions = {
  plans: Record<PlanOption, UpgradePlan>
  // 예산 안에서 향상이 가장 큰 조합(최고 성능 안은 예산을 넘을 수 있다). 이전 '성능 우선' 호환용.
  bestWithinBudget: UpgradePlan
  // 예산 안에서 가장 싼 조합. 이전 '예산 위주' 호환용.
  cheapestWithinBudget: UpgradePlan
  // 종류별 교체 후보(가격 오름차순). '다른 제품'에서 고를 수 있다.
  candidates: Partial<Record<UpgradePartKind, ScoredProduct[]>>
  neededCount: number
  // 부족한 부품 하나라도 해결하는 가장 싼 제품 가격. 예산이 모자랄 때 안내에 쓴다.
  cheapestFixKrw: number | null
}

export type PlanInput = {
  parts: PartInput[]
  productsByKind: Partial<Record<UpgradePartKind, PricedProduct[]>>
  kinds: UpgradePartKind[]
  targets: Partial<Record<UpgradePartKind, KindTarget>>
  budgetWon: number
  cpuMetric: CpuMetric
  // 호환성 검사에서 맞지 않은 제품 등 후보에서 뺄 카탈로그 상품 ID
  excludedIds?: string[]
}

// 최고 성능 안이 넘을 수 있는 예산 배율
export const BEST_BUDGET_RATIO = 1.5

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
  if (/threadripper|\b\d{3,5}(?:HX|HS|H|U|P|V)\b/i.test(name)) return null
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
    const generation = ddrGeneration(String(ram.specs.memoryType ?? '')) ?? ddrGeneration(ram.displayName)
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
  if (kind === 'CPU') return socket !== null && (product.specification?.socketCode ?? cpuSocket(product.name)) === socket
  if (kind === 'RAM') return ddr !== null &&
    (ddrGeneration(String(product.specification?.memoryType ?? '')) ?? ddrGeneration(product.name)) === ddr
  return true
}

function meetsTarget(target: KindTarget | undefined, score: number, vramGiB: number | null,
  kind: UpgradePartKind, product: PricedProduct, parts: PartInput[]): boolean | null {
  if (!target) return null
  const checks: (boolean | null)[] = []
  if (target.minScore !== null) checks.push(score >= target.minScore)
  if (target.minVramGiB !== null) checks.push(vramGiB === null ? null : vramGiB >= target.minVramGiB)
  if (target.minCores != null) {
    const cores = product.specification?.coreCount
    checks.push(typeof cores === 'number' ? cores >= target.minCores : null)
  }
  const after = replaceWithProduct(parts, kind, product)
  for (const name of target.games ?? []) {
    const game = GAME_REQUIREMENTS[name]
    const labels = kind === 'GPU' ? ['GPU', 'VRAM'] : [kind]
    checks.push(...diagnoseGame(after, game).recommended.checks.filter((check) => labels.includes(check.label))
      .map((check) => check.verdict === 'UNKNOWN' ? null : check.verdict === 'PASS'))
  }
  return checks.includes(false) ? false : checks.length && checks.every((check) => check === true) ? true : null
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
    const ram = kind === 'RAM' ? ramModules(product.specification) : null
    if (kind === 'RAM' && !ram) continue
    const score = ram ? ram.quantity * ram.capacityBytes / GIB : productScore(kind, product.name, cpuMetric)
    if (score === null) continue
    const vramBytes = product.specification?.vramBytes
    const vramGiB = kind === 'GPU' ? typeof vramBytes === 'number' ? vramBytes / GIB : vramFromName(product.name) : null
    const meets = meetsTarget(target, score, vramGiB, kind, product, parts)
    // 용량·코어 부족을 해결하는 교체는 점수 상승 폭이 작아도 남긴다.
    if (currentScore !== null && score < currentScore * (kind === 'RAM' ? 1.01 : MIN_GAIN) &&
      !(target?.needed === true && meets === true && score >= currentScore)) continue
    scored.push({ ...product, score, vramGiB, ratio: currentScore ? score / currentScore : null,
      meetsTarget: meets })
  }
  // 실제 판매처에서 관측한 가격(현재가·지난 판매가)이 있는 제품을 먼저 쓰고, 없을 때만 계산한 참고 추정가 제품을 쓴다.
  // 추정가는 단종 제품도 싸게 계산되는 경우가 있어(예: RTX 3080) 실제 가격 제품과 섞으면 항상 추천되는 문제가 있었다.
  const buyable = scored.filter((product) => product.priceKind !== 'ESTIMATE')
  const pool = (buyable.length ? buyable : scored).sort((a, b) => a.priceKrw - b.priceKrw || b.score - a.score)
  // 가격·목표 충족·성능 모두 열등한 후보만 제외한다. 저렴한 후보도 끝까지 보존한다.
  const list: ScoredProduct[] = []
  for (const product of pool) {
    if (!list.some((other) => other.priceKrw <= product.priceKrw && other.score >= product.score &&
      Number(other.meetsTarget === true) >= Number(product.meetsTarget === true))) list.push(product)
  }
  return { list, incompatible }
}

type Combo = { choices: (ScoredProduct | null)[]; total: number; met: number; gain: number }
type KindData = KindCandidates & { kind: UpgradePartKind; target: KindTarget | undefined }

const NO_TARGET: KindTarget = { minScore: null, minVramGiB: null, needed: null }

function itemsOf(parts: PartInput[], kindData: KindData[], choices: (ScoredProduct | null)[]): PlanItem[] {
  return kindData.map((data, index) => {
    const choice = choices[index] ?? null
    const target = data.target ?? NO_TARGET
    const currentLabel = parts.filter((part) => part.type === data.kind).map((part) => part.displayName).join(', ') || '입력 없음'
    const note = choice ? null : data.list.length === 0
      ? data.incompatible ? '같은 메인보드에 맞는 더 나은 제품 없음' : '더 나은 제품 없음'
      : target.needed === true ? data.list.some((candidate) => candidate.meetsTarget === true)
        ? '예산 부족으로 유지' : '현재 후보로 목표 기준 충족 확인 불가' : null
    return { kind: data.kind, currentLabel, target, choice,
      meets: choice ? choice.meetsTarget : target.needed === null ? null : !target.needed, note }
  })
}

function reasonOf(items: PlanItem[], neededCount: number): string {
  const met = items.filter((item) => item.target.needed === true && item.choice && item.meets === true).length
  if (!items.some((item) => item.choice)) {
    return items.length === 0 ? '바꿀 부품이 없습니다.'
      : neededCount === 0 ? items.every((item) => item.target.needed === false)
        ? '지금 구성으로 목표를 충족합니다.' : '판단에 필요한 부품·목표 자료를 확인해 주세요.'
        : items.some((item) => item.note === '예산 부족으로 유지') ? '예산 안에서 바꿀 수 있는 부품이 없습니다.'
          : '목표 기준을 충족하는 제품을 확인하지 못했습니다.'
  }
  return neededCount === 0 ? '목표는 이미 충족하며, 더 빠르게 하는 참고 구성입니다.'
    : met < neededCount ? `부족한 부품 ${neededCount}개 중 ${met}개 해결` : '부족한 부품을 모두 해결합니다.'
}

/**
 * 추천 안 3개. 모두 예산 안에서 부족한 부품을 가장 많이 해결하는 조합 중에서 고른다.
 * 가성비: 예산 안에서 1원당 성능 향상이 가장 큰 조합(가격 대비 성능).
 * 추천: 예산 안에서 성능 향상이 가장 큰 조합. 최고 성능: 예산의 1.5배 안에서 성능 향상이 가장 큰 조합.
 */
export function buildUpgradeOptions(input: PlanInput): UpgradeOptions {
  const { parts, budgetWon } = input
  const kinds = UPGRADE_PART_KINDS.filter((kind) => input.kinds.includes(kind))
  const kindData: KindData[] = kinds.map((kind) => ({ kind, target: input.targets[kind], ...candidatesFor(kind, input) }))
  const neededMet = (index: number, choice: ScoredProduct | null) =>
    kindData[index].target?.needed === true && choice?.meetsTarget === true ? 1 : 0

  // 각 단계에서 한도를 넘은 조합은 버리고, 해결 개수별로 '더 비싼데 향상이 같거나 작은' 조합도 버린다.
  // 남는 조합은 해결 개수별 가격-향상 경계선이라 안을 모두 여기서 고를 수 있다.
  const frontier = (limit: number) => {
    let combos: Combo[] = [{ choices: [], total: 0, met: 0, gain: 0 }]
    kindData.forEach((data, index) => {
      // 현재 점수가 없으면 후보끼리만 상대 비교한다. 현재 대비 임의의 배율을 만들지 않는다.
      const candidateBase = Math.min(...data.list.map((choice) => choice.score))
      // RAM 용량은 목표를 넘으면 체감 성능이 거의 늘지 않아 향상 계산에서는 1.25배까지만 친다.
      const gainOf = (choice: ScoredProduct) =>
        Math.log(Math.min(choice.ratio ?? choice.score / candidateBase, data.kind === 'RAM' ? 1.25 : Infinity))
      const next = combos.flatMap((combo) => [null, ...data.list].map((choice) => ({
        choices: [...combo.choices, choice],
        total: combo.total + (choice?.priceKrw ?? 0),
        met: combo.met + neededMet(index, choice),
        gain: combo.gain + (choice ? gainOf(choice) : 0),
      }))).filter((combo) => combo.total <= limit).sort((a, b) => a.total - b.total || b.gain - a.gain)
      const bestGains = new Map<number, number>()
      combos = next.filter((combo) => {
        const bestGain = bestGains.get(combo.met)
        if (bestGain !== undefined && bestGain >= combo.gain) return false
        bestGains.set(combo.met, combo.gain)
        return true
      })
    })
    // 부족한 부품을 가장 많이 해결하는 조합만 비교한다.
    const topMet = Math.max(0, ...combos.map((combo) => combo.met))
    return combos.filter((combo) => combo.met === topMet)
  }
  const byGain = (a: Combo, b: Combo) => b.gain - a.gain || a.total - b.total
  const neededCount = kindData.filter((data) => data.target?.needed === true).length
  const pool = frontier(budgetWon)
  const perWon = (a: Combo, b: Combo) => b.gain / b.total - a.gain / a.total || a.total - b.total
  const cheapest = [...pool].sort((a, b) => a.total - b.total || b.gain - a.gain)[0]
  // 가성비: 예산 안에서 1원당 성능 향상이 가장 큰 조합. 바꿀 것이 없으면 유지.
  const value = pool.filter((combo) => combo.total > 0 && combo.gain > 0).sort(perWon)[0] ?? cheapest
  const bestInBudget = [...pool].sort(byGain)[0]
  // 최고 성능은 예산을 조금 넘어도 고른다(예산의 1.5배까지, 화면에 초과 금액을 함께 보여 준다).
  // 상한이 없으면 예산과 관계없이 항상 카탈로그의 가장 비싼 조합이 나와 비교 의미가 없다.
  const best = frontier(budgetWon * BEST_BUDGET_RATIO).sort(byGain)[0]
  // 추천: 예산을 최대한 활용해 가장 빠른 조합. 최고 성능과 같으면 가성비보다 빠른 다른 조합을 고르고,
  // 그런 조합이 없으면(가성비가 이미 가장 빠름) 같은 구성으로 둔다. 가성비보다 느린 조합은 추천하지 않는다.
  const keyOf = (combo: Combo) => combo.choices.map((choice) => choice?.id ?? '-').join('|')
  const taken = new Set([keyOf(value), keyOf(best)])
  const balanced = !taken.has(keyOf(bestInBudget)) ? bestInBudget
    : pool.filter((combo) => combo.gain > value.gain && !taken.has(keyOf(combo))).sort(byGain)[0] ?? bestInBudget
  const minimumFixPrices = kindData.filter((data) => data.target?.needed === true).map((data) =>
    data.list.filter((choice) => choice.meetsTarget === true).reduce((min, choice) => Math.min(min, choice.priceKrw), Infinity))
  const fullFixTotal = minimumFixPrices.reduce((sum, price) => sum + price, 0)
  const fullFixKrw = neededCount > 0 && Number.isFinite(fullFixTotal) && fullFixTotal > budgetWon ? fullFixTotal : null

  const planOf = (option: PlanOption, combo: Combo): UpgradePlan => {
    const items = itemsOf(parts, kindData, combo.choices)
    return { option, items, totalKrw: combo.total, budgetWon, reason: reasonOf(items, neededCount), fullFixKrw, custom: false }
  }
  return {
    plans: { VALUE: planOf('VALUE', value), BALANCED: planOf('BALANCED', balanced), BEST: planOf('BEST', best) },
    bestWithinBudget: planOf('BEST', bestInBudget),
    cheapestWithinBudget: planOf('VALUE', cheapest),
    cheapestFixKrw: Number.isFinite(Math.min(...minimumFixPrices)) ? Math.min(...minimumFixPrices) : null,
    candidates: Object.fromEntries(kindData.map((data) => [data.kind, data.list])),
    neededCount,
  }
}

/** 이전 방식(성능 우선·예산 위주) 호환용. 예산 위주는 예산 안 가장 싼 조합, 성능 우선은 예산 안 가장 빠른 조합이다. */
export function buildUpgradePlan(input: PlanInput & { priority: RecommendPriority }): UpgradePlan {
  const { bestWithinBudget, cheapestWithinBudget } = buildUpgradeOptions(input)
  return input.priority === 'PERFORMANCE' ? bestWithinBudget : cheapestWithinBudget
}

/** 사용자가 고른 안에서 부품 하나를 다른 후보로 바꾸거나 유지로 돌린 구성. 총액·충족 여부를 다시 계산한다. */
export function applyOverrides(plan: UpgradePlan, overrides: PlanOverrides, options: UpgradeOptions): UpgradePlan {
  if (!Object.keys(overrides).length) return plan
  const items = plan.items.map((item) => {
    const override = overrides[item.kind]
    if (override === undefined) return item
    if (override === KEEP) return { ...item, choice: null, meets: item.target.needed === null ? null : !item.target.needed,
      note: item.target.needed === true ? '직접 유지로 선택' : null }
    const choice = options.candidates[item.kind]?.find((product) => product.id === override)
    return choice ? { ...item, choice, meets: choice.meetsTarget, note: null } : item
  })
  const totalKrw = items.reduce((sum, item) => sum + (item.choice?.priceKrw ?? 0), 0)
  return { ...plan, items, totalKrw, custom: true, reason: reasonOf(items, options.neededCount) }
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
        games: goal.games.filter((name) => Object.hasOwn(GAME_REQUIREMENTS, name)),
        needed: failed.has('CPU') ? true : needed(currentPartScore('CPU', parts, 'GAME'), cpu) },
      GPU: { minScore: gpu, minVramGiB: vram, needed: anyNeeded(gpuChecks),
        games: goal.games.filter((name) => Object.hasOwn(GAME_REQUIREMENTS, name)) },
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
      CPU: { minScore: null, minVramGiB: null, minCores: cores, needed: needed(facts.cores, cores) },
      GPU: { minScore: null, minVramGiB: vram, needed: needed(facts.vramGiB, vram) },
      RAM: { minScore: ram, minVramGiB: null, needed: needed(facts.ramGiB, ram) },
    }
  }
  return {}
}

const GIB = 1024 ** 3

export function ramModules(specification: CatalogSpecification | undefined): { quantity: number; capacityBytes: number } | null {
  const quantity = specification?.moduleCount, capacityBytes = specification?.moduleCapacityBytes
  return typeof quantity === 'number' && Number.isSafeInteger(quantity) && quantity >= 1 && quantity <= 64 &&
    typeof capacityBytes === 'number' && Number.isSafeInteger(capacityBytes) && capacityBytes > 0
    ? { quantity, capacityBytes } : null
}

function replaceWithProduct(parts: PartInput[], kind: UpgradePartKind, product: PricedProduct): PartInput[] {
  const ram = kind === 'RAM' ? ramModules(product.specification) : null
  const replaced = withReplacedPart(parts, kind, product.name, product.id)
  return replaced.map((part) => {
    if (part.catalogProductId !== product.id || part.type !== kind) return part
    const spec = product.specification
    return { ...part, quantity: ram?.quantity ?? 1, specs: kind === 'RAM'
      ? { capacityBytes: ram?.capacityBytes ?? null, memoryType: typeof spec?.memoryType === 'string' ? spec.memoryType : null }
      : kind === 'CPU' ? { cores: typeof spec?.coreCount === 'number' ? spec.coreCount : null }
        : { vramBytes: typeof spec?.vramBytes === 'number' ? spec.vramBytes : null } }
  })
}

/**
 * 한 종류의 부품을 후보 제품으로 바꾼 가상 구성. 같은 종류의 기존 부품(RAM 여러 개 등)은 모두 빠진다.
 * 이 이름만 받는 함수는 용량을 확정하지 않는다. 실제 플랜은 replaceWithProduct에서 카탈로그 제원을 반영한다.
 * 카탈로그 상품 ID를 주면 연결된 부품으로 두어 호환성 검사에 쓸 수 있다.
 */
export function withReplacedPart(parts: PartInput[], kind: UpgradePartKind, name: string, productId: string | null = null): PartInput[] {
  const index = parts.findIndex((part) => part.type === kind)
  const replacement: PartInput = { type: kind, displayName: name, rawName: null, quantity: 1, source: 'MANUAL',
    catalogProductId: productId, matchStatus: productId ? 'MATCHED' : 'UNMATCHED',
    specs: {} }
  const others = parts.filter((part) => part.type !== kind)
  return index < 0 ? [...parts, replacement] : [...others.slice(0, index), replacement, ...others.slice(index)]
}

/** 플랜의 교체 부품을 모두 반영한 최종 구성. */
export function applyPlan(parts: PartInput[], plan: UpgradePlan): PartInput[] {
  return plan.items.reduce((next, item) => item.choice ? replaceWithProduct(next, item.kind, item.choice) : next, parts)
}
