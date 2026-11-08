import { useEffect, useMemo } from 'react'
import { isString } from '../../lib/pageState.ts'
import { replacePlannerStep, usePlannerStep, type PlannerStep } from '../../lib/router.ts'
import { usePageState } from '../../lib/usePageState.ts'
import type { PartInput } from '../pc-scan/types.ts'
import { getPartTypeInfo } from '../pc/partCategories.ts'
import { GameRequirementsDiagnosis } from './GameRequirementsDiagnosis.tsx'
import { WorkloadDiagnosis } from './WorkloadDiagnosis.tsx'
import { UpgradeGoalForm } from './UpgradeGoalForm.tsx'
import { defaultGoal, goalIsReady, goalSummary, isBudgetInRange, isUpgradeGoal, withGoalDefaults } from './plannerGoal.ts'
import { PlannerStepper } from './PlannerStepper.tsx'
import { UpgradeRecommendationPanel } from './UpgradeRecommendationPanel.tsx'
import { useUpgradePlan } from './useUpgradePlan.ts'
import {
  isPlanOption, isPlanOverrides, type PlanOption, type PlanOverrides, type UpgradePartKind,
} from './upgradeRecommendation.ts'
import './planner.css'

// 호환성 검사는 추천 플랜(4단계) 아래에 함께 보여 주므로 단계는 4개다.
type Step = 2 | 3 | 4

// null이면 진단에서 부족한 부품을 그대로 쓴다.
const isIdList = (value: unknown): value is string[] =>
  Array.isArray(value) && value.length <= 100 && value.every((item) => typeof item === 'string' && item.trim().length > 0 && item.length <= 128)

type Props = {
  stateKey: string
  pcName: string
  parts: PartInput[]
  onBackToPc: () => void
}

// 진단 아래에 접어 두는 내 PC 부품 목록
function CurrentPc({ pcName, parts }: Pick<Props, 'pcName' | 'parts'>) {
  return <details className="planner-card planner-details">
    <summary>내 PC 부품 보기 · {pcName.trim() || '이름 없는 PC'} ({parts.length}개)</summary>
    <div className="planner-part-grid">
      {parts.map((part, index) => <div key={`${part.type}-${index}`} className="planner-part-item">
        <span>{getPartTypeInfo(part.type).label}</span><strong>{part.displayName}{part.quantity > 1 && ` × ${part.quantity}`}</strong>
      </div>)}
    </div>
  </details>
}

export function UpgradePlanner({ stateKey, pcName, parts, onBackToPc }: Props) {
  const [requestedStep, setStep] = usePlannerStep(2)
  const [storedGoal, setGoal] = usePageState(`${stateKey}goal`, defaultGoal, isUpgradeGoal)
  const goal = useMemo(() => withGoalDefaults(storedGoal), [storedGoal])
  const [budgetText, setBudgetText] = usePageState(`${stateKey}budgetText`, String(goal.budgetWon), isString)
  const budgetWon = Number(budgetText)
  // 임시 상태가 없는 직접 주소·초기화 후 방문 기록에서는 조건부터 다시 입력한다.
  // 예전 주소의 5단계(호환성)는 이제 추천 플랜에 포함되어 4단계로 연다.
  const step: Step = requestedStep < 2 || !isBudgetInRange(budgetWon) || !goalIsReady(goal) ? 2 : Math.min(requestedStep, 4) as Step
  useEffect(() => {
    // 이전 주소에서 조건 화면으로 돌아왔다면 입력을 고치는 중에 진단 화면이 열리지 않게 한다.
    if (requestedStep > 2 && step === 2) replacePlannerStep(2)
  }, [requestedStep, step])
  // 추천 안(가성비·추천·최고 성능)과 그 안에서 사용자가 직접 바꾼 부품
  const [option, setOption] = usePageState<PlanOption>(`${stateKey}planOption`, 'BALANCED', isPlanOption)
  const [overrides, setOverrides] = usePageState<PlanOverrides>(`${stateKey}planOverrides`, {}, isPlanOverrides)
  // 호환성 검사에서 맞지 않아 후보에서 뺀 카탈로그 상품
  const [excludedIds, setExcludedIds] = usePageState<string[]>(`${stateKey}excludedProducts`, [], isIdList)
  // 진단을 보는 동안 추천에 필요한 상품과 가격을 준비한다.
  const planState = useUpgradePlan({ parts, goal, option, overrides, excludedIds, enabled: step >= 3 })
  const changeBudget = (value: string) => {
    setBudgetText(value)
    const amount = Number(value)
    if (isBudgetInRange(amount)) setGoal((current) => ({ ...current, budgetWon: amount }))
  }
  const chooseOption = (next: PlanOption) => {
    if (next === option) return
    setOption(next)
    if (Object.keys(overrides).length > 0) setOverrides({})
  }
  const override = (kind: UpgradePartKind, value: string | null) => {
    const next = { ...overrides }
    if (value === null) delete next[kind]
    else next[kind] = value
    setOverrides(next)
  }

  if (step === 2) return <UpgradeGoalForm stateKey={stateKey} pcName={pcName} parts={parts} goal={goal} onChange={setGoal}
    budgetText={budgetText} onBudgetTextChange={changeBudget}
    onBack={onBackToPc} onNext={() => setStep(3)} onStep={setStep} />

  return <div className="planner-shell">
    <PlannerStepper current={step} canGo={(target) => target === 1 || target === 2 || goalIsReady(goal)}
      onGo={(target) => target === 1 ? onBackToPc() : setStep(target)} />
    <header className="planner-heading">
      <button type="button" className="button button--ghost" onClick={() => setStep((step - 1) as PlannerStep)}>← 이전 단계</button>
      <p className="eyebrow">UPGRADE PLAN</p>
      <h1>{step === 3 ? '현재 구성 진단' : '추천 플랜'}</h1>
      <p className="planner-goal-line">{goalSummary(goal)} · 예산 ₩{goal.budgetWon.toLocaleString('ko-KR')}</p>
    </header>
    {step === 3 && <div className="planner-main">
        {goal.purpose === 'GAME' ? <GameRequirementsDiagnosis games={goal.games} parts={parts} resolution={goal.resolution} targetFps={goal.targetFps} /> :
          goal.purpose === 'CREATION' || goal.purpose === 'DEVELOPMENT'
            ? <WorkloadDiagnosis programs={goal.programs} parts={parts} workload={goal.workload} videoResolution={goal.videoResolution} /> :
          <section className="planner-card"><h2>사무·학업 진단은 준비 중입니다</h2>
            <p className="muted">다음 단계에서 예산에 맞는 추천 플랜을 볼 수 있습니다.</p></section>}
      <CurrentPc pcName={pcName} parts={parts} />
    </div>}
    {step === 4 && <div className="planner-main">
      <UpgradeRecommendationPanel parts={parts} goal={goal} state={planState} option={option} onOptionChange={chooseOption}
        overrides={overrides} onOverride={override} onResetOverrides={() => setOverrides({})}
        excludedCount={excludedIds.length} onResetExcluded={() => setExcludedIds([])} onLinkParts={onBackToPc}
        onExclude={(ids) => { setExcludedIds([...new Set([...excludedIds, ...ids])]); setOverrides({}) }} />
    </div>}
    <div className="planner-actions">
      {step < 4 ? <button type="button" className="button button--primary button--lg" onClick={() => setStep((step + 1) as PlannerStep)}>
        추천 플랜 보기 →</button> :
        <button type="button" className="button button--ghost button--lg" onClick={onBackToPc}>내 PC로 돌아가기</button>}
    </div>
  </div>
}
