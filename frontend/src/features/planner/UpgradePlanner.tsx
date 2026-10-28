import { usePlannerStep, type PlannerStep } from '../../lib/router.ts'
import { usePageState } from '../../lib/usePageState.ts'
import type { PartInput } from '../pc-scan/types.ts'
import { getPartTypeInfo } from '../pc/partCategories.ts'
import { GameRequirementsDiagnosis } from './GameRequirementsDiagnosis.tsx'
import { WorkloadDiagnosis } from './WorkloadDiagnosis.tsx'
import { UpgradeGoalForm } from './UpgradeGoalForm.tsx'
import { defaultGoal, goalIsReady, goalSummary, isUpgradeGoal } from './plannerGoal.ts'
import { recommendPrototypeFocus } from './prototypeRecommendation.ts'
import './planner.css'

type Step = 2 | 3 | 4 | 5

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

function CurrentPc({ pcName, parts }: Pick<Props, 'pcName' | 'parts'>) {
  return <section className="planner-card" aria-labelledby="planner-current-pc">
    <div className="planner-card-head"><h2 id="planner-current-pc">현재 PC · {pcName.trim() || '이름 없는 PC'}</h2>
      <span className="badge badge--auto">{parts.length}개 부품 입력됨</span></div>
    <div className="planner-part-grid">
      {parts.map((part, index) => <div key={`${part.type}-${index}`} className="planner-part-item">
        <span>{getPartTypeInfo(part.type).label}</span><strong>{part.displayName} · {part.quantity}개</strong>
      </div>)}
    </div>
  </section>
}

function ScopeNotice({ diagnosis }: { diagnosis: 'GAME' | 'WORKLOAD' | null }) {
  return <p className="planner-scope" role="status">
    {diagnosis === 'GAME'
      ? '공식 요구 사양, 공개 벤치마크 기준 상대 성능, 해상도별 예상 프레임(추정)을 함께 표시합니다. 예상 가격·특정 제품 선정과 교체 후 호환성은 아직 계산하지 않습니다.'
      : diagnosis === 'WORKLOAD'
        ? '제작사 공식 사양과 작업 규모별 RAM·VRAM, 공개 벤치마크 기준 예상 성능(추정)을 함께 표시합니다. 예상 가격·특정 제품 선정은 아직 계산하지 않습니다.'
        : '현재 화면은 흐름 확인용입니다. 제품 선정, 예상 가격·FPS와 교체 후 호환성 판정은 아직 계산하지 않습니다.'}
  </p>
}

