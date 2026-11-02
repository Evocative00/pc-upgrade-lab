import { useEffect, useState } from 'react'
import { catalogClient } from '../catalog/catalogClient.ts'
import type { PartInput } from '../pc-scan/types.ts'
import type { UpgradeGoal } from './plannerGoal.ts'
import {
  buildUpgradePlan, planTargets, UPGRADE_PART_KINDS,
  type KindTarget, type PricedProduct, type RecommendPriority, type UpgradePartKind, type UpgradePlan,
} from './upgradeRecommendation.ts'
import { loadUpgradeProducts } from './recommendationProducts.ts'

type Products = Partial<Record<UpgradePartKind, PricedProduct[]>>
export type ProductLoad = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; products: Products }

export type UpgradePlanState = {
  load: ProductLoad
  targets: Partial<Record<UpgradePartKind, KindTarget>>
  // 실제 교체 대상. 사용자가 고르지 않았으면 진단에서 부족한 부품 전부.
  selected: UpgradePartKind[]
  plan: UpgradePlan | null
  retry: () => void
}

function useProducts(enabled: boolean): { load: ProductLoad; retry: () => void } {
  const [request, setRequest] = useState({ enabled, attempt: 0 })
  // 조건 화면을 다녀오거나 재조회하면 지난 가격으로 새 검사를 시작하지 않는다.
  if (request.enabled !== enabled) setRequest({ enabled, attempt: request.attempt + 1 })
  const [load, setLoad] = useState<{ attempt: number; value: ProductLoad }>({ attempt: -1, value: { status: 'loading' } })
  useEffect(() => {
    if (!enabled) return
    const controller = new AbortController()
    loadUpgradeProducts(catalogClient, controller.signal)
      .then((products) => {
        if (!controller.signal.aborted) setLoad({ attempt: request.attempt, value: { status: 'ready', products } })
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setLoad({ attempt: request.attempt, value: { status: 'error', message: error instanceof Error ? error.message : '알 수 없는 오류' } })
      })
    return () => controller.abort()
  }, [enabled, request.attempt])
  return { load: load.attempt === request.attempt ? load.value : { status: 'loading' },
    retry: () => setRequest((value) => ({ ...value, attempt: value.attempt + 1 })) }
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
  const { load, retry } = useProducts(enabled)
  const targets = planTargets(parts, goal)
  const selected = kinds ?? UPGRADE_PART_KINDS.filter((kind) => targets[kind]?.needed === true)
  const plan = load.status === 'ready' ? buildUpgradePlan({ parts, productsByKind: load.products, kinds: selected, targets,
    priority, budgetWon: goal.budgetWon, cpuMetric: goal.purpose === 'GAME' ? 'GAME' : 'WORK', excludedIds }) : null
  return { load, targets, selected, plan, retry }
}
