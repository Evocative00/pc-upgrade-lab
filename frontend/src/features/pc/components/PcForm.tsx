import { useEffect, useId, useLayoutEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { registerActiveDraft } from '../../auth/draftBridge.ts'
import { PcBuilder, type PickTarget } from '../../builder/PcBuilder.tsx'
import { slotOfDraft } from '../../builder/buildSlots.ts'
import { incompatibleByDraft } from '../../builder/compatibility.ts'
import { useCompatibility } from '../../builder/useCompatibility.ts'
import { PcScanPanel } from '../../pc-scan/PcScanPanel.tsx'
import type { ScanResult } from '../../pc-scan/types.ts'
import { UpgradePlanner } from '../../planner/UpgradePlanner.tsx'
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
  // 구성 화면 머리말(제목·설명). 오른쪽에 초기화·저장 버튼이 붙는다.
  head?: ReactNode
  onReset?: () => void
}

export function PcForm({ initial, pcId = null, submitLabel, onSubmit, onCancel, visual = false, head, onReset }: Props) {
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
  const [plannerOpen, setPlannerOpen] = useState(false)
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

  // PcScanPanel이 사양을 불러오면 자동으로 호출된다.
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
    if (plannerOpen || savingRef.current) return

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

  function openPlanner() {
    if (savingRef.current) return
    // 진단에는 PC 이름이 없어도 되지만 부품의 수량·용량 규칙은 저장할 때와 같다.
    const validationErrors = validatePcRequest('진단용 PC', toPartInputs(drafts))
    setErrors(validationErrors)
    if (validationErrors.length === 0) setPlannerOpen(true)
  }

  const unlinkedCount = drafts.filter((draft) => {
    const status = getPartStatus(draft)

    return status === 'auto' || status === 'manual' || status === 'model'
  }).length

  // 저장 버튼은 구성 화면에서 머리말과 요약 칸 두 곳에 같은 모양으로 둔다.
  const submitButton = (
    <button type="submit" className="button button--primary button--lg" disabled={saving}>
      {saving ? '저장 중…' : submitLabel}
    </button>
  )

  // 구성 화면은 머리말에, 수정 화면은 아래 버튼 줄에 둔다.
  const plannerButton = (
    <button type="button" className={`button button--ghost${visual ? ' button--lg' : ''}`}
      disabled={toPartInputs(drafts).length === 0} onClick={openPlanner}>
      업그레이드 계획 살펴보기
    </button>
  )

  const errorList = errors.length > 0 && (
    <ul role="alert" className="error">
      {errors.map((message, index) => (
        <li key={`${index}-${message}`}>{message}</li>
      ))}
    </ul>
  )

  const filledTypes = PART_TYPES.filter((info) =>
    partsOfType(drafts, info.type).some((draft) => getPartStatus(draft) !== 'empty')).length

  // 구성 화면에서는 이 입력란이 케이스 그림 왼쪽(부품 선택 칸 자리)에 들어간다.
  const partsPanel = (
    <section className="panel">
      <div className="panel__head">
        <h2>부품 구성</h2>
        <span className="panel__count">
          {filledTypes} / {PART_TYPES.length} SET
          {unlinkedCount > 0 && ` · 미연결 ${unlinkedCount}`}
        </span>
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
                // 한 종류에 항목이 여러 개이면 제거할 수 있다. 하나뿐이면 입력한 항목만 지울 수 있다
                // (여러 항목 종류, 또는 구성 화면). 빈 줄 하나를 지우면 다시 생기므로 버튼을 두지 않는다.
                onRemove={
                  rows.length > 1 || (getPartStatus(draft) !== 'empty' && (info.multiple || visual))
                    ? () => removeDraft(draft.key)
                    : undefined
                }
              />
            ))}
            {info.multiple && (
              <div className="part-group__add">
                <button
                  type="button"
                  className="button button--ghost button--add"
                  aria-label={`${info.label} 추가`}
                  onClick={() => addDraft(info.type)}
                >
                  {visual ? '+' : `+ ${info.label} 추가`}
                </button>
                <span className="muted">같은 모델은 수량을 늘리고, 다른 모델은 +로 추가하세요.</span>
              </div>
            )}
          </fieldset>
        )
      })}
    </section>
  )

  return (
    <form className="pc-form" onSubmit={handleSubmit} noValidate aria-busy={saving}>
      {plannerOpen && <UpgradePlanner pcName={name} parts={toPartInputs(drafts)}
        onBackToPc={() => setPlannerOpen(false)} />}
      {/* 검사 패널을 유지해 계획 화면을 다녀와도 진행 중 검사와 미반영 결과를 잃지 않는다. */}
      {/* disabled는 입력·버튼을, inert는 수집기 실행 링크 등 나머지 조작도 잠근다. */}
      <fieldset
        className="pc-form"
        hidden={plannerOpen}
        disabled={saving}
        inert={saving || plannerOpen}
        style={{ border: 0, padding: 0, margin: 0, minWidth: 0, display: plannerOpen ? 'none' : undefined }}
      >
        {visual && (
          <div className="builder-head">
            <div className="builder-head__text">{head}</div>
            <div className="actions">
              {plannerButton}
              {onReset && (
                <button type="button" className="button button--ghost button--lg"
                  onClick={() => { if (!savingRef.current) onReset() }}>
                  초기화
                </button>
              )}
              {submitButton}
            </div>
          </div>
        )}
        {/* 구성 화면은 저장 버튼이 위에 있으므로 오류도 바로 아래에 보인다. */}
        {visual && errorList}

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
            compatibility={compatibility} warnings={warnings}
            submit={<>{submitButton}{errorList}</>}>
            {partsPanel}
          </PcBuilder>
        ) : partsPanel}

        {!visual && <div className="actions">
          {plannerButton}
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
        </div>}
      </fieldset>
      {!plannerOpen && saving && <p role="status">PC 구성을 서버에 저장하고 있습니다.</p>}
      {!plannerOpen && !visual && errorList}
    </form>
  )
}
