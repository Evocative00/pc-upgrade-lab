import { PART_STATUS_LABEL, type PartStatus } from '../partDraft.ts'

export function PartStatusBadge({ status }: { status: PartStatus }) {
  return (
    <span className={`badge badge--${status}`}>{PART_STATUS_LABEL[status]}</span>
  )
}

// 범례는 짧은 이름만 칩으로 보이고, 설명은 마우스를 올리거나 화면 낭독기로 읽는다.
const LEGEND: [PartStatus, string][] = [['linked', '카탈로그 연결'], ['model', '모델 확인'], ['auto', '자동 인식'], ['manual', '직접 입력']]
const LEGEND_NOTE = '카탈로그 연결은 호환성 확인과 별개입니다. 미연결 부품도 입력한 이름과 검출 원문을 그대로 저장합니다.'

export function PartStatusLegend() {
  return (
    <p className="legend" title={LEGEND_NOTE}>
      {LEGEND.map(([status, label]) => (
        <span key={status} className={`legend__chip legend__chip--${status}`}>{label}</span>
      ))}
      <span className="visually-hidden">{LEGEND_NOTE}</span>
    </p>
  )
}
