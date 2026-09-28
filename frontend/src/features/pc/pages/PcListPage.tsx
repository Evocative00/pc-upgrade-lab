import { paths } from '../../../lib/router.ts'
import { pcRepository } from '../pcRepository.ts'
import type { PcSummary } from '../types.ts'
import { usePcQuery } from '../usePcQuery.ts'

function formatDate(iso: string) {
  return new Date(iso).toLocaleString('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  })
}

// 목록 API는 요약(이름·날짜)만 돌려주므로 부품 정보는 상세 화면에서 보여 준다.
function PcCard({ pc }: { pc: PcSummary }) {
  return (
    <li>
      <a className="pc-card" href={paths.detail(pc.id)}>
        <strong className="pc-card__name">{pc.name}</strong>
        <span className="pc-card__meta muted">
          <span>수정 {formatDate(pc.updatedAt)}</span>
          <span>등록 {formatDate(pc.createdAt)}</span>
        </span>
      </a>
    </li>
  )
}

export function PcListPage() {
  const query = usePcQuery(() => pcRepository.list())

  return (
    <>
      <div className="page-head">
        <h1>내 PC</h1>
        <a className="button button--primary" href={paths.new()}>
          새 PC 등록
        </a>
      </div>

      {query.status === 'loading' && <p className="muted">불러오는 중…</p>}
      {query.status === 'error' && (
        <p className="error">목록을 불러오지 못했습니다: {query.message}</p>
      )}
      {query.status === 'ready' &&
        (query.data.items.length === 0 ? (
          <div className="empty">
            <p>아직 저장한 PC가 없습니다.</p>
            <p className="muted">
              새 PC를 등록하고 부품을 직접 입력하거나 자동 인식으로 불러와 보세요.
            </p>
          </div>
        ) : (
          <ul className="pc-list">
            {query.data.items.map((pc) => (
              <PcCard key={pc.id} pc={pc} />
            ))}
          </ul>
        ))}
    </>
  )
}
