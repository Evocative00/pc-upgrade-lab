import { catalogPriceStatusLabel, formatPriceObservedAt } from './catalogPresentation.ts'
import type { CatalogPriceStatus, CurrentPrice } from './catalogTypes.ts'

export function CurrentPriceSource({ price, status }: { price: CurrentPrice | null; status?: CatalogPriceStatus | null }) {
  if (!price && !status) return null
  return <span className={`catalog-price-source ${status?.lookupStatus === 'UNAVAILABLE' ? 'error' : 'muted'}`}>
    <span>{catalogPriceStatusLabel(status).replace('합계 제외', '현재가 합계 제외')}</span>
    {price && <>
      {' · '}<a href={price.sourceUrl} target="_blank" rel="noopener noreferrer">{price.sourceName}</a>
      {' · 관측 '}<time dateTime={price.observedAt}>{formatPriceObservedAt(price.observedAt)}</time>
    </>}
    {status?.lookupStatus === 'UNAVAILABLE' && status.lastSuccessAt && <>
      {' · 마지막 성공 조회 '}<time dateTime={status.lastSuccessAt}>{formatPriceObservedAt(status.lastSuccessAt)}</time>
    </>}
    {!price && status?.checkedAt && status.lookupStatus !== 'UNAVAILABLE' && <>
      {' · 조회 '}<time dateTime={status.checkedAt}>{formatPriceObservedAt(status.checkedAt)}</time>
    </>}
  </span>
}
