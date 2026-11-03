import type { PartInput } from '../pc-scan/types.ts'
import { getPartTypeInfo } from '../pc/partCategories.ts'
import { CPU_GAMING_DATASET } from './cpuGamingScores.ts'
import { estimateGameFrames, frameText, supportsFrameEstimate } from './frameEstimate.ts'
import type { UpgradeGoal } from './plannerGoal.ts'
import {
  applyPlan, KEEP, PLAN_OPTION_LABEL, PLAN_OPTIONS, UPGRADE_PART_KINDS, UPGRADE_PART_LABEL,
  type PlanOption, type PlanOverrides, type PricedProduct, type UpgradePartKind, type UpgradePlan,
} from './upgradeRecommendation.ts'
import { COMPAT_CLASS, COMPAT_TEXT, usePlanCompatibility } from './planCompatibility.ts'
import { CompatibilityFooter } from './UpgradeCompatibilityPanel.tsx'
import type { UpgradePlanState } from './useUpgradePlan.ts'
import { diagnoseProgram } from './workloadEstimate.ts'

const OPTION_HINT: Record<PlanOption, string> = {
  VALUE: '예산 안에서 가격 대비 성능이 가장 좋은',
  BALANCED: '예산 안에서 가장 빠르게',
  BEST: '예산의 1.5배 안에서 가장 빠르게',
}

const won = (amount: number) => `₩${amount.toLocaleString('ko-KR')}`

export function PriceLine({ product }: { product: PricedProduct }) {
  return <span className="planner-price-line">
    <strong>{won(product.priceKrw)}</strong>
    <small>{product.priceKind === 'PAST'
      ? <>지난 판매가 · {product.observedAt ? `${Number(product.observedAt.slice(5, 7))}/${Number(product.observedAt.slice(8, 10))} 관측` : '관측일 미상'}
        {product.priceSource && ' · '}{product.priceUrl
          ? <a href={product.priceUrl} target="_blank" rel="noreferrer">{product.priceSource}</a> : product.priceSource}</>
      : product.priceKind === 'CURRENT'
      ? <>현재가{product.priceSource && ' · '}{product.priceUrl
        ? <a href={product.priceUrl} target="_blank" rel="noreferrer">{product.priceSource}</a> : product.priceSource}</>
      : '참고 추정가'}</small>
  </span>
}

type KeyMetric = { label: string; before: string; after: string; meets: boolean | null; target: string | null }

const presetName = (label: string) => label.replace(/\s*\(.*\)$/, '')

/**
 * 안을 비교할 대표 수치 한 가지.
 * 게임: 첫 게임에서 목표 FPS를 넘는 가장 높은 화질의 예상 프레임(없으면 가장 낮은 화질). 추천 계산도 같은 기준으로 목표 충족을 본다.
 * 작업: 첫 프로그램의 첫 예상 성능.
 */
function keyMetric(parts: PartInput[], plan: UpgradePlan | null, goal: UpgradeGoal): KeyMetric | null {
  const after = plan ? applyPlan(parts, plan) : parts
  if (goal.purpose === 'GAME') {
    const game = goal.games.find(supportsFrameEstimate)
    if (!game) return null
    const before = estimateGameFrames(parts, game, goal.resolution, goal.targetFps)
    const next = estimateGameFrames(after, game, goal.resolution, goal.targetFps)
    const meeting = next.map((estimate, index) => ({ estimate, index })).filter(({ estimate }) => estimate.target === 'MEETS')
    const index = meeting.length ? meeting[meeting.length - 1].index : 0
    if (!before[index] || !next[index]) return null
    return { label: `${game} · ${presetName(next[index].preset.label)}`, before: frameText(before[index]), after: frameText(next[index]),
      meets: next[index].target === null ? null : next[index].target === 'MEETS', target: `목표 ${goal.targetFps}fps` }
  }
  if (goal.purpose === 'CREATION' || goal.purpose === 'DEVELOPMENT') {
    const program = goal.programs[0]
    const before = program ? diagnoseProgram(program, parts, goal.workload, goal.videoResolution)?.estimates[0] : undefined
    const next = program ? diagnoseProgram(program, after, goal.workload, goal.videoResolution)?.estimates[0] : undefined
    if (!before || !next) return null
    return { label: `${program} · ${before.title}`, before: before.value, after: next.value, meets: null, target: null }
  }
  return null
}

// 앞 카드와 같은 조합일 때 표시(조사까지 맞춘 문구)
const SAME_AS: Record<PlanOption, string> = { VALUE: '가성비와 같은 구성', BALANCED: '추천과 같은 구성', BEST: '최고 성능과 같은 구성' }

