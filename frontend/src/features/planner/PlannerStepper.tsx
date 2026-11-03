export type StepNumber = 1 | 2 | 3 | 4

const STEP_LABELS = ['내 PC', '예산·목적', '진단', '추천 플랜']

/**
 * 업그레이드 진행 단계 표시. 지금 단계가 아닌 단계는 눌러서 바로 이동한다.
 * 진단·추천 플랜은 예산·목적을 다 고른 뒤에만 열 수 있다(canGo).
 */
export function PlannerStepper({ current, canGo, onGo }: {
  current: StepNumber
  canGo: (step: StepNumber) => boolean
  onGo: (step: StepNumber) => void
}) {
  return <nav className="planner-steps" aria-label="업그레이드 진행 단계">
    {STEP_LABELS.map((label, index) => {
      const step = (index + 1) as StepNumber
      const text = `${step < current ? '✓' : step} ${label}`
      const className = step === current ? 'is-current' : step < current ? 'is-complete' : undefined
      return step === current
        ? <span key={label} className={className} aria-current="step">{text}</span>
        : <button key={label} type="button" className={className} disabled={!canGo(step)} onClick={() => onGo(step)}>{text}</button>
    })}
  </nav>
}
