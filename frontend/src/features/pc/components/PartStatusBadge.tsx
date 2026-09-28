import { PART_STATUS_LABEL, type PartStatus } from '../partDraft.ts'

export function PartStatusBadge({ status }: { status: PartStatus }) {
  return (
    <span className={`badge badge--${status}`}>{PART_STATUS_LABEL[status]}</span>
  )
}

export function PartStatusLegend() {
  const statuses: PartStatus[] = ['linked', 'auto', 'manual']

  return (
    <p className="legend">
      {statuses.map((status) => (
        <PartStatusBadge key={status} status={status} />
      ))}
      <span className="muted">
        미연결 부품도 입력한 이름과 검출 원문을 그대로 저장합니다.
      </span>
    </p>
  )
}
