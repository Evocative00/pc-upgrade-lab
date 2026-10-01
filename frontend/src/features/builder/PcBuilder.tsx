import { CatalogPicker } from '../catalog/CatalogPicker.tsx'
import { formatCatalogPrice } from '../catalog/catalogPresentation.ts'
import type { CatalogProduct } from '../catalog/catalogTypes.ts'
import { getPartTypeInfo } from '../pc/partCategories.ts'
import { createManualDraft, getPartStatus, linkCatalog, withEmptyRows } from '../pc/partDraft.ts'
import type { PartDraft } from '../pc/types.ts'
import {
  assignSlots, priceTotal, ramLabel, ramSlotCount, SLOTS, slotInfo, type SlotId,
} from './buildSlots.ts'
import { CaseView, type SlotView } from './CaseView.tsx'
import { useCatalogDetails } from './useCatalogDetails.ts'
import './pc-builder.css'

// 자리에서 열었으면 draftKey는 null이고, 부품 행의 '부품 검색'에서 열었으면 그 행의 key다.
export type PickTarget = { slot: SlotId; draftKey: string | null }

type Props = {
  drafts: PartDraft[]
  updateDrafts: (update: (drafts: PartDraft[]) => PartDraft[]) => void
  target: PickTarget | null
  onTarget: (target: PickTarget | null) => void
}

// 2D 구성 화면: 부품 선택 | 케이스 그림 | 요약·상태·기준가격. 내용은 PC 입력 폼의 부품 항목과 같은 데이터다.
export function PcBuilder({ drafts, updateDrafts, target, onTarget }: Props) {
  const slots = assignSlots(drafts)
  const named = drafts.filter((draft) => getPartStatus(draft) !== 'empty')
  const details = useCatalogDetails(named.flatMap((draft) => draft.catalogProductId ?? []))
  const detailOf = (draft: PartDraft) => draft.catalogProductId ? details[draft.catalogProductId] : undefined
  const productOf = (draft: PartDraft) => {
    const entry = detailOf(draft)
    return entry?.status === 'done' && entry.detail.product.type === draft.type ? entry.detail : null
  }

  const views: Partial<Record<SlotId, SlotView>> = {}
  for (const [id, draft] of Object.entries(slots) as [SlotId, PartDraft][]) {
    views[id] = { name: draft.displayName.trim(), fadeKey: `${draft.key}:${draft.catalogProductId ?? draft.displayName}` }
  }
  const ramDrafts = named.filter((draft) => draft.type === 'RAM')
  const ramDetail = slots.RAM && productOf(slots.RAM)
  const ramSlots = ramSlotCount(ramDrafts.map((draft) => draft.quantity), ramDetail?.specification.moduleCount)
  const ramText = ramLabel(slots.RAM ? { ...slots.RAM.specs, ...ramDetail?.specification } : null)

  function choose(product: CatalogProduct) {
    if (!target) return
    const keyFromSlot = target.draftKey ?? slots[target.slot]?.key
    const existing = drafts.find((draft) => draft.key === keyFromSlot && draft.type === product.type)
    // 자리에 항목이 없으면 같은 종류의 빈 줄을 쓰고, 빈 줄도 없으면 새 줄을 만든다.
    const empty = drafts.find((draft) => draft.type === product.type && getPartStatus(draft) === 'empty')
    const base = existing ?? empty ?? createManualDraft(product.type)
    const visualSlot = target.draftKey === null && (target.slot === 'SSD' || target.slot === 'HDD')
      ? target.slot : base.visualSlot
    const linked = { ...linkCatalog(base, product), visualSlot }
    updateDrafts((current) => current.some((draft) => draft.key === base.key)
      ? current.map((draft) => draft.key === base.key ? linked : draft)
      : withEmptyRows([...current, linked]))
    onTarget(null)
  }

  function remove(key: string) {
    updateDrafts((current) => withEmptyRows(current.filter((draft) => draft.key !== key)))
  }

  const total = priceTotal(named.map((draft) => productOf(draft)?.product.referencePrice ?? null))
  const pickSlot = (slot: SlotId) => onTarget(target?.slot === slot && target.draftKey === null ? null : { slot, draftKey: null })
  const activeLabel = target && (target.draftKey
    ? getPartTypeInfo(slotInfo(target.slot).type).label : slotInfo(target.slot).label)

  return (
    <section className="builder" aria-label="PC 구성">
      <div className="builder__layout">
        <div className="builder__parts" role="group" aria-label="부품 종류">
          <ul>
            {SLOTS.map((slot) => {
              const active = target?.slot === slot.id && target.draftKey === null
              return (
                <li key={slot.id}>
                  <button type="button" aria-pressed={active}
                    className={`builder__part${active ? ' builder__part--active' : ''}`}
                    onClick={() => pickSlot(slot.id)}>
                    <span className="builder__part-label">{slot.label}</span>
                    <span className="muted">{slots[slot.id]?.displayName ?? '선택 안 함'}</span>
                  </button>
                </li>
              )
            })}
          </ul>
        </div>

        <div className="builder__visual">
          <CaseView slots={views} ramSlots={ramSlots} ramText={ramText}
            active={target?.slot ?? null} onPick={pickSlot} />
        </div>

        <aside className="builder__summary" aria-label="구성 요약">
          <h2>구성 요약</h2>
          <dl>
            {named.map((draft) => {
              const entry = detailOf(draft)
              const detail = productOf(draft)
              const placed = Object.entries(slots).find(([, item]) => item.key === draft.key)
              const label = placed ? slotInfo(placed[0] as SlotId).label : getPartTypeInfo(draft.type).label
              return (
                <div key={draft.key} className="builder__row">
                  <dt>{label}{draft.quantity > 1 && ` × ${draft.quantity}`}</dt>
                  <dd>
                    <span className="builder__name">{draft.displayName}</span>
                    {!draft.catalogProductId
                      ? <span className="muted">카탈로그 미연결 · 기준가격 없음</span>
                      : detail ? <span className="muted">{formatCatalogPrice(detail.product.referencePrice)}</span>
                        : entry?.status === 'error'
                          ? <span className="error">가격·제원 조회 실패: {entry.message}</span>
                          : <span className="muted">가격·제원 불러오는 중…</span>}
                    <button type="button" className="button button--ghost builder__remove"
                      onClick={() => remove(draft.key)}>빼기</button>
                  </dd>
                </div>
              )
            })}
          </dl>
          {named.length === 0 && <p className="muted">아직 고른 부품이 없습니다.</p>}

          <div className="builder__total">
            <span>확정 기준가격 합계</span>
            <strong>{total.confirmedKrw.toLocaleString('ko-KR')}원</strong>
            {total.unconfirmed > 0 && (
              <span className="muted">가격 미확정·미연결 {total.unconfirmed}개는 합계에서 뺐습니다.</span>
            )}
          </div>
          {/* 검사 전 상태를 '양호'로 보이지 않게 한다. 호환성 검사 API 연결 전까지 항상 미검사다. */}
          <p className="builder__check" role="status">
            <span aria-hidden="true">?</span> 호환성 미검사 · 확인 필요
          </p>
        </aside>
      </div>

      {target && (
        <section className="builder__picker" aria-label={`${activeLabel} 고르기`}>
          <h2>{activeLabel} 고르기</h2>
          <CatalogPicker key={`${target.slot}-${target.draftKey}`} type={slotInfo(target.slot).type}
            initialQuery={drafts.find((draft) => draft.key === target.draftKey)?.displayName ?? ''}
            onClose={() => onTarget(null)} onSelect={choose} />
        </section>
      )}
    </section>
  )
}
