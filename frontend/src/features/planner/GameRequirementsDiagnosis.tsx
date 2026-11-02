import type { PartInput } from '../pc-scan/types.ts'
import { diagnoseGame, type TierDiagnosis, type Verdict } from './gameDiagnosis.ts'
import { GAME_REQUIREMENTS, type RequirementTier } from './gameRequirements.ts'
import { CPU_BLENDER_DATASET } from './cpuBlenderScores.ts'
import { estimateGameFrames, frameText, SOURCE_KIND_LABEL, supportsFrameEstimate } from './frameEstimate.ts'
import { FRAME_SOURCES, GAME_FRAME_PROFILES, type FrameResolution } from './gameFrameData.ts'
import { GPU_SCORE_SOURCES } from './gpuRelativeScores.ts'
import { comparePartScore, formatRatio, type PartScoreComparison, type ScoredPart } from './partScores.ts'

const STATUS: Record<Verdict, string> = { PASS: '확인 항목 충족', FAIL: '부족 확인', UNKNOWN: '추가 확인 필요' }
const CHECK_STATUS: Record<Verdict, string> = { PASS: '충족', FAIL: '부족', UNKNOWN: '확인 불가' }

function TierCard({ title, requirement, diagnosis }: {
  title: string; requirement: RequirementTier; diagnosis: TierDiagnosis
}) {
  return <div className="planner-tier">
    <div className="planner-tier-head"><div><h4>{title}</h4></div>
      <span className={`planner-verdict planner-verdict--${diagnosis.verdict.toLowerCase()}`}>{STATUS[diagnosis.verdict]}</span>
    </div>
    <div className="planner-requirement-list">
      {diagnosis.checks.map((check) => <div className="planner-requirement-row" key={check.label}>
        <strong>{check.label}</strong><span>기준: {check.required}<br />내 PC: {check.actual}</span>
        <div><span className={`planner-verdict planner-verdict--${check.verdict.toLowerCase()}`}>
          {CHECK_STATUS[check.verdict]}</span></div>
      </div>)}
    </div>
    <small>저장 공간 {requirement.storage}</small>
  </div>
}

// 점수의 절대값은 자료마다 단위가 달라 보여 주지 않고, 기준 예시 대비 배율만 보여 준다.
function scoreText(result: PartScoreComparison): string {
  return result.ratio === null ? '배율 —' : `기준의 ${formatRatio(result.ratio)}`
}

function InternalScoreRows({ parts, minimum, recommended }: {
  parts: PartInput[]; minimum: RequirementTier; recommended: RequirementTier
}) {
  return <div className="planner-internal-score">
    <h4>CPU·GPU 성능 비교 <small>공식 예시 대비 배율</small></h4>
    {(['CPU', 'GPU'] as ScoredPart[]).map((kind) => {
      const models = parts.filter((part) => part.type === kind && part.quantity > 0 && part.displayName.trim())
        .map((part) => part.displayName).join(', ') || '미입력'
      return <div className="planner-internal-model" key={kind}>
        <strong>{kind} · 내 모델: {models}</strong>
        <div className="planner-internal-tier-grid">
          {([{ title: '최소 사양', tier: minimum }, { title: '권장 사양', tier: recommended }] as const)
            .map(({ title, tier }) => {
              const examples = kind === 'CPU' ? tier.cpu : tier.gpu
              const result = comparePartScore(kind, parts, examples)
              const verdict = result.status === 'UNKNOWN' ? 'unknown'
                : result.status === 'BELOW_BASELINE' ? 'fail' : 'pass'
              const label = result.status === 'UNKNOWN' ? '판단 보류'
                : result.status === 'BELOW_BASELINE' ? '기준보다 낮음' : '기준 이상'
              return <div className="planner-internal-tier" key={title}>
                <strong>{title}</strong>
                <span>{scoreText(result)}</span>
                <span>기준: {result.baselineModel ?? examples.join(' / ')}</span>
                <span className={`planner-verdict planner-verdict--${verdict}`}>{label}</span>
              </div>
            })}
        </div>
      </div>
    })}
  </div>
}

const RESOLUTION_TEXT: Record<FrameResolution, string> = { FHD: 'FHD(1080p)', QHD: 'QHD(1440p)', '4K': '4K(2160p)' }
const TARGET_TEXT = { MEETS: '목표 충족 예상', BORDERLINE: '목표 경계', BELOW: '목표 미달 예상' } as const

