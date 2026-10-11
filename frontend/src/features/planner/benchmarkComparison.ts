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
}

// 제조사·상표 표기와 WMI가 붙이는 설명만 제거한다. 숫자·Ti/SUPER 등 제품 변형은 유지한다.
// 'nth Gen' 접두어와 내장 그래픽 설명(with Radeon ... Graphics)도 모델명이 아니므로 제거한다.
// 메모리 용량은 1~2자리(8G, 12GB)만 지워 5600G 같은 모델 번호의 G 접미사를 보존한다.
export function normalizeHardwareModel(name: string): string {
  return name.toLowerCase().replace(/@\s*[\d.]+\s*ghz\b/g, '')
    .replace(/\b\d{1,2}(?:st|nd|rd|th)\s+gen\b/g, '')
    .replace(/\s(?:with|w\/)\s+radeon\b.*$/g, '')
    .replace(/\b(intel|amd|nvidia|geforce|radeon)\b/g, '')
    .replace(/\b(?:\d{1,2}gb?|(?:\d+|dual|quad|six|eight|twelve|sixteen)-core|processor|graphics|cpu|gpu|core|tm|r)\b/g, '')
    .replace(/\b(?:series|계열)\b/g, '')
    .replace(/[^a-z0-9]+/g, '')
}

// 'GeForce 400 Series'·'Radeon HD 7000 Series'처럼 끝자리가 0인 계열 표기만 제품군으로 본다.
// Windows는 AMD 카드를 'Radeon RX 580 Series'처럼 보고하므로 정확한 번호 뒤의 Series는 허용한다.
export function isSpecificHardwareModel(name: string): boolean {
  if (/계열/.test(name) || /\b\d00{1,2}\s+series\b/i.test(name)) return false
  return !/^(?:core i[3579]|ryzen [3579])$/i.test(name.trim())
}

function scoreFor(model: string, dataset: BenchmarkDataset): number | null {
  const key = normalizeHardwareModel(model)
  const matches = Object.entries(dataset.scores).filter(([name]) => normalizeHardwareModel(name) === key)
  if (!key || matches.length !== 1) return null
  const score = matches[0]?.[1]
  return typeof score === 'number' && Number.isFinite(score) && score > 0 ? score : null
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
  if (requirementModels.length === 0 || requirementModels.some((model) => !isSpecificHardwareModel(model))) {
    return unknown('공식 요구 사양이 특정 모델이 아닌 계열로 제시됐습니다.')
  }
  const actualScore = scoreFor(actualModel, dataset)
  if (actualScore === null) return unknown('내 부품의 점수가 자료에 없습니다.')
  const baselines = requirementModels.map((model) => ({ model, score: scoreFor(model, dataset) }))
  if (baselines.some((item) => item.score === null)) {
    return unknown('공식 예시 모델 중 점수가 없는 항목이 있어 기준을 정할 수 없습니다.')
  }
  // 공식 대안 중 하나를 충족하면 되는 구조이므로, 같은 지표에서 점수가 가장 낮은 예시를 기준으로 삼는다.
  const baseline = baselines.reduce((lowest, item) => Number(item.score) < Number(lowest.score) ? item : lowest)
  const baselineScore = Number(baseline.score)
  return { status: actualScore >= baselineScore ? 'ABOVE_BASELINE' : 'BELOW_BASELINE',
    actualScore, baselineScore, ratio: actualScore / baselineScore, baselineModel: baseline.model,
    reason: '같은 자료·시험 지표에서 공식 예시 모델과 비교한 값입니다. 실제 게임 FPS나 실행 가능 판정은 아닙니다.' }
}
