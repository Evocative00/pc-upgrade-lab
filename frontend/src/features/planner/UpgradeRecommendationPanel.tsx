import { useEffect, useState } from 'react'
import { catalogClient } from '../catalog/catalogClient.ts'
import type { CatalogProduct } from '../catalog/catalogTypes.ts'
import type { PartInput } from '../pc-scan/types.ts'
import { getPartTypeInfo } from '../pc/partCategories.ts'
import { estimateGameFrames, frameText, supportsFrameEstimate } from './frameEstimate.ts'
import type { UpgradeGoal } from './plannerGoal.ts'
import {
  applyPlan, buildUpgradePlan, planTargets, PRIORITY_LABEL, UPGRADE_PART_KINDS, UPGRADE_PART_LABEL,
  type PlanItem, type PricedProduct, type RecommendPriority, type UpgradePartKind, type UpgradePlan,
} from './upgradeRecommendation.ts'

const PRIORITY_HINT: Record<RecommendPriority, string> = {
  PERFORMANCE: '예산 안에서 가장 크게 좋아지는 조합',
  BUDGET: '목표를 채우는 가장 저렴한 조합',
}

type Products = Partial<Record<UpgradePartKind, PricedProduct[]>>
type Load = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; products: Products }

// 화면 전환 사이에 같은 목록을 다시 받지 않는다. 새로고침하면 다시 받는다.
let cache: Products | null = null

// 팀 중앙 가격 서버(Worker)의 자료만 쓴다. 로컬 DB에만 있는 판매가는 쓰지 않는다. 참고가는 중앙 서버에서만 온다.
function toPriced(product: CatalogProduct): PricedProduct | null {
  const current = product.priceStatus?.origin === 'SHARED' ? product.currentPrice : null
  const estimate = product.referenceEstimate
  const price = current ?? estimate
  if (!price || !Number.isSafeInteger(price.amountKrw) || price.amountKrw <= 0) return null
  return {
    id: product.id, name: `${product.manufacturer} ${product.modelName}`, priceKrw: price.amountKrw,
    priceKind: current ? 'CURRENT' : 'ESTIMATE',
    priceSource: current?.sourceName ?? null, priceUrl: current?.sourceUrl ?? null,
  }
}

async function loadKind(kind: UpgradePartKind, signal: AbortSignal): Promise<PricedProduct[]> {
  const products: PricedProduct[] = []
  // 카탈로그가 커져도 5페이지(500개)까지만 본다.
  for (let page = 0; page < 5; page += 1) {
    const result = await catalogClient.search(kind, '', { page, size: 100, signal })
    for (const item of result.items) {
      const priced = toPriced(item)
      if (priced && !products.some((known) => known.name === priced.name)) products.push(priced)
    }
    if (page + 1 >= result.totalPages) break
  }
  return products
}

function useProducts(): Load {
  const [load, setLoad] = useState<Load>(() => cache ? { status: 'ready', products: cache } : { status: 'loading' })
  useEffect(() => {
    if (cache) return
    const controller = new AbortController()
    Promise.all(UPGRADE_PART_KINDS.map((kind) => loadKind(kind, controller.signal)))
      .then((lists) => {
        cache = Object.fromEntries(UPGRADE_PART_KINDS.map((kind, index) => [kind, lists[index]]))
        setLoad({ status: 'ready', products: cache })
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setLoad({ status: 'error', message: error instanceof Error ? error.message : '알 수 없는 오류' })
      })
    return () => controller.abort()
  }, [])
  return load
}

const won = (amount: number) => `₩${amount.toLocaleString('ko-KR')}`

function PriceLine({ product }: { product: PricedProduct }) {
  return <span className="planner-price-line">
    <strong>{won(product.priceKrw)}</strong>
    <small>{product.priceKind === 'CURRENT'
      ? <>현재가{product.priceSource && ' · '}{product.priceUrl
        ? <a href={product.priceUrl} target="_blank" rel="noreferrer">{product.priceSource}</a> : product.priceSource}</>
      : '참고 추정가'}</small>
  </span>
}

const meetsText = (meets: boolean | null) => meets === null ? '목표 기준 없음' : meets ? '목표 충족' : '목표 미달'

/** 최종 구성: 내 PC의 모든 부품을 순서대로 보여 주고, 바뀌는 부품만 새 제품으로 바꿔 표시한다. */
function FinalBuild({ parts, plan }: { parts: PartInput[]; plan: UpgradePlan }) {
  const finalParts = applyPlan(parts, plan)
  const changed = plan.items.filter((item) => item.choice).length
  return <div className="planner-final">
    <h3>최종 구성 <small>교체 {changed}개 · 나머지는 지금 부품 그대로</small></h3>
    <ul>{finalParts.map((part, index) => {
      const item: PlanItem | undefined = plan.items.find((candidate) => candidate.kind === part.type)
      const choice = item?.choice && part.displayName === item.choice.name ? item.choice : null
      return <li key={`${part.type}-${index}`} className={choice ? 'is-replaced' : undefined}>
        <span className="planner-final-type">{getPartTypeInfo(part.type).label}</span>
        <div className="planner-final-name">
          <strong>{part.displayName}{!choice && part.quantity > 1 && ` × ${part.quantity}`}</strong>
          {choice && item
            ? <small>기존 {item.currentLabel} → 교체 · {choice.ratio ? `현재의 약 ${choice.ratio.toFixed(1)}배` : '성능 비교 자료 없음'}
              {' · '}{meetsText(item.meets)}</small>
            : <small>지금 부품 유지{item?.note && ` · ${item.note}`}</small>}
        </div>
        <span className="planner-final-status">{choice ? <PriceLine product={choice} />
          : <span className="badge badge--empty">유지</span>}</span>
      </li>
    })}</ul>
  </div>
}

