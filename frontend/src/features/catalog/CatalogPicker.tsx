import { useId, useState, type KeyboardEvent } from 'react'
import type { PartType } from '../pc-scan/types.ts'
import { catalogClient, type CatalogProduct } from './catalogClient.ts'

type Props = {
  type: PartType
  initialQuery: string
  onSelect: (product: CatalogProduct) => void
  onClose: () => void
}

type SearchState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'done'; products: CatalogProduct[] }
  | { status: 'error'; message: string }

export function CatalogPicker({ type, initialQuery, onSelect, onClose }: Props) {
  const inputId = useId()
  const [query, setQuery] = useState(initialQuery)
  const [search, setSearch] = useState<SearchState>({ status: 'idle' })

  async function runSearch() {
    setSearch({ status: 'loading' })

    try {
      const products = await catalogClient.search(type, query)
      setSearch({ status: 'done', products })
    } catch (error) {
      setSearch({
        status: 'error',
        message: error instanceof Error ? error.message : '알 수 없는 오류',
      })
    }
  }

  // 부품 입력 폼 안에 있으므로 Enter가 폼 전체를 제출하지 않게 막는다.
  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter') {
      event.preventDefault()
      void runSearch()
    } else if (event.key === 'Escape') {
      onClose()
    }
  }

  return (
    <div className="catalog-picker">
      <div className="catalog-picker__bar">
        <label htmlFor={inputId} className="visually-hidden">
          카탈로그 검색어
        </label>
        <input
          id={inputId}
          type="search"
          value={query}
          placeholder="제조사나 모델명으로 검색 (예시 카탈로그)"
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={handleKeyDown}
          autoFocus
        />
        <button type="button" className="button" onClick={runSearch}>
          검색
        </button>
        <button type="button" className="button button--ghost" onClick={onClose}>
          닫기
        </button>
      </div>

      {search.status === 'loading' && <p className="muted">검색 중…</p>}
      {search.status === 'error' && (
        <p className="error">검색 실패: {search.message}</p>
      )}
      {search.status === 'done' &&
        (search.products.length === 0 ? (
          <p className="muted">
            일치하는 제품이 없습니다. 입력한 모델명은 미연결 상태로 저장됩니다.
          </p>
        ) : (
          <ul className="catalog-picker__results">
            {search.products.map((product) => (
              <li key={product.id}>
                <button type="button" onClick={() => onSelect(product)}>
                  <span className="muted">{product.manufacturer}</span>{' '}
                  {product.name}
                </button>
              </li>
            ))}
          </ul>
        ))}
    </div>
  )
}