// 같은 제품을 고른 안인지(카드에 '같은 구성' 표시용)
const planKey = (plan: UpgradePlan) => plan.items.map((item) => item.choice?.id ?? '-').join('|')
// 카드에 보일 짧은 이름: 제조사·쿨러 이름을 줄여 칩셋 위주로 보여 준다.
const shortName = (name: string) => name.replace(/^(AMD|Intel|NVIDIA|MSI|Sapphire|Gigabyte|ASUS|ASRock|Corsair|G\.SKILL|Kingston|Samsung)\s+/i, '')
  .replace(/\s+(VENTUS|WINDFORCE|PULSE|EAGLE|GAMING|Challenger|Phantom|TUF|DUAL).*$/i, '')

function Diagnosis({ state, metric }: { state: UpgradePlanState; metric: KeyMetric | null }) {
  const short = state.selected.filter((kind) => state.targets[kind]?.needed === true)
  const unknown = UPGRADE_PART_KINDS.filter((kind) => state.targets[kind]?.needed == null)
  return <p className="planner-diagnosis-line">
    {short.length ? <><strong>{short.map((kind) => UPGRADE_PART_LABEL[kind]).join('·')}</strong>가 목표에 못 미칩니다.</>
      : unknown.length ? <><strong>목표 충족 여부를 확인할 수 없습니다.</strong> 부품 사양과 목표 자료를 확인해 주세요.</>
      : <><strong>지금 구성으로 목표를 충족합니다.</strong> 더 빠르게 하려면 아래 안을 참고하세요.</>}
    {unknown.length > 0 && <span> 확인 필요: {unknown.map((kind) => UPGRADE_PART_LABEL[kind]).join('·')}</span>}
    {metric && <span> {metric.label} 지금 {metric.before}{metric.target && ` (${metric.target})`}</span>}
  </p>
}

function OptionCards({ parts, goal, state, option, onOptionChange }: {
  parts: PartInput[]; goal: UpgradeGoal; state: UpgradePlanState; option: PlanOption; onOptionChange: (option: PlanOption) => void
}) {
  const plans = state.options?.plans
  if (!plans) return null
  return <div className="planner-option-cards" role="radiogroup" aria-label="추천 안">
    {PLAN_OPTIONS.map((value, index) => {
      const plan = plans[value]
      const metric = keyMetric(parts, plan, goal)
      const changed = plan.items.filter((item) => item.choice)
      const sameAs = PLAN_OPTIONS.slice(0, index).find((other) => planKey(plans[other]) === planKey(plan))
      return <button key={value} type="button" role="radio" aria-checked={option === value}
        className={`planner-option${value === 'BALANCED' ? ' is-recommended' : ''}`} onClick={() => onOptionChange(value)}>
        <span className="planner-option-head"><strong>{PLAN_OPTION_LABEL[value]}</strong><small>{OPTION_HINT[value]}</small></span>
        <span className="planner-option-price">{won(plan.totalKrw)}</span>
        {changed.some((item) => item.choice?.priceKind === 'PAST') && <small>지난 판매가 포함 · 실제 구매가 확인 필요</small>}
        {changed.some((item) => item.choice?.priceKind === 'ESTIMATE') && <small>참고 추정가 포함</small>}
        {plan.totalKrw > plan.budgetWon && <span className="planner-option-over">예산 {won(plan.totalKrw - plan.budgetWon)} 초과</span>}
        <span className="planner-option-parts">{changed.length
          ? changed.map((item) => `${UPGRADE_PART_LABEL[item.kind]} ${shortName(item.choice!.name)}`).join(' + ')
          : plan.items.some((item) => item.target.needed === true)
            ? `바꾸지 않음 · 예산 부족${state.options?.cheapestFixKrw ? ` (${won(state.options.cheapestFixKrw)}부터 해결 가능)` : ''}`
            : state.options?.hasUnknownTargets ? '바꾸지 않음 · 목표 충족 확인 필요' : '바꾸지 않음 · 지금 구성으로 충분'}</span>
        {metric && <span className={`planner-option-metric${metric.meets === true ? ' is-meets' : metric.meets === false ? ' is-below' : ''}`}>
          {metric.label.split(' · ').at(-1)} {metric.after}{metric.meets === false && ' · 목표 미달'}</span>}
        {sameAs && <small className="muted">{SAME_AS[sameAs]}</small>}
      </button>
    })}
  </div>
}

