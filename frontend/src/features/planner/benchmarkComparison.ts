/**
 * 같은 시험·같은 판의 점수끼리만 비교한다. 점수 데이터는 외부에서 주입하며,
 * 이 파일은 점수를 수집하거나 특정 게임의 FPS를 추정하지 않는다.
 */
export type BenchmarkMetric = 'CPU_SINGLE_THREAD' | 'CPU_MULTI_THREAD' | 'GPU_3D'
export type ComponentKind = 'CPU' | 'GPU'

export type BenchmarkDataset = {
  kind: ComponentKind
  metric: BenchmarkMetric
  publisher: string
  edition: string
  sourceUrl: string
  checkedOn: string
  scores: Record<string, number>
  // 여러 시험을 담는 자료는 모델마다 실제 비교 가능한 시험판을 지정한다.
  // 지정된 자료에서 그룹이 없는 모델은 비교하지 않는다.
  scoreGroups?: Record<string, string>
  // 모델별 측정 횟수. 자료가 제공할 때만 있다.
  sampleCounts?: Record<string, number>
}

export type ScoreComparison = {
  status: 'ABOVE_BASELINE' | 'BELOW_BASELINE' | 'UNKNOWN'
  actualScore: number | null
  baselineScore: number | null
  ratio: number | null
  baselineModel: string | null
  reason: string
  // 서로 다른 시험 표(출처·연도)의 점수를 공통 눈금으로 환산해 비교했는지. 같은 표끼리보다 오차가 크다.
  bridged?: boolean
}

// 제조사·상표 표기와 WMI가 붙이는 설명만 제거한다. 숫자·Ti/SUPER 등 제품 변형은 유지한다.
// 'nth Gen' 접두어와 내장 그래픽 설명(with Radeon ... Graphics)도 모델명이 아니므로 제거한다.
// 용량과 메모리 종류는 제품 변형이므로 보존한다. 6G와 6GB만 같은 표기로 맞춘다.
function modelWords(name: string): string {
  return name.toLowerCase().replace(/@\s*[\d.]+\s*ghz\b/g, '')
    .replace(/\b\d{1,2}(?:st|nd|rd|th)\s+gen\b/g, '')
    .replace(/\s(?:with|w\/)\s+radeon\b.*$/g, '')
    .replace(/\b(intel|amd|nvidia|geforce|radeon)\b/g, '')
    .replace(/\b(\d{1,2})\s*g(?:b)?\b/g, '$1gb')
    .replace(/\b(?:(?:\d+|dual|quad|six|eight|twelve|sixteen)-core|processor|graphics|cpu|gpu|core|tm|r)\b/g, '')
    .replace(/\b(?:series|계열)\b/g, '')
    .replace(/[^a-z0-9]+/g, ' ').trim().replace(/\s+/g, ' ')
}

export function normalizeHardwareModel(name: string): string {
  return modelWords(name).replace(/ /g, '')
}

// 'GeForce 400 Series'·'Radeon HD 7000 Series'처럼 끝자리가 0인 계열 표기만 제품군으로 본다.
// Windows는 AMD 카드를 'Radeon RX 580 Series'처럼 보고하므로 정확한 번호 뒤의 Series는 허용한다.
export function isSpecificHardwareModel(name: string): boolean {
  if (/계열/.test(name) || /\b\d00{1,2}\s+series\b/i.test(name)) return false
  return !/^(?:core i[3579]|ryzen [3579])$/i.test(name.trim())
}

function validScore(score: number | undefined): number | null {
  return typeof score === 'number' && Number.isFinite(score) && score > 0 ? score : null
}

type ScoreEntry = { model: string; score: number; group: string }

function entryFor(name: string, dataset: BenchmarkDataset): ScoreEntry | null {
  const score = validScore(dataset.scores[name])
  const group = dataset.scoreGroups ? dataset.scoreGroups[name] : `${dataset.publisher}:${dataset.metric}:${dataset.edition}`
  return score !== null && group?.trim() ? { model: name, score, group } : null
}

