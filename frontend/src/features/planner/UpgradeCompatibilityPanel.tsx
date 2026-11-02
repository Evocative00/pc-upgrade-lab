import { incompatibleByDraft, type CompatibilityStatus } from '../builder/compatibility.ts'
import { useCompatibility } from '../builder/useCompatibility.ts'
import type { PartInput, PartType } from '../pc-scan/types.ts'
import { getPartTypeInfo } from '../pc/partCategories.ts'
import type { PartDraft } from '../pc/types.ts'
import { applyPlan, type UpgradePlan } from './upgradeRecommendation.ts'
import type { UpgradePlanState } from './useUpgradePlan.ts'

const STATUS_TEXT: Record<CompatibilityStatus, string> = { COMPATIBLE: '호환', NEEDS_CHECK: '확인 필요', INCOMPATIBLE: '비호환' }
const STATUS_CLASS: Record<CompatibilityStatus, string> = { COMPATIBLE: 'pass', NEEDS_CHECK: 'unknown', INCOMPATIBLE: 'fail' }
// 호환성 검사 API가 보는 부품. 저장장치·쿨러·모니터는 검사하지 않는다.
const CHECKED_TYPES: PartType[] = ['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'PSU', 'CASE']

// 검사 결과를 부품 행에 다시 연결할 수 있게 순서대로 고정 key를 붙인다.
function toDrafts(parts: PartInput[]): PartDraft[] {
  return parts.map((part, index) => ({ ...part, key: `${part.type}-${index}`, edited: false, persisted: false }))
}

function finalDraftsOf(parts: PartInput[], plan: UpgradePlan | null): PartDraft[] {
  return toDrafts(plan ? applyPlan(parts, plan) : parts)
}

export function UpgradeCompatibilityPanel({ parts, state, onExclude, onLinkParts }: {
  parts: PartInput[]
  state: UpgradePlanState
  // 비호환 판정을 받은 교체 제품을 후보에서 빼고 플랜을 다시 만든다.
  onExclude: (productIds: string[]) => void
  onLinkParts: () => void
}) {
  const { plan, load } = state
  const drafts = finalDraftsOf(parts, plan)
  const compatibility = useCompatibility(drafts, load.status !== 'loading')
  const replacedIds = new Map(plan?.items.flatMap((item) => item.choice ? [[item.choice.id, item]] as const : []) ?? [])
  const isReplaced = (draft: PartDraft) => draft.catalogProductId !== null && replacedIds.has(draft.catalogProductId)

  const unlinked = CHECKED_TYPES.filter((type) => drafts.some((draft) => draft.type === type && !draft.catalogProductId))
  const missing = (['PSU', 'CASE'] as PartType[]).filter((type) => !drafts.some((draft) => draft.type === type))
  const result = compatibility.status === 'done' ? compatibility.result : null
  const warnings = result ? incompatibleByDraft(drafts, result) : new Map<string, string[]>()
  const badReplaced = drafts.filter((draft) => isReplaced(draft) && warnings.has(draft.key))

  return <div className="planner-result-grid">
    <section className="planner-card planner-compat">
      <div className="planner-card-head"><h2>최종 구성 호환성</h2>
        {result && <span className={`planner-verdict planner-verdict--${STATUS_CLASS[result.status]}`}>{STATUS_TEXT[result.status]}</span>}
      </div>

      <ul className="planner-compat-parts">{drafts.filter((draft) => CHECKED_TYPES.includes(draft.type)).map((draft) =>
        <li key={draft.key} className={warnings.has(draft.key) ? 'is-bad' : isReplaced(draft) ? 'is-replaced' : undefined}>
          <span className="planner-final-type">{getPartTypeInfo(draft.type).label}</span>
          <div className="planner-final-name"><strong>{draft.displayName}</strong>
            <small>{isReplaced(draft) ? '교체' : '유지'}{!draft.catalogProductId && ' · 검사 제외(미연결)'}</small>
            {warnings.get(draft.key)?.map((message) => <small key={message} className="planner-compat-warning">{message}</small>)}
          </div>
        </li>)}
      </ul>

      {badReplaced.length > 0 && <div className="planner-compat-action">
        <p>교체 부품 {badReplaced.length}개가 맞지 않습니다.</p>
        <button type="button" className="button button--primary button--lg"
          onClick={() => onExclude(badReplaced.map((draft) => draft.catalogProductId!))}>다른 제품으로 다시 추천</button>
      </div>}

      {compatibility.status === 'loading' && <p className="muted" role="status">검사 중…</p>}
      {compatibility.status === 'error' && <p className="error" role="alert">{compatibility.message}</p>}
      {result && <>
        <details className="planner-details" open={result.status === 'INCOMPATIBLE'}>
        <summary>검사 항목 {result.checks.length}개 보기</summary>
        <ul className="planner-check-list">{result.checks.map((check) =>
          <li key={check.code}><strong>{check.message}</strong>
            <span className={`planner-verdict planner-verdict--${STATUS_CLASS[check.status]}`}>{STATUS_TEXT[check.status]}</span></li>)}
        </ul>
        </details>
      </>}
    </section>

    <aside className="planner-card planner-aside">
      <h2>검사 범위</h2>
      {unlinked.length > 0 ? <>
        <p className="planner-warning">미연결: {unlinked.map((type) => getPartTypeInfo(type).label).join(', ')}</p>
        <button type="button" className="button button--ghost" onClick={onLinkParts}>내 PC에서 연결하기</button>
      </> : <p className="planner-caveat">모든 부품 검사됨</p>}
      {missing.length > 0 && <p className="planner-caveat">미입력: {missing.map((type) => getPartTypeInfo(type).label).join(', ')}</p>}
    </aside>
  </div>
}
