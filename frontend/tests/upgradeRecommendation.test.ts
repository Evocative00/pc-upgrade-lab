import assert from 'node:assert/strict'
import test from 'node:test'
import type { PartInput, PartType } from '../src/features/pc-scan/types.ts'
import { defaultGoal, type UpgradeGoal } from '../src/features/planner/plannerGoal.ts'
import {
  applyPlan, buildUpgradePlan, cpuSocket, currentSocket, gameTargetScore, planTargets, withReplacedPart,
  type KindTarget, type PlanInput, type PricedProduct,
} from '../src/features/planner/upgradeRecommendation.ts'

const GIB = 1024 ** 3
function part(type: PartType, displayName: string, specs: PartInput['specs'] = {}, quantity = 1): PartInput {
  return { type, displayName, rawName: null, specs, quantity, source: 'MANUAL',
    catalogProductId: null, matchStatus: 'UNMATCHED' }
}
function product(name: string, priceKrw: number, priceKind: PricedProduct['priceKind'] = 'CURRENT'): PricedProduct {
  return { id: name, name, priceKrw, priceKind, priceSource: 'TEST', priceUrl: null }
}

const pc = [part('CPU', 'AMD Ryzen 5 5600'), part('MOTHERBOARD', 'MSI B550M PRO-VDH'),
  part('RAM', 'DDR4-3200 8GB', { capacityBytes: 8 * GIB }, 2), part('GPU', 'GeForce GTX 1660 SUPER'),
  part('STORAGE', 'Samsung 980 1TB')]
const products: PlanInput['productsByKind'] = {
  GPU: [product('Sapphire PULSE Radeon RX 7600 8GB', 489_400), product('MSI GeForce RTX 5060 8G VENTUS 2X OC', 668_860),
    product('Sapphire PULSE Radeon RX 7700 XT 12GB', 726_440)],
  CPU: [product('AMD Ryzen 7 5800X3D', 582_330), product('AMD Ryzen 9 3900X', 400_000), product('AMD Ryzen 7 9800X3D', 720_000)],
  RAM: [product('Samsung DDR4-3200 32GB (2x16GB)', 120_000), product('G.SKILL DDR5-6000 32GB (2x16GB)', 150_000)],
}
const need = (minScore: number | null, needed: boolean | null = true): KindTarget => ({ minScore, minVramGiB: null, needed })
function plan(overrides: Partial<PlanInput>) {
  return buildUpgradePlan({ parts: pc, productsByKind: products, kinds: ['CPU', 'GPU', 'RAM'],
    targets: { GPU: need(200), CPU: need(60), RAM: need(32) }, priority: 'BUDGET', budgetWon: 2_000_000, cpuMetric: 'GAME',
    ...overrides })
}

test('CPU 이름과 메인보드 칩셋으로 소켓을 추정한다', () => {
  assert.equal(cpuSocket('AMD Ryzen 5 5600'), 'AM4')
  assert.equal(cpuSocket('AMD Ryzen 7 9800X3D'), 'AM5')
  assert.equal(cpuSocket('Intel Core i5-14400F'), 'LGA1700')
  assert.equal(cpuSocket('Intel Core i7-10700K'), 'LGA1200')
  assert.equal(currentSocket([part('CPU', 'Core i5-12400'), part('MOTHERBOARD', 'ASUS PRIME B760M-A')]), 'LGA1700')
  assert.equal(currentSocket(pc), 'AM4')
})

test('예산 위주는 부족한 부품을 모두 채우는 가장 싼 조합을 고른다', () => {
  const result = plan({})
  const names = Object.fromEntries(result.items.map((item) => [item.kind, item.choice?.name]))
  assert.equal(names.GPU, 'Sapphire PULSE Radeon RX 7600 8GB')
  // 3900X는 게임에서 5600보다 느려 후보가 아니고, 소켓이 다른 9800X3D도 빠진다.
  assert.equal(names.CPU, 'AMD Ryzen 7 5800X3D')
  assert.equal(names.RAM, 'Samsung DDR4-3200 32GB (2x16GB)')
  assert.equal(result.totalKrw, 489_400 + 582_330 + 120_000)
  assert.ok(result.items.every((item) => item.meets === true))
})

test('성능 우선은 같은 예산 안에서 더 큰 향상을 고른다', () => {
  const result = plan({ priority: 'PERFORMANCE' })
  assert.equal(result.items.find((item) => item.kind === 'GPU')?.choice?.name, 'Sapphire PULSE Radeon RX 7700 XT 12GB')
  assert.ok(result.totalKrw <= 2_000_000)
})

test('예산이 모자라면 채울 수 있는 부품만 바꾸고 나머지는 유지하며 전체 금액을 알려 준다', () => {
  const result = plan({ budgetWon: 700_000 })
  const changed = result.items.filter((item) => item.choice)
  assert.ok(changed.length >= 1 && changed.length < 3)
  assert.ok(result.totalKrw <= 700_000)
  assert.equal(result.fullFixKrw, 489_400 + 582_330 + 120_000)
  assert.ok(result.items.some((item) => !item.choice && item.note === '예산 부족으로 유지'))
})

