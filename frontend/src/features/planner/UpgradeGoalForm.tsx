import { useId, useState } from 'react'
import { isString } from '../../lib/pageState.ts'
import { usePageState } from '../../lib/usePageState.ts'
import type { PartInput } from '../pc-scan/types.ts'
import { getPartTypeInfo } from '../pc/partCategories.ts'
import { estimateGameFrames, frameText } from './frameEstimate.ts'
import { diagnoseProgram } from './workloadEstimate.ts'
import {
  budgetFromSlider, BUDGET_SLIDER_STEPS, changePurpose, frameEstimateGames, GAME_SUGGESTIONS, goalIsReady, goalSummary,
  isBudgetInRange, MAX_BUDGET_WON, MIN_BUDGET_WON, PROGRAMS, PURPOSES, sliderFromBudget,
  TARGET_FPS_OPTIONS, toggleChoice, type Purpose, type Resolution, type UpgradeGoal, type VideoResolution, type Workload,
} from './plannerGoal.ts'
import { PlannerStepper } from './PlannerStepper.tsx'
import './planner.css'

type Props = {
  stateKey: string
  pcName: string
  parts: PartInput[]
  goal: UpgradeGoal
  budgetText: string
  onBudgetTextChange: (value: string) => void
  onChange: (goal: UpgradeGoal) => void
  onBack: () => void
  onNext: () => void
  // 단계 표시에서 진단(3)·추천 플랜(4)으로 바로 이동
  onStep: (step: 3 | 4) => void
}

const PRESETS = [300_000, 500_000, 800_000, 1_200_000, 2_000_000, 3_000_000]
const WORKLOADS: { value: Workload; label: string; help: string }[] = [
  { value: 'LIGHT', label: '가벼움', help: '한두 프로그램 위주' },
  { value: 'MEDIUM', label: '보통', help: '여러 작업을 함께 사용' },
  { value: 'HEAVY', label: '무거움', help: '큰 작업이나 여러 도구 동시 사용' },
]

