import assert from 'node:assert/strict'
import test from 'node:test'
import type { PriceHistorySeries, PriceInsight } from '../src/features/catalog/priceInsightClient.ts'
import {
  chartGeometry, formatChangeRate, formatKst, insightFlags, insightOffer, reasonText, saleConfigurationLabel,
} from '../src/features/catalog/priceInsightPresentation.ts'

function series(externalId: string, points: [string, number][], saleUnit: 'PRODUCT' | 'RAM_KIT' = 'PRODUCT'): PriceHistorySeries {
  return { sourceName: 'DANAWA', externalId, sourceUrl: 'https://example.com', saleSku: `SKU-${externalId}`,
    saleUnit, moduleCount: saleUnit === 'RAM_KIT' ? 2 : null,
    points: points.map(([observedAt, amountKrw]) => ({ amountKrw, observedAt, evidenceUrl: 'https://example.com', origin: 'CATALOG' })) }
}

test('formats UTC observations in KST without changing the source time', () => {
  assert.equal(formatKst('2026-10-10T16:10:24.509Z'), '2026-10-11 01:10 KST')
  assert.equal(formatKst('2026-10-10T16:10:24.509Z', false), '2026-10-11')
})

test('formats change rate and explains insufficient days with the actual counts', () => {
  assert.equal(formatChangeRate(null), '비교 불가')
  assert.equal(formatChangeRate(0.1), '+10%')
  assert.equal(formatChangeRate(-0.0345), '-3.5%')
  const insight = { reason: 'TOO_FEW_DAYS', observedDayCount: 1, minComparableDays: 2 } as PriceInsight
  assert.match(reasonText(insight), /1일뿐.*2일 이상/)
})

test('stale and low-confidence flags accompany the verdict instead of hiding it', () => {
  const base = { verdict: 'FAIR', reason: 'WITHIN_RANGE', analyzedAt: '2026-11-07T00:00:00Z',
    observedDayCount: 3, minObservedDays: 14,
    latest: { amountKrw: 399000, observedAt: '2026-10-10T08:10:24.509Z', sourceName: 'DANAWA', externalId: '1', saleSku: 'S' },
  } as PriceInsight
  const both = insightFlags({ ...base, stale: true, lowConfidence: true })
  assert.deepEqual(both.map((flag) => flag.label), ['최신화되지 않음', '관측일 부족'])
  assert.match(both[0].detail, /마지막 관측 27일 전/)
  assert.match(both[1].detail, /관측 3일 · 14일 미만/)
  assert.deepEqual(insightFlags({ ...base, stale: false, lowConfidence: false }), [])
})

test('labels RAM kits by module count so kit prices are not read as per-module prices', () => {
  assert.equal(saleConfigurationLabel(series('1', [['2026-10-08T00:00:00Z', 1]], 'RAM_KIT')),
    'DANAWA 1 · SKU-1 · 2개 모듈 키트')
})

test('shows the judged sale configuration and links only evidence for that exact observation', () => {
  const chosen = series('1', [['2026-10-08T00:00:00Z', 22_000]], 'RAM_KIT')
  const other = { ...chosen, sourceName: 'OTHER', points: [{ ...chosen.points[0], evidenceUrl: 'https://example.com/other' }] }
  const history = { productId: 'ram-1', canonicalId: null, series: [other, chosen] }
  const latest = { ...chosen.points[0], sourceName: chosen.sourceName, externalId: chosen.externalId, saleSku: chosen.saleSku }
  assert.deepEqual(insightOffer(latest, history), {
    label: 'DANAWA 1 · SKU-1 · 2개 모듈 키트', evidenceUrl: 'https://example.com',
  })
  // 이력 조회 실패 또는 새 관측이 두 조회 사이에 들어온 경우에도 판정 대상은 남는다.
  assert.deepEqual(insightOffer(latest, null), { label: 'DANAWA 1 · SKU-1', evidenceUrl: null })
  assert.equal(insightOffer({ ...latest, amountKrw: 21_000 }, history)?.evidenceUrl, null)
})

test('chart breaks lines across missing days and keeps sale configurations separate', () => {
  const geometry = chartGeometry([
    series('A', [['2026-10-08T00:00:00Z', 100], ['2026-10-09T00:00:00Z', 110], ['2026-10-12T00:00:00Z', 120],
      ['2026-10-13T00:00:00Z', 90]]),
    series('B', [['2026-10-10T00:00:00Z', 200]]),
  ])
  assert.ok(geometry)
  assert.equal(geometry.lines.length, 2)
  // 10-09 → 10-12 공백에서 선을 끊는다.
  assert.equal(geometry.lines[0].segments.length, 2)
  assert.equal(geometry.lines[0].dots.length, 4)
  // 점 하나뿐인 판매 구성은 선 없이 점으로만 표시한다.
  assert.equal(geometry.lines[1].segments.length, 0)
  assert.equal(geometry.lines[1].dots.length, 1)
  assert.equal(geometry.minKrw, 90)
  assert.equal(geometry.maxKrw, 200)
  for (const line of geometry.lines) for (const dot of line.dots) {
    assert.ok(dot.x >= 0 && dot.x <= geometry.width && dot.y >= 0 && dot.y <= geometry.height)
  }
  assert.equal(chartGeometry([]), null)
})

test('chart leaves an unobserved Korean calendar date empty even when the gap is under 36 hours', () => {
  const geometry = chartGeometry([series('A', [
    ['2026-10-01T14:59:00Z', 20_000], // 10-01 23:59 KST
    ['2026-10-03T00:00:00Z', 18_000], // 10-03 09:00 KST
  ])])
  assert.ok(geometry)
  assert.equal(geometry.lines[0].dots.length, 2)
  assert.deepEqual(geometry.lines[0].segments, [])
})

test('chart keys distinguish sale SKU and source URL within a listing', () => {
  const listing = series('1', [['2026-10-08T00:00:00Z', 100]])
  const geometry = chartGeometry([
    listing,
    { ...listing, saleSku: 'OTHER-SKU' },
    { ...listing, sourceUrl: 'https://example.com/other' },
  ])
  assert.ok(geometry)
  assert.equal(new Set(geometry.lines.map((line) => line.key)).size, 3)
})

test('does not generate a chart from a large otherwise valid history response', () => {
  const row = series('A', [['2026-10-01T14:59:00Z', 20_000]])
  const history = Array.from({ length: 32 }, (_, index) => ({ ...row,
    externalId: String(index), points: Array(5_000).fill(row.points[0]),
  }))
  assert.equal(chartGeometry(history), null)
})
