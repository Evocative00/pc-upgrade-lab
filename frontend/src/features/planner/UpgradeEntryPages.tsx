import { useState } from 'react'
import { authKey, useAuth } from '../auth/authStore.ts'
import { pcRepository } from '../pc/pcApi.ts'
import type { PcSummary } from '../pc/types.ts'
import { usePcQuery } from '../pc/usePcQuery.ts'
import { navigate, paths } from '../../lib/router.ts'
import { isPageNumber, pcStateKey } from '../../lib/pageState.ts'
import { usePageState } from '../../lib/usePageState.ts'
import { UpgradePlanner } from './UpgradePlanner.tsx'
import './planner.css'

const PAGE_SIZE = 20

function SavedPcList({ page, onPageChange }: { page: number; onPageChange: (page: number) => void }) {
  const [attempt, setAttempt] = useState(0)
  return <SavedPcListResults key={`${page}:${attempt}`} page={page} onPageChange={onPageChange}
    onRetry={() => setAttempt((current) => current + 1)} />
}

function SavedPcListResults({ page, onPageChange, onRetry }: {
  page: number
  onPageChange: (page: number) => void
  onRetry: () => void
}) {
  const query = usePcQuery(() => pcRepository.list(page, PAGE_SIZE))

  return <>
    {query.status === 'loading' && <p className="muted" role="status">저장한 PC를 불러오는 중…</p>}
    {query.status === 'error' && <div className="planner-main">
      <p className="error" role="alert">PC 목록을 불러오지 못했습니다: {query.message}</p>
      <button type="button" className="button button--ghost" onClick={onRetry}>다시 시도</button>
    </div>}
    {query.status === 'ready' && (query.data.items.length === 0 ? <p className="muted">{page === 0 ? '저장한 PC가 아직 없습니다. 새 PC로 시작해 주세요.' : '이 페이지에 PC가 없습니다.'}</p>
      : <ul className="pc-list">{query.data.items.map((pc: PcSummary) => <li key={pc.id}>
        <a className="pc-card" href={paths.upgradePc(pc.id)}>
          <strong className="pc-card__name">{pc.name}</strong>
          <span className="muted">이 PC로 플랜 시작 →</span>
        </a>
      </li>)}</ul>)}
    {(page > 0 || query.status === 'ready' && query.data.totalPages > 1) && <nav className="planner-pager" aria-label="저장한 PC 페이지">
      <button type="button" className="button button--ghost" disabled={page === 0}
        onClick={() => onPageChange(page - 1)}>이전</button>
      <span className="muted" role="status">{page + 1}페이지{query.status === 'ready' && ` · 전체 ${query.data.totalPages}페이지`}</span>
      <button type="button" className="button button--ghost" disabled={query.status !== 'ready' || page + 1 >= query.data.totalPages}
        onClick={() => onPageChange(page + 1)}>다음</button>
    </nav>}
  </>
}

export function UpgradeEntryPage() {
  const auth = useAuth()
  const [page, setPage] = usePageState('upgrade:page', 0, isPageNumber)

  return <div className="planner-entry">
    <header className="page-head"><div><h1>업그레이드 플랜</h1>
      <p className="muted">업그레이드할 PC를 선택해 주세요. 예산과 용도는 다음 화면에서 입력합니다.</p></div></header>
    <div className="planner-entry-options">
      <section className="panel"><h2>새 PC로 시작</h2>
        <p className="muted">부품을 직접 입력하거나 Windows 보조 프로그램으로 불러올 수 있습니다.</p>
        <a className="button button--primary" href={paths.new()}>내 PC 불러오기·입력하기 →</a>
      </section>
      <section className="panel"><h2>저장한 PC로 시작</h2>
        {auth.status === 'loading' && <p className="muted">로그인 상태를 확인하는 중…</p>}
        {auth.status === 'signedOut' && <p className="muted">저장한 PC를 사용하려면 <a href={paths.login()}>로그인</a>해 주세요.</p>}
        {(auth.status === 'signedIn' || auth.status === 'unavailable') &&
          <SavedPcList key={authKey(auth)} page={page} onPageChange={setPage} />}
      </section>
    </div>
    <p className="planner-scope">게임 5종은 공식 최소·권장 사양의 일부 항목을 비교합니다. 추천 제품·가격·예상 FPS 계산은 아직 제공하지 않습니다.</p>
  </div>
}

function SavedPcPlanner({ id, onRetry }: { id: number; onRetry: () => void }) {
  const query = usePcQuery(() => pcRepository.get(id))
  if (query.status === 'loading') return <p className="muted">PC 구성을 불러오는 중…</p>
  if (query.status === 'error') return <div className="planner-main">
    <p className="error" role="alert">PC 구성을 불러오지 못했습니다: {query.message}</p>
    <button type="button" className="button button--ghost" onClick={onRetry}>다시 시도</button>
  </div>
  if (query.data === null) return <div className="empty"><p>PC를 찾을 수 없습니다.</p>
    <a href={paths.upgrade()}>다른 PC 선택하기</a></div>

  return <UpgradePlanner stateKey={pcStateKey(id)} pcName={query.data.name} parts={query.data.parts}
    onBackToPc={() => navigate(paths.edit(id))} />
}

export function UpgradeSavedPcPage({ id }: { id: number }) {
  const [attempt, setAttempt] = useState(0)
  return <>
    <a className="back-link" href={paths.upgrade()}>← 다른 PC 선택</a>
    <SavedPcPlanner key={attempt} id={id} onRetry={() => setAttempt((current) => current + 1)} />
  </>
}
