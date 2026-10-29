import type { ReactNode } from 'react'
import { CatalogPickerDialog } from './CatalogPickerDialog.tsx'
import { formatProjectPrice, specificationRows } from '../catalog/catalogPresentation.ts'
import type { CatalogDetail, CatalogModel, CatalogProduct } from '../catalog/catalogTypes.ts'
import { MotherboardStorageDetail } from '../catalog/MotherboardStorageDetail.tsx'
import { getPartTypeInfo } from '../pc/partCategories.ts'
import { getPartStatus } from '../pc/partDraft.ts'
import type { PartDraft } from '../pc/types.ts'
import {
  applyCatalogSelection, applyCatalogModelSelection, assignSlots, shortPartName, priceTotal, referencePriceTotal, ramModuleViews, slotInfo, type PickTarget, type SlotId,
} from './buildSlots.ts'
import { CaseView, type SlotView } from './CaseView.tsx'
import type { CompatibilityStatus } from './compatibility.ts'
import { useCatalogDetails } from './useCatalogDetails.ts'
import type { CompatibilityState } from './useCompatibility.ts'
import './pc-builder.css'

// 자리에서 열었으면 draftKey는 null이고, 부품 행의 '부품 검색'에서 열었으면 그 행의 key다.
export type { PickTarget } from './buildSlots.ts'

type Props = {
  drafts: PartDraft[]
  updateDrafts: (update: (drafts: PartDraft[]) => PartDraft[]) => void
  target: PickTarget | null
  onTarget: (target: PickTarget | null) => void
  compatibility: CompatibilityState
  // 불가 판정을 받은 부품 행 key → 경고 문구
  warnings: Map<string, string[]>
  // 왼쪽 칸에 넣을 부품 입력란 (PC 입력 폼의 '부품 구성')
  children: ReactNode
  // 요약 칸 아래에 붙어 따라오는 저장 버튼
  submit?: ReactNode
}

// 호환성 3단계. 검사 결과가 없을 때는 어느 단계도 켜지 않는다(양호로 보이지 않게).
const STEPS: { status: CompatibilityStatus; icon: string; label: string }[] = [
  { status: 'COMPATIBLE', icon: '✓', label: '호환' },
  { status: 'NEEDS_CHECK', icon: '!', label: '확인 필요' },
  { status: 'INCOMPATIBLE', icon: '✕', label: '불가' },
]
const ORDER: Record<CompatibilityStatus, number> = { INCOMPATIBLE: 0, NEEDS_CHECK: 1, COMPATIBLE: 2 }

function CompatibilityPanel({ state }: { state: CompatibilityState }) {
  const current = state.status === 'done' ? state.result.status : null
  const checks = state.status === 'done' ? state.result.checks : []
  const stateLabel = state.status === 'done' ? STEPS.find((step) => step.status === current)!.label
    : { idle: '미검사', loading: '검사 중', error: '검사 실패' }[state.status]
  return (
    <section className="builder-card compat" aria-label="호환성 체크">
      <div className="builder-card__head">
        <h3>호환성 체크</h3>
        <span className="builder-card__tag" role="status">{stateLabel}</span>
      </div>
      {/* 검사 결과가 없으면 수를 비워 둔다(0건 = 양호로 보이지 않게). */}
      <ol className="compat__steps">
        {STEPS.map((step) => (
          <li key={step.status} className={`compat__step compat__step--${step.status.toLowerCase()}`}
            aria-current={current === step.status ? 'step' : undefined}>
            <strong>{state.status === 'done' ? checks.filter((check) => check.status === step.status).length : '—'}</strong>
            {step.label}
          </li>
        ))}
      </ol>
      {state.status === 'error' && <p className="error" role="alert">{state.message}</p>}
      {state.status === 'done' && (
        <ul className="compat__checks">
          {[...state.result.checks].sort((a, b) => ORDER[a.status] - ORDER[b.status]).map((check, index) => (
            <li key={`${check.code}-${index}`} className={`compat__check compat__check--${check.status.toLowerCase()}`}>
              <span aria-hidden="true">{STEPS.find((step) => step.status === check.status)!.icon}</span>
              <span>{check.message}</span>
            </li>
          ))}
        </ul>
      )}
      <p className="muted">카탈로그에서 부품을 고르면 CPU·메인보드·RAM 규격과 그래픽카드·파워·케이스의 장착 및 전원 조건을 검사합니다.</p>
      <p className="muted">저장장치·쿨러, 그래픽 슬롯·전원 연결과 전체 PC 동작은 별도 확인이 필요합니다.</p>
    </section>
  )
}

