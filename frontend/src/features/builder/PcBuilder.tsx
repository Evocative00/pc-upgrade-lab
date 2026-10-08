import type { ReactNode } from 'react'
import { CatalogPicker } from '../catalog/CatalogPicker.tsx'
import { formatCurrentPrice } from '../catalog/catalogPresentation.ts'
import { CurrentPriceSource } from '../catalog/CurrentPriceSource.tsx'
import type { CatalogProduct } from '../catalog/catalogTypes.ts'
import { getPartTypeInfo } from '../pc/partCategories.ts'
import { getPartStatus, withEmptyRows } from '../pc/partDraft.ts'
import type { PartDraft } from '../pc/types.ts'
import {
  applyCatalogSelection, assignSlots, priceTotal, ramModuleViews, slotInfo, type PickTarget, type SlotId,
} from './buildSlots.ts'
import { CaseView, type SlotView } from './CaseView.tsx'
import { useCatalogDetails } from './useCatalogDetails.ts'
import './pc-builder.css'

// 자리에서 열었으면 draftKey는 null이고, 부품 행의 '부품 검색'에서 열었으면 그 행의 key다.
export type { PickTarget } from './buildSlots.ts'

type Props = {
  drafts: PartDraft[]
  updateDrafts: (update: (drafts: PartDraft[]) => PartDraft[]) => void
  target: PickTarget | null
  onTarget: (target: PickTarget | null) => void
  // 왼쪽 칸에 넣을 부품 입력란 (PC 입력 폼의 '부품 구성')
  children: ReactNode
}

// 2D 구성 화면: 부품 구성 입력 | 케이스 그림 | 요약·상태·현재 상품가. 내용은 PC 입력 폼의 부품 항목과 같은 데이터다.
export function PcBuilder({ drafts, updateDrafts, target, onTarget, children }: Props) {
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
    views[id] = { name: draft.displayName.trim(), fadeKey: `${draft.key}:${draft.catalogProductId ?? draft.displayName}` }
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

  function remove(key: string) {
    updateDrafts((current) => withEmptyRows(current.filter((draft) => draft.key !== key)))
    if (target?.draftKey === key) onTarget(null)
  }

  const total = priceTotal(named.map((draft) => ({ draft, detail: productOf(draft) })))
  const pickSlot = (slot: SlotId) => onTarget(target?.slot === slot && target.draftKey === null ? null : { slot, draftKey: null })
  const activeLabel = activeTarget && (activeTarget.draftKey
    ? getPartTypeInfo(slotInfo(activeTarget.slot).type).label : slotInfo(activeTarget.slot).label)

  return (
    <section className="builder" aria-label="PC 구성">
      <div className="builder__layout">
        <div className="builder__parts">{children}</div>

        <div className="builder__visual">
          <CaseView slots={views} ramModules={ram.modules}
            active={activeTarget?.slot ?? null} onPick={pickSlot} />
          <p className="muted">구성 이해를 위한 예시 그림입니다. 실제 크기·위치·장착 가능 수를 뜻하지 않습니다.
            {ram.installedCount > 0 && ` RAM 장착 ${ram.installedCount}개 중 첫 ${ram.modules.length}개를 표시합니다.`}</p>
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
                      ? <span className="muted">카탈로그 미연결 · 현재 상품가 없음</span>
                      : detail ? <>
                        <span className="muted">{formatCurrentPrice(detail.product.currentPrice)}
                          {detail.product.currentPrice && (draft.type === 'RAM' ? ' / 판매 묶음' : ' / 1개')}</span>
                        <CurrentPriceSource price={detail.product.currentPrice} />
                      </>
                        : entry?.status === 'error'
                          ? <>
                            <span className="error">가격·제원 조회 실패: {entry.message}</span>
                            <button type="button" className="button button--ghost"
                              onClick={() => retry(draft.catalogProductId!)}>다시 조회</button>
                          </>
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
            <span>현재 상품가 합계</span>
            <strong>{total.pricedItems > 0 ? `${total.currentKrw.toLocaleString('ko-KR')}원` : '산정 가능한 가격 없음'}</strong>
            {total.unpriced > 0 && (
              <span className="muted">현재 상품가 미확인·미연결 {total.unpriced}개 항목은 합계에서 뺐습니다.</span>
            )}
            {total.quantityNeedsCheck > 0 && (
              <span className="muted">수량·RAM 판매 묶음 확인이 필요한 {total.quantityNeedsCheck}건은 합계에서 뺐습니다.</span>
            )}
            <span className="muted">RAM은 같은 제품의 장착 모듈 합계가 판매 묶음 수와 맞을 때만 계산합니다.</span>
            <button type="button" className="button button--ghost" onClick={refreshAll}
              disabled={catalogIds.length === 0 || catalogIds.some((id) => !details[id] || details[id].status === 'loading')}>
              가격 새로고침
            </button>
          </div>
          {/* 검사 전 상태를 '양호'로 보이지 않게 한다. 호환성 검사 API 연결 전까지 항상 미검사다. */}
          <p className="builder__check" role="status">
            <span aria-hidden="true">?</span> 호환성 미검사 · 확인 필요
          </p>
        </aside>
      </div>

      {activeTarget && (
        <section className="builder__picker" aria-label={`${activeLabel} 고르기`}>
          <h2>{activeLabel} 고르기</h2>
          <CatalogPicker key={`${activeTarget.slot}-${activeTarget.draftKey}`} type={slotInfo(activeTarget.slot).type}
            initialQuery={drafts.find((draft) => draft.key === activeTarget.draftKey)?.displayName ?? ''}
            onClose={() => onTarget(null)} onSelect={choose} />
        </section>
      )}
    </section>
  )
}