function scoreFor(model: string, dataset: BenchmarkDataset): ScoreEntry | null {
  const key = normalizeHardwareModel(model)
  if (!key) return null
  const matches = Object.keys(dataset.scores).filter((name) => normalizeHardwareModel(name) === key)
  if (matches.length === 1) return entryFor(matches[0], dataset)
  if (matches.length > 1) return null
  // Windows와 공식 사양은 'GeForce GTX 1060', 'Radeon RX 580 Series'처럼 VRAM·메모리 종류를 빼고 적는다.
  // 자료에 그 칩셋의 메모리 변형만 있으면 변형 중 점수가 낮은 값을 쓴다(성능을 부풀리지 않는 쪽).
  const variants = Object.keys(dataset.scores).filter((name) => {
    const nameKey = normalizeHardwareModel(name)
    return nameKey.startsWith(key) && /^(?:\d{1,2}gb|gddr\d\w*|ddr\d\w*)+$/.test(nameKey.slice(key.length))
  }).map((name) => entryFor(name, dataset)).filter((entry): entry is ScoreEntry => entry !== null)
  return variants.length ? variants.reduce((low, entry) => entry.score < low.score ? entry : low) : null
}

// 내 부품은 카탈로그 판매 제품명(예: 'GeForce GTX 1660 SUPER VENTUS XS OC')일 수 있다.
// GPU 칩셋의 전체 토큰과 명시된 메모리 변형을 찾는다. 임의 제품 접미사를 잘라 기본 모델로 바꾸지 않는다.
// CPU는 정확한 모델만 허용하며, GPU 판매명 뒤에는 알려진 유통/쿨러 설명만 허용한다.
const marketingWords = new Set(['ventus', 'xs', 'oc', 'pulse', 'gaming', 'x', 'trio', 'dual', 'eagle',
  'windforce', 'tuf', 'rog', 'strix', 'asus', 'msi', 'gigabyte', 'sapphire', 'powercolor', 'xfx',
  'palit', 'zotac', 'amp', 'lhr', 'aero', 'armor', 'itx', 'mini', 'white', 'black', 'edition'])

function scoreForPart(model: string, dataset: BenchmarkDataset): ScoreEntry | null {
  const exact = scoreFor(model, dataset)
  if (exact !== null || dataset.kind === 'CPU' || /laptop|mobile|max[\s-]?q/i.test(model)) return exact
  const words = modelWords(model)
  const chipsets = [...words.matchAll(/\b(?:gtx|rtx|gt|rx|r[579]|hd|uhd|arc)\s*\d+[a-z0-9]*(?:\s+(?:ti|super|xt|xtx|gre))*\b/g)]
  if (chipsets.length !== 1) return null
  const chipset = chipsets[0]
  const memory = words.match(/\b(?:\d{1,2}gb|gddr\d\w*|ddr\d\w*)\b/g) ?? []
  const tail = words.slice(Number(chipset.index) + chipset[0].length)
    .replace(/\b(?:\d{1,2}gb|gddr\d\w*|ddr\d\w*)\b/g, '').trim()
  if (tail && tail.split(/\s+/).some((word) => !marketingWords.has(word))) return null
  return scoreFor([chipset[0], ...memory].join(' '), dataset)
}

