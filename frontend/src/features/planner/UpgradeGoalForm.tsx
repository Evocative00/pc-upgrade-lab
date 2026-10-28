import { useState } from 'react'
import { isString } from '../../lib/pageState.ts'
import { usePageState } from '../../lib/usePageState.ts'
import type { PartInput } from '../pc-scan/types.ts'
import { getPartTypeInfo } from '../pc/partCategories.ts'
import { estimateGameFrames, frameText } from './frameEstimate.ts'
import { diagnoseProgram } from './workloadEstimate.ts'
import {
  changePurpose, frameEstimateGames, GAME_SUGGESTIONS, goalIsReady, goalSummary, PROGRAMS, PURPOSES,
  TARGET_FPS_OPTIONS, toggleChoice, type Purpose, type Resolution, type UpgradeGoal, type VideoResolution, type Workload,
} from './plannerGoal.ts'
import './planner.css'

type Props = {
  stateKey: string
  pcName: string
  parts: PartInput[]
  goal: UpgradeGoal
  onChange: (goal: UpgradeGoal) => void
  onBack: () => void
  onNext: () => void
}

const PRESETS = [300_000, 500_000, 800_000, 1_200_000, 1_800_000]
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

export function UpgradeGoalForm({ stateKey, pcName, parts, goal, onChange, onBack, onNext }: Props) {
  const [gameInput, setGameInput] = usePageState(`${stateKey}gameInput`, '', isString)
  const [gameLimitNotice, setGameLimitNotice] = useState(false)
  const [budgetText, setBudgetText] = usePageState(`${stateKey}budgetText`, String(goal.budgetWon), isString)
  const pcSummary = parts.slice(0, 5).map((part) => `${getPartTypeInfo(part.type).label} ${part.displayName}`)
  const frameGames = frameEstimateGames(goal.games)

  function chooseGame(game: string) {
    if (goal.games.length >= 3 && !goal.games.includes(game.trim())) {
      setGameLimitNotice(true)
      return
    }
    onChange({ ...goal, games: toggleChoice(goal.games, game) })
    setGameLimitNotice(false)
    setGameInput('')
  }

  function setBudget(value: number) {
    if (Number.isFinite(value)) {
      const budgetWon = Math.min(2_000_000, Math.max(100_000, Math.round(value / 10_000) * 10_000))
      onChange({ ...goal, budgetWon })
      setBudgetText(String(budgetWon))
    }
  }

  return <div className="planner-shell">
    <nav className="planner-steps" aria-label="업그레이드 진행 단계">
      <span className="is-complete">✓ 내 PC</span><span aria-current="step" className="is-current">2 예산·목적</span>
      <span>3 진단</span><span>4 추천 플랜</span><span>5 호환성</span>
    </nav>
    <div className="planner-layout">
      <div className="planner-main">
        <header className="planner-heading">
          <button type="button" className="planner-text-button" onClick={onBack}>← 내 PC 수정</button>
          <h1>예산과 목적을 정해주세요</h1>
          <p>목적에 따라 먼저 살펴볼 부품이 달라져요. 모르는 항목은 내 PC 화면에서 보완할 수 있습니다.</p>
        </header>

        <section className="planner-card" aria-labelledby="planner-budget-title">
          <div className="planner-card-head">
            <h2 id="planner-budget-title">업그레이드 예산</h2>
            <label className="planner-money">₩ <input aria-label="업그레이드 예산" type="number" min="100000" max="2000000"
              step="10000" value={budgetText} onChange={(event) => {
                const next = event.target.value
                setBudgetText(next)
                const value = Number(next)
                if (next !== '' && Number.isInteger(value) && value >= 100_000 && value <= 2_000_000) {
                  onChange({ ...goal, budgetWon: value })
                }
              }} onBlur={() => setBudgetText(String(goal.budgetWon))} /></label>
          </div>
          <input className="planner-range" aria-label="예산 슬라이더" type="range" min="100000" max="2000000"
            step="10000" value={goal.budgetWon} onChange={(event) => setBudget(Number(event.target.value))} />
          <div className="planner-range-labels"><span>10만 원</span><span>200만 원</span></div>
          <div className="planner-presets" aria-label="예산 빠른 선택">
            {PRESETS.map((amount) => <button type="button" key={amount} aria-pressed={goal.budgetWon === amount}
              onClick={() => setBudget(amount)}>{amount / 10_000}만</button>)}
          </div>
          <p className="planner-caveat">입력한 예산은 현재 플랜의 추천 계산에 반영되지 않습니다.</p>
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
            </div>
            <div className="planner-inline-input">
              <label htmlFor="planner-custom-game">목록에 없는 게임</label>
              <input id="planner-custom-game" value={gameInput} maxLength={40} placeholder="게임 이름 직접 입력"
                onChange={(event) => setGameInput(event.target.value)} />
              <button type="button" onClick={() => chooseGame(gameInput)} disabled={!gameInput.trim()}>추가</button>
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
              <p className="planner-caveat">예상 프레임은 공식 사양·공개 측정값을 기준으로 GPU 성능 비율로 계산한 추정치입니다.
                출처와 신뢰도는 진단 결과에서 확인할 수 있습니다.</p>
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
              <p className="planner-caveat">DaVinci Resolve·Blender는 작업 규모를 편집 해상도로 계산합니다:
                가벼움 = 1080p, 보통 = 4K, 무거움 = 6K~8K.</p>}
          </div>}
        </section>
        <div className="planner-actions"><button type="button" className="planner-primary" disabled={!goalIsReady(goal)}
          onClick={onNext}>진단 결과 보기 →</button></div>
      </div>
      <aside className="planner-card planner-aside" aria-label="진단 기준">
        <h2>진단 기준</h2>
        <dl><div><dt>예산</dt><dd>₩{goal.budgetWon.toLocaleString('ko-KR')}</dd></div>
          <div><dt>용도·목표</dt><dd>{goalSummary(goal)}</dd></div></dl>
        <p className="planner-caveat">예산은 아직 추천 판단에 반영되지 않습니다.</p>
        <hr /><h3>기존 구성 · {pcName.trim() || '이름 없는 PC'}</h3>
        {pcSummary.length > 0 ? <ul>{pcSummary.map((part, index) => <li key={`${index}-${part}`}>{part}</li>)}</ul>
          : <p className="planner-warning">부품을 먼저 불러오거나 입력해 주세요.</p>}
        {parts.length > 5 && <p>외 {parts.length - 5}개 부품</p>}
        <button type="button" className="planner-text-button" onClick={onBack}>기존 구성 수정하기</button>
      </aside>
    </div>
  </div>
}
