import assert from 'node:assert/strict'
import test from 'node:test'
import {
  buildCompatibilityRequest, localCompatibilityChecks, mergeCompatibilityChecks,
  type CompatibilityCheck, type CompatibilityResult,
} from '../src/features/builder/compatibility.ts'
import type { CompatibilityState } from '../src/features/builder/useCompatibility.ts'
import type { PartType } from '../src/features/pc-scan/types.ts'
import type { PartDraft } from '../src/features/pc/types.ts'
import { summarizePlanCompatibility } from '../src/features/planner/planCompatibility.ts'
import type { ScoredProduct, UpgradePlan } from '../src/features/planner/upgradeRecommendation.ts'

const plan: UpgradePlan = {
  option: 'BALANCED', items: [], totalKrw: 0, budgetWon: 800_000, reason: '', fullFixKrw: null, custom: false,
}
const draft = (type: PartType, id: string | null, quantity = 1): PartDraft => ({
  type, key: `${type}-${id}`, displayName: id ?? `미연결 ${type}`, quantity, rawName: null, source: 'MANUAL',
  catalogProductId: id, matchStatus: id ? 'MATCHED' : 'UNMATCHED', specs: {}, edited: false, persisted: false,
})
const check = (code: string, status: CompatibilityCheck['status'], affectedFields: string[], message = code): CompatibilityCheck =>
  ({ code, status, message, affectedFields })
const done = (drafts: PartDraft[], checks: CompatibilityCheck[] = []): CompatibilityState => ({
  status: 'done', result: mergeCompatibilityChecks({ status: 'COMPATIBLE', checks }, localCompatibilityChecks(drafts)),
})

test('서버의 단일 필드와 RAM 인덱스를 최종 구성 순서로 연결하고 가장 나쁜 판정을 표시한다', () => {
  const drafts = [draft('CPU', 'cpu'), draft('STORAGE', 'ssd'), draft('RAM', 'ram-a'), draft('MOTHERBOARD', 'board'),
    draft('RAM', 'ram-b'), draft('GPU', 'gpu'), draft('PSU', 'psu'), draft('CASE', 'case')]
  const replacement: ScoredProduct = {
    id: 'ram-b', name: '교체 RAM', priceKrw: 10_000, priceKind: 'ESTIMATE', priceSource: null, priceUrl: null,
    score: 32, ratio: 2, vramGiB: null, meetsTarget: true,
  }
  const replacedPlan: UpgradePlan = { ...plan, items: [{ kind: 'RAM', currentLabel: '기존 RAM',
    target: { minScore: 32, minVramGiB: null, needed: true }, choice: replacement, meets: true, note: null }] }
  const checks = [
    check('CPU_SOCKET', 'COMPATIBLE', ['cpuProductId', 'motherboardProductId']),
    check('MOTHERBOARD_RAM_TYPE_0', 'COMPATIBLE', ['motherboardProductId', 'ram[0].catalogProductId']),
    check('MOTHERBOARD_RAM_TYPE_1', 'INCOMPATIBLE', ['motherboardProductId', 'ram[1].catalogProductId', 'ram[1].quantity'], 'DDR 불일치'),
    check('MOTHERBOARD_RAM_FORM_1', 'NEEDS_CHECK', ['motherboardProductId', 'ram[1].catalogProductId'], 'DDR 불일치'),
    check('GPU_PSU', 'COMPATIBLE', ['gpuProductId', 'psuProductId']),
    check('GPU_CASE_LENGTH', 'COMPATIBLE', ['gpuProductId', 'caseProductId']),
  ]
  const summary = summarizePlanCompatibility(drafts, replacedPlan, done(drafts, checks))
  assert.deepEqual(summary.byIndex.map((row) => row?.status ?? null),
    ['COMPATIBLE', null, 'COMPATIBLE', 'INCOMPATIBLE', 'INCOMPATIBLE', 'COMPATIBLE', 'COMPATIBLE', 'COMPATIBLE'])
  assert.deepEqual(summary.byIndex[4]?.messages, ['DDR 불일치'])
  assert.deepEqual(summary.badReplacedIds, ['ram-b'])
})

test('중복 행과 복수 수량으로 서버에서 제외한 단일 부품은 모두 확인 필요다', () => {
  for (const type of ['CPU', 'MOTHERBOARD', 'GPU', 'PSU', 'CASE'] as const) {
    for (const drafts of [[draft(type, `${type}-a`), draft(type, `${type}-b`)], [draft(type, `${type}-a`, 2)]]) {
      const summary = summarizePlanCompatibility(drafts, plan, done(drafts))
      assert.equal(summary.result?.status, 'NEEDS_CHECK')
      assert.ok(summary.byIndex.every((row) => row?.status === 'NEEDS_CHECK'), `${type}는 요청 제외를 호환으로 표시하면 안 된다`)
      assert.ok(summary.byIndex.every((row) => row!.messages.length === 1))
      assert.deepEqual(summary.unlinked, [])
    }
  }
})

