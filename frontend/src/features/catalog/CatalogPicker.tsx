import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import type { PartType } from '../pc-scan/types.ts'
import { catalogClient } from './catalogClient.ts'
import { CatalogAttributions, CatalogProductDetail } from './CatalogProductDetail.tsx'
import { formatCurrentPrice, VERIFICATION_LABEL } from './catalogPresentation.ts'
import { CurrentPriceSource } from './CurrentPriceSource.tsx'
import type { CatalogDetail, CatalogPage, CatalogProduct } from './catalogTypes.ts'

type Props = {
  type: PartType
  initialQuery: string
  onSelect: (product: CatalogProduct) => void
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

export function CatalogPicker({ type, initialQuery, onSelect, onClose }: Props) {
  const inputId = useId()
  const [query, setQuery] = useState(initialQuery.slice(0, 100))
  const [search, setSearch] = useState<SearchState>({ status: 'idle' })
  const [detail, setDetail] = useState<DetailState>({ status: 'idle' })
  const searchController = useRef<AbortController | null>(null)
  const detailController = useRef<AbortController | null>(null)

  useEffect(() => () => {
    searchController.current?.abort()
    detailController.current?.abort()
  }, [])

  function clearDetail() {
    detailController.current?.abort()
    setDetail({ status: 'idle' })
  }

  async function runSearch(page = 0, keyword = query.trim()) {
    searchController.current?.abort()
    const controller = new AbortController()
    searchController.current = controller
    clearDetail()
    setSearch({ status: 'loading' })

    try {
      const result = await catalogClient.search(type, keyword, { page, signal: controller.signal })
      if (!controller.signal.aborted) setSearch({ status: 'done', result, keyword })
    } catch (error) {
      if (!controller.signal.aborted) setSearch({
        status: 'error',
        message: error instanceof Error ? error.message : '부품 검색 중 오류가 발생했습니다.',
      })
    }
  }

  async function showDetail(product: CatalogProduct) {
    detailController.current?.abort()
    const controller = new AbortController()
    detailController.current = controller
    setDetail({ status: 'loading', product })
    try {
      const result = await catalogClient.get(product.id, controller.signal)
      if (result.product.type !== type) throw new Error('현재 입력 항목과 부품 종류가 다릅니다.')
      if (!controller.signal.aborted) setDetail({ status: 'done', product, result })
    } catch (error) {
      if (!controller.signal.aborted) setDetail({ status: 'error', product,
        message: error instanceof Error ? error.message : '상세 정보를 불러오지 못했습니다.' })
    }
  }

  function changeQuery(value: string) {
    searchController.current?.abort()
    clearDetail()
    setQuery(value)
    setSearch({ status: 'idle' })
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
        현재 등록된 검토용 부품을 조회합니다. 카탈로그 연결은 제품 선택을 뜻하며, 호환성 판정은 아직 수행하지 않습니다.
      </p>

      {search.status === 'idle' && <p className="muted">검색어를 입력하거나 ‘전체 보기’로 이 종류의 등록 부품을 확인해 주세요.</p>}
      {search.status === 'loading' && <p role="status" className="muted">검색 중…</p>}
      {search.status === 'error' && (
        <p role="alert" className="error">검색 실패: {search.message}</p>
      )}
      {search.status === 'done' && <>
        <p role="status" className="muted">{search.keyword ? `‘${search.keyword}’ 검색 결과` : '등록 부품'} {search.result.totalElements}개</p>
        {search.result.items.length === 0 ? (
          <p className="muted">일치하는 제품이 없습니다. 현재 입력한 내용은 유지되며 직접 입력해 저장할 수 있습니다.</p>
        ) : <>
          <ul className="catalog-picker__results">
            {search.result.items.map((product) => (
              <li key={product.id}>
                <button type="button" onClick={() => void showDetail(product)}
                  aria-expanded={detail.status !== 'idle' && detail.product.id === product.id}
                  aria-label={`${product.modelName} 상세 보기`}>
                  <span><span className="muted">{product.manufacturer}</span> {product.modelName}</span>
                  <span className="catalog-picker__meta muted">
                    {VERIFICATION_LABEL[product.verificationStatus]} · {formatCurrentPrice(product.currentPrice)}
                    {product.currentPrice && (type === 'RAM' ? ' / 판매 묶음' : ' / 1개')} · 상세 보기
                  </span>
                </button>
                <CurrentPriceSource price={product.currentPrice} />
              </li>
            ))}
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
      {detail.status === 'loading' && <p role="status" className="muted">{detail.product.modelName} 상세 조회 중…</p>}
      {detail.status === 'error' && <div role="alert">
        <p className="error">{detail.message}</p>
        <button type="button" className="button" onClick={() => void showDetail(detail.product)}>상세 다시 조회</button>
      </div>}
      {detail.status === 'done' && <CatalogProductDetail detail={detail.result} onSelect={onSelect}
        onRefresh={() => void showDetail(detail.product)} />}
      {(detail.status === 'done' || search.status === 'done') && (
        <CatalogAttributions items={detail.status === 'done' ? detail.result.attributions
          : search.status === 'done' ? search.result.attributions : []} />
      )}
    </div>
  )
}
