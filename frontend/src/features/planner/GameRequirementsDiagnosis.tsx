import type { PartInput } from '../pc-scan/types.ts'
import { diagnoseGame, type TierDiagnosis, type Verdict } from './gameDiagnosis.ts'
import { GAME_REQUIREMENTS, type RequirementTier } from './gameRequirements.ts'
import { CPU_BLENDER_DATASET } from './cpuBlenderScores.ts'
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
      CPU는 Blender 렌더링 점수(멀티코어 처리 성능), GPU는 1080p 게임 실측 기반 상대값입니다.
      자료 오차가 있어 0.9~1.1배는 비슷한 수준으로 보세요. 공식 충족 판정이나 예상 FPS가 아닙니다.</p>
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
