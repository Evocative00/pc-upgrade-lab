import assert from 'node:assert/strict'
import test from 'node:test'
import type { PartInput, PartType } from '../src/features/pc-scan/types.ts'
import { defaultGoal, type UpgradeGoal } from '../src/features/planner/plannerGoal.ts'
import { buildCompatibilityRequest } from '../src/features/builder/compatibility.ts'
import { diagnoseGame } from '../src/features/planner/gameDiagnosis.ts'
import { GAME_REQUIREMENTS } from '../src/features/planner/gameRequirements.ts'
import {
  applyOverrides, applyPlan, buildUpgradeOptions, buildUpgradePlan, cpuSocket, KEEP, currentSocket, gameTargetScore, planTargets, withReplacedPart,
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
  RAM: [{ ...product('Samsung DDR4-3200 32GB (2x16GB)', 120_000),
    specification: { moduleCount: 2, moduleCapacityBytes: 16 * GIB, memoryType: 'DDR4' } },
    { ...product('G.SKILL DDR5-6000 32GB (2x16GB)', 150_000),
      specification: { moduleCount: 2, moduleCapacityBytes: 16 * GIB, memoryType: 'DDR5' } }],
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
  assert.ok(result.items.some((item) => !item.choice && item.note === '예산 부족'))
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
  assert.equal(final[2].quantity, 2)
  assert.equal(final[2].specs.capacityBytes, 16 * GIB)
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

const optionInput = () => ({ parts: pc, productsByKind: products, kinds: ['CPU', 'GPU', 'RAM'] as ('CPU' | 'GPU' | 'RAM')[],
  targets: { GPU: need(200), CPU: need(60), RAM: need(32) }, budgetWon: 2_000_000, cpuMetric: 'GAME' as const })

test('추천 안 3개: 가성비는 가격 대비 성능이 가장 좋고, 추천·최고 성능 순으로 빨라진다', () => {
  const { plans } = buildUpgradeOptions(optionInput())
  const gain = (option: 'VALUE' | 'BALANCED' | 'BEST') => plans[option].items
    .reduce((sum, item) => sum + Math.log(Math.min(item.choice?.ratio ?? 1, item.kind === 'RAM' ? 1.25 : Infinity)), 0)
  const perWon = (option: 'VALUE' | 'BALANCED' | 'BEST') => gain(option) / plans[option].totalKrw
  assert.ok(perWon('VALUE') >= perWon('BALANCED') - 1e-12 && perWon('VALUE') >= perWon('BEST') - 1e-12)
  assert.ok(gain('BEST') >= gain('BALANCED') && gain('BALANCED') >= gain('VALUE'))
  for (const option of ['VALUE', 'BALANCED'] as const) assert.ok(plans[option].totalKrw <= 2_000_000)
  for (const option of ['VALUE', 'BALANCED', 'BEST'] as const) assert.equal(plans[option].reason, '부족한 부품을 모두 해결합니다.')
})

test('안에서 부품을 다른 후보로 바꾸거나 유지하면 총액과 해결 여부를 다시 계산한다', () => {
  const options = buildUpgradeOptions(optionInput())
  const base = options.plans.VALUE
  const baseGpu = base.items.find((item) => item.kind === 'GPU')?.choice
  const other = options.candidates.GPU?.find((product) => product.id !== baseGpu?.id)
  assert.ok(baseGpu && other)
  const swapped = applyOverrides(base, { GPU: other.id }, options)
  assert.equal(swapped.custom, true)
  assert.equal(swapped.items.find((item) => item.kind === 'GPU')?.choice?.id, other.id)
  assert.equal(swapped.totalKrw, base.totalKrw - baseGpu.priceKrw + other.priceKrw)
  const kept = applyOverrides(base, { CPU: KEEP }, options)
  assert.equal(kept.items.find((item) => item.kind === 'CPU')?.choice, null)
  assert.equal(kept.reason, '부족한 부품 3개 중 2개 해결')
})

test('추천 RAM은 판매 키트 가격 한 번과 실제 모듈 2개·개당 용량으로 검사한다', () => {
  const result = plan({ kinds: ['RAM'] })
  const final = applyPlan(pc, result)
  const ram = final.find((item) => item.type === 'RAM')!
  assert.equal(result.totalKrw, 120_000)
  assert.equal(ram.quantity, 2)
  assert.equal(ram.specs.capacityBytes, 16 * GIB)
  const request = buildCompatibilityRequest(final.map((item, index) => ({ ...item, key: String(index), edited: false, persisted: false })))!
  assert.deepEqual(request.ram, [{ catalogProductId: ram.catalogProductId, quantity: 2 }])
})

test('이름만 32GB인 RAM은 모듈 개수·용량을 확정하거나 추천하지 않는다', () => {
  const result = plan({ kinds: ['RAM'], productsByKind: { RAM: [product('DDR4 32GB (2x16GB)', 50_000)] } })
  assert.equal(result.items[0].choice, null)
})

test('후보가 30개를 넘어도 저렴한 목표 충족 제품을 보존한다', () => {
  const result = plan({ kinds: ['RAM'], parts: [part('RAM', 'DDR4', { capacityBytes: GIB })],
    targets: { RAM: need(4) }, productsByKind: { RAM: Array.from({ length: 35 }, (_, index) => ({
      ...product(`DDR4 ${index + 4}GB`, (index + 1) * 10_000),
      specification: { moduleCount: 1, moduleCapacityBytes: (index + 4) * GIB, memoryType: 'DDR4' },
    })) } })
  assert.equal(result.totalKrw, 10_000)
})

test('작업 CPU 코어 부족은 후보의 확인된 코어 수로 해결 여부를 판정한다', () => {
  const parts = [part('CPU', 'Ryzen 5 5600', { cores: 4 }), ...pc.filter((item) => item.type !== 'CPU')]
  const targets = planTargets(parts, { ...defaultGoal(), purpose: 'CREATION', programs: ['Blender'] })
  const input = { kinds: ['CPU'] as const, parts, targets, cpuMetric: 'WORK' as const }
  const under = { ...product('Ryzen 9 5950X', 500_000), specification: { coreCount: 4 } }
  const unresolved = plan({ ...input, kinds: [...input.kinds], productsByKind: { CPU: [under] } })
  assert.equal(unresolved.items[0].choice, null)
  assert.equal(unresolved.items[0].note, '목표를 채우는 제품 없음')
  const result = plan({ ...input, kinds: [...input.kinds], productsByKind: { CPU: [{ ...under, specification: { coreCount: 16 } }] } })
  assert.equal(result.items[0].meets, true)
  assert.equal(applyPlan(parts, result).find((item) => item.type === 'CPU')?.specs.cores, 16)
})

test('성능 차이가 10% 미만이어도 VRAM 부족을 해결하는 제품을 남긴다', () => {
  const result = plan({ kinds: ['GPU'], parts: [part('GPU', 'GeForce GTX 1060 3GB', { vramBytes: 3 * GIB })],
    targets: { GPU: { minScore: 100, minVramGiB: 6, needed: true } }, productsByKind: { GPU: [
      { ...product('GeForce GTX 1060 6GB', 100_000), specification: { vramBytes: 6 * GIB } },
    ] } })
  assert.ok(result.items[0].choice)
  assert.equal(result.items[0].meets, true)
})

test('확인되지 않은 목표는 충분·해결로 표시하지 않는다', () => {
  const result = plan({ kinds: ['CPU'], productsByKind: {}, targets: {} })
  assert.doesNotMatch(result.reason, /이미 목표를 충족|모두 해결/)
})

test('추천 CPU의 기준 충족 표시는 교체 후 공식 사양 진단과 모순되지 않는다', () => {
  const goal = { ...defaultGoal(), purpose: 'GAME' as const, games: ['사이버펑크 2077'] }
  const parts = [part('CPU', 'Ryzen 5 7600'), part('GPU', 'GeForce RTX 4070')]
  const result = plan({ parts, kinds: ['CPU'], targets: planTargets(parts, goal),
    productsByKind: { CPU: [product('Ryzen 7 7800X3D', 500_000)] } })
  const after = diagnoseGame(applyPlan(parts, result), GAME_REQUIREMENTS['사이버펑크 2077'])
  if (result.items[0].meets === true) assert.equal(after.recommended.checks.find((check) => check.label === 'CPU')?.verdict, 'PASS')
})

test('소켓 미확인·모바일 CPU는 임의의 데스크톱 CPU 추천으로 확정하지 않는다', () => {
  assert.equal(cpuSocket('AMD Ryzen 7 7840HS'), null)
  assert.equal(cpuSocket('Intel Core Ultra 7 258V'), null)
  assert.equal(cpuSocket('Ryzen Threadripper 3970X'), null)
  assert.equal(plan({ parts: [part('CPU', '알 수 없는 CPU')], kinds: ['CPU'] }).items[0].choice, null)
})

test('현재 CPU 점수가 미확인이어도 성능 우선은 후보 중 높은 점수를 고른다', () => {
  const result = plan({ priority: 'PERFORMANCE', budgetWon: 700_000, kinds: ['CPU'], cpuMetric: 'WORK',
    parts: [part('CPU', '수동 입력 CPU', { cores: 4 }), part('MOTHERBOARD', 'B550')],
    targets: { CPU: { ...need(null), minCores: 8 } }, productsByKind: { CPU: [
      { ...product('Ryzen 7 5800X3D', 300_000), specification: { coreCount: 8 } },
      { ...product('Ryzen 9 5950X', 500_000), specification: { coreCount: 16 } },
    ] } })
  assert.equal(result.items[0].choice?.name, 'Ryzen 9 5950X')
  assert.equal(result.items[0].choice?.ratio, null)
})

test('최고 성능 안은 예산의 1.5배까지 넘어도 가장 빠른 조합을 고르고, 가성비·추천은 예산 안에 둔다', () => {
  const { plans, bestWithinBudget } = buildUpgradeOptions({ ...optionInput(), kinds: ['GPU'], targets: { GPU: need(200) }, budgetWon: 700_000 })
  assert.ok(plans.VALUE.totalKrw <= 700_000 && plans.BALANCED.totalKrw <= 700_000)
  assert.ok(plans.BEST.totalKrw > 700_000 && plans.BEST.totalKrw <= 700_000 * 1.5)
  assert.ok((plans.BEST.items[0].choice?.score ?? 0) > (bestWithinBudget.items[0].choice?.score ?? 0))
  assert.ok(bestWithinBudget.totalKrw <= 700_000)
})

test('실제 관측 가격(지난 판매가 포함) 제품이 있으면 계산한 추정가 제품보다 먼저 고른다', () => {
  const past: PricedProduct = { ...product('Sapphire PULSE Radeon RX 7700 XT 12GB', 726_440), priceKind: 'PAST' }
  const estimate: PricedProduct = { ...product('MSI GeForce RTX 3080 VENTUS 3X PLUS 10G OC LHR', 709_000), priceKind: 'ESTIMATE' }
  const { plans } = buildUpgradeOptions({ ...optionInput(), kinds: ['GPU'], targets: { GPU: need(200) }, budgetWon: 800_000,
    productsByKind: { GPU: [past, estimate] } })
  assert.equal(plans.BALANCED.items[0].choice?.priceKind, 'PAST')
})
