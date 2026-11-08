import type { PriceHistory, PriceHistorySeries, PriceInsight, PriceVerdict, PriceVerdictReason } from './priceInsightClient.ts'

export const VERDICT_LABELS: Record<PriceVerdict, string> = {
  GOOD_PRICE: '좋은 가격',
  FAIR: '평균 수준',
  WAIT: '대기 권장',
  INSUFFICIENT_DATA: '자료 부족',
}

export function reasonText(insight: PriceInsight): string {
  const reasons: Record<PriceVerdictReason, string> = {
    NO_OBSERVATION: '등록된 판매가 관측이 없습니다.',
    TOO_FEW_DAYS: `비교할 관측일이 ${insight.observedDayCount}일뿐이라 가격 수준을 판단하지 않습니다. ` +
      `${insight.minComparableDays}일 이상 관측되면 판정합니다.`,
    BELOW_MEDIAN: '최근 가격이 비교 기간 중앙값보다 3% 이상 낮습니다.',
    NEAR_PERIOD_LOW: '최근 가격이 비교 기간 최저가에 가깝습니다.',
    ABOVE_MEDIAN: '최근 가격이 비교 기간 중앙값보다 5% 이상 높습니다.',
    WITHIN_RANGE: '최근 가격이 비교 기간의 일반적인 범위 안에 있습니다.',
  }
  return reasons[insight.reason]
}

export type InsightFlag = { kind: 'stale' | 'low-confidence'; label: string; detail: string }

/** 판정과 별개로 붙는 표시. 판정을 숨기지 않고 자료 상태만 알린다. */
export function insightFlags(insight: PriceInsight): InsightFlag[] {
  const flags: InsightFlag[] = []
  if (insight.stale && insight.latest) {
    const days = Math.max(0, Math.floor((Date.parse(insight.analyzedAt) - Date.parse(insight.latest.observedAt)) / 86_400_000))
    flags.push({ kind: 'stale', label: '최신화되지 않음',
      detail: `마지막 관측 ${days}일 전 · 과거 관측을 비교한 결과입니다. 현재 구매가는 판매처에서 다시 확인해 주세요.` })
  }
  if (insight.lowConfidence) {
    flags.push({ kind: 'low-confidence', label: '관측일 부족',
      detail: `관측 ${insight.observedDayCount}일 · ${insight.minObservedDays}일 미만이라 참고용입니다.` })
  }
  return flags
}

const krw = new Intl.NumberFormat('ko-KR')
export const formatKrw = (amount: number | null) => amount === null ? '미확인' : `${krw.format(amount)}원`

/** 원본 UTC 시각을 한국 시간으로 표시한다. */
export function formatKst(utc: string, withTime = true): string {
  const parts = new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
    ...(withTime ? { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' as const } : {}),
  }).formatToParts(new Date(utc))
  const get = (type: string) => parts.find((part) => part.type === type)?.value ?? ''
  const date = `${get('year')}-${get('month')}-${get('day')}`
  return withTime ? `${date} ${get('hour')}:${get('minute')} KST` : date
}

export function formatChangeRate(rate: number | null): string {
  if (rate === null) return '비교 불가'
  // 음수도 0에서 먼 쪽으로 반올림해 -3.45%가 -3.4%로 줄어들지 않게 한다.
  const percent = Math.sign(rate) * Math.round(Math.abs(rate) * 1000) / 10
  return `${percent > 0 ? '+' : ''}${percent}%`
}

export function saleConfigurationLabel(series: PriceHistorySeries): string {
  const unit = series.saleUnit === 'RAM_KIT' ? ` · ${series.moduleCount}개 모듈 키트` : ''
  return `${series.sourceName} ${series.externalId} · ${series.saleSku}${unit}`
}

/** 판정에 쓰인 정확한 관측만 이력에서 찾아 판매 단위와 출처 링크를 붙인다. */
export function insightOffer(latest: PriceInsight['latest'], history: PriceHistory | null) {
  if (!latest) return null
  for (const series of history?.series ?? []) {
    if (series.sourceName !== latest.sourceName || series.externalId !== latest.externalId || series.saleSku !== latest.saleSku) continue
    const point = series.points.find((candidate) =>
      candidate.observedAt === latest.observedAt && candidate.amountKrw === latest.amountKrw)
    if (point) return { label: saleConfigurationLabel(series), evidenceUrl: point.evidenceUrl }
  }
  return { label: `${latest.sourceName} ${latest.externalId} · ${latest.saleSku}`, evidenceUrl: null }
}

export type ChartLine = { key: string; label: string; segments: string[]; dots: { x: number; y: number }[] }
export type ChartGeometry = {
  width: number; height: number; lines: ChartLine[]
  minKrw: number; maxKrw: number; startUtc: string; endUtc: string
}

const DAY_MS = 86_400_000
const KST_OFFSET_MS = 9 * 60 * 60 * 1000
const MAX_CHART_POINTS = 5_000

/**
 * 판매 구성별 선을 만든다. 표시하는 한국 날짜에 관측이 없는 날이 끼면 선을 끊는다.
 * 관측이 없거나 화면에 그릴 수 있는 점 수를 넘으면 null이다.
 */
export function chartGeometry(series: PriceHistorySeries[], width = 560, height = 180, padding = 16): ChartGeometry | null {
  const count = series.reduce((sum, item) => sum + item.points.length, 0)
  if (count === 0 || count > MAX_CHART_POINTS) return null
  let start = Infinity, end = -Infinity, low = Infinity, high = -Infinity
  for (const { points } of series) for (const point of points) {
    const time = Date.parse(point.observedAt)
    start = Math.min(start, time)
    end = Math.max(end, time)
    low = Math.min(low, point.amountKrw)
    high = Math.max(high, point.amountKrw)
  }
  const spanX = Math.max(end - start, DAY_MS)
  const spanY = Math.max(high - low, Math.max(1, Math.round(high * 0.02)))
  const x = (time: number) => Math.round((padding + (time - start) / spanX * (width - padding * 2)) * 10) / 10
  const y = (amount: number) => Math.round((height - padding - (amount - low) / spanY * (height - padding * 2)) * 10) / 10
  const lines = series.map((item) => {
    const points = [...item.points].sort((a, b) => Date.parse(a.observedAt) - Date.parse(b.observedAt))
    const segments: string[] = []
    let current: string[] = []
    let previousDay: number | null = null
    for (const point of points) {
      const time = Date.parse(point.observedAt)
      const day = Math.floor((time + KST_OFFSET_MS) / DAY_MS)
      if (previousDay !== null && day - previousDay > 1) {
        if (current.length > 1) segments.push(current.join(' '))
        current = []
      }
      current.push(`${x(time)},${y(point.amountKrw)}`)
      previousDay = day
    }
    if (current.length > 1) segments.push(current.join(' '))
    return {
      key: `${item.sourceName}:${item.externalId}:${item.saleUnit}:${item.moduleCount ?? ''}`,
      label: saleConfigurationLabel(item),
      segments,
      dots: points.map((point) => ({ x: x(Date.parse(point.observedAt)), y: y(point.amountKrw) })),
    }
  })
  return { width, height, lines, minKrw: low, maxKrw: high,
    startUtc: new Date(start).toISOString(), endUtc: new Date(end).toISOString() }
}
