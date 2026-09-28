import { useId, useState } from 'react'
import { CatalogPicker } from '../../catalog/CatalogPicker.tsx'
import { getPartTypeInfo } from '../partCategories.ts'
import {
  formatOtherSpecs,
  getCapacityBytes,
  getPartStatus,
  isKnownCapacityValid,
  renameDraft,
  setCapacityInput,
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
  const invalidCapacity = !isKnownCapacityValid(draft.specs.capacityBytes)
  const otherSpecs = formatOtherSpecs(draft)

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
                draft.capacityText ?? (capacityBytes === null || !Number.isFinite(capacityBytes)
                  ? ''
                  : capacityBytes / info.capacityUnit.bytes)
              }
              placeholder="미확인"
              aria-invalid={invalidCapacity || undefined}
              aria-describedby={invalidCapacity ? `${capacityId}-error` : undefined}
              onChange={(event) => onChange(setCapacityInput(
                draft,
                event.target.value,
                event.target.validity.badInput,
              ))}
            />
            <span className="muted">{info.capacityUnit.label}</span>
            {invalidCapacity && (
              <span id={`${capacityId}-error`} className="error">
                0보다 큰 유효한 용량을 입력해 주세요. 모르면 비워 주세요.
              </span>
            )}
          </span>
        )}
        <PartStatusBadge status={status} />
        <button
          type="button"
          className="button button--ghost"
          onClick={() => setPickerOpen((open) => !open)}
          aria-expanded={pickerOpen}
        >
          예시 모델명 찾기
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
            // 예시 데이터의 ID를 실제 제품 연결로 저장하지 않고 모델명만 입력한다.
            onChange(renameDraft(draft, product.name))
            setPickerOpen(false)
          }}
        />
      )}
    </div>
  )
}