/** 최종 구성: 내 PC의 모든 부품을 순서대로 보여 주고, 검토하는 부품은 다른 후보나 유지로 직접 바꿀 수 있다. */
/** 최종 구성 + 호환성: 부품마다 교체·유지와 호환성 판정을 한 줄에 보여 준다. 부품 목록은 여기 한 곳에만 있다. */
function FinalBuild({ parts, plan, state, overrides, onOverride, onExclude, onLinkParts }: {
  parts: PartInput[]; plan: UpgradePlan; state: UpgradePlanState
  overrides: PlanOverrides; onOverride: (kind: UpgradePartKind, value: string | null) => void
  onExclude: (productIds: string[]) => void; onLinkParts: () => void
}) {
  const finalParts = applyPlan(parts, plan)
  const compat = usePlanCompatibility(finalParts, plan)
  const overall = compat.result?.status
  // 지금 구성에 없는 부품을 새로 추가하는 경우도 목록에 나오도록 최종 구성 기준으로 그린다.
  return <div className="planner-final">
    <div className="planner-final-head">
      <h3>최종 구성 <small>교체 {plan.items.filter((item) => item.choice).length}개{plan.custom && ' · 직접 바꾼 구성'}</small></h3>
      {compat.state.status === 'loading' ? <span className="muted" role="status">호환성 검사 중…</span>
        : overall && <span className={`planner-verdict planner-verdict--${COMPAT_CLASS[overall]}`}>호환성 {COMPAT_TEXT[overall]}</span>}
    </div>
    <ul>{finalParts.map((part, index) => {
      const partCompat = compat.byIndex[index]
      const item = plan.items.find((candidate) => candidate.kind === part.type)
      const choice = item?.choice && part.displayName === item.choice.name ? item.choice : null
      const candidates = item ? state.options?.candidates[item.kind] ?? [] : []
      const rowClass = [choice && 'is-replaced', partCompat?.status === 'INCOMPATIBLE' && 'is-bad'].filter(Boolean).join(' ')
      return <li key={`${part.type}-${index}`} className={rowClass || undefined}>
        <span className="planner-final-type">{getPartTypeInfo(part.type).label}
          {partCompat && <span className={`planner-verdict planner-verdict--${COMPAT_CLASS[partCompat.status]}`}>
            {COMPAT_TEXT[partCompat.status]}</span>}</span>
        <div className="planner-final-name">
          <strong>{part.displayName}{part.quantity > 1 && ` · 모듈 ${part.quantity}개`}</strong>
          {choice && item
            ? <small>기존 {item.currentLabel}{choice.ratio && ` · ${item.kind === 'RAM' ? '용량' : '점수'} 약 ${choice.ratio.toFixed(1)}배`}
              {item.meets === false ? ' · 목표 미달' : item.meets === null ? ' · 목표 충족 확인 필요' : ' · 목표 충족'}</small>
            : item?.note && <small>{item.note}</small>}
          {partCompat?.status === 'INCOMPATIBLE' && partCompat.messages.map((message) =>
            <small key={message} className="planner-compat-warning">{message}</small>)}
          {item && candidates.length > 0 && <label className="planner-swap">
            <span>다른 제품</span>
            <select value={overrides[item.kind] ?? (item.choice?.id ?? KEEP)}
              onChange={(event) => onOverride(item.kind, event.target.value)}>
              <option value={KEEP}>지금 부품 유지</option>
              {candidates.map((product) => <option key={product.id} value={product.id}>
                {shortName(product.name)} · {won(product.priceKrw)}{product.ratio ? ` · 약 ${product.ratio.toFixed(1)}배` : ''}
                {product.meetsTarget === false ? ' · 목표 미달' : ''}
              </option>)}
            </select>
          </label>}
        </div>
        <span className="planner-final-status">{choice ? <PriceLine product={choice} />
          : <span className="badge badge--empty">유지</span>}</span>
      </li>
    })}</ul>
    <CompatibilityFooter compat={compat} onExclude={onExclude} onLinkParts={onLinkParts} />
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
  return <details className="planner-details planner-frame-change">
    <summary>게임별 예상 프레임 전체 보기</summary>
    <table>
      <thead><tr><th>게임 · 품질</th><th>지금</th><th>최종 구성</th></tr></thead>
      <tbody>{rows.map((row) => <tr key={`${row.game}-${row.preset}`}>
        <td>{row.game} · {row.preset}</td><td>{frameText(row.before)}</td>
        <td className={row.after?.target === 'MEETS' ? 'is-meets' : undefined}>{row.after ? frameText(row.after) : '추정 불가'}
          {row.after?.cpuLimited && <small> CPU 한계</small>}</td>
      </tr>)}</tbody>
    </table>
  </details>
}

