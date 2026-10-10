import { useId } from 'react'
import { formatProjectPrice, ramKitLabel, specificationRows, VERIFICATION_LABEL } from './catalogPresentation.ts'
import { CurrentPriceSource } from './CurrentPriceSource.tsx'
import { ReferencePriceSource } from './ReferencePriceSource.tsx'
import { MotherboardStorageDetail } from './MotherboardStorageDetail.tsx'
import { CatalogProductIdentity } from './CatalogProductIdentity.tsx'
import { CatalogLinkedModel } from './CatalogLinkedModel.tsx'
import type { CatalogAttribution, CatalogDetail, CatalogModel, CatalogProduct } from './catalogTypes.ts'

export function CatalogAttributions({ items }: { items: CatalogAttribution[] }) {
  return (
    <div className="catalog-attribution muted">
      {items.map((item) => (
        <p key={`${item.url}-${item.license}`}>
          {item.notice}{' '}
          <a href={item.url} target="_blank" rel="noopener noreferrer">{item.name}</a>
          {' · '}<a href={item.licenseUrl} target="_blank" rel="noopener noreferrer">{item.license}</a>
        </p>
      ))}
    </div>
  )
}

export function CatalogProductDetail({ detail, onSelect, onRefresh, onSelectModel }: {
  detail: CatalogDetail
  onSelect: (product: CatalogProduct) => void
  onRefresh: () => void
  onSelectModel?: (model: CatalogModel) => void
}) {
  const headingId = useId()
  const { product, specification, sources } = detail
  const sourceNames = { BUILDCORES: 'BuildCores 원본', MANUFACTURER: '제조사 자료', MANUAL: '확인 자료' }

  return (
    <section className="catalog-detail" aria-labelledby={headingId}>
      <div>
        <p className="muted">{product.manufacturer} · {VERIFICATION_LABEL[product.verificationStatus]}{!product.active && ' · 검토용'}</p>
        <h3 id={headingId}>{product.modelName}</h3>
        <p className="muted">부품번호: {product.partNumber ?? '미확인'}</p>
        <p>가격: {formatProjectPrice(product)}{(product.currentPrice || product.referenceEstimate) &&
          (product.type === 'RAM' ? ' / 판매 단위' : ' / 1개')}</p>
        {(product.currentPrice || !product.referenceEstimate) && <CurrentPriceSource price={product.currentPrice} status={product.priceStatus} />}
        <ReferencePriceSource price={product.referenceEstimate} detailed />
        <button type="button" className="button button--ghost" onClick={onRefresh}>가격·제원 새로고침</button>
      </div>

      <CatalogProductIdentity product={product} detailed />
      <CatalogLinkedModel key={`${product.id}:${product.modelId ?? ''}`} product={product} onSelectModel={onSelectModel} />

      {product.type === 'RAM' && (
        <p className="notice">
          제품 구성: {ramKitLabel(specification)}. 내 PC의 수량에는 실제 장착한 모듈 수를 입력해 주세요.
        </p>
      )}
      <dl className="catalog-specs">
        {specificationRows(product.type, specification).map(([label, value]) => (
          <div key={label}><dt>{label}</dt><dd>{value}</dd></div>
        ))}
      </dl>
      {product.type === 'CPU' && (
        <p className="muted">TDP·기본 전력(PBP)·최대 터보 전력(MTP)은 서로 다른 공표값이며 실제 사용 전력과 다를 수 있습니다. 최대 부스트는 모든 코어의 동시 동작 클럭을 뜻하지 않습니다.</p>
      )}
      {product.type === 'GPU' && (
        <p className="muted">카드 공표 전력·권장 파워 용량은 실제 사용 전력과 다를 수 있습니다.</p>
      )}
      {product.type === 'STORAGE' && <p className="muted">판매 용량은 십진 GB 기준이며 Windows 표시 용량과 다를 수 있습니다. M.2는 장착 형태입니다. SATA·PCIe 인터페이스와 NVMe 프로토콜을 각각 확인해 주세요.</p>}
      {product.type === 'MOTHERBOARD' && <MotherboardStorageDetail key={product.id} productId={product.id} />}

      <div className="catalog-sources">
        <span className="muted">제원 출처</span>
        {sources.map((source, index) => (
          <a key={`${source.sourceUrl}-${index}`} href={source.sourceUrl} target="_blank" rel="noopener noreferrer">
            {sourceNames[source.sourceName]} {index + 1}
          </a>
        ))}
      </div>
      <p className="muted">연결하면 모델명과 제품 ID가 반영됩니다. 현재 입력한 장착 수량·제원과 자동 인식 원문은 유지됩니다.</p>
      <button type="button" className="button button--primary" onClick={() => onSelect(product)}>이 부품 연결</button>
    </section>
  )
}
