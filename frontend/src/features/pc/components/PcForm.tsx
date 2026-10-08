import { useEffect, useId, useLayoutEffect, useRef, useState, type FormEvent } from 'react'
import { registerActiveDraft } from '../../auth/draftBridge.ts'
import { PcBuilder, type PickTarget } from '../../builder/PcBuilder.tsx'
import { slotOfDraft } from '../../builder/buildSlots.ts'
import { incompatibleByDraft } from '../../builder/compatibility.ts'
import { useCompatibility } from '../../builder/useCompatibility.ts'
import { PcScanPanel } from '../../pc-scan/PcScanPanel.tsx'
import type { ScanResult } from '../../pc-scan/types.ts'
import { PART_TYPES } from '../partCategories.ts'
import {
  createManualDraft,
  getPartStatus,
  partsOfType,
  planScanApply,
  toPersistedDraft,
  toPartInputs,
  validatePcRequest,
  withEmptyRows,
} from '../partDraft.ts'
import { PcApiError } from '../pcRepository.ts'
import type { PartDraft, PcRequest } from '../types.ts'
import { PartRow } from './PartRow.tsx'
import { PartStatusLegend } from './PartStatusBadge.tsx'

type Props = {
  initial: PcRequest
  pcId?: number | null
  submitLabel: string
  onSubmit: (request: PcRequest) => Promise<void>
  onCancel: () => void
  // 2D 구성 화면(부품 선택·케이스 그림·요약)을 함께 보여 준다. 카탈로그 검색은 구성 화면의 검색 창으로 통일한다.
  visual?: boolean
}

