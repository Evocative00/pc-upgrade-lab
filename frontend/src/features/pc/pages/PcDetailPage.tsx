import { useState } from 'react'
import { navigate, paths } from '../../../lib/router.ts'
import { clearDraftFor } from '../../auth/draftBridge.ts'
import { PartStatusBadge, PartStatusLegend } from '../components/PartStatusBadge.tsx'
import { PART_TYPES } from '../partCategories.ts'
import {
  formatCapacity,
  formatOtherSpecs,
  getPartStatus,
  partsOfType,
} from '../partDraft.ts'
import { pcRepository } from '../pcApi.ts'
import { PcApiError } from '../pcRepository.ts'
import { usePcQuery } from '../usePcQuery.ts'

// 계정에 저장된 PC 삭제. 작성 중인 구성 초기화(폼)와 다른 동작임을 문구로 구분한다.
// 재훈 님의 삭제 확인 모달이 준비되면 window.confirm 대신 연결한다.
function DeletePcButton({ id, name }: { id: number; name: string }) {
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleDelete() {
    if (deleting) return
    if (!window.confirm(`계정에 저장된 PC '${name}'을(를) 삭제할까요?
삭제하면 목록과 상세에서 다시 볼 수 없습니다.`)) return

    setDeleting(true)
    setError(null)
    try {
      await pcRepository.delete(id)
      clearDraftFor(id)
      navigate(paths.list())
    } catch (deleteError) {
      if (deleteError instanceof PcApiError && deleteError.status === 404) {
        window.alert('이미 삭제되었거나 찾을 수 없는 PC입니다. 목록을 새로 불러옵니다.')
        navigate(paths.list())
        return
      }
      if (deleteError instanceof PcApiError && deleteError.status === 401) {
        navigate(paths.login())
        return
      }
      setError(deleteError instanceof Error ? deleteError.message : '삭제 중 알 수 없는 오류가 발생했습니다.')
      setDeleting(false)
    }
  }

  return (
    <>
      <button type="button" className="button button--danger" onClick={handleDelete} disabled={deleting}>
        {deleting ? '삭제 중…' : '저장된 PC 삭제'}
      </button>
      {error !== null && <p role="alert" className="error">{error}</p>}
    </>
  )
}

export function PcDetailPage({ id }: { id: number }) {
  const query = usePcQuery(() => pcRepository.get(id))

  if (query.status === 'loading') {
    return <p className="muted">불러오는 중…</p>
  }

  if (query.status === 'error') {
    return <p className="error">PC를 불러오지 못했습니다: {query.message}</p>
  }

  const pc = query.data

  if (pc === null) {
    return (
      <div className="empty">
        <p>PC를 찾을 수 없습니다.</p>
        <a href={paths.list()}>목록으로</a>
      </div>
    )
  }

  return (
    <>
      <div className="page-head">
        <div>
          <a href={paths.list()} className="back-link">
            ← 목록
          </a>
          <h1>{pc.name}</h1>
        </div>
        <div className="actions">
          <DeletePcButton id={pc.id} name={pc.name} />
          <a className="button button--primary" href={paths.edit(pc.id)}>
            수정
          </a>
        </div>
      </div>

      <PartStatusLegend />

      <div className="table-wrap">
        <table className="part-table">
          <thead>
            <tr>
              <th scope="col">부품</th>
              <th scope="col">모델</th>
              <th scope="col">수량</th>
              <th scope="col">상태</th>
            </tr>
          </thead>
          <tbody>
            {PART_TYPES.map((info) => {
              const parts = partsOfType(pc.parts, info.type)

              if (parts.length === 0) {
                return (
                  <tr key={info.type}>
                    <th scope="row">{info.label}</th>
                    <td className="muted">—</td>
                    <td className="muted">—</td>
                    <td>
                      <PartStatusBadge status="empty" />
                    </td>
                  </tr>
                )
              }

              return parts.map((part, index) => {
                const capacity = formatCapacity(part)
                const otherSpecs = formatOtherSpecs(part)

                return (
                  <tr key={`${info.type}-${index}`}>
                    <th scope="row">
                      {parts.length > 1 ? `${info.label} ${index + 1}` : info.label}
                    </th>
                    <td>
                      {part.displayName}
                      {info.capacityUnit !== null && (
                        <span className="muted"> · 1개당 {capacity ?? '용량 미확인'}</span>
                      )}
                      {part.rawName !== null && part.rawName !== part.displayName && (
                        <div className="muted">검출 원문: {part.rawName}</div>
                      )}
                      {otherSpecs.length > 0 && (
                        <div className="muted">{otherSpecs.join(', ')}</div>
                      )}
                    </td>
                    <td>{part.quantity}</td>
                    <td>
                      <PartStatusBadge status={getPartStatus(part)} />
                    </td>
                  </tr>
                )
              })
            })}
          </tbody>
        </table>
      </div>
    </>
  )
}
