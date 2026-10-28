import type { PartInput } from '../pc-scan/types.ts'
import type { Workload } from './plannerGoal.ts'
import { WORKLOAD_SOURCES, type WorkloadSourceKind } from './workloadData.ts'
import { diagnoseProgram, type Verdict } from './workloadEstimate.ts'

const STATUS: Record<Verdict, string> = { PASS: '충족', FAIL: '부족', UNKNOWN: '추가 확인 필요' }
const CHECK_STATUS: Record<Verdict, string> = { PASS: '충족', FAIL: '부족', UNKNOWN: '확인 불가' }
const WORKLOAD_TEXT: Record<Workload, string> = { LIGHT: '가벼움', MEDIUM: '보통', HEAVY: '무거움' }
const SOURCE_KIND: Record<WorkloadSourceKind, string> = {
  OFFICIAL: '제작사 공식', SECONDARY: '공식 안내 재인용', REVIEW: '전문 업체 가이드', BENCHMARK_DB: '공개 벤치마크',
}

export function WorkloadDiagnosis({ programs, parts, workload }: {
  programs: string[]; parts: PartInput[]; workload: Workload
}) {
  return <section className="planner-card" aria-labelledby="planner-workload-diagnosis">
    <h2 id="planner-workload-diagnosis">작업별 요구 사양과 예상 성능</h2>
    <p className="planner-caveat">제작사 공식 최소·권장 사양과 작업 규모({WORKLOAD_TEXT[workload]})에 맞는 RAM·VRAM·코어를 확인하고,
      공개 벤치마크를 내 부품 점수로 환산한 예상 수치를 보여 줍니다. 예상 수치는 추정이며 프로젝트·설정에 따라 크게 달라집니다.</p>
    <div className="planner-game-list">
      {programs.map((program) => {
        const result = diagnoseProgram(program, parts, workload)
        if (!result) return <article className="planner-game" key={program}>
          <h3>{program}</h3><p>이 프로그램의 요구 사양 자료가 아직 없어 판단하지 않습니다.</p>
        </article>
        return <article className="planner-game" key={program}>
          <div className="planner-card-head"><h3>{program}</h3></div>
          <p className="planner-source">성능을 좌우하는 부품: {result.profile.keyParts}</p>
          <div className="planner-tier-grid">
            {result.levels.map((level) => <div className="planner-tier" key={level.level}>
              <div className="planner-tier-head"><div><h4>{level.level}</h4></div>
                <span className={`planner-verdict planner-verdict--${level.verdict.toLowerCase()}`}>{STATUS[level.verdict]}</span>
              </div>
              <div className="planner-requirement-list">
                {level.checks.map((check) => <div className="planner-requirement-row" key={check.item}>
                  <strong>{check.item}</strong><span>기준: {check.required}<br />내 PC: {check.actual}</span>
                  <div><span className={`planner-verdict planner-verdict--${check.verdict.toLowerCase()}`}>
                    {CHECK_STATUS[check.verdict]}</span></div>
                </div>)}
              </div>
              {(level.level === '공식 최소' ? result.profile.minimum.note : level.level === '공식 권장'
                ? result.profile.recommended.note : undefined) &&
                <p className="planner-caveat">{level.level === '공식 최소' ? result.profile.minimum.note : result.profile.recommended.note}</p>}
            </div>)}
          </div>
          <div className="planner-internal-score planner-frame-estimate">
            <h4>예상 성능 <small>추정</small></h4>
            <div className="planner-internal-tier-grid">
              {result.estimates.map((estimate) => <div className="planner-internal-tier" key={estimate.title}>
                <strong>{estimate.title}</strong>
                <span className="planner-frame-value">{estimate.value}</span>
                <small>{estimate.detail}</small>
              </div>)}
            </div>
            <p className="planner-source">근거: {result.profile.sourceIds.map((id, index) => <span key={id}>{index > 0 && ' · '}
              <a href={WORKLOAD_SOURCES[id].url} target="_blank" rel="noopener noreferrer">{WORKLOAD_SOURCES[id].name}</a>
              {' '}({SOURCE_KIND[WORKLOAD_SOURCES[id].kind]})</span>)}</p>
          </div>
        </article>
      })}
    </div>
  </section>
}
