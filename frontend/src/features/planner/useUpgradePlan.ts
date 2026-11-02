import { useEffect, useState } from 'react'
import { catalogClient } from '../catalog/catalogClient.ts'
import type { CatalogProduct } from '../catalog/catalogTypes.ts'
import type { PartInput } from '../pc-scan/types.ts'
import type { UpgradeGoal } from './plannerGoal.ts'
import {
  buildUpgradePlan, planTargets, UPGRADE_PART_KINDS,
  type KindTarget, type PricedProduct, type RecommendPriority, type UpgradePartKind, type UpgradePlan,
} from './upgradeRecommendation.ts'

type Products = Partial<Record<UpgradePartKind, PricedProduct[]>>
export type ProductLoad = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; products: Products }

export type UpgradePlanState = {
  load: ProductLoad
  targets: Partial<Record<UpgradePartKind, KindTarget>>
  // 실제 교체 대상. 사용자가 고르지 않았으면 진단에서 부족한 부품 전부.
  selected: UpgradePartKind[]
  plan: UpgradePlan | null
}

// 화면 전환 사이에 같은 목록을 다시 받지 않는다. 새로고침하면 다시 받는다.
let cache: Products | null = null

// 팀 중앙 가격 서버(Worker)의 자료만 쓴다. 로컬 DB에만 있는 판매가는 쓰지 않는다. 참고가는 중앙 서버에서만 온다.
function toPriced(product: CatalogProduct): PricedProduct | null {
  const current = product.priceStatus?.origin === 'SHARED' ? product.currentPrice : null
  const estimate = product.referenceEstimate
  const price = current ?? estimate
  if (!price || !Number.isSafeInteger(price.amountKrw) || price.amountKrw <= 0) return null
  return {
    id: product.id, name: `${product.manufacturer} ${product.modelName}`, priceKrw: price.amountKrw,
    priceKind: current ? 'CURRENT' : 'ESTIMATE',
    priceSource: current?.sourceName ?? null, priceUrl: current?.sourceUrl ?? null,
  }
}

async function loadKind(kind: UpgradePartKind, signal: AbortSignal): Promise<PricedProduct[]> {
  const products: PricedProduct[] = []
  // 카탈로그가 커져도 5페이지(500개)까지만 본다.
  for (let page = 0; page < 5; page += 1) {
    const result = await catalogClient.search(kind, '', { page, size: 100, signal })
    for (const item of result.items) {
      const priced = toPriced(item)
      if (priced && !products.some((known) => known.name === priced.name)) products.push(priced)
    }
    if (page + 1 >= result.totalPages) break
  }
  return products
}

function useProducts(enabled: boolean): ProductLoad {
  const [load, setLoad] = useState<ProductLoad>(() => cache ? { status: 'ready', products: cache } : { status: 'loading' })
  useEffect(() => {
    if (cache || !enabled) return
    const controller = new AbortController()
    Promise.all(UPGRADE_PART_KINDS.map((kind) => loadKind(kind, controller.signal)))
      .then((lists) => {
        cache = Object.fromEntries(UPGRADE_PART_KINDS.map((kind, index) => [kind, lists[index]]))
        setLoad({ status: 'ready', products: cache })
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setLoad({ status: 'error', message: error instanceof Error ? error.message : '알 수 없는 오류' })
      })
    return () => controller.abort()
  }, [enabled])
  return load
}

/** 추천 플랜(4단계)과 호환성 검사(5단계)가 같은 플랜을 쓰도록 한 곳에서 계산한다. */
export function useUpgradePlan({ parts, goal, priority, kinds, excludedIds, enabled }: {
  parts: PartInput[]
  goal: UpgradeGoal
  priority: RecommendPriority
  kinds: UpgradePartKind[] | null
  excludedIds: string[]
  enabled: boolean
}): UpgradePlanState {
  const load = useProducts(enabled)
  const targets = planTargets(parts, goal)
  const selected = kinds ?? UPGRADE_PART_KINDS.filter((kind) => targets[kind]?.needed === true)
  const plan = load.status === 'ready' ? buildUpgradePlan({ parts, productsByKind: load.products, kinds: selected, targets,
    priority, budgetWon: goal.budgetWon, cpuMetric: goal.purpose === 'GAME' ? 'GAME' : 'WORK', excludedIds }) : null
  return { load, targets, selected, plan }
}