test('부족하지 않은 부품은 예산 위주에서 바꾸지 않는다', () => {
  const result = plan({ targets: { GPU: need(200), CPU: need(40, false), RAM: need(16, false) } })
  assert.deepEqual(result.items.filter((item) => item.choice).map((item) => item.kind), ['GPU'])
  assert.equal(result.items.find((item) => item.kind === 'CPU')?.note, null)
})

test('판매가 제품이 있으면 더 빠른 참고 추정가 제품보다 먼저 고른다', () => {
  const result = plan({ kinds: ['GPU'], priority: 'PERFORMANCE', budgetWon: 800_000, productsByKind: { GPU: [
    ...products.GPU ?? [], product('MSI GeForce RTX 3080 VENTUS 3X PLUS 10G OC LHR', 709_000, 'ESTIMATE')] } })
  assert.equal(result.items[0].choice?.name, 'Sapphire PULSE Radeon RX 7700 XT 12GB')
})

test('최종 구성은 교체 부품만 바꾸고 나머지 부품과 순서를 유지한다', () => {
  const result = plan({})
  const final = applyPlan(pc, result)
  assert.deepEqual(final.map((item) => item.type), ['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'STORAGE'])
  assert.equal(final[1].displayName, 'MSI B550M PRO-VDH')
  assert.equal(final[4].displayName, 'Samsung 980 1TB')
  assert.equal(final[2].displayName, 'Samsung DDR4-3200 32GB (2x16GB)')
  assert.equal(final[2].quantity, 1)
})

test('RAM 여러 개는 키트 하나로 바뀐다', () => {
  const after = withReplacedPart([...pc, part('RAM', 'DDR4-3200 8GB', { capacityBytes: 8 * GIB })], 'RAM', 'Kit 32GB (2x16GB)')
  assert.equal(after.filter((item) => item.type === 'RAM').length, 1)
})

test('게임 목표는 권장 사양과 목표 FPS로 부족한 부품을 정한다', () => {
  const goal: UpgradeGoal = { ...defaultGoal(), purpose: 'GAME', games: ['사이버펑크 2077'], resolution: 'FHD', targetFps: 60 }
  const targets = planTargets(pc, goal)
  assert.equal(targets.GPU?.needed, true)
  assert.equal(targets.RAM?.needed, false)
  const low = gameTargetScore('GPU', pc, ['사이버펑크 2077'], 'FHD', 30)
  const high = gameTargetScore('GPU', pc, ['사이버펑크 2077'], 'FHD', 144)
  assert.ok(low !== null && high !== null && high > low)
})

test('작업 목표는 프로그램의 RAM·VRAM 기준으로 부족한 부품을 정한다', () => {
  const goal: UpgradeGoal = { ...defaultGoal(), purpose: 'CREATION', programs: ['Premiere Pro'], videoResolution: '8K' }
  const targets = planTargets(pc, goal)
  assert.equal(targets.RAM?.needed, true)
  assert.equal(targets.RAM?.minScore, 64)
})

test('호환성 검사에서 뺀 제품은 다음 후보로 바뀐다', () => {
  const first = plan({ kinds: ['GPU'] })
  const firstId = first.items[0].choice?.id
  assert.ok(firstId)
  const next = plan({ kinds: ['GPU'], excludedIds: [firstId] })
  assert.ok(next.items[0].choice && next.items[0].choice.id !== firstId)
})

test('교체 부품은 카탈로그 상품으로 연결되어 호환성 검사에 쓰인다', () => {
  const final = applyPlan(pc, plan({ kinds: ['GPU'] }))
  const gpu = final.find((item) => item.type === 'GPU')
  assert.equal(gpu?.matchStatus, 'MATCHED')
  assert.equal(gpu?.catalogProductId, 'Sapphire PULSE Radeon RX 7600 8GB')
  assert.equal(final.find((item) => item.type === 'CPU')?.catalogProductId, null)
})

test('3단계 진단에서 권장 사양 부족인 부품은 추천 플랜에서도 바꿀 대상이 된다', () => {
  // 게임 점수로는 충분하지만 공식 권장 예시(Blender 점수) 비교에서는 CPU 부족으로 나오는 구성
  const parts = [part('CPU', 'AMD Ryzen 5 7600'), part('GPU', 'GeForce RTX 4070')]
  const goal: UpgradeGoal = { ...defaultGoal(), purpose: 'GAME', games: ['사이버펑크 2077'], resolution: 'FHD', targetFps: 60 }
  const targets = planTargets(parts, goal)
  assert.equal(targets.CPU?.needed, true)
  const result = buildUpgradePlan({ parts, targets, kinds: ['CPU'], priority: 'BUDGET', budgetWon: 800_000, cpuMetric: 'GAME',
    productsByKind: { CPU: [product('AMD Ryzen 7 7800X3D', 690_180), product('AMD Ryzen 5 5600', 150_000)] } })
  assert.equal(result.items[0].choice?.name, 'AMD Ryzen 7 7800X3D')
})

test('바꿀 부품을 모두 끄면 선택하라고 안내한다', () => {
  assert.equal(plan({ kinds: [] }).reason, '바꿀 부품을 골라 주세요.')
})