function FrameEstimates({ parts, game, resolution, targetFps }: {
  parts: PartInput[]; game: string; resolution: FrameResolution; targetFps: number
}) {
  const estimates = estimateGameFrames(parts, game, resolution, targetFps)
  return <div className="planner-frame-estimate">
    <h4>예상 프레임 <small>{RESOLUTION_TEXT[resolution]} · 목표 {targetFps}fps · 추정</small></h4>
    <div className="planner-internal-tier-grid">
      {estimates.map((estimate) => {
        const verdict = estimate.target === 'MEETS' ? 'pass' : estimate.target === 'BELOW' ? 'fail' : 'unknown'
        return <div className="planner-internal-tier" key={estimate.preset.label}>
          <strong>화질 {estimate.preset.label}</strong>
          <span className="planner-frame-value">{frameText(estimate)}</span>
          {estimate.target && <span className={`planner-verdict planner-verdict--${verdict}`}>{TARGET_TEXT[estimate.target]}</span>}
          {estimate.requiredGpuMultiplier !== null && !estimate.cpuLimited &&
            <small>목표까지 GPU 약 {estimate.requiredGpuMultiplier.toFixed(1)}배 필요</small>}
          {estimate.cpuLimited && <small className="planner-warning">CPU가 한계</small>}
        </div>
      })}
    </div>
  </div>
}

// 근거 자료 목록. 화면에는 접어 두고 '자세히 보기'에서만 보여 준다.
function Sources({ game }: { game: string }) {
  const frameIds = supportsFrameEstimate(game)
    ? [...new Set(GAME_FRAME_PROFILES[game].presets.flatMap((preset) => preset.points.map((point) => point.sourceId)))] : []
  const requirement = GAME_REQUIREMENTS[game]
  return <p className="planner-source">근거:{' '}
    <a href={requirement.source} target="_blank" rel="noopener noreferrer">{requirement.sourceName}</a>
    {frameIds.map((id) => <span key={id}>{' · '}
      <a href={FRAME_SOURCES[id].url} target="_blank" rel="noopener noreferrer">{FRAME_SOURCES[id].name}</a>
      {' '}({SOURCE_KIND_LABEL[FRAME_SOURCES[id].kind]})</span>)}
    {' · '}<a href={CPU_BLENDER_DATASET.sourceUrl} target="_blank" rel="noopener noreferrer">{CPU_BLENDER_DATASET.publisher}</a>
    {GPU_SCORE_SOURCES.map((source) => <span key={source.url}>{' · '}
      <a href={source.url} target="_blank" rel="noopener noreferrer">{source.name}</a></span>)}
  </p>
}

const SUMMARY: Record<Verdict, string> = { PASS: '권장 사양 충족', FAIL: '권장 사양 부족', UNKNOWN: '일부 확인 필요' }

export function GameRequirementsDiagnosis({ games, parts, resolution, targetFps }: {
  games: string[]; parts: PartInput[]; resolution: FrameResolution; targetFps: number
}) {
  return <section className="planner-card" aria-labelledby="planner-game-diagnosis">
    <h2 id="planner-game-diagnosis">게임별 진단</h2>
    <div className="planner-game-list">
      {games.map((name) => {
        const game = Object.hasOwn(GAME_REQUIREMENTS, name) ? GAME_REQUIREMENTS[name] : undefined
        if (!game) return <article className="planner-game" key={name}>
          <h3>{name}</h3><p className="muted">공식 사양 자료가 없어 판단하지 않습니다.</p>
        </article>
        const result = diagnoseGame(parts, game)
        const short = result.recommended.checks.filter((check) => check.verdict === 'FAIL').map((check) => check.label)
        return <article className="planner-game" key={name}>
          <div className="planner-card-head"><h3>{game.name}</h3>
            <span className={`planner-verdict planner-verdict--${result.recommended.verdict.toLowerCase()}`}>
              {SUMMARY[result.recommended.verdict]}</span></div>
          {short.length > 0 && <p className="planner-short">부족: {short.join(', ')}</p>}
          {supportsFrameEstimate(name) &&
            <FrameEstimates parts={parts} game={name} resolution={resolution} targetFps={targetFps} />}
          <details className="planner-details">
            <summary>사양 비교 자세히 보기</summary>
            <div className="planner-tier-grid">
              <TierCard title="최소 사양" requirement={game.minimum} diagnosis={result.minimum} />
              <TierCard title="권장 사양" requirement={game.recommended} diagnosis={result.recommended} />
            </div>
            <InternalScoreRows parts={parts} minimum={game.minimum} recommended={game.recommended} />
            {game.note && <p className="planner-caveat">{game.note}</p>}
            <Sources game={name} />
          </details>
        </article>
      })}
    </div>
  </section>
}
