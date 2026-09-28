import { useState } from 'react'
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

const PAGE_SIZE = 20

function PcListResults({
  page,
  onPageChange,
  onRetry,
}: {
  page: number
  onPageChange: (page: number) => void
  onRetry: () => void
}) {
  const query = usePcQuery(() => pcRepository.list(page, PAGE_SIZE))
  const hasNextPage = query.status === 'ready' && page + 1 < query.data.totalPages

  return (
    <>
      {query.status === 'error' && (
        <div>
          <p className="error" role="alert">
            목록을 불러오지 못했습니다: {query.message}
          </p>
          <button type="button" className="button" onClick={onRetry}>
            다시 시도
          </button>
        </div>
      )}
      {query.status === 'ready' &&
        (query.data.items.length === 0 ? (
          <div className="empty">
            <p>{page === 0 ? '아직 저장한 PC가 없습니다.' : '이 페이지에는 PC가 없습니다.'}</p>
            <p className="muted">
              {page === 0
                ? '새 PC를 등록하고 부품을 직접 입력하거나 자동 인식으로 불러와 보세요.'
                : '이전 페이지로 이동해 주세요.'}
            </p>
          </div>
        ) : (
          <ul className="pc-list">
            {query.data.items.map((pc) => (
              <PcCard key={pc.id} pc={pc} />
            ))}
          </ul>
        ))}

      {/* 결과가 비었거나 요청이 실패해도 이전 페이지로 돌아갈 수 있게 유지한다. */}
      <nav className="page-head" aria-label="PC 목록 페이지">
        <button
          type="button"
          className="button button--ghost"
          disabled={page === 0}
          onClick={() => onPageChange(page - 1)}
        >
          이전 페이지
        </button>
        <p className="muted" role="status" aria-live="polite" aria-atomic="true">
          {query.status === 'loading' && `${page + 1}페이지 불러오는 중…`}
          {query.status === 'error' && `${page + 1}페이지를 불러오지 못했습니다.`}
          {query.status === 'ready' &&
            (query.data.totalPages === 0
              ? '저장된 PC 0대'
              : `${page + 1}페이지 · 전체 ${query.data.totalPages}페이지 · PC ${query.data.totalElements}대`)}
        </p>
        <button
          type="button"
          className="button button--ghost"
          disabled={!hasNextPage}
          onClick={() => onPageChange(page + 1)}
        >
          다음 페이지
        </button>
      </nav>
    </>
  )
}

export function PcListPage() {
  const [page, setPage] = useState(0)
  const [attempt, setAttempt] = useState(0)

  return (
    <>
      <div className="page-head">
        <h1>내 PC</h1>
        <a className="button button--primary" href={paths.new()}>
          새 PC 등록
        </a>
      </div>

      {/* usePcQuery는 한 번만 조회하므로 페이지 이동·재시도 때 결과 영역을 새로 연다. */}
      <PcListResults
        key={`${page}:${attempt}`}
        page={page}
        onPageChange={setPage}
        onRetry={() => setAttempt((current) => current + 1)}
      />
    </>
  )
}