function FrameChange({ parts, plan, goal }: { parts: PartInput[]; plan: UpgradePlan; goal: UpgradeGoal }) {
  const games = goal.games.filter(supportsFrameEstimate)
  if (!games.length || !plan.items.some((item) => item.choice)) return null
  const after = applyPlan(parts, plan)
  const rows = games.flatMap((game) => {
    const before = estimateGameFrames(parts, game, goal.resolution, goal.targetFps)
    const next = estimateGameFrames(after, game, goal.resolution, goal.targetFps)
    return before.map((estimate, index) => ({ game, preset: estimate.preset.label, before: estimate, after: next[index] }))
  })
  return <div className="planner-frame-change">
    <h3>교체 후 예상 프레임 <small>{goal.resolution} · 목표 {goal.targetFps}fps · 추정</small></h3>
    <table>
      <thead><tr><th>게임 · 품질</th><th>지금</th><th>최종 구성</th></tr></thead>
      <tbody>{rows.map((row) => <tr key={`${row.game}-${row.preset}`}>
        <td>{row.game} · {row.preset}</td><td>{frameText(row.before)}</td>
        <td className={row.after?.target === 'MEETS' ? 'is-meets' : undefined}>{row.after ? frameText(row.after) : '추정 불가'}
          {row.after?.cpuLimited && <small> CPU 한계</small>}</td>
      </tr>)}</tbody>
    </table>
  </div>
}

export function UpgradeRecommendationPanel({ parts, goal, priority, onPriorityChange, kinds, onKindsChange }: {
  parts: PartInput[]
  goal: UpgradeGoal
  priority: RecommendPriority
  onPriorityChange: (priority: RecommendPriority) => void
  // 사용자가 고른 교체 대상. null이면 진단에서 부족한 부품 전부.
  kinds: UpgradePartKind[] | null
  onKindsChange: (kinds: UpgradePartKind[]) => void
}) {
  const load = useProducts()
  const targets = planTargets(parts, goal)
  const selected = kinds ?? UPGRADE_PART_KINDS.filter((kind) => targets[kind]?.needed === true)
  const plan = load.status === 'ready' ? buildUpgradePlan({ parts, productsByKind: load.products, kinds: selected, targets,
    priority, budgetWon: goal.budgetWon, cpuMetric: goal.purpose === 'GAME' ? 'GAME' : 'WORK' }) : null
  const toggle = (kind: UpgradePartKind) => onKindsChange(UPGRADE_PART_KINDS.filter((value) =>
    value === kind ? !selected.includes(kind) : selected.includes(value)))

  return <section className="planner-card planner-plan-card" aria-labelledby="planner-plan-title">
    <div className="planner-card-head"><h2 id="planner-plan-title">추천 플랜</h2>
      <span className="badge badge--empty">예산 {won(goal.budgetWon)}</span></div>

    <div className="planner-priority" role="group" aria-label="추천 우선순위">
      {(['PERFORMANCE', 'BUDGET'] as RecommendPriority[]).map((value) =>
        <button key={value} type="button" aria-pressed={priority === value} onClick={() => onPriorityChange(value)}>
          <strong>{PRIORITY_LABEL[value]}</strong><span>{PRIORITY_HINT[value]}</span>
        </button>)}
    </div>

    <div className="planner-part-choice">
      <span className="muted">바꿀 부품 (진단에서 부족한 부품이 기본으로 선택됩니다)</span>
      <div className="planner-choice" role="group" aria-label="바꿀 부품">
        {UPGRADE_PART_KINDS.map((kind) => <button key={kind} type="button" aria-pressed={selected.includes(kind)}
          onClick={() => toggle(kind)}>
          {UPGRADE_PART_LABEL[kind]}{targets[kind]?.needed === true ? ' · 부족' : targets[kind]?.needed === false ? ' · 충분' : ''}
        </button>)}
      </div>
    </div>

    {load.status === 'loading' && <p className="muted" role="status">카탈로그 제품과 가격을 불러오는 중…</p>}
    {load.status === 'error' && <p className="error" role="alert">제품 목록을 불러오지 못했습니다: {load.message}</p>}
    {plan && <>
      <div className="planner-plan-summary">
        <div><span>추가 비용</span><strong>{won(plan.totalKrw)}</strong>
          <small>예산 {won(plan.budgetWon)} · 남는 금액 {won(Math.max(0, plan.budgetWon - plan.totalKrw))}</small></div>
        <p>{plan.reason}{plan.fullFixKrw !== null && ` 부족한 부품을 모두 채우려면 약 ${won(plan.fullFixKrw)}이 필요합니다.`}</p>
      </div>
      <FinalBuild parts={parts} plan={plan} />
      {goal.purpose === 'GAME' && <FrameChange parts={parts} plan={plan} goal={goal} />}
      <p className="planner-caveat">팀 중앙 가격 서버에 가격이 있는 제품만 비교합니다. 판매가 제품을 먼저 고르고, 판매가가 없는 종류만
        참고 추정가 제품을 씁니다. 메인보드는 그대로 두므로 CPU 소켓·메모리 규격이 같은 제품만 고릅니다.
        성능은 공개 벤치마크로 계산한 추정치이며, 파워·케이스 호환성은 다음 단계에서 확인하세요.</p>
    </>}
  </section>
}