export function PcForm({ initial, pcId = null, submitLabel, onSubmit, onCancel, visual = false }: Props) {
  const nameId = useId()
  const nameErrorId = useId()
  const nameInputRef = useRef<HTMLInputElement>(null)
  const [name, setName] = useState(initial.name)
  // 같은 회원 안의 이름 중복(409 PC_NAME_DUPLICATE)은 이름 입력란 바로 아래에 안내한다.
  const [nameError, setNameError] = useState<string | null>(null)

  // 저장 중 잠금이 풀린 뒤 이름 입력란으로 이동해 바로 고칠 수 있게 한다.
  useEffect(() => {
    if (nameError !== null) nameInputRef.current?.focus()
  }, [nameError])
  const [drafts, setDrafts] = useState(() => withEmptyRows(initial.parts.map(toPersistedDraft)))
  // 다음 클릭 전에 최신 값을 등록한다. 편집 초안은 기존 PC ID를 유지한다.
  useLayoutEffect(() => registerActiveDraft(() => ({
    request: { name, parts: toPartInputs(drafts) },
    pcId,
  })), [name, drafts, pcId])
  const [notice, setNotice] = useState<string | null>(null)
  const [errors, setErrors] = useState<string[]>([])
  const [saving, setSaving] = useState(false)
  // state가 다시 렌더링되기 전의 연속 클릭도 같은 요청을 두 번 보내지 않게 막는다.
  const savingRef = useRef(false)
  const [pickTarget, setPickTarget] = useState<PickTarget | null>(null)
  // 구성 화면에서만 CPU·메인보드·RAM 호환성을 검사한다. 불가 판정 부품은 행에 경고를 표시한다.
  const compatibility = useCompatibility(drafts, visual)
  const warnings = compatibility.status === 'done'
    ? incompatibleByDraft(drafts, compatibility.result) : new Map<string, string[]>()

  function updateDrafts(update: (current: PartDraft[]) => PartDraft[]) {
    if (!savingRef.current) setDrafts(update)
  }

  function updateDraft(next: PartDraft) {
    if (savingRef.current) return
    setDrafts((current) =>
      current.map((draft) => (draft.key === next.key ? next : draft)),
    )
  }

  function removeDraft(key: string) {
    if (savingRef.current) return
    setDrafts((current) => withEmptyRows(current.filter((draft) => draft.key !== key)))
  }

  function addDraft(type: PartDraft['type']) {
    if (savingRef.current) return
    setDrafts((current) => {
      const index = current.findLastIndex((draft) => draft.type === type)
      const next = [...current]
      next.splice(index + 1, 0, createManualDraft(type))

      return next
    })
  }

  // PcScanPanel의 '입력란에 반영'을 눌렀을 때 호출된다.
  function applyScan(result: ScanResult) {
    if (savingRef.current) return
    const plan = planScanApply(drafts, result)

    if (
      plan.editedReplaced > 0 &&
      !window.confirm(
        `저장했거나 직접 보완한 자동 인식 항목 ${plan.editedReplaced}개가 새 결과로 바뀝니다. 계속할까요?`,
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
    if (savingRef.current) return

    const request: PcRequest = { name: name.trim(), parts: toPartInputs(drafts) }
    const validationErrors = validatePcRequest(request.name, request.parts)

    setErrors(validationErrors)
    setNameError(null)

    if (validationErrors.length > 0) {
      return
    }

    savingRef.current = true
    setSaving(true)

    try {
      await onSubmit(request)
    } catch (submitError) {
      // 저장에 실패해도 입력 내용은 그대로 둔다.
      if (submitError instanceof PcApiError && submitError.code === 'PC_NAME_DUPLICATE') {
        setNameError(submitError.message)
        return
      }
      setErrors([
        submitError instanceof Error
          ? submitError.message
          : '저장 중 알 수 없는 오류가 발생했습니다.',
      ])
    } finally {
      savingRef.current = false
      setSaving(false)
    }
  }

  const unlinkedCount = drafts.filter((draft) => {
    const status = getPartStatus(draft)

    return status === 'auto' || status === 'manual'
  }).length

  // 구성 화면에서는 이 입력란이 케이스 그림 왼쪽(부품 선택 칸 자리)에 들어간다.
  const partsPanel = (
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
                warnings={warnings.get(draft.key)}
                onSearch={visual
                  ? () => setPickTarget({ slot: slotOfDraft(drafts, draft), draftKey: draft.key })
                  : undefined}
                // 여러 항목을 쓰는 종류이거나, 자동 인식으로 한 종류에 항목이 여러 개 생긴 경우 제거할 수 있다.
                // 구성 화면에서는 단일 종류도 입력한 항목을 삭제(빈 줄로 되돌리기)할 수 있다.
                onRemove={
                  info.multiple || rows.length > 1 || (visual && getPartStatus(draft) !== 'empty')
                    ? () => removeDraft(draft.key)
                    : undefined
                }
              />
            ))}
            {/* 구성 화면에서 비어 있는 단일 종류는 오른쪽 선택 창을 연다. */}
            {visual && !info.multiple && rows.every((draft) => getPartStatus(draft) === 'empty') && (
              <button
                type="button"
                className="button button--ghost"
                onClick={() => setPickTarget({ slot: slotOfDraft(drafts, rows[0]), draftKey: rows[0].key })}
              >
                + {info.label} 추가
              </button>
            )}
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
  )

  return (
    <form className="pc-form" onSubmit={handleSubmit} noValidate aria-busy={saving}>
      {/* disabled는 입력·버튼을, inert는 수집기 실행 링크 등 나머지 조작도 잠근다. */}
      <fieldset
        className="pc-form"
        disabled={saving}
        inert={saving}
        style={{ border: 0, padding: 0, margin: 0, minWidth: 0 }}
      >
        <div className="field">
          <label htmlFor={nameId}>PC 이름</label>
          <input
            id={nameId}
            ref={nameInputRef}
            value={name}
            maxLength={100}
            placeholder="예: 집 데스크톱"
            aria-invalid={nameError !== null}
            aria-describedby={nameError !== null ? nameErrorId : undefined}
            onChange={(event) => {
              if (savingRef.current) return
              setName(event.target.value)
              setNameError(null)
            }}
            required
          />
          {nameError !== null && (
            <p id={nameErrorId} role="alert" className="error">
              {nameError}
            </p>
          )}
        </div>

        <PcScanPanel onApply={applyScan} />
        {notice && (
          <p role="status" className="notice">
            {notice}
          </p>
        )}

        {visual ? (
          <PcBuilder drafts={drafts} updateDrafts={updateDrafts} target={pickTarget} onTarget={setPickTarget}
            compatibility={compatibility} warnings={warnings}>
            {partsPanel}
          </PcBuilder>
        ) : partsPanel}

        <div className="actions">
          <button
            type="button"
            className="button button--ghost"
            onClick={() => {
              if (!savingRef.current) onCancel()
            }}
          >
            취소
          </button>
          <button type="submit" className="button button--primary" disabled={saving}>
            {saving ? '저장 중…' : submitLabel}
          </button>
        </div>
      </fieldset>
      {saving && <p role="status">PC 구성을 서버에 저장하고 있습니다.</p>}
      {errors.length > 0 && (
        <ul role="alert" className="error">
          {errors.map((message, index) => (
            <li key={`${index}-${message}`}>{message}</li>
          ))}
        </ul>
      )}
    </form>
  )
}
