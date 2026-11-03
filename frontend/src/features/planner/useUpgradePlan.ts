import { useEffect, useState } from 'react'
import { catalogClient } from '../catalog/catalogClient.ts'
import type { PartInput } from '../pc-scan/types.ts'
import type { UpgradeGoal } from './plannerGoal.ts'
import {
  applyOverrides, buildUpgradeOptions, planTargets, UPGRADE_PART_KINDS,
  type KindTarget, type PlanOption, type PlanOverrides, type PricedProduct, type UpgradeOptions, type UpgradePartKind,
  type UpgradePlan,
} from './upgradeRecommendation.ts'
import { loadUpgradeProducts } from './recommendationProducts.ts'

type Products = Partial<Record<UpgradePartKind, PricedProduct[]>>
export type ProductLoad = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; products: Products }

export type UpgradePlanState = {
  load: ProductLoad
  targets: Partial<Record<UpgradePartKind, KindTarget>>
  // 교체를 검토하는 부품. 진단에서 부족한 부품이고, 없으면 CPU·그래픽카드.
  selected: UpgradePartKind[]
  options: UpgradeOptions | null
  // 고른 안에 직접 바꾼 부품까지 반영한 최종 플랜(5단계 호환성 검사에도 쓴다).
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
export function useUpgradePlan({ parts, goal, option, overrides, excludedIds, enabled }: {
  parts: PartInput[]
  goal: UpgradeGoal
  option: PlanOption
  overrides: PlanOverrides
  excludedIds: string[]
  enabled: boolean
}): UpgradePlanState {
  const { load, retry } = useProducts(enabled)
  const targets = planTargets(parts, goal)
  const needed = UPGRADE_PART_KINDS.filter((kind) => targets[kind]?.needed === true)
  // 부족한 부품이 없으면 '더 빠르게' 안을 보여 주기 위해 CPU·그래픽카드를 검토한다(메모리 용량은 체감 향상이 작다).
  const selected: UpgradePartKind[] = needed.length ? needed : ['CPU', 'GPU']
  const options = load.status === 'ready' ? buildUpgradeOptions({ parts, productsByKind: load.products, kinds: selected, targets,
    budgetWon: goal.budgetWon, cpuMetric: goal.purpose === 'GAME' ? 'GAME' : 'WORK', excludedIds }) : null
  const plan = options ? applyOverrides(options.plans[option], overrides, options) : null
  return { load, targets, selected, options, plan, retry }
}
