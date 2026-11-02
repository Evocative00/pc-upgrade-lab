import { usePlannerStep, type PlannerStep } from '../../lib/router.ts'
import { usePageState } from '../../lib/usePageState.ts'
import type { PartInput } from '../pc-scan/types.ts'
import { getPartTypeInfo } from '../pc/partCategories.ts'
import { GameRequirementsDiagnosis } from './GameRequirementsDiagnosis.tsx'
import { WorkloadDiagnosis } from './WorkloadDiagnosis.tsx'
import { UpgradeGoalForm } from './UpgradeGoalForm.tsx'
import { defaultGoal, goalIsReady, goalSummary, isUpgradeGoal, withGoalDefaults } from './plannerGoal.ts'
import { UpgradeCompatibilityPanel } from './UpgradeCompatibilityPanel.tsx'
import { UpgradeRecommendationPanel } from './UpgradeRecommendationPanel.tsx'
import { useUpgradePlan } from './useUpgradePlan.ts'
import { isRecommendPriority, isUpgradePartKind, type RecommendPriority, type UpgradePartKind } from './upgradeRecommendation.ts'
import './planner.css'

type Step = 2 | 3 | 4 | 5

// null이면 진단에서 부족한 부품을 그대로 쓴다.
const isPartKindList = (value: unknown): value is UpgradePartKind[] | null =>
  value === null || Array.isArray(value) && value.length <= 3 && new Set(value).size === value.length && value.every(isUpgradePartKind)
const isIdList = (value: unknown): value is string[] =>
  Array.isArray(value) && value.length <= 100 && value.every((item) => typeof item === 'string' && item.trim().length > 0 && item.length <= 128)

type Props = {
  stateKey: string
  pcName: string
  parts: PartInput[]
  onBackToPc: () => void
}

const STEP_LABELS = ['내 PC', '예산·목적', '진단', '추천 플랜', '호환성']

function Stepper({ current }: { current: Step }) {
  return <nav className="planner-steps" aria-label="업그레이드 진행 단계">
    {STEP_LABELS.map((label, index) => {
      const step = index + 1
      return <span key={label} className={step === current ? 'is-current' : step < current ? 'is-complete' : undefined}
        aria-current={step === current ? 'step' : undefined}>{step < current ? '✓' : step} {label}</span>
    })}
  </nav>
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
  const goal = withGoalDefaults(storedGoal)
  // 임시 상태가 없는 직접 주소·초기화 후 방문 기록에서는 조건부터 다시 입력한다.
  const step: Step = requestedStep < 2 || !goalIsReady(goal) ? 2 : requestedStep as Step
  const [priority, setPriority] = usePageState<RecommendPriority>(`${stateKey}priority`, 'PERFORMANCE', isRecommendPriority)
  const [upgradeKinds, setUpgradeKinds] = usePageState<UpgradePartKind[] | null>(`${stateKey}upgradeParts`, null, isPartKindList)
  // 호환성 검사(5단계)에서 맞지 않아 후보에서 뺀 카탈로그 상품
  const [excludedIds, setExcludedIds] = usePageState<string[]>(`${stateKey}excludedProducts`, [], isIdList)
  const planState = useUpgradePlan({ parts, goal, priority, kinds: upgradeKinds, excludedIds, enabled: step >= 4 })

  if (step === 2) return <UpgradeGoalForm stateKey={stateKey} pcName={pcName} parts={parts} goal={goal} onChange={setGoal}
    onBack={onBackToPc} onNext={() => setStep(3)} />

  return <div className="planner-shell">
    <Stepper current={step} />
    <header className="planner-heading">
      <button type="button" className="button button--ghost" onClick={() => setStep((step - 1) as PlannerStep)}>← 이전 단계</button>
      <p className="eyebrow">UPGRADE PLAN</p>
      <h1>{step === 3 ? '현재 구성 진단' : step === 4 ? '추천 플랜' : '호환성 상세'}</h1>
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
      <UpgradeRecommendationPanel parts={parts} goal={goal} priority={priority} onPriorityChange={setPriority}
        onKindsChange={setUpgradeKinds} state={planState} excludedCount={excludedIds.length} onResetExcluded={() => setExcludedIds([])} />
    </div>}
    {step === 5 && <UpgradeCompatibilityPanel parts={parts} state={planState} onLinkParts={onBackToPc}
      onExclude={(ids) => setExcludedIds([...new Set([...excludedIds, ...ids])])} />}
    <div className="planner-actions">
      {step < 5 ? <button type="button" className="button button--primary button--lg" onClick={() => setStep((step + 1) as PlannerStep)}>
        {step === 3 ? '추천 플랜 보기' : '호환성 상세 보기'} →</button> :
        <button type="button" className="button button--ghost button--lg" onClick={onBackToPc}>내 PC로 돌아가기</button>}
    </div>
  </div>
}