// 메인보드 기준 포트·슬롯 정보. 카탈로그에 있는 제원만 보여 주고, 자료가 없는 항목은 미확인으로 둔다.
function BoardPorts({ board, linked }: { board: CatalogDetail | null; linked: boolean }) {
  return (
    <section className="board-ports" aria-label="메인보드 포트·슬롯">
      <h3>메인보드 포트·슬롯</h3>
      {!board
        ? <p className="muted">{linked ? '메인보드 제원을 불러오는 중…' : '카탈로그에서 메인보드를 고르면 슬롯 정보를 볼 수 있습니다.'}</p>
        : <>
          <dl className="catalog-specs">
            {specificationRows('MOTHERBOARD', board.specification).map(([label, value]) => (
              <div key={label}><dt>{label}</dt><dd>{value}</dd></div>
            ))}
            <div><dt>USB 포트·허브·팬 헤더</dt><dd>미확인 (카탈로그 자료 없음)</dd></div>
          </dl>
          <MotherboardStorageDetail key={board.product.id} productId={board.product.id} />
        </>}
    </section>
  )
}

// 2D 구성 화면: 부품 구성 입력 | 케이스 그림 | 요약·상태·현재 상품가. 내용은 PC 입력 폼의 부품 항목과 같은 데이터다.
export function PcBuilder({ drafts, updateDrafts, target, onTarget, compatibility, warnings, children, submit }: Props) {
  const slots = assignSlots(drafts)
  const named = drafts.filter((draft) => getPartStatus(draft) !== 'empty')
  const catalogIds = named.flatMap((draft) => draft.catalogProductId ?? [])
  const { entries: details, retry, refreshAll } = useCatalogDetails(catalogIds)
  const detailOf = (draft: PartDraft) => draft.catalogProductId ? details[draft.catalogProductId] : undefined
  const productOf = (draft: PartDraft) => {
    const entry = detailOf(draft)
    return entry?.status === 'done' && entry.detail.product.type === draft.type ? entry.detail : null
  }

  const views: Partial<Record<SlotId, SlotView>> = {}
  for (const [id, draft] of Object.entries(slots) as [SlotId, PartDraft][]) {
    views[id] = { name: shortPartName(draft.type, draft.displayName), fadeKey: `${draft.key}:${draft.catalogProductId ?? draft.displayName}` }
  }
  const ram = ramModuleViews(named, (draft) => productOf(draft)?.specification ?? null)
  // 검색 대상 행을 지우거나 재스캔한 뒤에는 이전 검색 결과를 다른 행에 적용하지 않는다.
  const activeTarget = target?.draftKey && !drafts.some((draft) => draft.key === target.draftKey)
    ? null : target

  function choose(product: CatalogProduct) {
    if (!activeTarget) return
    updateDrafts((current) => applyCatalogSelection(current, activeTarget, product))
    onTarget(null)
  }

  function chooseModel(model: CatalogModel) {
    if (!activeTarget) return
    updateDrafts((current) => applyCatalogModelSelection(current, activeTarget, model))
    onTarget(null)
  }

  const total = priceTotal(named.map((draft) => ({ draft, detail: productOf(draft) })))
  const referenceTotal = referencePriceTotal(named.map((draft) => ({ draft, detail: productOf(draft) })))
  const pickSlot = (slot: SlotId) => onTarget(target?.slot === slot && target.draftKey === null ? null : { slot, draftKey: null })
  const activeLabel = activeTarget && (activeTarget.draftKey
    ? getPartTypeInfo(slotInfo(activeTarget.slot).type).label : slotInfo(activeTarget.slot).label)

  return (
    <section className="builder" aria-label="PC 구성">
      <div className="builder__layout">
        <div className="builder__parts">{children}</div>

        <div className="builder__visual">
          <div className="builder__view-head" aria-hidden="true">
            <span>&gt; LAYOUT VIEW</span><span>ATX · 예시 배치</span>
          </div>
          <div className="builder__view">
            <CaseView slots={views} ramModules={ram.modules}
              active={activeTarget?.slot ?? null} onPick={pickSlot} />
          </div>
          <p className="muted">구성 이해를 위한 예시 그림입니다. 실제 크기·위치·장착 가능 수를 뜻하지 않습니다.
            {ram.installedCount > 0 && ` RAM 장착 ${ram.installedCount}개 중 첫 ${ram.modules.length}개를 표시합니다.`}</p>
          <BoardPorts board={slots.MOTHERBOARD ? productOf(slots.MOTHERBOARD) : null}
            linked={Boolean(slots.MOTHERBOARD?.catalogProductId)} />
        </div>

        <aside className="builder__summary" aria-label="구성 요약">
          <div className="builder-card">
          <div className="builder-card__head">
            <h2>구성 요약</h2>
            <span className="builder-card__tag" aria-hidden="true">SUMMARY</span>
          </div>
          {/* 가격이 없으면 '— 원'으로 두고 0원으로 쓰지 않는다. */}
          <div className="builder__total">
            <span>현재 상품가 합계</span>
            <strong className="builder__price">{total.pricedItems > 0 ? total.currentKrw.toLocaleString('ko-KR') : '—'} 원</strong>
            {total.unpriced > 0 && (
              <span className="muted">가격 미확인·미연결·만료·연결 실패 등으로 {total.unpriced}개 항목은 합계에서 뺐습니다. 각 부품의 가격 상태를 확인해 주세요.</span>
            )}
            {total.quantityNeedsCheck > 0 && (
              <span className="muted">수량·RAM 판매 묶음 확인이 필요한 {total.quantityNeedsCheck}건은 합계에서 뺐습니다.</span>
            )}
            <span className="muted">RAM은 장착 모듈 합계가 판매 묶음 수와 맞을 때 계산됩니다.</span>
          </div>
          <div className="builder__total">
            <span>참고가격 합계</span>
            <strong className="builder__price builder__price--sub">{referenceTotal.pricedItems > 0 ? referenceTotal.referenceKrw.toLocaleString('ko-KR') : '—'} 원</strong>
            <span className="muted">확인한 판매가를 우선하며 과거가·출시가·추정가를 포함합니다. 실제 구매 금액과 다를 수 있습니다.</span>
            {(referenceTotal.unpriced > 0 || referenceTotal.quantityNeedsCheck > 0) && <span className="muted">
              가격·연결 미확인 {referenceTotal.unpriced}개, 수량·RAM 판매 단위 확인 필요 {referenceTotal.quantityNeedsCheck}건은 참고 합계에서 뺐습니다.
            </span>}
          </div>
          <button type="button" className="button button--ghost builder__refresh" onClick={refreshAll}
            disabled={catalogIds.length === 0 || catalogIds.some((id) => !details[id] || details[id].status === 'loading')}>
            <span aria-hidden="true">⟳</span> 가격 새로고침
          </button>

          {/* 부품마다 한 줄: 종류 | 이름 | 가격. 자세한 상태는 마우스·화면 낭독기로 전한다. 제거는 왼쪽 입력란에서 한다. */}
          <dl className="builder__list">
            {named.map((draft) => {
              const entry = detailOf(draft)
              const detail = productOf(draft)
              const placed = Object.entries(slots).find(([, item]) => item.key === draft.key)
              const label = placed ? slotInfo(placed[0] as SlotId).label : getPartTypeInfo(draft.type).label
              const state = !draft.catalogProductId
                ? `${draft.catalogModelId ? '모델 확인 · 상품 미연결' : '카탈로그 미연결'} · 현재 상품가 없음`
                : detail ? formatProjectPrice(detail.product)
                  : entry?.status === 'error' ? `가격·제원 조회 실패: ${entry.message}` : '가격·제원 불러오는 중'
              return (
                <div key={draft.key} className={`builder__row builder__row--${getPartStatus(draft)}${warnings.has(draft.key) ? ' builder__row--invalid' : ''}`}
                  title={`${draft.displayName} · ${state}`}>
                  <dt>{label}{draft.quantity > 1 && ` ×${draft.quantity}`}</dt>
                  <dd className="builder__name">{shortPartName(draft.type, draft.displayName)}</dd>
                  <dd className="builder__row-price">
                    {detail ? formatProjectPrice(detail.product)
                      : entry?.status === 'error'
                        ? <button type="button" className="link-button" onClick={() => retry(draft.catalogProductId!)}>다시 조회</button>
                        : <span aria-hidden="true">{draft.catalogProductId ? '…' : '—'}</span>}
                    {!detail && <span className="visually-hidden">{state}</span>}
                  </dd>
                  {warnings.get(draft.key)?.map((message) => (
                    <dd key={message} className="builder__row-warning error">⚠ {message}</dd>
                  ))}
                </div>
              )
            })}
          </dl>
          {named.length === 0 && <p className="muted">아직 고른 부품이 없습니다.</p>}
          </div>
          <CompatibilityPanel state={compatibility} />
          {submit && <div className="builder__submit">{submit}</div>}
        </aside>
      </div>

      {activeTarget && (
        <CatalogPickerDialog key={`${activeTarget.slot}-${activeTarget.draftKey}`} label={activeLabel!}
          type={slotInfo(activeTarget.slot).type}
          initialQuery={drafts.find((draft) => draft.key === activeTarget.draftKey)?.displayName ?? ''}
          onClose={() => onTarget(null)} onSelect={choose} onSelectModel={chooseModel} />
      )}
    </section>
  )
}
