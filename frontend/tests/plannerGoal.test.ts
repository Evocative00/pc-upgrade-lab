import assert from 'node:assert/strict'
import test from 'node:test'
import {
  budgetFromSlider, BUDGET_SLIDER_STEPS, changePurpose, defaultGoal, goalIsReady, goalSummary, isUpgradeGoal,
  MAX_BUDGET_WON, MIN_BUDGET_WON, sliderFromBudget, toggleChoice, withGoalDefaults,
} from '../src/features/planner/plannerGoal.ts'

test('사용 목적에 맞는 선택지를 고르기 전에는 진단으로 진행하지 않는다', () => {
  const empty = defaultGoal()
  assert.equal(goalIsReady(empty), false)
  const game = changePurpose(empty, 'GAME')
  assert.equal(goalIsReady(game), false)
  assert.equal(goalIsReady({ ...game, games: ['발로란트'] }), true)
  const creation = changePurpose(game, 'CREATION')
  assert.deepEqual(creation.games, [])
  assert.equal(goalIsReady(creation), false)
  assert.equal(goalIsReady({ ...creation, programs: ['Blender'] }), true)
})

test('게임과 프로그램은 최대 세 개이며 같은 항목은 선택 해제한다', () => {
  const values = ['게임 A', '게임 B', '게임 C']
  assert.deepEqual(toggleChoice(values, '게임 D'), values)
  assert.deepEqual(toggleChoice(values, '게임 B'), ['게임 A', '게임 C'])
  assert.deepEqual(toggleChoice(['게임 A'], '게임 a'), [])
  assert.deepEqual(toggleChoice([], ' '.repeat(3)), [])
})

test('예산은 범위 밖이면 진단을 시작하지 않고 목적 요약에 가격 예측을 추가하지 않는다', () => {
  const game = { ...changePurpose(defaultGoal(), 'GAME'), games: ['발로란트'] }
  assert.equal(goalIsReady({ ...game, budgetWon: 0 }), false)
  assert.equal(goalIsReady({ ...game, budgetWon: 3_000_000 }), true)
  assert.equal(goalIsReady({ ...game, budgetWon: 5_000_000 }), true)
  assert.equal(goalIsReady({ ...game, budgetWon: 5_100_000 }), false)
  assert.equal(isUpgradeGoal({ ...game, budgetWon: 5_000_000 }), true)
  for (const invalid of ['5000000', [5_000_000], 5_000_000.5]) {
    assert.equal(isUpgradeGoal({ ...game, budgetWon: invalid }), false)
  }
  assert.match(goalSummary(game), /FHD · 60fps 목표/)
  assert.doesNotMatch(goalSummary(game), /예상|원/)
})

test('예전에 저장된 목표에 편집 해상도가 없으면 4K로 채우고, 잘못된 값은 거부한다', () => {
  const saved = { ...defaultGoal() } as Record<string, unknown>
  delete saved.videoResolution
  assert.equal(isUpgradeGoal(saved), true)
  assert.equal(withGoalDefaults(saved as never).videoResolution, '4K')
  assert.equal(isUpgradeGoal({ ...defaultGoal(), videoResolution: '16K' }), false)
})

test('예산 슬라이더는 100만 원까지 1만 원, 그 위로 10만 원 단위이고 양 끝이 예산 범위와 같다', () => {
  assert.equal(budgetFromSlider(0), MIN_BUDGET_WON)
  assert.equal(budgetFromSlider(BUDGET_SLIDER_STEPS), MAX_BUDGET_WON)
  assert.equal(budgetFromSlider(1), 110_000)
  assert.equal(budgetFromSlider(sliderFromBudget(1_000_000) + 1), 1_100_000)
  assert.equal(budgetFromSlider(-5), MIN_BUDGET_WON)
  assert.equal(budgetFromSlider(BUDGET_SLIDER_STEPS + 5), MAX_BUDGET_WON)
  for (const budget of [100_000, 370_000, 1_000_000, 1_800_000, 3_000_000, 5_000_000]) {
    assert.equal(budgetFromSlider(sliderFromBudget(budget)), budget)
  }
  // 직접 입력한 1만 원 단위 금액은 가장 가까운 칸에 놓인다.
  assert.equal(budgetFromSlider(sliderFromBudget(1_230_000)), 1_200_000)
})
