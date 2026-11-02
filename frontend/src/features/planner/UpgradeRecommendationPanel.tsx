import type { PartInput } from '../pc-scan/types.ts'
import { getPartTypeInfo } from '../pc/partCategories.ts'
import { estimateGameFrames, frameText, supportsFrameEstimate } from './frameEstimate.ts'
import type { UpgradeGoal } from './plannerGoal.ts'
import {
  applyPlan, PRIORITY_LABEL, UPGRADE_PART_KINDS, UPGRADE_PART_LABEL,
  type PlanItem, type PricedProduct, type RecommendPriority, type UpgradePartKind, type UpgradePlan,
} from './upgradeRecommendation.ts'
import type { UpgradePlanState } from './useUpgradePlan.ts'

const PRIORITY_HINT: Record<RecommendPriority, string> = {
  PERFORMANCE: '예산 안에서 가장 크게 좋아지는 조합',
  BUDGET: '목표를 채우는 가장 저렴한 조합',
}

const won = (amount: number) => `₩${amount.toLocaleString('ko-KR')}`

export function PriceLine({ product }: { product: PricedProduct }) {
  return <span className="planner-price-line">
    <strong>{won(product.priceKrw)}</strong>
    <small>{product.priceKind === 'CURRENT'
      ? <>현재가{product.priceSource && ' · '}{product.priceUrl
        ? <a href={product.priceUrl} target="_blank" rel="noreferrer">{product.priceSource}</a> : product.priceSource}</>
      : '참고 추정가'}</small>
  </span>
}

/** 최종 구성: 내 PC의 모든 부품을 순서대로 보여 주고, 바뀌는 부품만 새 제품으로 바꿔 표시한다. */
function FinalBuild({ parts, plan }: { parts: PartInput[]; plan: UpgradePlan }) {
  const finalParts = applyPlan(parts, plan)
  const changed = plan.items.filter((item) => item.choice).length
  return <div className="planner-final">
    <h3>최종 구성 <small>교체 {changed}개</small></h3>
    <ul>{finalParts.map((part, index) => {
      const item: PlanItem | undefined = plan.items.find((candidate) => candidate.kind === part.type)
      const choice = item?.choice && part.displayName === item.choice.name ? item.choice : null
      return <li key={`${part.type}-${index}`} className={choice ? 'is-replaced' : undefined}>
        <span className="planner-final-type">{getPartTypeInfo(part.type).label}</span>
        <div className="planner-final-name">
          <strong>{part.displayName}{!choice && part.quantity > 1 && ` × ${part.quantity}`}</strong>
          {choice && item
            ? <small>기존 {item.currentLabel}{choice.ratio && ` · 약 ${choice.ratio.toFixed(1)}배`}{item.meets === false && ' · 목표 미달'}</small>
            : item?.note && <small>{item.note}</small>}
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

export function UpgradeRecommendationPanel({ parts, goal, priority, onPriorityChange, onKindsChange, state, excludedCount, onResetExcluded }: {
  parts: PartInput[]
  goal: UpgradeGoal
  priority: RecommendPriority
  onPriorityChange: (priority: RecommendPriority) => void
  onKindsChange: (kinds: UpgradePartKind[]) => void
  state: UpgradePlanState
  // 호환성 검사에서 맞지 않아 뺀 제품 수
  excludedCount: number
  onResetExcluded: () => void
}) {
  const { load, targets, selected, plan } = state
  const toggle = (kind: UpgradePartKind) => onKindsChange(UPGRADE_PART_KINDS.filter((value) =>
    value === kind ? !selected.includes(kind) : selected.includes(value)))

  return <section className="planner-card planner-plan-card" aria-label="추천 플랜">


    <div className="planner-priority" role="group" aria-label="추천 우선순위">
      {(['PERFORMANCE', 'BUDGET'] as RecommendPriority[]).map((value) =>
        <button key={value} type="button" aria-pressed={priority === value} onClick={() => onPriorityChange(value)}>
          <strong>{PRIORITY_LABEL[value]}</strong><span>{PRIORITY_HINT[value]}</span>
        </button>)}
    </div>

    <div className="planner-part-choice">
      <span className="muted">바꿀 부품</span>
      <div className="planner-choice" role="group" aria-label="바꿀 부품">
        {UPGRADE_PART_KINDS.map((kind) => <button key={kind} type="button" aria-pressed={selected.includes(kind)}
          onClick={() => toggle(kind)}>
          {UPGRADE_PART_LABEL[kind]}{targets[kind]?.needed === true ? ' · 부족' : targets[kind]?.needed === false ? ' · 충분' : ''}
        </button>)}
      </div>
    </div>

    {load.status === 'loading' && <p className="muted" role="status">제품과 가격을 불러오는 중…</p>}
    {load.status === 'error' && <p className="error" role="alert">제품 목록을 불러오지 못했습니다: {load.message}</p>}
    {plan && <>
      <div className="planner-plan-summary">
        <div><span>추가 비용</span><strong>{won(plan.totalKrw)}</strong>
          <small>남는 예산 {won(Math.max(0, plan.budgetWon - plan.totalKrw))}</small></div>
        <p>{plan.reason}{plan.fullFixKrw !== null && ` 모두 바꾸려면 약 ${won(plan.fullFixKrw)}`}</p>
      </div>
      {excludedCount > 0 && <p className="planner-warning">호환되지 않는 제품 {excludedCount}개 제외{' '}
        <button type="button" className="button button--ghost" onClick={onResetExcluded}>초기화</button></p>}
      <FinalBuild parts={parts} plan={plan} />
      {goal.purpose === 'GAME' && <FrameChange parts={parts} plan={plan} goal={goal} />}
      <p className="planner-caveat">가격·성능은 추정치입니다. 메인보드는 그대로 두고 호환되는 제품만 고릅니다.</p>
    </>}
  </section>
}
