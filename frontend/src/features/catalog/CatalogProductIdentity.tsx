import { catalogIdentityDescription, catalogIdentityLabel, catalogRoleLabel } from './catalogPresentation.ts'
import type { CatalogProduct } from './catalogTypes.ts'

export function CatalogProductIdentity({ product, detailed = false }: {
  product: Pick<CatalogProduct, 'identityKind' | 'role'>
  detailed?: boolean
}) {
  const summary = <span className="catalog-picker__meta muted">
    자료: {catalogIdentityLabel(product.identityKind)} · 용도: {catalogRoleLabel(product.role)}
  </span>

  if (!detailed) return summary

  return (
    <div className="catalog-product-identity">
      {summary}
      <p className="muted">{catalogIdentityDescription(product.identityKind)}</p>
      <p className="muted">자료 구분과 용도는 구매 추천이나 최신 가격을 보장하지 않습니다. 이 자료 구분만으로 내 PC 실물의 정확한 구성까지 확인된 것은 아닙니다.</p>
    </div>
  )
}