export function UpgradePlanner({ stateKey, pcName, parts, onBackToPc }: Props) {
  const [requestedStep, setStep] = usePlannerStep(2)
  const [goal, setGoal] = usePageState(`${stateKey}goal`, defaultGoal, isUpgradeGoal)
  // 임시 상태가 없는 직접 주소·초기화 후 방문 기록에서는 조건부터 다시 입력한다.
  const step: Step = requestedStep < 2 || !goalIsReady(goal) ? 2 : requestedStep as Step
  const focus = goal.purpose === 'GAME' ? recommendPrototypeFocus(parts, goal.games) : null

  if (step === 2) return <UpgradeGoalForm stateKey={stateKey} pcName={pcName} parts={parts} goal={goal} onChange={setGoal}
    onBack={onBackToPc} onNext={() => setStep(3)} />

  return <div className="planner-shell">
    <Stepper current={step} />
    <header className="planner-heading">
      <button type="button" className="planner-text-button" onClick={() => setStep((step - 1) as PlannerStep)}>← 이전 단계</button>
      <p className="planner-eyebrow">{goalSummary(goal)}</p>
      <h1>{step === 3 ? '현재 구성 진단' : step === 4 ? '추천 플랜' : '호환성 상세'}</h1>
      <p>{step === 3 ? '내 PC와 선택한 목표를 공식 요구 사양에 비춰 확인해 보세요.' :
        step === 4 ? '가장 적합한 업그레이드 한 가지를 보여줄 자리입니다.' :
          '교체 전후의 연결 조건을 항목별로 확인할 자리입니다.'}</p>
    </header>
    <ScopeNotice diagnosis={step !== 3 ? null : goal.purpose === 'GAME' ? 'GAME'
      : goal.purpose === 'CREATION' || goal.purpose === 'DEVELOPMENT' ? 'WORKLOAD' : null} />
    {step === 3 && <div className="planner-result-grid">
      <div className="planner-main">
        {goal.purpose === 'GAME' ? <GameRequirementsDiagnosis games={goal.games} parts={parts} resolution={goal.resolution} targetFps={goal.targetFps} /> :
          goal.purpose === 'CREATION' || goal.purpose === 'DEVELOPMENT'
            ? <WorkloadDiagnosis programs={goal.programs} parts={parts} workload={goal.workload} videoResolution={goal.videoResolution} /> :
          <section className="planner-card planner-highlight">
          <span className="badge badge--empty">분석 준비 중</span>
          <h2>어떤 부품을 바꾸면 좋을까요?</h2>
          <p>선택한 용도에 맞춰 부품별 여유와 병목을 표시할 예정입니다. 지금은 실제 성능 판단을 제공하지 않습니다.</p>
        </section>}
        <CurrentPc pcName={pcName} parts={parts} />
      </div>
      <aside className="planner-card planner-aside">
        <h2>선택한 목표</h2><p>{goalSummary(goal)}</p>
        <hr /><p className="planner-caveat">입력 예산 ₩{goal.budgetWon.toLocaleString('ko-KR')}은 아직 추천 계산에 반영되지 않습니다.</p>
      </aside>
    </div>}
    {step === 4 && <div className="planner-main">
      <section className="planner-card planner-plan-card">
        <div className="planner-card-head"><h2>추천 플랜 1개</h2><span className="badge badge--empty">시연용 점검 방향</span></div>
        <p>{focus?.reason ?? '선택한 작업에 대한 비교 점수는 아직 준비 중입니다.'}</p>
        <div className="planner-placeholder-grid">
          <div><span>먼저 점검할 부품</span><strong>{focus?.part === 'RAM' ? '메모리' : focus?.part === 'GPU' ? '그래픽카드'
            : focus?.part === 'CPU' ? 'CPU' : '판단 보류'}</strong></div>
          <div><span>판단 근거</span><strong>{focus?.basis === 'OFFICIAL_CAPACITY' ? '공식 권장 용량'
            : focus?.basis === 'BENCHMARK_SCORE' ? '공개 벤치마크 비교' : '추가 자료 필요'}</strong></div>
          <div><span>제품·비용·FPS</span><strong>아직 선정·계산하지 않음</strong></div>
        </div>
        <p className="planner-caveat">이 화면은 구매할 제품을 확정한 추천이 아닙니다. 입력 예산과 목표 해상도·FPS는
          아직 계산에 반영되지 않으며, 실제 교체 전에는 제원과 호환성을 별도로 확인해야 합니다.</p>
      </section>
      <section className="planner-card">
        <h2>현재 구성과 추천 구성 비교</h2>
        <p>선정 기준이 정해지면 유지·교체 항목을 한 줄씩 비교할 수 있습니다.</p>
        <div className="planner-compare-head"><strong>현재 부품</strong><strong>추천 부품</strong></div>
        {parts.map((part, index) => <div className="planner-compare-row" key={`${part.type}-${index}`}>
          <span>{getPartTypeInfo(part.type).label} · {part.displayName} ({part.quantity}개)</span>
          <span>선정 전</span>
        </div>)}
      </section>
    </div>}
    {step === 5 && <div className="planner-result-grid">
      <section className="planner-card">
        <h2>호환성 점검 항목</h2>
        <p>추천 부품이 정해지면 아래 조건을 하나씩 확인합니다. 현재는 교체 후 호환 여부를 판정할 수 없습니다.</p>
        <ul className="planner-check-list">
          <li><strong>CPU · 메인보드</strong><span>소켓과 BIOS 확인 예정</span></li>
          <li><strong>메모리 · 메인보드</strong><span>DDR 규격과 슬롯 확인 예정</span></li>
          <li><strong>그래픽카드 · 파워</strong><span>권장 출력과 전원 커넥터 확인 예정</span></li>
          <li><strong>그래픽카드 · 케이스</strong><span>장착 가능 길이 확인 예정</span></li>
          <li><strong>쿨러 · 케이스</strong><span>높이와 장착 공간 확인 예정</span></li>
        </ul>
      </section>
      <aside className="planner-card planner-aside"><h2>판정 상태</h2>
        <span className="badge badge--empty">확인 전</span>
        <p className="planner-caveat">확인되지 않은 정보를 ‘호환됨’으로 표시하지 않습니다. 부품을 선택하고 필요한 규격이 확보되면 결과를 연결합니다.</p>
      </aside>
    </div>}
    <div className="planner-actions">
      {step < 5 ? <button type="button" className="button button--primary" onClick={() => setStep((step + 1) as PlannerStep)}>
        {step === 3 ? '추천 플랜 보기' : '호환성 상세 보기'} →</button> :
        <button type="button" className="button button--ghost" onClick={onBackToPc}>내 PC로 돌아가기</button>}
    </div>
  </div>
}