export function compareBenchmarkScores(kind: ComponentKind, actualModel: string,
  requirementModels: string[], dataset: BenchmarkDataset | null): ScoreComparison {
  const unknown = (reason: string): ScoreComparison => ({ status: 'UNKNOWN', actualScore: null,
    baselineScore: null, ratio: null, baselineModel: null, reason })
  if (!dataset || dataset.kind !== kind ||
    (kind === 'CPU' && !dataset.metric.startsWith('CPU_')) ||
    (kind === 'GPU' && dataset.metric !== 'GPU_3D') ||
    !dataset.publisher.trim() || !dataset.edition.trim() || !dataset.sourceUrl.startsWith('https://')) {
    return unknown('사용 가능한 동일 기준의 점수 자료가 없습니다.')
  }
  if (!actualModel.trim() || !isSpecificHardwareModel(actualModel)) return unknown('내 부품의 정확한 모델이 필요합니다.')
  if (requirementModels.length === 0) return unknown('공식 요구 사양 예시가 없습니다.')
  const actual = scoreForPart(actualModel, dataset)
  if (actual === null) return unknown('내 부품의 정확한 모델·변형에 해당하는 점수가 자료에 없습니다.')
  // 공식 사양은 예시 중 하나만 갖추면 되는 구조다. 점수가 있는 예시 중 가장 낮은 것을 기준으로 삼는다.
  // 계열 표기(Core i7 계열 등)나 자료에 없는 예시는 기준 후보에서 빠진다.
  const baselines = requirementModels.map((model) => ({
    model, entry: isSpecificHardwareModel(model) ? scoreFor(model, dataset) : null }))
  const scored = baselines.filter((item): item is { model: string; entry: ScoreEntry } => item.entry !== null)
  const missing = baselines.filter((item) => item.entry === null).map((item) => item.model)
  if (scored.length === 0) {
    const family = requirementModels.find((model) => !isSpecificHardwareModel(model))
    return unknown(family
      ? `공식 사양이 '${family}'처럼 제품군으로만 적혀 있어 성능을 비교할 수 없습니다.`
      : '공식 예시 모델의 점수가 자료에 없어 기준을 정할 수 없습니다.')
  }
  const baseline = scored.reduce((lowest, item) => item.entry.score < lowest.entry.score ? item : lowest)
  const actualScore = actual.score
  const baselineScore = baseline.entry.score
  const ratio = actualScore / baselineScore
  // 활용도를 위해 다른 시험 표의 점수도 공통 눈금(GTX 1060 6GB = 100)으로 환산해 비교한다. 결과에 환산 여부를 표시한다.
  const bridged = baseline.entry.group !== actual.group
  const basis = bridged ? '서로 다른 시험 표를 공통 눈금으로 환산해 비교한 값입니다(오차 약 ±15%).'
    : '같은 자료·시험 지표에서 공식 예시 모델과 비교한 값입니다.'
  const missingNote = missing.length ? ` 자료 없음: ${missing.join(', ')}.` : ''
  const caveat = '실제 게임 FPS나 실행 가능 판정은 아닙니다.'
  if (actualScore >= baselineScore) {
    // 점수가 있는 공식 대안 하나를 넘었으므로, 자료가 없는 예시가 있어도 기준 이상이다.
    return { status: 'ABOVE_BASELINE', actualScore, baselineScore, ratio, baselineModel: baseline.model, bridged,
      reason: `${basis}${missingNote} ${caveat}` }
  }
  if (missing.length > 0) {
    // 자료가 없는 예시가 더 낮은 사양일 수 있으므로 부족하다고 단정하지 않는다.
    return { status: 'UNKNOWN', actualScore, baselineScore, ratio, baselineModel: baseline.model, bridged,
      reason: `점수 자료가 있는 공식 예시보다 낮지만, 자료가 없는 예시(${missing.join(', ')})가 있어 부족하다고 단정할 수 없습니다.` }
  }
  return { status: 'BELOW_BASELINE', actualScore, baselineScore, ratio, baselineModel: baseline.model, bridged,
    reason: `${basis} ${caveat}` }
}

/** 내 부품 이름에 해당하는 자료 모델명. 예상 프레임 계산처럼 점수표를 직접 참조할 때 쓴다. */
export function findPartModel(model: string, dataset: BenchmarkDataset): string | null {
  return isSpecificHardwareModel(model) ? scoreForPart(model, dataset)?.model ?? null : null
}

/** 공식 예시·기준점 모델명에 정확히 해당하는 자료 모델명. */
export function findDatasetModel(model: string, dataset: BenchmarkDataset): string | null {
  return isSpecificHardwareModel(model) ? scoreFor(model, dataset)?.model ?? null : null
}
