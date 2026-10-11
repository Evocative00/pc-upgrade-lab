import type { PartInput } from '../pc-scan/types.ts'
import { diagnoseGame, type TierDiagnosis, type Verdict } from './gameDiagnosis.ts'
import { GAME_REQUIREMENTS, type RequirementTier } from './gameRequirements.ts'

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

export function GameRequirementsDiagnosis({ games, parts }: { games: string[]; parts: PartInput[] }) {
  return <section className="planner-card" aria-labelledby="planner-game-diagnosis">
    <h2 id="planner-game-diagnosis">게임별 공식 요구 사양 비교</h2>
    <p className="planner-caveat">RAM·VRAM 용량은 수치로 비교합니다. CPU·GPU는 공식 예시와 동일한 모델만 확인하며,
      다른 모델의 성능 우열은 아직 판정하지 않습니다. 이 결과는 목표 FPS나 전체 실행 가능 여부를 보증하지 않습니다.</p>
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
          {game.note && <p className="planner-caveat">{game.note}</p>}
        </article>
      })}
    </div>
  </section>
}
