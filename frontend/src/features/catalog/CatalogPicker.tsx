import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { isString } from '../../lib/pageState.ts'
import { usePageState } from '../../lib/usePageState.ts'
import { isExpandedId, isSearchMode, isSearchRequest } from './catalogSearchState.ts'
import type { PartType } from '../pc-scan/types.ts'
import { catalogClient } from './catalogClient.ts'
import { CatalogModelPicker } from './CatalogModelPicker.tsx'
import { CatalogSearchHelp } from './CatalogSearchHelp.tsx'
import { CatalogAttributions, CatalogProductDetail } from './CatalogProductDetail.tsx'
import { formatProjectPrice } from './catalogPresentation.ts'
import type { CatalogDetail, CatalogModel, CatalogPage, CatalogProduct } from './catalogTypes.ts'

type Props = {
  stateKey: string
  type: PartType
  initialQuery: string
  onSelect: (product: CatalogProduct) => void
  onSelectModel?: (model: CatalogModel) => void
  onClose: () => void
}

type SearchState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'done'; result: CatalogPage; keyword: string }
  | { status: 'error'; message: string }

type DetailState =
  | { status: 'idle' }
  | { status: 'loading'; product: CatalogProduct }
  | { status: 'done'; product: CatalogProduct; result: CatalogDetail }
  | { status: 'error'; product: CatalogProduct; message: string }

