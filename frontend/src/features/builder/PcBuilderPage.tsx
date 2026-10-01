import { useEffect, useRef, useState } from 'react'
import { CatalogPicker } from '../catalog/CatalogPicker.tsx'
import { catalogClient } from '../catalog/catalogClient.ts'
import { formatCatalogPrice } from '../catalog/catalogPresentation.ts'
import type { CatalogProduct } from '../catalog/catalogTypes.ts'
import { priceTotal, SLOTS, type Build, type SlotId } from './buildSlots.ts'
import { CaseView } from './CaseView.tsx'
import './pc-builder.css'

// 2D PC 구성 화면 와이어프레임. 구성은 메모리에만 있고 저장·초안 보관은 아직 하지 않는다.
export function PcBuilderPage() {
  const [build, setBuild] = useState<Build>({})
  const [active, setActive] = useState<SlotId | null>(null)
  const [specErrors, setSpecErrors] = useState<Partial<Record<SlotId, string>>>({})
  const controllers = useRef<Partial<Record<SlotId, AbortController>>>({})

  useEffect(() => () => Object.values(controllers.current).forEach((controller) => controller.abort()), [])

  // 검색 결과에는 제원이 없어서, 고른 뒤 상세를 한 번 더 받아 그림 라벨(용량·세대·모듈 수)에 쓴다.
  async function choose(slotId: SlotId, product: CatalogProduct) {
    controllers.current[slotId]?.abort()
    const controller = new AbortController()
    controllers.current[slotId] = controller
    setBuild((prev) => ({ ...prev, [slotId]: { product, specs: null } }))
    setSpecErrors((prev) => ({ ...prev, [slotId]: undefined }))
    setActive(null)
    try {
      const detail = await catalogClient.get(product.id, controller.signal)
      setBuild((prev) => prev[slotId]?.product.id === product.id
        ? { ...prev, [slotId]: { product, specs: detail.specification } } : prev)
    } catch (error) {
      if (!controller.signal.aborted) setSpecErrors((prev) => ({ ...prev,
        [slotId]: error instanceof Error ? error.message : '제원을 불러오지 못했습니다.' }))
    }
  }

  function remove(slotId: SlotId) {
    controllers.current[slotId]?.abort()
    setBuild((prev) => ({ ...prev, [slotId]: undefined }))
    setSpecErrors((prev) => ({ ...prev, [slotId]: undefined }))
  }

  const activeSlot = SLOTS.find((slot) => slot.id === active)
  const total = priceTotal(build)

  return (
    <div className="builder">
      <div className="page-head">
        <div>
          <h1>PC 구성하기</h1>
          <p className="muted">그림의 자리나 왼쪽 목록을 눌러 부품을 고르세요. 구성 내용은 아직 저장되지 않습니다.</p>
        </div>
      </div>

      <div className="builder__layout">
        <nav className="builder__parts" aria-label="부품 종류">
          <ul>
            {SLOTS.map((slot) => (
              <li key={slot.id}>
                <button type="button" aria-pressed={active === slot.id}
                  className={`builder__part${active === slot.id ? ' builder__part--active' : ''}`}
                  onClick={() => setActive(active === slot.id ? null : slot.id)}>
                  <span className="builder__part-label">{slot.label}</span>
                  <span className="muted">{build[slot.id]?.product.modelName ?? '선택 안 함'}</span>
                </button>
              </li>
            ))}
          </ul>
        </nav>

        <div className="builder__visual">
          <CaseView build={build} active={active} onPick={(slotId) => setActive(slotId)} />
        </div>

        <aside className="builder__summary" aria-label="구성 요약">
          <h2>구성 요약</h2>
          <dl>
            {SLOTS.filter((slot) => build[slot.id]).map((slot) => {
              const part = build[slot.id]!
              return (
                <div key={slot.id} className="builder__row">
                  <dt>{slot.label}</dt>
                  <dd>
                    <span className="builder__name">{part.product.modelName}</span>
                    <span className="muted">{formatCatalogPrice(part.product.referencePrice)}</span>
                    {part.specs === null && !specErrors[slot.id] && <span className="muted">제원 불러오는 중…</span>}
                    {specErrors[slot.id] && <span className="error">제원 조회 실패: {specErrors[slot.id]}</span>}
                    <button type="button" className="button button--ghost builder__remove"
                      onClick={() => remove(slot.id)}>빼기</button>
                  </dd>
                </div>
              )
            })}
          </dl>
          {!Object.values(build).some(Boolean) && <p className="muted">아직 고른 부품이 없습니다.</p>}

          <div className="builder__total">
            <span>확정 기준가격 합계</span>
            <strong>{total.confirmedKrw.toLocaleString('ko-KR')}원</strong>
            {total.unconfirmed > 0 && <span className="muted">가격 미확정 {total.unconfirmed}개는 합계에서 뺐습니다.</span>}
          </div>
          {/* 검사 전 상태를 '양호'로 보이지 않게 한다. 호환성 검사 API 연결 전까지 항상 미검사다. */}
          <p className="builder__check" role="status">
            <span aria-hidden="true">?</span> 호환성 미검사 · 확인 필요
          </p>
        </aside>
      </div>

      {activeSlot && (
        <section className="builder__picker" aria-label={`${activeSlot.label} 고르기`}>
          <h2>{activeSlot.label} 고르기</h2>
          <CatalogPicker key={activeSlot.id} type={activeSlot.type} initialQuery=""
            onClose={() => setActive(null)} onSelect={(product) => void choose(activeSlot.id, product)} />
        </section>
      )}
    </div>
  )
}
