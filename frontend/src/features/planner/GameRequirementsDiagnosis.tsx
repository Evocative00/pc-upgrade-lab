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
    <div className="planner-tier-head"><div><h4>{title}</h4><p>{requirement.context}</p></div>
      <span className={`planner-verdict planner-verdict--${diagnosis.verdict.toLowerCase()}`}>{STATUS[diagnosis.verdict]}</span>
    </div>
    <div className="planner-requirement-list">
      {diagnosis.checks.map((check) => <div className="planner-requirement-row" key={check.label}>
        <strong>{check.label}</strong><span>기준: {check.required}<br />내 PC: {check.actual}</span>
        <div><span className={`planner-verdict planner-verdict--${check.verdict.toLowerCase()}`}>
          {CHECK_STATUS[check.verdict]}</span><small>{check.reason}</small></div>
      </div>)}
    </div>
    <p className="planner-caveat">별도 확인: 저장 공간 {requirement.storage} · {requirement.os}</p>
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
    <h4>CPU·GPU 모델 비교 <small>공개 벤치마크 기준 상대 성능</small></h4>
    <p>공식 최소·권장 예시 중 성능이 낮은 모델을 기준으로 내 부품이 몇 배인지 보여 줍니다.
      CPU는 Blender 렌더링 점수(멀티코어 처리 성능), GPU는 출처별 게임 실측 또는 합성 시험 값입니다.
      시험 표가 다르면 공통 눈금(GTX 1060 6GB = 100)으로 환산해 비교하며 오차가 더 큽니다. 예상 FPS가 아닙니다.</p>
    <p className="planner-source">근거:{' '}
      <a href={CPU_BLENDER_DATASET.sourceUrl} target="_blank" rel="noopener noreferrer">{CPU_BLENDER_DATASET.publisher}</a>
      {GPU_SCORE_SOURCES.map((source) => <span key={source.url}>{' · '}
        <a href={source.url} target="_blank" rel="noopener noreferrer">{source.name}</a></span>)}
      {' '}· 확인일 {CPU_BLENDER_DATASET.checkedOn}</p>
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
                <span>기준 예시: {result.baselineModel ?? examples.join(' / ')}</span>
                <span className={`planner-verdict planner-verdict--${verdict}`}>{label}</span>
                <small>{result.reason}</small>
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
  const sourceIds = [...new Set(GAME_FRAME_PROFILES[game].presets.flatMap((preset) => preset.points.map((point) => point.sourceId)))]
  return <div className="planner-internal-score planner-frame-estimate">
    <h4>예상 프레임 <small>{RESOLUTION_TEXT[resolution]} · 목표 {targetFps}fps · 추정</small></h4>
    <p>{GAME_FRAME_PROFILES[game].note} GPU가 병목인 상황을 기준으로 하며 CPU·드라이버·게임 패치·장면에 따라 달라질 수 있습니다.</p>
    <div className="planner-internal-tier-grid">
      {estimates.map((estimate) => {
        const verdict = estimate.target === 'MEETS' ? 'pass' : estimate.target === 'BELOW' ? 'fail' : 'unknown'
        return <div className="planner-internal-tier" key={estimate.preset.label}>
          <strong>화질 {estimate.preset.label}</strong>
          <span className="planner-frame-value">{frameText(estimate)}</span>
          {estimate.target && <span className={`planner-verdict planner-verdict--${verdict}`}>{TARGET_TEXT[estimate.target]}</span>}
          {estimate.requiredGpuMultiplier !== null && !estimate.cpuLimited &&
            <span>목표에 필요한 GPU 성능: 현재의 약 {estimate.requiredGpuMultiplier.toFixed(1)}배</span>}
          <small>{estimate.reason}</small>
          {estimate.cpuNote && <small className="planner-warning">{estimate.cpuNote}</small>}
        </div>
      })}
    </div>
    <p className="planner-source">근거: {sourceIds.map((id, index) => <span key={id}>{index > 0 && ' · '}
      <a href={FRAME_SOURCES[id].url} target="_blank" rel="noopener noreferrer">{FRAME_SOURCES[id].name}</a>
      {' '}({SOURCE_KIND_LABEL[FRAME_SOURCES[id].kind]})</span>)}</p>
  </div>
}

export function GameRequirementsDiagnosis({ games, parts, resolution, targetFps }: {
  games: string[]; parts: PartInput[]; resolution: FrameResolution; targetFps: number
}) {
  return <section className="planner-card" aria-labelledby="planner-game-diagnosis">
    <h2 id="planner-game-diagnosis">게임별 공식 요구 사양 비교</h2>
    <p className="planner-caveat">RAM·VRAM 용량은 수치로 비교합니다. CPU·GPU는 공식 예시와 같은 모델이면 그대로 확인하고,
      다른 모델이면 공개 벤치마크 배율로 충족·부족을 추정합니다(다른 시험 표를 환산한 경우 함께 표시). 이 결과는 실제 프레임이나
      전체 실행 가능 여부를 보증하지 않습니다.</p>
    <div className="planner-game-list">
      {games.map((name) => {
        const game = Object.hasOwn(GAME_REQUIREMENTS, name) ? GAME_REQUIREMENTS[name] : undefined
        if (!game) return <article className="planner-game" key={name}>
          <h3>{name}</h3><p>공식 요구 사양을 아직 확인하지 못한 게임입니다. 임의로 충족·부족을 판단하지 않습니다.</p>
        </article>
        const result = diagnoseGame(parts, game)
        return <article className="planner-game" key={name}>
          <div className="planner-card-head"><h3>{game.name}</h3>
            <a href={game.source} target="_blank" rel="noopener noreferrer">공식 사양 보기 ↗</a></div>
          <p className="planner-source">{game.sourceName} · 확인일 {game.checkedOn}</p>
          <div className="planner-tier-grid">
            <TierCard title="최소 사양" requirement={game.minimum} diagnosis={result.minimum} />
            <TierCard title="권장 사양" requirement={game.recommended} diagnosis={result.recommended} />
          </div>
          {supportsFrameEstimate(name) &&
            <FrameEstimates parts={parts} game={name} resolution={resolution} targetFps={targetFps} />}
          <InternalScoreRows parts={parts} minimum={game.minimum} recommended={game.recommended} />
          {game.note && <p className="planner-caveat">{game.note}</p>}
        </article>
      })}
    </div>
  </section>
}
