import type { ReferenceEstimate } from './catalogTypes.ts'
import { referencePriceLabel } from './catalogPresentation.ts'

export function ReferencePriceSource({ price, detailed = false }: { price: ReferenceEstimate | null | undefined; detailed?: boolean }) {
  if (!price) return null
  return <div className="price-source">
    <p>중앙 · {referencePriceLabel(price)} · {price.sourceDate ? `가격 기준 ${price.sourceDate}`
      : price.confidence === 'ESTIMATED' ? '유사 부품 가격으로 추정' : '가격 기준일 미확인'}
      {price.saleUnit === 'RAM_KIT' ? ` · ${price.moduleCount}개 판매 묶음 기준` : ' · 상품 1개 기준'}</p>
    {detailed && <>
      <p className="muted">{price.notes}</p>
      {price.rangeLowKrw !== null && price.rangeHighKrw !== null &&
        <p>참고 범위 {price.rangeLowKrw.toLocaleString('ko-KR')}~{price.rangeHighKrw.toLocaleString('ko-KR')}원</p>}
      <ul>{price.sourceQuotes.map((quote, index) => <li key={`${quote.sourceUrl}:${index}`}>
        <a href={quote.sourceUrl} target="_blank" rel="noopener noreferrer">{quote.modelName}</a>
        {' · '}{quote.amountKrw.toLocaleString('ko-KR')}원{quote.sourceDate && ` · ${quote.sourceDate}`}
        {quote.saleUnit === 'RAM_KIT' ? ` · ${quote.moduleCount}개 묶음` : quote.moduleCount === 1 ? ' · 모듈 1개' : ' · 상품 1개'}
      </li>)}</ul>
    </>}
  </div>
}
