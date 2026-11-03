import { getPartTypeInfo } from '../pc/partCategories.ts'
import { COMPAT_CLASS, COMPAT_TEXT, type PlanCompatibility } from './planCompatibility.ts'

/** 최종 구성 목록 아래에 붙는 호환성 요약: 다시 추천, 검사 범위, 세부 검사 항목. */
export function CompatibilityFooter({ compat, onExclude, onLinkParts }: {
  compat: PlanCompatibility
  onExclude: (productIds: string[]) => void
  onLinkParts: () => void
}) {
  const { state, result, unlinked, missing, badReplacedIds } = compat
  return <div className="planner-compat">
    {state.status === 'error' && <p className="error" role="alert">{state.message}</p>}
    {badReplacedIds.length > 0 && <div className="planner-compat-action">
      <p>교체 부품 {badReplacedIds.length}개가 지금 구성과 맞지 않습니다.</p>
      <button type="button" className="button button--primary button--lg" onClick={() => onExclude(badReplacedIds)}>
        다른 제품으로 다시 추천</button>
    </div>}
    <ul className="planner-compat-scope">
      {unlinked.length > 0 && <li>검사 제외(카탈로그 미연결): {unlinked.map((type) => getPartTypeInfo(type).label).join(', ')}{' '}
        <button type="button" className="link-button" onClick={onLinkParts}>내 PC에서 연결하기</button></li>}
      {missing.length > 0 && <li>미입력: {missing.map((type) => getPartTypeInfo(type).label).join(', ')}</li>}
    </ul>
    {result && <details className="planner-details" open={result.status === 'INCOMPATIBLE'}>
      <summary>호환성 검사 항목 {result.checks.length}개 보기</summary>
      <ul className="planner-check-list">{result.checks.map((check) =>
        <li key={check.code}><strong>{check.message}</strong>
          <span className={`planner-verdict planner-verdict--${COMPAT_CLASS[check.status]}`}>{COMPAT_TEXT[check.status]}</span></li>)}
      </ul>
      <p className="planner-caveat">CPU·메인보드·RAM·그래픽카드·파워·케이스의 일부 조건만 검사합니다.
        저장장치·쿨러·모니터, BIOS·전체 PC 동작은 별도로 확인하세요.</p>
    </details>}
  </div>
}
