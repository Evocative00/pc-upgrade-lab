import { useEffect, useId, useRef, useState } from 'react'
import { catalogModelClient } from './catalogModelClient.ts'
import { readLinkedCatalogModel } from './catalogLinkedModelRead.ts'
import type { LinkedModelProduct } from './catalogLinkedModelRead.ts'
import { VERIFICATION_LABEL } from './catalogPresentation.ts'
import type { CatalogModel, CatalogModelDetail } from './catalogTypes.ts'

type Props = { product: LinkedModelProduct; onSelectModel?: (model: CatalogModel) => void }
type ViewState = { phase: 'idle' | 'loading' } | { phase: 'error'; message: string } |
  { phase: 'ready'; detail: CatalogModelDetail }

export function CatalogLinkedModel(props: Props) {
  if (!props.product.modelId) return null
  return <LinkedModelView key={`${props.product.id}:${props.product.type}:${props.product.modelId}`} {...props} />
}

function LinkedModelView({ product, onSelectModel }: Props) {
  const detailId = useId()
  const [view, setView] = useState<ViewState>({ phase: 'idle' })
  const controllerRef = useRef<AbortController | null>(null)
  useEffect(() => () => controllerRef.current?.abort(), [])

  async function load() {
    controllerRef.current?.abort()
    const controller = new AbortController()
    controllerRef.current = controller
    setView({ phase: 'loading' })
    try {
      const detail = await readLinkedCatalogModel(catalogModelClient, product, controller.signal)
      if (!controller.signal.aborted) setView({ phase: 'ready', detail })
    } catch (error) {
      if (!controller.signal.aborted) setView({ phase: 'error',
        message: error instanceof Error ? error.message : '연결된 모델을 조회하지 못했습니다.' })
    }
  }

  function close() {
    controllerRef.current?.abort()
    setView({ phase: 'idle' })
  }

  const detail = view.phase === 'ready' ? view.detail : null
  return <section className="catalog-linked-model" aria-label="연결된 모델 확인">
    <button type="button" className="button button--ghost" aria-expanded={view.phase !== 'idle'}
      aria-controls={detailId} disabled={view.phase === 'loading'} onClick={() => void load()}>
      {view.phase === 'error' ? '연결된 모델 다시 조회' : '연결된 모델 보기'}
    </button>
    {view.phase !== 'idle' && <div id={detailId}>
      {view.phase === 'loading' && <p className="muted" role="status">연결된 모델 조회 중…</p>}
      {view.phase === 'error' && <p className="error" role="alert">{view.message}</p>}
      {detail && <>
        <p><strong>{detail.model.manufacturer} {detail.model.modelName}</strong></p>
        <p className="muted">{VERIFICATION_LABEL[detail.model.verificationStatus]} ·
          {detail.model.kind === 'RAM_SPEC_GROUP' ? ' RAM 규격군' :
            detail.model.kind === 'GPU_CHIP_MODEL' ? ' GPU 칩 모델' : ' 부품 모델'}</p>
        <p className="notice">모델만 확인하면 정확한 판매 상품과 현재 상품가는 연결되지 않습니다.
          {detail.model.kind === 'RAM_SPEC_GROUP' && ' 이 규격군은 개별 RAM 모듈의 부품번호나 판매 키트를 확인한 자료가 아닙니다.'}
          {detail.model.kind === 'GPU_CHIP_MODEL' && ' 이 칩 모델만으로 제조사별 그래픽 카드의 크기·전원·판매 상품을 확정할 수 없습니다.'}</p>
        {detail.sources.length > 0 && <div className="catalog-sources">
          <span className="muted">모델 확인 근거</span>
          {detail.sources.map((source, index) => <a key={`${source.sourceUrl}-${index}`}
            href={source.sourceUrl} target="_blank" rel="noopener noreferrer">근거 {index + 1}</a>)}
        </div>}
        {onSelectModel && <p className="muted">모델만 확인하면 제품 연결과 해당 상품가가 해제됩니다.
          장착 수량·제원·자동 인식 원문은 유지됩니다.</p>}
        {onSelectModel && <button type="button" className="button" onClick={() => onSelectModel(detail.model)}>
          이 모델만 확인
        </button>}
      </>}
      <button type="button" className="button button--ghost" onClick={close}>모델 보기 닫기</button>
    </div>}
  </section>
}