export function UpgradeRecommendationPanel({ parts, goal, state, option, onOptionChange, overrides, onOverride,
  excludedCount, onResetExcluded, onExclude, onLinkParts }: {
  parts: PartInput[]
  goal: UpgradeGoal
  state: UpgradePlanState
  option: PlanOption
  onOptionChange: (option: PlanOption) => void
  overrides: PlanOverrides
  // value가 null이면 직접 바꾼 내용을 지운다.
  onOverride: (kind: UpgradePartKind, value: string | null) => void
  // 호환성 검사에서 맞지 않아 뺀 제품 수
  excludedCount: number
  onResetExcluded: () => void
  // 호환되지 않는 교체 제품을 빼고 다시 추천 / 내 PC에서 카탈로그 연결
  onExclude: (productIds: string[]) => void
  onLinkParts: () => void
}) {
  const { load, plan } = state
  const nowMetric = keyMetric(parts, null, goal)
  const over = plan ? plan.totalKrw - plan.budgetWon : 0
  const metric = plan ? keyMetric(parts, plan, goal) : null
  const attributions = [...new Map((plan?.items.flatMap((item) => item.choice?.attributions ?? []) ?? [])
    .map((entry) => [entry.url, entry])).values()]

  return <section className="planner-card planner-plan-card" aria-label="추천 플랜">
    <Diagnosis state={state} metric={nowMetric} />
    {load.status === 'loading' && <p className="muted" role="status">제품과 가격을 불러오는 중…</p>}
    {load.status === 'error' && <div role="alert"><p className="error">제품 목록을 불러오지 못했습니다: {load.message}</p>
      <button type="button" className="button button--ghost" onClick={state.retry}>다시 불러오기</button></div>}
    <OptionCards parts={parts} goal={goal} state={state} option={option} onOptionChange={onOptionChange} />

    {plan && <>
      <div className="planner-plan-summary">
        <div><span>{PLAN_OPTION_LABEL[plan.option]}{plan.custom && ' (직접 수정)'}</span><strong>{won(plan.totalKrw)}</strong>
          <small className={over > 0 ? 'planner-over' : undefined}>
            {over > 0 ? `예산 ${won(over)} 초과` : `남는 예산 ${won(plan.budgetWon - plan.totalKrw)}`}</small></div>
        <p>{plan.reason}{metric && plan.items.some((item) => item.choice) && <> · {metric.label} {metric.before} → <strong>{metric.after}</strong></>}
          {plan.fullFixKrw !== null && plan.items.some((item) => item.target.needed === true && item.meets !== true)
            && ` · 모두 바꾸려면 약 ${won(plan.fullFixKrw)}`}
          {plan.custom && <>{' '}<button type="button" className="link-button" onClick={() => onOptionChange(plan.option)}>추천대로 되돌리기</button></>}</p>
      </div>
      {excludedCount > 0 && <p className="planner-warning">호환되지 않는 제품 {excludedCount}개 제외{' '}
        <button type="button" className="button button--ghost" onClick={onResetExcluded}>초기화</button></p>}
      <FinalBuild parts={parts} plan={plan} state={state} overrides={overrides} onOverride={onOverride}
        onExclude={onExclude} onLinkParts={onLinkParts} />
      {goal.purpose === 'GAME' && <FrameChange parts={parts} plan={plan} goal={goal} />}
      <p className="planner-caveat">가격·성능은 추정치입니다. 메인보드는 그대로 두고 소켓·DDR이 맞는 제품만 고르며,
        BIOS·파워·케이스 등은 위 최종 구성의 호환성 검사 결과와 확인 범위를 살펴보세요.</p>
      <details className="planner-details"><summary>계산 기준 보기</summary>
        <p>CPU 게임 점수에는 실측 외 단일 스레드·클럭·구조로 환산한 팀 추정이 포함됩니다.
          작업 CPU는 Blender, GPU는 게임 점수를 사용하며 실제 프로그램 성능 향상 배율을 보장하지 않습니다.</p>
        <a href={CPU_GAMING_DATASET.sourceUrl} target="_blank" rel="noreferrer">CPU 게임 점수 기준 자료</a>
        {attributions.map((entry) => <p key={entry.url}>{entry.notice}{' '}
          <a href={entry.url} target="_blank" rel="noreferrer">{entry.name}</a>{' · '}
          <a href={entry.licenseUrl} target="_blank" rel="noreferrer">{entry.license}</a></p>)}
      </details>
    </>}
  </section>
}