function Choice<T extends string | number>({ options, value, onChange, label }: {
  options: { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
  label: string
}) {
  return <div className="planner-choice" role="group" aria-label={label}>
    {options.map((option) => <button key={option.value} type="button"
      className={option.value === value ? 'is-selected' : ''}
      aria-pressed={option.value === value} onClick={() => onChange(option.value)}>{option.label}</button>)}
  </div>
}

export function UpgradeGoalForm({ stateKey, pcName, parts, goal, budgetText, onBudgetTextChange, onChange, onBack, onNext, onStep }: Props) {
  const [gameInput, setGameInput] = usePageState(`${stateKey}gameInput`, '', isString)
  const [gameLimitNotice, setGameLimitNotice] = useState(false)
  const budgetErrorId = useId()
  const validBudget = isBudgetInRange(Number(budgetText))
  const canContinue = validBudget && goalIsReady(goal)
  const pcSummary = parts.slice(0, 5).map((part) => `${getPartTypeInfo(part.type).label} ${part.displayName}`)
  const frameGames = frameEstimateGames(goal.games)
  const customGames = goal.games.filter((game) => !GAME_SUGGESTIONS.includes(game))

  function chooseGame(game: string) {
    const games = toggleChoice(goal.games, game)
    if (games === goal.games) {
      setGameLimitNotice(true)
      return
    }
    onChange({ ...goal, games })
    setGameLimitNotice(false)
  }

  function addCustomGame() {
    const game = gameInput.trim()
    if (!game) return
    const gameKey = game.toLocaleLowerCase()
    if (!goal.games.some((name) => name.toLocaleLowerCase() === gameKey)) {
      const games = toggleChoice(goal.games, game)
      if (games === goal.games) {
        setGameLimitNotice(true)
        return
      }
      onChange({ ...goal, games })
    }
    setGameLimitNotice(false)
    setGameInput('')
  }

  function setBudget(value: number) {
    if (Number.isFinite(value)) {
      const budgetWon = Math.min(MAX_BUDGET_WON, Math.max(MIN_BUDGET_WON, Math.round(value / 10_000) * 10_000))
      onBudgetTextChange(String(budgetWon))
    }
  }

  return <div className="planner-shell">
    <PlannerStepper current={2} canGo={(step) => step === 1 || canContinue}
      onGo={(step) => step === 1 ? onBack() : step === 3 || step === 4 ? onStep(step) : undefined} />
    <div className="planner-layout">
      <div className="planner-main">
        <header className="planner-heading">
          <button type="button" className="button button--ghost" onClick={onBack}>← 내 PC 수정</button>
          <p className="eyebrow">UPGRADE PLAN</p>
          <h1>예산과 목적을 정해주세요</h1>
        </header>

        <section className="planner-card" aria-labelledby="planner-budget-title">
          <div className="planner-card-head">
            <h2 id="planner-budget-title">업그레이드 예산</h2>
            <label className="planner-money">₩ <input aria-label="업그레이드 예산" type="number" min={MIN_BUDGET_WON} max={MAX_BUDGET_WON}
              step="1" value={budgetText} aria-invalid={!validBudget} aria-describedby={!validBudget ? budgetErrorId : undefined}
              onChange={(event) => onBudgetTextChange(event.target.value)} /></label>
          </div>
          {!validBudget && <p id={budgetErrorId} className="error" role="status">
            예산은 {MIN_BUDGET_WON / 10_000}만 원부터 {MAX_BUDGET_WON / 10_000}만 원까지 원 단위로 입력해 주세요.
          </p>}
          {/* 100만 원까지는 1만 원, 그 위는 10만 원 단위로 움직인다(plannerGoal.ts). */}
          <input className="planner-range" aria-label="예산 슬라이더" type="range" min="0" max={BUDGET_SLIDER_STEPS}
            step="1" value={sliderFromBudget(goal.budgetWon)} aria-valuetext={`${goal.budgetWon / 10_000}만 원`}
            onChange={(event) => setBudget(budgetFromSlider(Number(event.target.value)))} />
          <div className="planner-range-labels">
            <span>10만 원</span>
            <span className="planner-range-mid" style={{ left: `${sliderFromBudget(1_000_000) / BUDGET_SLIDER_STEPS * 100}%` }}>100만</span>
            <span>500만 원</span>
          </div>
          <div className="planner-presets" aria-label="예산 빠른 선택">
            {PRESETS.map((amount) => <button type="button" key={amount} aria-pressed={goal.budgetWon === amount}
              onClick={() => setBudget(amount)}>{amount / 10_000}만</button>)}
          </div>
        </section>

        <section className="planner-card" aria-labelledby="planner-purpose-title">
          <h2 id="planner-purpose-title">주로 어디에 쓰나요?</h2>
          <div className="planner-purpose-options" role="group" aria-label="사용 목적">
            {PURPOSES.map((purpose) => <button type="button" key={purpose.value}
              className={goal.purpose === purpose.value ? 'is-selected' : ''}
              aria-pressed={goal.purpose === purpose.value}
              onClick={() => { if (goal.purpose !== purpose.value) onChange(changePurpose(goal, purpose.value)) }}>
              {purpose.label}</button>)}
          </div>
          {goal.purpose === 'GAME' && <div className="planner-reveal" aria-label="게임 목표">
            <h3>목표 게임 <small>{goal.games.length}/3 선택</small></h3>
            <div className="planner-chip-list">
              {GAME_SUGGESTIONS.map((game) => <button type="button" key={game}
                className={goal.games.includes(game) ? 'is-selected' : ''}
                aria-pressed={goal.games.includes(game)} onClick={() => chooseGame(game)}>{game}</button>)}
              {customGames.map((game) => <button type="button" key={game} className="is-selected"
                aria-label={`${game} 선택 해제`} onClick={() => chooseGame(game)}>{game} <span aria-hidden="true">×</span></button>)}
            </div>
            <div className="planner-inline-input">
              <label htmlFor="planner-custom-game">목록에 없는 게임</label>
              <input id="planner-custom-game" value={gameInput} maxLength={40} placeholder="게임 이름 직접 입력"
                onChange={(event) => setGameInput(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key !== 'Enter' || event.nativeEvent.isComposing) return
                  event.preventDefault()
                  addCustomGame()
                }} />
              <button type="button" onClick={addCustomGame} disabled={!gameInput.trim()}>추가</button>
            </div>
            {gameLimitNotice && <p role="status" className="planner-warning">게임은 최대 3개까지 선택할 수 있어요.</p>}
            {frameGames.length > 0 && <>
              <div className="planner-goal-pair">
                <div><h3>목표 해상도</h3><Choice label="목표 해상도"
                  options={(['FHD', 'QHD', '4K'] as Resolution[]).map((value) => ({ value, label: value }))}
                  value={goal.resolution} onChange={(resolution) => onChange({ ...goal, resolution })} /></div>
                <div><h3>목표 프레임</h3><Choice label="목표 프레임"
                  options={TARGET_FPS_OPTIONS.map((value) => ({ value, label: `${value}fps` }))}
                  value={goal.targetFps} onChange={(targetFps) => onChange({ ...goal, targetFps })} /></div>
              </div>
              {frameGames.map((game) => <div className="planner-frame-preview" key={game} role="status" aria-live="polite">
                <strong>{game} · 내 PC 예상 ({goal.resolution})</strong>
                {estimateGameFrames(parts, game, goal.resolution, goal.targetFps).map((estimate) =>
                  <span key={estimate.preset.label}>화질 {estimate.preset.label}: <b>{frameText(estimate)}</b>
                    {estimate.target === 'MEETS' ? ' · 목표 충족' : estimate.target === 'BELOW' ? ' · 목표 미달'
                      : estimate.target === 'BORDERLINE' ? ' · 목표 경계' : ''}</span>)}
              </div>)}
              <p className="planner-caveat">예상 프레임은 추정치입니다.</p>
            </>}
          </div>}
          {goal.purpose !== null && goal.purpose !== 'GAME' && <div className="planner-reveal" aria-label="작업 목표">
            <h3>주로 사용하는 프로그램·작업 <small>최대 3개</small></h3>
            <div className="planner-chip-list">
              {PROGRAMS[goal.purpose as Exclude<Purpose, 'GAME'>].map((program) => <button type="button" key={program}
                className={goal.programs.includes(program) ? 'is-selected' : ''}
                aria-pressed={goal.programs.includes(program)}
                onClick={() => onChange({ ...goal, programs: toggleChoice(goal.programs, program) })}>{program}</button>)}
            </div>
            <h3>작업 규모</h3>
            <div className="planner-workloads" role="group" aria-label="작업 규모">
              {WORKLOADS.map((item) => <button type="button" key={item.value}
                className={goal.workload === item.value ? 'is-selected' : ''}
                aria-pressed={goal.workload === item.value}
                onClick={() => onChange({ ...goal, workload: item.value })}>
                <strong>{item.label}</strong><span>{item.help}</span></button>)}
            </div>
            {goal.purpose === 'CREATION' && goal.programs.includes('Premiere Pro') && <>
              <h3>Premiere Pro 편집 해상도</h3>
              <Choice label="Premiere Pro 편집 해상도"
                options={([['FHD', '1080p'], ['4K', '4K'], ['8K', '8K']] as [VideoResolution, string][])
                  .map(([value, label]) => ({ value, label }))}
                value={goal.videoResolution} onChange={(videoResolution) => onChange({ ...goal, videoResolution })} />
              <div className="planner-frame-preview" role="status" aria-live="polite">
                <strong>Premiere Pro · 내 PC 예상</strong>
                {diagnoseProgram('Premiere Pro', parts, goal.workload, goal.videoResolution)?.estimates.slice(0, 2)
                  .map((estimate) => <span key={estimate.title}>{estimate.title}: <b>{estimate.value}</b></span>)}
              </div>
            </>}
            {goal.purpose === 'CREATION' && goal.programs.some((program) => program !== 'Premiere Pro') &&
              <p className="planner-caveat">작업 규모: 가벼움 1080p · 보통 4K · 무거움 6K~8K</p>}
          </div>}
        </section>
        <div className="planner-actions"><button type="button" className="button button--primary button--lg" disabled={!canContinue}
          onClick={onNext}>진단 결과 보기 →</button></div>
      </div>
      <aside className="planner-card planner-aside" aria-label="진단 기준">
        <h2>진단 기준</h2>
        <dl><div><dt>예산</dt><dd>{validBudget ? `₩${goal.budgetWon.toLocaleString('ko-KR')}` : '입력 확인 필요'}</dd></div>
          <div><dt>용도·목표</dt><dd>{goalSummary(goal)}</dd></div></dl>
        <hr /><h3>기존 구성 · {pcName.trim() || '이름 없는 PC'}</h3>
        {pcSummary.length > 0 ? <ul>{pcSummary.map((part, index) => <li key={`${index}-${part}`}>{part}</li>)}</ul>
          : <p className="planner-warning">부품을 먼저 불러오거나 입력해 주세요.</p>}
        {parts.length > 5 && <p>외 {parts.length - 5}개 부품</p>}
        <button type="button" className="button button--ghost" onClick={onBack}>기존 구성 수정하기</button>
      </aside>
    </div>
  </div>
}
