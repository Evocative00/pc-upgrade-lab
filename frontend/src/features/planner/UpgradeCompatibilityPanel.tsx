import { getPartTypeInfo } from '../pc/partCategories.ts'
import {
  COMPAT_CLASS, COMPAT_TEXT, compatCounts, compatibilityPairs, displayStatus, type CompatStatus, type PlanCompatibility,
} from './planCompatibility.ts'

const ICON: Record<CompatStatus, string> = { COMPATIBLE: '✓', INFO: 'i', NEEDS_CHECK: '!', INCOMPATIBLE: '✕' }
// 참고는 문제가 아니라서 맨 뒤에 둔다.
const ORDER: CompatStatus[] = ['COMPATIBLE', 'NEEDS_CHECK', 'INCOMPATIBLE', 'INFO']

/**
 * 최종 구성 목록 위에 붙는 호환성 요약: 상태별 개수 3칸과 부품 조합별 한 줄 목록.
 * 개수가 0인 칸은 흐리게 두어 문제가 있는 칸만 색이 보이게 한다.
 */
export function CompatibilitySummary({ compat, hasGpu, onExclude }: {
  compat: PlanCompatibility; hasGpu: boolean; onExclude: (productIds: string[]) => void
}) {
  const { state, result, missing, badReplacedIds } = compat
  if (state.status === 'loading') return <p className="muted planner-compat-loading" role="status">호환성 검사 중…</p>
  if (state.status === 'error') return <p className="error" role="alert">{state.message}</p>
  if (!result) return null
  const counts = compatCounts(result)
  const pairs = compatibilityPairs(result, missing, hasGpu)
  return <div className={`planner-compat-summary${counts.INCOMPATIBLE > 0 ? ' is-bad' : ''}`}>
    <div className="planner-compat-counts" aria-label="호환성 검사 결과">
      {ORDER.map((status) => <div key={status}
        className={`planner-compat-count planner-compat-count--${COMPAT_CLASS[status]}${counts[status] === 0 ? ' is-zero' : ''}`}>
        <span>{ICON[status]} {COMPAT_TEXT[status]}</span><strong>{counts[status]}</strong>
      </div>)}
    </div>
    {pairs.length > 0 && <ul className="planner-compat-pairs" aria-label="부품끼리 맞는지">
      {pairs.map((pair) => <li key={pair.label} className={`is-${COMPAT_CLASS[pair.status]}`}>
        <span className="planner-compat-icon" aria-label={COMPAT_TEXT[pair.status]}>{ICON[pair.status]}</span>
        <span>{pair.label}</span>
        <small>{pair.reason}</small>
      </li>)}
    </ul>}
    {badReplacedIds.length > 0 && <div className="planner-compat-action">
      <p>교체 부품 {badReplacedIds.length}개가 맞지 않습니다.</p>
      <button type="button" className="button button--primary button--lg"
        onClick={() => onExclude(badReplacedIds)}>다른 제품으로 다시 추천</button>
    </div>}
  </div>
}

/** 최종 구성 목록 아래에 검사 범위와 항목별 원문을 보여 준다. */
export function CompatibilityFooter({ compat, onLinkParts }: {
  compat: PlanCompatibility
  onLinkParts: () => void
}) {
  const { result, unlinked, missing } = compat
  return <div className="planner-compat">
    {(unlinked.length > 0 || missing.length > 0) && <ul className="planner-compat-scope">
      {unlinked.length > 0 && <li>검사 제외(카탈로그 미연결): {unlinked.map((type) => getPartTypeInfo(type).label).join(', ')}{' '}
        <button type="button" className="link-button" onClick={onLinkParts}>내 PC에서 연결하기</button></li>}
      {missing.length > 0 && <li>미입력: {missing.map((type) => getPartTypeInfo(type).label).join(', ')}</li>}
    </ul>}
    {result && <details className="planner-details">
      <summary>검사 항목 {result.checks.length}개 원문 보기</summary>
      <ul className="planner-check-list">{result.checks.map((check) =>
        <li key={check.code}><strong>{check.message}</strong>
          <span className={`planner-verdict planner-verdict--${COMPAT_CLASS[displayStatus(check)]}`}>{COMPAT_TEXT[displayStatus(check)]}</span></li>)}
      </ul>
      <p className="planner-caveat">CPU·메인보드·RAM·그래픽카드·파워·케이스의 일부 조건만 검사합니다.
        저장장치·쿨러·모니터, BIOS·전체 PC 동작은 별도로 확인하세요.</p>
    </details>}
  </div>
}
