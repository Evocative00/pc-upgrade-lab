import { formatPriceObservedAt } from './catalogPresentation.ts'
import type { CurrentPrice } from './catalogTypes.ts'

export function CurrentPriceSource({ price }: { price: CurrentPrice | null }) {
  if (!price) return null
  return <span className="catalog-price-source muted">
    <a href={price.sourceUrl} target="_blank" rel="noopener noreferrer">{price.sourceName}</a>
    {' · 확인 '}<time dateTime={price.observedAt}>{formatPriceObservedAt(price.observedAt)}</time>
  </span>
}
