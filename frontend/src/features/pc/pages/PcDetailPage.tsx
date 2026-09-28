import { paths } from '../../../lib/router.ts'
import { PartStatusBadge, PartStatusLegend } from '../components/PartStatusBadge.tsx'
import { PART_TYPES } from '../partCategories.ts'
import {
  formatCapacity,
  formatOtherSpecs,
  getPartStatus,
  partsOfType,
} from '../partDraft.ts'
import { pcRepository } from '../pcRepository.ts'
import { usePcQuery } from '../usePcQuery.ts'

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
        <a className="button button--primary" href={paths.edit(pc.id)}>
          수정
        </a>
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
