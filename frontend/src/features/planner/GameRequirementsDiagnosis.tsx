import type { PartInput } from '../pc-scan/types.ts'
import { diagnoseGame, type TierDiagnosis, type Verdict } from './gameDiagnosis.ts'
import { GAME_REQUIREMENTS, type RequirementTier } from './gameRequirements.ts'
import { CPU_BLENDER_DATASET } from './cpuBlenderScores.ts'
import { GPU_GRADE_SOURCES, INTERNAL_SCORE_MAX, INTERNAL_SCORE_VERSION, type ScoredPart } from './internalScores.ts'
import { comparePartScore, type PartScoreComparison } from './partScores.ts'

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

function scoreText(kind: ScoredPart, result: PartScoreComparison): string {
  if (kind === 'GPU') return `내 등급 ${result.actual ?? '—'} / 기준 등급 ${result.baseline ?? '—'}`
  const ratio = result.ratio === null ? '' : ` (기준의 ${result.ratio.toFixed(2)}배)`
  return `내 점수 ${result.actual ?? '—'} / 기준 점수 ${result.baseline ?? '—'}${ratio}`
}

function InternalScoreRows({ parts, minimum, recommended }: {
  parts: PartInput[]; minimum: RequirementTier; recommended: RequirementTier
}) {
  return <div className="planner-internal-score">
    <h4>CPU·GPU 모델 비교 <small>CPU {CPU_BLENDER_DATASET.publisher} · GPU 내부 등급 {INTERNAL_SCORE_VERSION}</small></h4>
    <p>CPU는 {CPU_BLENDER_DATASET.edition} 점수로, GPU는 공개 벤치마크를 근거로 팀이 정한
      1~{INTERNAL_SCORE_MAX}등급으로 공식 최소·권장 예시와 비교합니다. CPU 점수는 멀티코어 렌더링 성능이며
      점수 환산 오차가 있습니다. 공식 충족 판정이나 예상 FPS가 아닙니다.</p>
    <p className="planner-source">근거:{' '}
      <a href={CPU_BLENDER_DATASET.sourceUrl} target="_blank" rel="noopener noreferrer">{CPU_BLENDER_DATASET.publisher}</a>
      {GPU_GRADE_SOURCES.map((source) => <span key={source.url}>{' · '}
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
              const basisLabel = kind === 'CPU' ? '벤치마크' : '내부'
              const label = result.status === 'UNKNOWN' ? '판단 보류'
                : result.status === 'BELOW_BASELINE' ? `${basisLabel} 기준보다 낮음` : `${basisLabel} 기준 이상`
              return <div className="planner-internal-tier" key={title}>
                <strong>{title}</strong>
                <span>{scoreText(kind, result)}</span>
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

export function GameRequirementsDiagnosis({ games, parts }: { games: string[]; parts: PartInput[] }) {
  return <section className="planner-card" aria-labelledby="planner-game-diagnosis">
    <h2 id="planner-game-diagnosis">게임별 공식 요구 사양 비교</h2>
    <p className="planner-caveat">RAM·VRAM 용량은 수치로 비교합니다. 공식 사양 카드의 CPU·GPU는 공식 예시와 동일한 모델만
      확인하고, 다른 모델과의 비교는 아래 점수·등급 비교에서 따로 보여 줍니다. 이 결과는 목표 FPS나 전체 실행 가능 여부를
      보증하지 않습니다.</p>
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
          <InternalScoreRows parts={parts} minimum={game.minimum} recommended={game.recommended} />
          {game.note && <p className="planner-caveat">{game.note}</p>}
        </article>
      })}
    </div>
  </section>
}