export function CatalogPicker({ stateKey, type, initialQuery, onSelect, onSelectModel, onClose }: Props) {
  const inputId = useId()
  const [query, setQuery] = usePageState(`${stateKey}query`, initialQuery.slice(0, 100), isString)
  const [mode, setMode] = usePageState(`${stateKey}mode`, 'product', isSearchMode)
  const [request, setRequest] = usePageState(`${stateKey}request`, null, isSearchRequest)
  const [expandedId, setExpandedId] = usePageState(`${stateKey}expanded`, null, isExpandedId)
  const [search, setSearch] = useState<SearchState>(() => ({ status: request ? 'loading' : 'idle' }))
  const [detail, setDetail] = useState<DetailState>({ status: 'idle' })
  const [detailAttempt, setDetailAttempt] = useState(0)
  const searchController = useRef<AbortController | null>(null)
  const detailController = useRef<AbortController | null>(null)
  const modelSelection = ['CPU', 'GPU', 'MOTHERBOARD', 'RAM', 'STORAGE'].includes(type) ? onSelectModel : undefined

  useEffect(() => () => {
    searchController.current?.abort()
    detailController.current?.abort()
  }, [])

  function clearDetail() {
    detailController.current?.abort()
    setDetail({ status: 'idle' })
    setExpandedId(null)
  }

  function runSearch(page = 0, keyword = query.trim()) {
    searchController.current?.abort()
    clearDetail()
    setSearch({ status: 'loading' })
    setRequest({ page, keyword })
  }

  useEffect(() => {
    if (request === null || mode !== 'product') return
    const controller = new AbortController()
    searchController.current = controller
    catalogClient.search(type, request.keyword, { page: request.page, signal: controller.signal }).then((result) => {
      if (!controller.signal.aborted) setSearch({ status: 'done', result, keyword: request.keyword })
    }, (error: unknown) => {
      if (!controller.signal.aborted) setSearch({
        status: 'error',
        message: error instanceof Error ? error.message : '부품 검색 중 오류가 발생했습니다.',
      })
    })
    return () => controller.abort()
  }, [request, mode, type])

  function showDetail(product: CatalogProduct) {
    detailController.current?.abort()
    setExpandedId(product.id)
    setDetailAttempt((attempt) => attempt + 1)
    setDetail({ status: 'loading', product })
  }

  useEffect(() => {
    if (search.status !== 'done' || expandedId === null || mode !== 'product') return
    const product = search.result.items.find((item) => item.id === expandedId)
    if (!product) return
    const controller = new AbortController()
    detailController.current = controller
    catalogClient.get(product.id, controller.signal).then((result) => {
      if (result.product.type !== type) {
        if (!controller.signal.aborted) setDetail({ status: 'error', product, message: '현재 입력 항목과 부품 종류가 다릅니다.' })
        return
      }
      if (!controller.signal.aborted) setDetail({ status: 'done', product, result })
    }, (error: unknown) => {
      if (!controller.signal.aborted) setDetail({ status: 'error', product,
        message: error instanceof Error ? error.message : '상세 정보를 불러오지 못했습니다.' })
    })
    return () => controller.abort()
  }, [expandedId, detailAttempt, search, mode, type])

  function changeQuery(value: string) {
    searchController.current?.abort()
    clearDetail()
    setQuery(value)
    setRequest(null)
    setSearch({ status: 'idle' })
  }

  function showModels() {
    searchController.current?.abort()
    clearDetail()
    setSearch({ status: 'idle' })
    setMode('model')
  }

  // 부품 입력 폼 안에 있으므로 Enter가 폼 전체를 제출하지 않게 막는다.
  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.nativeEvent.isComposing) return
    if (event.key === 'Enter') {
      event.preventDefault()
      void runSearch()
    } else if (event.key === 'Escape') {
      event.preventDefault()
      onClose()
    }
  }

  return (
    <div className="catalog-picker" aria-label="부품 카탈로그 검색">
      {modelSelection && <div className="catalog-picker__bar" aria-label="확인 범위">
        <button type="button" className="button" aria-pressed={mode === 'product'} onClick={() => {
          if (mode === 'product') return
          setSearch({ status: request ? 'loading' : 'idle' })
          setMode('product')
        }}>제품 연결</button>
        <button type="button" className="button" aria-pressed={mode === 'model'} onClick={showModels}>모델만 확인</button>
      </div>}
      {mode === 'model' && modelSelection ? <CatalogModelPicker stateKey={stateKey} type={type} initialQuery={query}
        onSelect={modelSelection} onClose={onClose} /> : <>
      <div className="catalog-picker__bar">
        <label htmlFor={inputId} className="visually-hidden">
          부품 검색어
        </label>
        <input
          id={inputId}
          type="search"
          value={query}
          maxLength={100}
          placeholder="제조사·모델명·부품번호"
          onChange={(event) => changeQuery(event.target.value)}
          onKeyDown={handleKeyDown}
          autoFocus
        />
        <button type="button" className="button" onClick={() => void runSearch()}>
          검색
        </button>
        <button type="button" className="button button--ghost" onClick={() => {
          setQuery('')
          void runSearch(0, '')
        }}>전체 보기</button>
        <button type="button" className="button button--ghost" onClick={onClose}>
          닫기
        </button>
      </div>

      <p className="muted">
        등록된 부품을 조회합니다. 제품을 연결한 뒤 구성 화면에서 규격과 장착·전원 조건을 확인할 수 있습니다.
      </p>

      {search.status === 'idle' && <p className="muted">검색어를 입력하거나 ‘전체 보기’로 이 종류의 등록 부품을 확인해 주세요.</p>}
      {search.status === 'loading' && <p role="status" className="muted">검색 중…</p>}
      {search.status === 'error' && (
        <p role="alert" className="error">검색 실패: {search.message}</p>
      )}
      {search.status === 'done' && <>
        <p role="status" className="muted">{search.keyword ? `‘${search.keyword}’ 검색 결과` : '등록 부품'} {search.result.totalElements}개</p>
        {search.result.items.length === 0 ? (
          <>
            <p className="muted">일치하는 제품이 없습니다. 현재 입력한 내용은 유지되며 직접 입력해 저장할 수 있습니다.</p>
            <CatalogSearchHelp type={type} mode="product" onShowModels={modelSelection ? showModels : undefined} />
          </>
        ) : <>
          <ul className="catalog-picker__results">
            {search.result.items.map((product) => {
              // 상세 정보는 목록 맨 아래가 아니라 누른 부품 바로 아래에 펼친다. 다시 누르면 접는다.
              const expanded = expandedId === product.id
              return (
                <li key={product.id}>
                  <button type="button" onClick={() => expanded ? clearDetail() : void showDetail(product)}
                    aria-expanded={expanded}
                    aria-label={`${product.modelName} 상세 ${expanded ? '접기' : '보기'}`}>
                    <span><span className="muted">{product.manufacturer}</span> {product.modelName}</span>
                    <span className="catalog-picker__meta muted">
                      {formatProjectPrice(product)}
                      {expanded ? ' · 상세 접기' : ' · 상세 보기'}
                    </span>
                  </button>
                  {expanded && <div className="catalog-picker__inline-detail">
                    {(detail.status === 'idle' || detail.status === 'loading') && <p role="status" className="muted">{product.modelName} 상세 조회 중…</p>}
                    {detail.status === 'error' && <div role="alert">
                      <p className="error">{detail.message}</p>
                      <button type="button" className="button" onClick={() => void showDetail(detail.product)}>상세 다시 조회</button>
                    </div>}
                    {detail.status === 'done' && <CatalogProductDetail detail={detail.result} onSelect={onSelect}
                      onSelectModel={modelSelection} onRefresh={() => void showDetail(detail.product)} />}
                  </div>}
                </li>
              )
            })}
          </ul>
        </>}
        {search.result.totalPages > 1 && <div className="catalog-pagination" aria-label="부품 검색 페이지">
          <button type="button" className="button" disabled={search.result.page === 0}
            onClick={() => void runSearch(search.result.page - 1, search.keyword)}>이전</button>
          <span>{search.result.page + 1} / {search.result.totalPages} 페이지</span>
          <button type="button" className="button" disabled={search.result.page + 1 >= search.result.totalPages}
            onClick={() => void runSearch(search.result.page + 1, search.keyword)}>다음</button>
        </div>}
      </>}
      {(detail.status === 'done' || search.status === 'done') && (
        <CatalogAttributions items={detail.status === 'done' ? detail.result.attributions
          : search.status === 'done' ? search.result.attributions : []} />
      )}
      </>}
    </div>
  )
}
