import { CatalogApiError } from './catalogClient.ts'
import { createCatalogRead, isRecord, isText, isWebUrl } from './catalogRead.ts'

// 가격 판단 API(GET 전용). 기존 currentPrice·referenceEstimate와 별개의 응답이다.
export type PriceVerdict = 'GOOD_PRICE' | 'FAIR' | 'WAIT' | 'INSUFFICIENT_DATA'
export type PriceVerdictReason = 'NO_OBSERVATION' | 'TOO_FEW_DAYS' |
  'BELOW_MEDIAN' | 'NEAR_PERIOD_LOW' | 'ABOVE_MEDIAN' | 'WITHIN_RANGE'
export type PriceHistoryPoint = {
  amountKrw: number; observedAt: string; evidenceUrl: string; origin: 'CATALOG' | 'HISTORY'
}
export type PriceHistorySeries = {
  sourceName: string; externalId: string; sourceUrl: string; saleSku: string
  saleUnit: 'PRODUCT' | 'RAM_KIT'; moduleCount: number | null; points: PriceHistoryPoint[]
}
export type PriceHistory = { productId: string; canonicalId: string | null; series: PriceHistorySeries[] }
export type PriceInsight = {
  productId: string; canonicalId: string | null; basis: 'REGISTERED_OBSERVATIONS'; methodVersion: string
  analyzedAt: string; verdict: PriceVerdict; reason: PriceVerdictReason
  /** 마지막 관측이 staleAfterDays 이상 지남. 판정은 그 시점 가격 기준이다. */
  stale: boolean
  /** 판정은 냈지만 관측일이 minObservedDays 미만이라 참고용이다. */
  lowConfidence: boolean
  windowStart: string; windowEnd: string
  minComparableDays: number; minObservedDays: number; staleAfterDays: number
  sampleCount: number; observedDayCount: number
  latest: { amountKrw: number; observedAt: string; sourceName: string; externalId: string; saleSku: string } | null
  medianKrw: number | null; minKrw: number | null; maxKrw: number | null; changeRate7d: number | null
}

const VERDICTS: PriceVerdict[] = ['GOOD_PRICE', 'FAIR', 'WAIT', 'INSUFFICIENT_DATA']
const REASONS: PriceVerdictReason[] = ['NO_OBSERVATION', 'TOO_FEW_DAYS',
  'BELOW_MEDIAN', 'NEAR_PERIOD_LOW', 'ABOVE_MEDIAN', 'WITHIN_RANGE']
const MAX_POINTS = 5000

/** 관측 시각은 UTC(Z) ISO 문자열만 받는다. 브라우저 시간대로 재해석하지 않는다. */
export function isUtcTimestamp(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) &&
    Number.isFinite(Date.parse(value))
}
const isAmount = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) > 0
const isCount = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 0
const isNullableAmount = (value: unknown) => value === null || isAmount(value)
const isNullableCanonical = (value: unknown) => value === null || isText(value, 36)

function isPoint(value: unknown): value is PriceHistoryPoint {
  return isRecord(value) && isAmount(value.amountKrw) && isUtcTimestamp(value.observedAt) &&
    isWebUrl(value.evidenceUrl) && (value.origin === 'CATALOG' || value.origin === 'HISTORY')
}

function isSeries(value: unknown): value is PriceHistorySeries {
  return isRecord(value) && isText(value.sourceName, 32) && isText(value.externalId, 128) &&
    isWebUrl(value.sourceUrl) && isText(value.saleSku, 255) &&
    (value.saleUnit === 'PRODUCT' && value.moduleCount === null ||
      value.saleUnit === 'RAM_KIT' && Number.isSafeInteger(value.moduleCount) && Number(value.moduleCount) > 0) &&
    Array.isArray(value.points) && value.points.length > 0 && value.points.length <= MAX_POINTS &&
    value.points.every(isPoint)
}

export function isPriceHistory(value: unknown): value is PriceHistory {
  return isRecord(value) && isText(value.productId, 128) && isNullableCanonical(value.canonicalId) &&
    Array.isArray(value.series) && value.series.length <= 100 && value.series.every(isSeries)
}

function isLatest(value: unknown): boolean {
  return value === null || isRecord(value) && isAmount(value.amountKrw) && isUtcTimestamp(value.observedAt) &&
    isText(value.sourceName, 32) && isText(value.externalId, 128) && isText(value.saleSku, 255)
}

export function isPriceInsight(value: unknown): value is PriceInsight {
  if (!isRecord(value)) return false
  const insufficient = value.verdict === 'INSUFFICIENT_DATA'
  const judgedReasons = ['BELOW_MEDIAN', 'NEAR_PERIOD_LOW', 'ABOVE_MEDIAN', 'WITHIN_RANGE']
  return isText(value.productId, 128) && isNullableCanonical(value.canonicalId) &&
    value.basis === 'REGISTERED_OBSERVATIONS' && isText(value.methodVersion, 32) &&
    isUtcTimestamp(value.analyzedAt) && VERDICTS.includes(value.verdict as PriceVerdict) &&
    REASONS.includes(value.reason as PriceVerdictReason) &&
    // 판정과 사유의 조합을 확인한다. 자료 부족 판정에 가격 수준 사유가 붙지 않는다.
    insufficient !== judgedReasons.includes(String(value.reason)) &&
    typeof value.stale === 'boolean' && typeof value.lowConfidence === 'boolean' &&
    // 관측일 부족 표시는 판정을 낸 경우에만 붙는다.
    !(insufficient && value.lowConfidence) &&
    isUtcTimestamp(value.windowStart) && isUtcTimestamp(value.windowEnd) &&
    isCount(value.minComparableDays) && isCount(value.minObservedDays) && isCount(value.staleAfterDays) &&
    isCount(value.sampleCount) && isCount(value.observedDayCount) &&
    Number(value.observedDayCount) <= Number(value.sampleCount) &&
    isLatest(value.latest) && (value.latest === null) === (value.reason === 'NO_OBSERVATION') &&
    isNullableAmount(value.medianKrw) && isNullableAmount(value.minKrw) && isNullableAmount(value.maxKrw) &&
    (value.changeRate7d === null || typeof value.changeRate7d === 'number' && Number.isFinite(value.changeRate7d)) &&
    (insufficient || value.medianKrw !== null && value.minKrw !== null && value.maxKrw !== null)
}

export interface PriceInsightClient {
  history(id: string, signal?: AbortSignal): Promise<PriceHistory>
  insight(id: string, signal?: AbortSignal): Promise<PriceInsight>
}

export function createPriceInsightClient(fetcher: typeof fetch = globalThis.fetch): PriceInsightClient {
  const read = createCatalogRead(fetcher)
  const base = (id: string) => `/api/catalog/products/${encodeURIComponent(id)}`
  const mismatch = () => new CatalogApiError('선택한 부품과 가격 자료가 일치하지 않습니다.', 200, 'INVALID_RESPONSE')
  return {
    async history(id, signal) {
      const result = await read(`${base(id)}/price-history`, isPriceHistory, signal)
      if (result.productId !== id) throw mismatch()
      return result
    },
    async insight(id, signal) {
      const result = await read(`${base(id)}/price-insight`, isPriceInsight, signal)
      if (result.productId !== id) throw mismatch()
      return result
    },
  }
}

export const priceInsightClient = createPriceInsightClient()
