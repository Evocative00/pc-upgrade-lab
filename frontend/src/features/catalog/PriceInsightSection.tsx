import { useEffect, useId, useState } from 'react'
import { priceInsightClient, type PriceHistory, type PriceInsight, type PriceInsightClient } from './priceInsightClient.ts'
import {
  VERDICT_LABELS, chartGeometry, formatChangeRate, formatKrw, formatKst, insightFlags, reasonText,
  saleConfigurationLabel,
} from './priceInsightPresentation.ts'

type State =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; insight: PriceInsight; history: PriceHistory }

const LINE_COLORS = ['var(--accent)', 'var(--linked)', 'var(--manual)', 'var(--detected)']

/** 부품 상세의 가격 판단. 상위에서 key={product.id}로 렌더링해 부품이 바뀌면 상태를 새로 시작한다. */
export function PriceInsightSection({ productId, client = priceInsightClient }: {
  productId: string
  client?: PriceInsightClient
}) {
  const headingId = useId()
  const [state, setState] = useState<State>({ status: 'loading' })
  const [reload, setReload] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    void (async () => {
      try {
        const [insight, history] = await Promise.all([
          client.insight(productId, controller.signal), client.history(productId, controller.signal)])
        if (!controller.signal.aborted) setState({ status: 'ready', insight, history })
      } catch (failure) {
        if (!controller.signal.aborted) {
          setState({ status: 'error', message: failure instanceof Error ? failure.message : '가격 판단 자료 조회 실패' })
        }
      }
    })()
    return () => controller.abort()
  }, [client, productId, reload])

  return (
    <section className="price-insight" aria-labelledby={headingId}>
      <h4 id={headingId}>가격 판단</h4>
      {state.status === 'loading' && <p className="muted" role="status">가격 관측 자료를 불러오는 중…</p>}
      {state.status === 'error' && <>
        <p role="alert" className="error">{state.message}</p>
        <button type="button" className="button button--ghost" onClick={() => {
          setState({ status: 'loading' }); setReload((value) => value + 1)
        }}>다시 조회</button>
      </>}
      {state.status === 'ready' && <InsightBody insight={state.insight} history={state.history} />}
      <p className="muted price-insight__note">
        등록된 판매가 관측만으로 비교한 판단이며 미래 가격 예측이 아닙니다. 배송비·할인·재고는 반영하지 않습니다.
      </p>
    </section>
  )
}

function InsightBody({ insight, history }: { insight: PriceInsight; history: PriceHistory }) {
  const verdictClass = `price-insight__verdict price-insight__verdict--${insight.verdict.toLowerCase().replace('_', '-')}`
  const flags = insightFlags(insight)
  return <>
    <p className="price-insight__badges">
      <span className={verdictClass}>{VERDICT_LABELS[insight.verdict]}</span>
      {flags.map((flag) => <span key={flag.kind} className={`price-insight__flag price-insight__flag--${flag.kind}`}
        title={flag.detail}>{flag.label}</span>)}
    </p>
    <p>{reasonText(insight)}</p>
    {flags.length > 0 && <ul className="price-insight__flags">
      {flags.map((flag) => <li key={flag.kind}>{flag.detail}</li>)}
    </ul>}
    <dl className="catalog-specs">
      <div><dt>최근 관측가</dt><dd>{formatKrw(insight.latest?.amountKrw ?? null)}</dd></div>
      <div><dt>마지막 관측</dt><dd>{insight.latest ? formatKst(insight.latest.observedAt) : '없음'}</dd></div>
      <div><dt>비교 기간 (마지막 관측 기준)</dt><dd>{formatKst(insight.windowStart, false)} ~ {formatKst(insight.windowEnd, false)}</dd></div>
      <div><dt>관측일 / 관측 수</dt><dd>{insight.observedDayCount}일 / {insight.sampleCount}건 (충분한 자료 기준 {insight.minObservedDays}일)</dd></div>
      <div><dt>기간 중앙값</dt><dd>{formatKrw(insight.medianKrw)}</dd></div>
      <div><dt>기간 최저 · 최고</dt><dd>{formatKrw(insight.minKrw)} · {formatKrw(insight.maxKrw)}</dd></div>
      <div><dt>7일 전 대비</dt><dd>{formatChangeRate(insight.changeRate7d)}</dd></div>
      <div><dt>판정 기준</dt><dd>{insight.methodVersion}</dd></div>
    </dl>
    <PriceHistoryChart history={history} />
  </>
}

export function PriceHistoryChart({ history }: { history: PriceHistory }) {
  const geometry = chartGeometry(history.series)
  if (!geometry) return <p className="muted">표시할 가격 관측 이력이 없습니다.</p>
  const summary = `${history.series.length}개 판매 구성, ${formatKst(geometry.startUtc, false)}부터 ` +
    `${formatKst(geometry.endUtc, false)}까지 ${formatKrw(geometry.minKrw)}~${formatKrw(geometry.maxKrw)}`
  return (
    <figure className="price-insight__chart">
      <svg viewBox={`0 0 ${geometry.width} ${geometry.height}`} role="img" aria-label={`가격 관측 이력: ${summary}`}>
        <rect x="0" y="0" width={geometry.width} height={geometry.height} fill="none" stroke="var(--border)" />
        {geometry.lines.map((line, index) => {
          const color = LINE_COLORS[index % LINE_COLORS.length]
          return <g key={line.key}>
            {line.segments.map((segment) => <polyline key={segment} points={segment} fill="none" stroke={color} strokeWidth="2" />)}
            {line.dots.map((dot, dotIndex) => <circle key={dotIndex} cx={dot.x} cy={dot.y} r="3" fill={color} />)}
          </g>
        })}
      </svg>
      <figcaption className="muted">
        {formatKst(geometry.startUtc, false)} ~ {formatKst(geometry.endUtc, false)} · {formatKrw(geometry.minKrw)} ~ {formatKrw(geometry.maxKrw)}.
        {' '}관측이 없는 날은 선으로 잇지 않습니다.
        <ul>
          {history.series.map((series, index) => <li key={`${series.sourceName}:${series.externalId}:${series.saleUnit}`}>
            <span style={{ color: LINE_COLORS[index % LINE_COLORS.length] }}>●</span>{' '}
            <a href={series.sourceUrl} target="_blank" rel="noopener noreferrer">{saleConfigurationLabel(series)}</a>
            {' · '}{series.points.length}건
          </li>)}
        </ul>
      </figcaption>
    </figure>
  )
}
