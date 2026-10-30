import { useId } from 'react'
import { isBoolean } from '../../../lib/pageState.ts'
import { usePageState } from '../../../lib/usePageState.ts'
import { CatalogPicker } from '../../catalog/CatalogPicker.tsx'
import { getPartTypeInfo } from '../partCategories.ts'
import {
  formatOtherSpecs,
  getCapacityBytes,
  getPartStatus,
  isKnownCapacityValid,
  linkCatalog,
  linkCatalogModel,
  renameDraft,
  setCapacityInput,
  unlinkCatalog,
} from '../partDraft.ts'
import type { PartDraft } from '../types.ts'
import { PartStatusBadge } from './PartStatusBadge.tsx'

type Props = {
  stateKey: string
  draft: PartDraft
  // 같은 종류의 몇 번째 항목인지와 전체 개수 (여러 항목일 때 라벨에 쓴다)
  index: number
  count: number
  onChange: (draft: PartDraft) => void
  onRemove?: () => void
  // 있으면 행 안의 검색 창 대신 구성 화면의 검색 창을 연다.
  onSearch?: () => void
  // 호환성 검사에서 불가 판정을 받은 이유. 있으면 빨간 테두리와 경고를 표시한다.
  warnings?: string[]
}

export function PartRow({ stateKey, draft, index, count, onChange, onRemove, onSearch, warnings = [] }: Props) {
  const nameId = useId()
  const quantityId = useId()
  const capacityId = useId()
  const [pickerOpen, setPickerOpen] = usePageState(`${stateKey}row:${draft.key}:open`, false, isBoolean)
  const info = getPartTypeInfo(draft.type)
  const status = getPartStatus(draft)
  const label = count > 1 ? `${info.label} ${index + 1}` : info.label
  const capacityBytes = getCapacityBytes(draft)
  const invalidCapacity = !isKnownCapacityValid(draft.specs.capacityBytes)
  const otherSpecs = formatOtherSpecs(draft)

  return (
    <div className={`part-row part-row--${status}${warnings.length > 0 ? ' part-row--invalid' : ''}`}>
      <div className="part-row__head">
        <span className="part-row__code" aria-hidden="true">{info.code}</span>
        <label htmlFor={nameId} className="part-row__label">
          {label}
        </label>
        <PartStatusBadge status={status} />
      </div>
      <div className="part-row__fields">
        <input
          id={nameId}
          value={draft.displayName}
          maxLength={255}
          placeholder="모델명 직접 입력"
          onChange={(event) => onChange(renameDraft(draft, event.target.value))}
        />
        {(info.multiple || info.capacityUnit !== null) && <span className="part-row__units">
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
              개당
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
              placeholder="—"
              title="모르면 비워 두세요 (미확인)"
              aria-invalid={invalidCapacity || undefined}
              aria-describedby={invalidCapacity ? `${capacityId}-error` : undefined}
              onChange={(event) => onChange(setCapacityInput(
                draft,
                event.target.value,
                event.target.validity.badInput,
              ))}
            />
            <span className="muted">{info.capacityUnit.label}</span>
          </span>
        )}
        </span>}
        <button
          type="button"
          className="button button--ghost part-row__search"
          onClick={() => onSearch ? onSearch() : setPickerOpen((open) => !open)}
          aria-expanded={onSearch ? undefined : pickerOpen}
        >
          <svg viewBox="0 0 16 16" width="13" height="13" aria-hidden="true">
            <circle cx="7" cy="7" r="4.5" fill="none" stroke="currentColor" strokeWidth="1.6" />
            <path d="M10.5 10.5 14 14" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          </svg>
          검색
        </button>
        {(draft.matchStatus === 'MATCHED' || draft.catalogModelId) && (
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

      {invalidCapacity && (
        <p id={`${capacityId}-error`} className="part-row__hint error">
          0보다 큰 유효한 용량을 입력해 주세요. 모르면 비워 주세요.
        </p>
      )}

      {(draft.rawName !== null || otherSpecs.length > 0) && (
        <p className="part-row__hint muted">
          {draft.rawName !== null && <>검출 원문: {draft.rawName}</>}
          {draft.source === 'AUTO' && draft.edited && ' (사용자가 보완함)'}
          {draft.rawName !== null && otherSpecs.length > 0 && ' · '}
          {otherSpecs.join(', ')}
        </p>
      )}

      {warnings.length > 0 && (
        <ul role="alert" className="part-row__warnings error">
          {warnings.map((message) => <li key={message}>호환성 문제: {message}</li>)}
        </ul>
      )}

      {pickerOpen && (
        <CatalogPicker
          stateKey={`${stateKey}catalog:${draft.key}:`}
          type={draft.type}
          initialQuery={draft.displayName}
          onClose={() => setPickerOpen(false)}
          onSelect={(product) => {
            onChange(linkCatalog(draft, product))
            setPickerOpen(false)
          }}
          onSelectModel={(model) => {
            onChange(linkCatalogModel(draft, model))
            setPickerOpen(false)
          }}
        />
      )}
    </div>
  )
}
