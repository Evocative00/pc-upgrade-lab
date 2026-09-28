import { useId, useState, type FormEvent } from 'react'
import { PcScanPanel } from '../../pc-scan/PcScanPanel.tsx'
import type { ScanResult } from '../../pc-scan/types.ts'
import { PART_TYPES } from '../partCategories.ts'
import {
  createManualDraft,
  getPartStatus,
  partsOfType,
  planScanApply,
  toDraft,
  toPartInputs,
  validatePcRequest,
  withEmptyRows,
} from '../partDraft.ts'
import type { PartDraft, PcRequest } from '../types.ts'
import { PartRow } from './PartRow.tsx'
import { PartStatusLegend } from './PartStatusBadge.tsx'

type Props = {
  initial: PcRequest
  submitLabel: string
  onSubmit: (request: PcRequest) => Promise<void>
  onCancel: () => void
}

export function PcForm({ initial, submitLabel, onSubmit, onCancel }: Props) {
  const nameId = useId()
  const [name, setName] = useState(initial.name)
  const [drafts, setDrafts] = useState(() => withEmptyRows(initial.parts.map(toDraft)))
  const [notice, setNotice] = useState<string | null>(null)
  const [errors, setErrors] = useState<string[]>([])
  const [saving, setSaving] = useState(false)

  function updateDraft(next: PartDraft) {
    setDrafts((current) =>
      current.map((draft) => (draft.key === next.key ? next : draft)),
    )
  }

  function removeDraft(key: string) {
    setDrafts((current) => withEmptyRows(current.filter((draft) => draft.key !== key)))
  }

  function addDraft(type: PartDraft['type']) {
    setDrafts((current) => {
      const index = current.findLastIndex((draft) => draft.type === type)
      const next = [...current]
      next.splice(index + 1, 0, createManualDraft(type))

      return next
    })
  }

  // PcScanPanel의 '입력란에 반영'을 눌렀을 때 호출된다.
  function applyScan(result: ScanResult) {
    const plan = planScanApply(drafts, result)

    if (
      plan.editedReplaced > 0 &&
      !window.confirm(
        `직접 고친 자동 인식 항목 ${plan.editedReplaced}개가 새 결과로 바뀝니다. 계속할까요?`,
      )
    ) {
      return
    }

    setDrafts(plan.next)
    setNotice(
      `자동 인식 부품 ${plan.applied}개를 입력란에 반영했습니다.` +
        (plan.keptManual > 0
          ? ` 직접 입력한 부품 ${plan.keptManual}개는 그대로 두었습니다.`
          : '') +
        ' 내용을 확인하고 빠진 정보를 보완해 주세요.',
    )
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    const request: PcRequest = { name: name.trim(), parts: toPartInputs(drafts) }
    const validationErrors = validatePcRequest(request.name, request.parts)

    setErrors(validationErrors)

    if (validationErrors.length > 0) {
      return
    }

    setSaving(true)

    try {
      await onSubmit(request)
    } catch (submitError) {
      // 저장에 실패해도 입력 내용은 그대로 둔다.
      setErrors([
        submitError instanceof Error
          ? submitError.message
          : '저장 중 알 수 없는 오류가 발생했습니다.',
      ])
      setSaving(false)
    }
  }

  const unlinkedCount = drafts.filter((draft) => {
    const status = getPartStatus(draft)

    return status === 'auto' || status === 'manual'
  }).length

  return (
    <form className="pc-form" onSubmit={handleSubmit} noValidate>
      <div className="field">
        <label htmlFor={nameId}>PC 이름</label>
        <input
          id={nameId}
          value={name}
          maxLength={100}
          placeholder="예: 집 데스크톱"
          onChange={(event) => setName(event.target.value)}
          required
        />
      </div>

      <PcScanPanel onApply={applyScan} />
      {notice && (
        <p role="status" className="notice">
          {notice}
        </p>
      )}

      <section className="panel">
        <div className="panel__head">
          <h2>부품 구성</h2>
          {unlinkedCount > 0 && (
            <span className="muted">카탈로그 미연결 {unlinkedCount}개</span>
          )}
        </div>
        <PartStatusLegend />

        {PART_TYPES.map((info) => {
          const rows = partsOfType(drafts, info.type)

          return (
            <fieldset key={info.type} className="part-group">
              <legend className="visually-hidden">{info.label}</legend>
              {rows.map((draft, index) => (
                <PartRow
                  key={draft.key}
                  draft={draft}
                  index={index}
                  count={rows.length}
                  onChange={updateDraft}
                  // 여러 항목을 쓰는 종류이거나, 자동 인식으로 한 종류에 항목이 여러 개 생긴 경우 제거할 수 있다.
                  onRemove={
                    info.multiple || rows.length > 1
                      ? () => removeDraft(draft.key)
                      : undefined
                  }
                />
              ))}
              {info.multiple && (
                <button
                  type="button"
                  className="button button--ghost"
                  onClick={() => addDraft(info.type)}
                >
                  + {info.label} 추가
                </button>
              )}
            </fieldset>
          )
        })}
      </section>

      {errors.length > 0 && (
        <ul role="alert" className="error">
          {errors.map((message) => (
            <li key={message}>{message}</li>
          ))}
        </ul>
      )}

      <div className="actions">
        <button type="button" className="button button--ghost" onClick={onCancel}>
          취소
        </button>
        <button type="submit" className="button button--primary" disabled={saving}>
          {saving ? '저장 중…' : submitLabel}
        </button>
      </div>
    </form>
  )
}
