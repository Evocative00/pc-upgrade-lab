import { useEffect, useId, useRef, useState } from 'react'
import type { PartType } from '../pc-scan/types.ts'
import { catalogModelClient } from './catalogModelClient.ts'
import { VERIFICATION_LABEL } from './catalogPresentation.ts'
import type { CatalogModel, CatalogModelPage } from './catalogTypes.ts'

export function CatalogModelPicker({ type, initialQuery, onSelect, onClose }: {
  type: PartType; initialQuery: string; onSelect: (model: CatalogModel) => void; onClose: () => void
}) {
  const inputId = useId()
  const [query, setQuery] = useState(initialQuery.slice(0, 100))
  const [result, setResult] = useState<CatalogModelPage | null>(null)
  const [keyword, setKeyword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const controllerRef = useRef<AbortController | null>(null)
  useEffect(() => () => controllerRef.current?.abort(), [])

  async function search(page = 0, term = query.trim()) {
    controllerRef.current?.abort()
    const controller = new AbortController()
    controllerRef.current = controller
    setLoading(true); setError(null); setResult(null)
    try {
      const response = await catalogModelClient.search(type, term, { page, signal: controller.signal })
      if (!controller.signal.aborted) { setResult(response); setKeyword(term) }
    } catch (failure) {
      if (!controller.signal.aborted) setError(failure instanceof Error ? failure.message : '모델 조회 실패')
    } finally { if (!controller.signal.aborted) setLoading(false) }
  }
  return <section aria-label="부품 모델 확인">
    <p className="notice">모델명이나 RAM 규격군만 확인할 때 사용합니다. 정확한 판매 상품과 현재 상품가는 연결되지 않습니다.</p>
    <div className="catalog-picker__bar">
      <label className="visually-hidden" htmlFor={inputId}>모델 검색어</label>
      <input id={inputId} type="search" maxLength={100} value={query} autoFocus
        onChange={(event) => {
          controllerRef.current?.abort(); setQuery(event.target.value); setResult(null); setError(null); setLoading(false)
        }} onKeyDown={(event) => {
          if (event.nativeEvent.isComposing) return
          if (event.key === 'Enter') { event.preventDefault(); void search() }
          if (event.key === 'Escape') { event.preventDefault(); onClose() }
        }} />
      <button type="button" className="button" onClick={() => void search()}>검색</button>
      <button type="button" className="button button--ghost" onClick={() => { setQuery(''); void search(0, '') }}>전체 보기</button>
      <button type="button" className="button button--ghost" onClick={onClose}>닫기</button>
    </div>
    {loading && <p className="muted" role="status">모델 조회 중…</p>}
    {error && <p className="error" role="alert">{error}</p>}
    {result && <>
      <p className="muted" role="status">확인 모델 {result.totalElements}개</p>
      {result.items.length === 0 && <p className="muted">등록된 모델이 없습니다. 입력한 내용을 직접 저장할 수 있습니다.</p>}
      <ul className="catalog-picker__results">{result.items.map((model) => <li key={model.id}>
        <button type="button" onClick={() => onSelect(model)}>
          <span>{model.manufacturer} {model.modelName}</span>
          <span className="catalog-picker__meta muted">{VERIFICATION_LABEL[model.verificationStatus]} ·
            {model.kind === 'RAM_SPEC_GROUP' ? ' 규격군으로 기록' : ' 모델로 기록'}</span>
        </button>
      </li>)}</ul>
      {result.totalPages > 1 && <div className="catalog-pagination">
        <button type="button" disabled={result.page === 0} onClick={() => void search(result.page - 1, keyword)}>이전</button>
        <span>{result.page + 1} / {result.totalPages}</span>
        <button type="button" disabled={result.page + 1 >= result.totalPages} onClick={() => void search(result.page + 1, keyword)}>다음</button>
      </div>}
    </>}
  </section>
}