test('수량 오류 RAM은 로컬 검증에 남기고 전송한 RAM 인덱스만 서버 결과에 연결한다', () => {
  for (const quantity of [0, 1.5, 65, NaN]) {
    const drafts = [draft('RAM', 'invalid', quantity), draft('STORAGE', 'ssd'), draft('RAM', 'valid')]
    assert.deepEqual(buildCompatibilityRequest(drafts)?.ram, [{ catalogProductId: 'valid', quantity: 1 }])
    const summary = summarizePlanCompatibility(drafts, plan, done(drafts, [
      check('RAM_MODULE_FEATURES_0', 'COMPATIBLE', ['ram[0].catalogProductId']),
    ]))
    assert.deepEqual(summary.byIndex.map((row) => row?.status ?? null), ['NEEDS_CHECK', null, 'COMPATIBLE'])
    assert.match(summary.byIndex[0]!.messages[0], /수량/)
    assert.deepEqual(summary.byIndex[2]!.messages, [])
  }
})

test('미연결 RAM도 서버 요청 인덱스에 포함하고 전체 RAM 검사도 각 전송 행에 연결한다', () => {
  const drafts = [draft('RAM', null), draft('RAM', 'linked')]
  assert.deepEqual(buildCompatibilityRequest(drafts)?.ram, [
    { catalogProductId: null, quantity: 1 }, { catalogProductId: 'linked', quantity: 1 },
  ])
  const summary = summarizePlanCompatibility(drafts, plan, done(drafts, [
    check('MOTHERBOARD_RAM_TYPE_1', 'INCOMPATIBLE', ['ram[1].catalogProductId']),
    check('RAM_SLOT_COUNT', 'NEEDS_CHECK', ['ram']),
  ]))
  assert.deepEqual(summary.byIndex.map((row) => row?.status), ['UNCHECKED', 'INCOMPATIBLE'])
  assert.deepEqual(summary.byIndex[1]!.messages, ['MOTHERBOARD_RAM_TYPE_1', 'RAM_SLOT_COUNT'])
  assert.deepEqual(summary.unlinked, ['RAM'])
})

test('64행을 넘어서 서버 검사에서 제외한 RAM을 호환으로 표시하지 않는다', () => {
  const drafts = Array.from({ length: 65 }, (_, index) => draft('RAM', `ram-${index}`))
  assert.equal(buildCompatibilityRequest(drafts), null)
  const summary = summarizePlanCompatibility(drafts, plan, done(drafts))
  assert.equal(summary.result?.checks[0].code, 'RAM_SELECTION_COUNT')
  assert.ok(summary.byIndex.every((row) => row?.status === 'NEEDS_CHECK'))
})

test('완료 결과에 해당 부품의 검사 항목이 없으면 호환을 추정하지 않는다', () => {
  const drafts = [draft('CPU', 'cpu'), draft('MOTHERBOARD', 'board'), draft('GPU', 'gpu')]
  const summary = summarizePlanCompatibility(drafts, plan, done(drafts, [
    check('CPU_SOCKET', 'COMPATIBLE', ['cpuProductId', 'motherboardProductId']),
  ]))
  assert.deepEqual(summary.byIndex.map((row) => row?.status), ['COMPATIBLE', 'COMPATIBLE', 'UNCHECKED'])
})

test('진행·오류·결과 초기화 중에는 지난 판정을 표시하지 않고 미연결과 미입력을 유지한다', () => {
  const drafts = [draft('CPU', 'cpu'), draft('RAM', null), draft('STORAGE', 'ssd')]
  const previous: CompatibilityResult = { status: 'INCOMPATIBLE', checks: [check('CPU_SOCKET', 'INCOMPATIBLE', ['cpuProductId'])] }
  assert.equal(summarizePlanCompatibility(drafts, plan, { status: 'done', result: previous }).byIndex[0]?.status, 'INCOMPATIBLE')
  for (const state of [{ status: 'idle' }, { status: 'loading' }, { status: 'error', message: '연결 오류' }] as CompatibilityState[]) {
    const summary = summarizePlanCompatibility(drafts, plan, state)
    assert.equal(summary.result, null)
    assert.deepEqual(summary.byIndex, [null, { status: 'UNCHECKED', messages: [] }, null])
    assert.deepEqual(summary.badReplacedIds, [])
    assert.deepEqual(summary.unlinked, ['RAM'])
    assert.deepEqual(summary.missing, ['MOTHERBOARD', 'GPU', 'PSU', 'CASE'])
  }
})
