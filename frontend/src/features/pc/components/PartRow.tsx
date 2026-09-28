import { useId, useState } from 'react'
import { CatalogPicker } from '../../catalog/CatalogPicker.tsx'
import { getPartTypeInfo } from '../partCategories.ts'
import {
  formatOtherSpecs,
  getCapacityBytes,
  getPartStatus,
  linkCatalog,
  renameDraft,
  setCapacityBytes,
  unlinkCatalog,
} from '../partDraft.ts'
import type { PartDraft } from '../types.ts'
import { PartStatusBadge } from './PartStatusBadge.tsx'

type Props = {
  draft: PartDraft
  // 같은 종류의 몇 번째 항목인지와 전체 개수 (여러 항목일 때 라벨에 쓴다)
  index: number
  count: number
  onChange: (draft: PartDraft) => void
  onRemove?: () => void
}

export function PartRow({ draft, index, count, onChange, onRemove }: Props) {
  const nameId = useId()
  const quantityId = useId()
  const capacityId = useId()
  const [pickerOpen, setPickerOpen] = useState(false)
  const info = getPartTypeInfo(draft.type)
  const status = getPartStatus(draft)
  const label = count > 1 ? `${info.label} ${index + 1}` : info.label
  const capacityBytes = getCapacityBytes(draft)
  const otherSpecs = formatOtherSpecs(draft)

  function changeCapacity(value: string) {
    if (info.capacityUnit === null) return

    const amount = Number(value)
    // 비우거나 0 이하이면 '미확인'(null)으로 둔다. 0으로 단정하지 않는다.
    const bytes =
      value === '' || !(amount > 0) ? null : Math.round(amount * info.capacityUnit.bytes)

    onChange(setCapacityBytes(draft, bytes))
  }

  return (
    <div className={`part-row part-row--${status}`}>
      <div className="part-row__fields">
        <label htmlFor={nameId} className="part-row__label">
          {label}
        </label>
        <input
          id={nameId}
          value={draft.displayName}
          maxLength={255}
          placeholder="모델명 직접 입력"
          onChange={(event) => onChange(renameDraft(draft, event.target.value))}
        />
        {info.multiple && (
          <span className="part-row__unit">
            <label htmlFor={quantityId} className="muted">
              수량
            </label>
            <input
              id={quantityId}
              type="number"
              min={1}
              max={64}
              step={1}
              inputMode="numeric"
              value={Number.isNaN(draft.quantity) ? '' : draft.quantity}
              onChange={(event) =>
                onChange({
                  ...draft,
                  quantity: event.target.value === '' ? NaN : Number(event.target.value),
                  edited: true,
                })
              }
            />
          </span>
        )}
        {info.capacityUnit !== null && (
          <span className="part-row__unit">
            <label htmlFor={capacityId} className="muted">
              1개당
            </label>
            <input
              id={capacityId}
              type="number"
              min={0}
              step="any"
              value={
                capacityBytes === null
                  ? ''
                  : Number((capacityBytes / info.capacityUnit.bytes).toFixed(2))
              }
              placeholder="미확인"
              onChange={(event) => changeCapacity(event.target.value)}
            />
            <span className="muted">{info.capacityUnit.label}</span>
          </span>
        )}
        <PartStatusBadge status={status} />
        <button
          type="button"
          className="button button--ghost"
          onClick={() => setPickerOpen((open) => !open)}
          aria-expanded={pickerOpen}
        >
          카탈로그에서 선택
        </button>
        {draft.matchStatus === 'MATCHED' && (
          <button
            type="button"
            className="button button--ghost"
            onClick={() => onChange(unlinkCatalog(draft))}
          >
            연결 해제
          </button>
        )}
        {onRemove && (
          <button
            type="button"
            className="button button--ghost button--danger"
            onClick={onRemove}
          >
            제거
          </button>
        )}
      </div>

      {(draft.rawName !== null || otherSpecs.length > 0) && (
        <p className="part-row__hint muted">
          {draft.rawName !== null && <>검출 원문: {draft.rawName}</>}
          {draft.source === 'AUTO' && draft.edited && ' (사용자가 보완함)'}
          {draft.rawName !== null && otherSpecs.length > 0 && ' · '}
          {otherSpecs.join(', ')}
        </p>
      )}

      {pickerOpen && (
        <CatalogPicker
          type={draft.type}
          initialQuery={draft.displayName}
          onClose={() => setPickerOpen(false)}
          onSelect={(product) => {
            onChange(linkCatalog(draft, product))
            setPickerOpen(false)
          }}
        />
      )}
    </div>
  )
}
