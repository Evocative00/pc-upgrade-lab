import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import ts from 'typescript'
import {
  applyCatalogSelection, assignSlots, chipLabel, priceTotal, referencePriceTotal, ramLabel, ramModuleViews, slotInfo, slotOfDraft,
} from '../src/features/builder/buildSlots.ts'
import { createManualDraft, toPartInputs, withEmptyRows } from '../src/features/pc/partDraft.ts'
import type { CatalogDetail, CatalogPriceStatus, CatalogProduct, ReferenceEstimate } from '../src/features/catalog/catalogTypes.ts'
import type { PartDraft } from '../src/features/pc/types.ts'
import type { DetailEntry } from '../src/features/builder/useCatalogDetails.ts'
import { buildCompatibilityRequest, incompatibleByDraft } from '../src/features/builder/compatibility.ts'

const named = (type: PartDraft['type'], displayName: string, extra: Partial<PartDraft> = {}): PartDraft =>
  ({ ...createManualDraft(type), displayName, ...extra })
const product = (type: CatalogProduct['type'], id = 'catalog-1'): CatalogProduct => ({
  id, type, manufacturer: 'Test', modelName: `${type} model`, partNumber: null,
  verificationStatus: 'CORE_VERIFIED', active: true,
  referencePrice: { amountKrw: 140000, status: 'CONFIRMED', updatedAt: '' },
  currentPrice: { amountKrw: 100000, sourceName: '판매처', sourceUrl: 'https://shop.example/p/1', observedAt: '2026-10-06T01:00:00Z' },
  createdAt: '', updatedAt: '',
})
const detail = (type: CatalogProduct['type'], kit?: number, id = 'catalog-1'): CatalogDetail => ({
  product: product(type, id), specification: kit === undefined ? {} : { moduleCount: kit }, sources: [], attributions: [],
})
const gib = 1024 ** 3
const sharedStatus = (overrides: Partial<CatalogPriceStatus> = {}): CatalogPriceStatus => ({
  origin: 'SHARED', lookupStatus: 'OK', freshness: 'FRESH', catalogVersion: 'pilot-2026-10-10-v1',
  checkedAt: '2026-10-10T12:00:00Z', lastSuccessAt: '2026-10-10T11:00:00Z', includedInTotal: true, ...overrides,
})
const estimate = (overrides: Partial<ReferenceEstimate> = {}): ReferenceEstimate => ({
  amountKrw: 80000, basis: 'SIMILAR_PART_ESTIMATE', identityScope: 'SIMILAR_SPEC', saleUnit: 'PRODUCT', moduleCount: null,
  sourceDate: null, reviewedAt: '2026-10-10T12:00:00Z', confidence: 'ESTIMATED', method: 'SPEC_NEIGHBOUR_MEDIAN',
  rangeLowKrw: 60000, rangeHighKrw: 100000, notes: '유사 제원 참고가', sourceQuotes: [{ canonicalId: null,
    modelName: '비교 부품', amountKrw: 100000, sourceName: '판매처', sourceUrl: 'https://shop.example/p/1',
    sourceDate: '2026-10-06', saleUnit: 'PRODUCT', moduleCount: null }], ...overrides,
})

test('참고 합계는 만료된 실제 가격을 우선하며 별도 추정가를 중복 합산하지 않는다', () => {
  const old = detail('CPU')
  old.product.priceStatus = sharedStatus({ freshness: 'EXPIRED', includedInTotal: false })
  old.product.referenceEstimate = estimate()
  const pending = detail('GPU')
  pending.product.currentPrice = null
  pending.product.referenceEstimate = estimate()
  const lines = [{ draft: named('CPU', 'old'), detail: old }, { draft: named('GPU', 'estimate', { quantity: 2 }), detail: pending }]
  assert.equal(priceTotal(lines).currentKrw, 0)
  assert.deepEqual(referencePriceTotal(lines), { referenceKrw: 260000, pricedItems: 2, unpriced: 0, quantityNeedsCheck: 0 })
  assert.equal(old.product.priceStatus.includedInTotal, false)
  assert.equal(pending.product.currentPrice, null)
})

test('참고 합계는 모델만 연결한 부품과 확인한 관측이 없는 장애 가격을 제외한다', () => {
  const entry = detail('CPU')
  entry.product.priceStatus = sharedStatus({ lookupStatus: 'UNAVAILABLE', lastSuccessAt: null, includedInTotal: false })
  assert.equal(referencePriceTotal([{ draft: named('CPU', 'invalid observation'), detail: entry }]).pricedItems, 0)
  entry.product.referenceEstimate = estimate()
  const model = named('CPU', 'model only', { catalogModelId: 'model', catalogProductId: null })
  assert.deepEqual(referencePriceTotal([{ draft: model, detail: entry }]), { referenceKrw: 0, pricedItems: 0, unpriced: 1, quantityNeedsCheck: 0 })
})

test('RAM 참고가는 원래 키트 단위로 실제 장착 모듈을 환산하며 수량 불일치를 제외한다', () => {
  const kit = detail('RAM', 2)
  kit.product.currentPrice = null
  kit.product.referenceEstimate = estimate({ saleUnit: 'RAM_KIT', moduleCount: 2 })
  assert.equal(referencePriceTotal([{ draft: named('RAM', 'kit', { quantity: 4 }), detail: kit }]).referenceKrw, 160000)
  assert.equal(referencePriceTotal([{ draft: named('RAM', 'one module'), detail: kit }]).quantityNeedsCheck, 1)
  kit.product.referenceEstimate.moduleCount = 4
  assert.equal(referencePriceTotal([{ draft: named('RAM', 'unit mismatch', { quantity: 4 }), detail: kit }]).quantityNeedsCheck, 1)
})

test('CPU 이름은 굵은 줄과 작은 줄로 나눈다', () => {
  assert.deepEqual(chipLabel('Ryzen 5 5500GT'), ['RYZEN 5', '5500GT'])
  assert.deepEqual(chipLabel('i5'), ['I5', ''])
})

test('RAM 그림은 실제 장착 수량만 사용하며 카탈로그 묶음 장수를 더하지 않는다', () => {
  const draft = named('RAM', '2-module kit', { quantity: 1, specs: { capacityBytes: 8 * gib } })
  const view = ramModuleViews([draft], () => ({ moduleCount: 2, moduleCapacityBytes: 16 * gib }))
  assert.equal(view.installedCount, 1)
  assert.equal(view.modules.length, 1)
  assert.deepEqual(view.modules[0].label, ['8GB', ''])
  assert.equal(toPartInputs([draft])[0].quantity, 1)
})

test('서로 다른 RAM 모듈은 각 항목의 용량으로 표시하고 첫 두 개만 그린다', () => {
  const first = named('RAM', 'A', { specs: { capacityBytes: 8 * gib, memoryType: 'DDR4' } })
  const second = named('RAM', 'B', { quantity: 3, specs: { capacityBytes: 16 * gib, memoryType: 'DDR4' } })
  const view = ramModuleViews([first, second], () => null)
  assert.equal(view.installedCount, 4)
  assert.deepEqual(view.modules.map((module) => module.label), [['8GB', 'DDR4'], ['16GB', 'DDR4']])
  assert.deepEqual(view.modules.map((module) => module.name), ['A', 'B'])
})

test('RAM 용량은 미확인과 잘못된 입력을 구분하고 소수 용량을 반올림해 단정하지 않는다', () => {
  assert.deepEqual(ramLabel({ capacityBytes: null }, { moduleCapacityBytes: 8 * gib, memoryType: 'DDR4' }), ['8GB', 'DDR4'])
  assert.deepEqual(ramLabel({ capacityBytes: 0 }, { moduleCapacityBytes: 8 * gib }), ['용량 미확인', ''])
  assert.deepEqual(ramLabel({ capacityBytes: 0.5 * gib }), ['0.5GB', ''])
  assert.deepEqual(ramLabel(null), ['용량 미확인', ''])
  const invalid = named('RAM', 'invalid', { quantity: Number.NaN })
  assert.deepEqual(ramModuleViews([invalid], () => null), { modules: [], installedCount: 0 })
})

test('저장장치는 실제 SSD/HDD 종류를 추정하지 않고 위치 이름으로 표시한다', () => {
  const first = named('STORAGE', 'A SSD')
  const second = named('STORAGE', 'B SSD', { visualSlot: 'HDD' })
  const extra = named('STORAGE', 'C')
  const drafts = [createManualDraft('CPU'), named('GPU', 'RTX'), first, second, extra]
  const slots = assignSlots(drafts)
  assert.equal(slots.CPU, undefined)
  assert.equal(slots.GPU?.displayName, 'RTX')
  assert.equal(slots.SSD, first)
  assert.equal(slots.HDD, second)
  assert.equal(slotInfo('SSD').label, '저장장치 1')
  assert.equal(slotInfo('HDD').label, '저장장치 2')
  assert.equal(slotOfDraft(drafts, second), 'HDD')
  assert.equal(slotOfDraft(drafts, extra), 'SSD')
})

test('현재 상품가는 일반 부품의 장치 수를 곱하고 없는 가격은 따로 센다', () => {
  const confirmed = detail('MONITOR')
  const pending = detail('GPU')
  pending.product.currentPrice = null
  assert.deepEqual(priceTotal([
    { draft: named('MONITOR', 'monitor', { quantity: 2 }), detail: confirmed },
    { draft: named('GPU', 'gpu'), detail: pending },
    { draft: named('CPU', 'unlinked'), detail: null },
  ]), { currentKrw: 200000, pricedItems: 1, unpriced: 2, quantityNeedsCheck: 0 })
})

test('확정 기준가격만 있어도 현재 상품가 합계는 산정하지 않는다', () => {
  const legacy = detail('CPU')
  legacy.product.currentPrice = null
  assert.deepEqual(priceTotal([{ draft: named('CPU', 'cpu'), detail: legacy }]), {
    currentKrw: 0, pricedItems: 0, unpriced: 1, quantityNeedsCheck: 0,
  })
})

test('중앙 최근·오래된 관측은 서버 정책대로 합산하고 만료·장애 캐시 가격은 제외한다', () => {
  const lines = (['FRESH', 'STALE', 'EXPIRED'] as const).map((freshness) => {
    const entry = detail('GPU', undefined, freshness)
    entry.product.priceStatus = sharedStatus({ freshness, includedInTotal: freshness !== 'EXPIRED' })
    return { draft: named('GPU', freshness), detail: entry }
  })
  const unavailable = detail('CPU')
  unavailable.product.priceStatus = sharedStatus({ lookupStatus: 'UNAVAILABLE', includedInTotal: false })
  lines.push({ draft: named('CPU', 'last cached price'), detail: unavailable })
  assert.deepEqual(priceTotal(lines), { currentKrw: 200000, pricedItems: 2, unpriced: 2, quantityNeedsCheck: 0 })
  assert.equal(lines[0].detail.product.currentPrice?.observedAt, '2026-10-06T01:00:00Z')
})

test('중앙 미확인·미등록·장애와 잘못된 합계 포함 플래그는 0원 가격으로 산정하지 않는다', () => {
  for (const lookupStatus of ['NO_PRICE', 'UNKNOWN_PRODUCT', 'UNAVAILABLE'] as const) {
    const entry = detail('CPU')
    entry.product.currentPrice = null
    entry.product.priceStatus = sharedStatus({ lookupStatus, freshness: 'NO_PRICE', includedInTotal: false })
    assert.deepEqual(priceTotal([{ draft: named('CPU', lookupStatus), detail: entry }]), {
      currentKrw: 0, pricedItems: 0, unpriced: 1, quantityNeedsCheck: 0,
    })
  }
  for (const overrides of [{ freshness: 'EXPIRED' as const }, { lookupStatus: 'UNAVAILABLE' as const },
    { catalogVersion: null }, { lastSuccessAt: null }]) {
    const entry = detail('CPU')
    entry.product.priceStatus = sharedStatus(overrides)
    assert.equal(priceTotal([{ draft: named('CPU', 'bad flag'), detail: entry }]).pricedItems, 0)
  }
})

test('범위 밖 로컬 가격과 이전 응답의 가격 합계는 그대로 유지한다', () => {
  const local = detail('GPU')
  local.product.priceStatus = { origin: 'LOCAL', lookupStatus: 'NOT_IN_SCOPE', freshness: null,
    catalogVersion: null, checkedAt: null, lastSuccessAt: null, includedInTotal: true }
  const legacy = detail('CPU')
  legacy.product.priceStatus = null
  assert.deepEqual(priceTotal([{ draft: named('GPU', 'local'), detail: local }, { draft: named('CPU', 'legacy'), detail: legacy }]),
    { currentKrw: 200000, pricedItems: 2, unpriced: 0, quantityNeedsCheck: 0 })
})

test('중앙 RAM 가격도 실제 모듈 수를 키트로 환산하며 만료·모델만 확인한 항목은 제외한다', () => {
  const kit = detail('RAM', 2)
  kit.product.priceStatus = sharedStatus({ freshness: 'STALE' })
  const draft = named('RAM', 'kit', { quantity: 2, catalogProductId: kit.product.id })
  assert.deepEqual(priceTotal([{ draft, detail: kit }]), { currentKrw: 100000, pricedItems: 1, unpriced: 0, quantityNeedsCheck: 0 })
  kit.product.priceStatus = sharedStatus({ freshness: 'EXPIRED', includedInTotal: false })
  assert.deepEqual(priceTotal([{ draft, detail: kit }]), { currentKrw: 0, pricedItems: 0, unpriced: 1, quantityNeedsCheck: 0 })
  kit.product.priceStatus = sharedStatus()
  const modelOnly = { ...draft, catalogProductId: null, catalogModelId: 'model-only', recognitionLevel: 'MODEL' as const }
  assert.deepEqual(priceTotal([{ draft: modelOnly, detail: kit }]), { currentKrw: 0, pricedItems: 0, unpriced: 1, quantityNeedsCheck: 0 })
})

test('같은 RAM 제품의 여러 행은 모듈 합계를 판매 묶음 수로 환산한다', () => {
  const kit = detail('RAM', 2)
  assert.deepEqual(priceTotal([
    { draft: named('RAM', 'module A'), detail: kit },
    { draft: named('RAM', 'module B'), detail: kit },
  ]), { currentKrw: 100000, pricedItems: 2, unpriced: 0, quantityNeedsCheck: 0 })
  assert.equal(priceTotal([{ draft: named('RAM', '4 modules', { quantity: 4 }), detail: kit }]).currentKrw, 200000)
})

test('RAM 묶음이 맞지 않거나 모르면 가격을 임의로 나누거나 올림하지 않는다', () => {
  for (const kit of [detail('RAM', 2), detail('RAM')]) {
    assert.deepEqual(priceTotal([{ draft: named('RAM', 'one module'), detail: kit }]), {
      currentKrw: 0, pricedItems: 0, unpriced: 0, quantityNeedsCheck: 1,
    })
  }
  const differentKits = [detail('RAM', 2, 'A'), detail('RAM', 2, 'B')]
  assert.equal(priceTotal(differentKits.map((kit) => ({ draft: named('RAM', kit.product.id), detail: kit }))).quantityNeedsCheck, 2)
  assert.deepEqual(priceTotal([{ draft: named('MONITOR', 'invalid', { quantity: Number.NaN }), detail: detail('MONITOR') }]), {
    currentKrw: 0, pricedItems: 0, unpriced: 0, quantityNeedsCheck: 1,
  })
})

test('카탈로그 연결은 최신 수량·수집 원문·용량을 유지하고 같은 자리의 행을 갱신한다', () => {
  const original = named('RAM', 'raw RAM', { source: 'AUTO', rawName: 'raw RAM', quantity: 2,
    specs: { capacityBytes: 8 * gib } })
  const next = applyCatalogSelection([original], { slot: 'RAM', draftKey: original.key }, product('RAM'))
  assert.equal(next.length, 1)
  assert.equal(next[0].key, original.key)
  assert.equal(next[0].quantity, 2)
  assert.equal(next[0].source, 'AUTO')
  assert.equal(next[0].rawName, 'raw RAM')
  assert.deepEqual(next[0].specs, original.specs)
  assert.equal(next[0].catalogProductId, 'catalog-1')
  assert.equal(original.catalogProductId, null)
})

test('검색 대상 행을 지우거나 재스캔하면 이전 검색 결과로 새 행을 만들지 않는다', () => {
  const removed = named('RAM', 'removed')
  const current = withEmptyRows([named('RAM', 'new scan')])
  assert.equal(applyCatalogSelection(current, { slot: 'RAM', draftKey: removed.key }, product('RAM')), current)
  assert.equal(applyCatalogSelection(current, { slot: 'CPU', draftKey: null }, product('RAM')), current)
})

test('빈 자리는 기존 빈 행을 사용하고 다음 선택은 같은 행을 교체한다', () => {
  const current = withEmptyRows([])
  const empty = current.find((draft) => draft.type === 'STORAGE')!
  const first = applyCatalogSelection(current, { slot: 'HDD', draftKey: null }, product('STORAGE', 'first'))
  const linked = first.find((draft) => draft.key === empty.key)!
  assert.equal(linked.visualSlot, 'HDD')
  const second = applyCatalogSelection(first, { slot: 'HDD', draftKey: null }, product('STORAGE', 'second'))
  assert.equal(second.filter((draft) => draft.type === 'STORAGE').length, 1)
  assert.equal(second.find((draft) => draft.key === empty.key)?.catalogProductId, 'second')
  assert.equal('visualSlot' in toPartInputs(second)[0], false)
})

// 실제 훅 코드를 실행하고 React 상태·효과만 대체해 요청 취소와 재시도를 확인한다.
function detailHookHarness() {
  type Effect = { callback: () => void | (() => void); deps: unknown[]; cleanup?: () => void }
  type Pending = { id: string; signal: AbortSignal; resolve: (value: CatalogDetail) => void; reject: (error: Error) => void }
  const values: unknown[] = []
  const effects = new Map<number, Effect>()
  const scheduled = new Set<number>()
  const requests: Pending[] = []
  let index = 0
  const react = {
    useRef(initial: unknown) {
      const slot = index++
      return values[slot] ??= { current: initial }
    },
    useState(initial: unknown) {
      const slot = index++
      if (!(slot in values)) values[slot] = initial
      return [values[slot], (next: unknown) => {
        values[slot] = typeof next === 'function' ? next(values[slot]) : next
      }]
    },
    useCallback(callback: unknown) { index++; return callback },
    useEffect(callback: Effect['callback'], deps: unknown[]) {
      const slot = index++
      const previous = effects.get(slot)
      if (!previous || deps.some((value, i) => value !== previous.deps[i])) {
        effects.set(slot, { callback, deps, cleanup: previous?.cleanup })
        scheduled.add(slot)
      }
    },
  }
  const code = ts.transpileModule(readFileSync(new URL('../src/features/builder/useCatalogDetails.ts', import.meta.url), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText
  const module = { exports: {} as Record<string, unknown> }
  const require = (name: string) => name === 'react' ? react : {
    catalogClient: { get: (id: string, signal: AbortSignal) => new Promise<CatalogDetail>((resolve, reject) => {
      requests.push({ id, signal, resolve, reject })
    }) },
  }
  new Function('require', 'exports', 'module', code)(require, module.exports, module)
  const hookFunction = module.exports.useCatalogDetails as (ids: string[]) => {
    entries: Record<string, DetailEntry>; retry: (id: string) => void; refreshAll: () => void,
  }
  function render(ids = ['catalog-1']) {
    index = 0
    const result = hookFunction(ids)
    for (const slot of scheduled) {
      const effect = effects.get(slot)!
      effect.cleanup?.()
      const cleanup = effect.callback()
      effect.cleanup = typeof cleanup === 'function' ? cleanup : undefined
    }
    scheduled.clear()
    return result
  }
  function strictReplay() {
    for (const effect of effects.values()) effect.cleanup?.()
    for (const effect of effects.values()) {
      const cleanup = effect.callback()
      effect.cleanup = typeof cleanup === 'function' ? cleanup : undefined
    }
  }
  return { render, strictReplay, requests }
}

test('가격·제원 조회 실패 후 다시 조회하면 오류를 지우고 최신 응답을 표시한다', async () => {
  const h = detailHookHarness()
  h.render()
  h.requests[0].reject(new Error('temporary failure'))
  await new Promise((resolve) => setImmediate(resolve))
  let view = h.render()
  assert.equal(view.entries['catalog-1'].status, 'error')
  view.retry('catalog-1')
  view = h.render()
  assert.equal(view.entries['catalog-1'].status, 'loading')
  assert.equal(h.requests.length, 2)
  h.requests[1].resolve(detail('CPU'))
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(h.render().entries['catalog-1'].status, 'done')
})

test('가격 새로고침은 연결된 제품을 한 번씩 다시 조회하고 갱신된 현재가를 반환한다', async () => {
  const h = detailHookHarness()
  h.render(['catalog-1', 'catalog-1'])
  assert.equal(h.requests.length, 1)
  h.requests[0].resolve(detail('CPU'))
  await new Promise((resolve) => setImmediate(resolve))
  const view = h.render(['catalog-1', 'catalog-1'])
  assert.equal(view.entries['catalog-1'].status, 'done')
  view.refreshAll()
  assert.equal(h.render(['catalog-1']).entries['catalog-1'].status, 'loading')
  assert.equal(h.requests.length, 2)
  const changed = detail('CPU')
  changed.product.currentPrice!.amountKrw = 90000
  h.requests[1].resolve(changed)
  await new Promise((resolve) => setImmediate(resolve))
  const refreshed = h.render(['catalog-1']).entries['catalog-1']
  assert.equal(refreshed.status, 'done')
  if (refreshed.status === 'done') assert.equal(refreshed.detail.product.currentPrice?.amountKrw, 90000)
})

test('StrictMode 재실행은 취소한 요청을 다시 보내고 이전 성공 응답을 무시한다', async () => {
  const h = detailHookHarness()
  h.render()
  h.strictReplay()
  assert.equal(h.requests.length, 2)
  assert.equal(h.requests[0].signal.aborted, true)
  const latest = detail('CPU')
  latest.product.modelName = 'latest'
  h.requests[1].resolve(latest)
  await new Promise((resolve) => setImmediate(resolve))
  h.requests[0].resolve(detail('CPU'))
  await new Promise((resolve) => setImmediate(resolve))
  const entry = h.render().entries['catalog-1']
  assert.equal(entry.status, 'done')
  if (entry.status === 'done') assert.equal(entry.detail.product.modelName, 'latest')
})

test('호환성 검사 요청은 연결된 CPU·메인보드·RAM이 있을 때만 만들고, 불가 판정만 해당 행에 표시한다', () => {
  assert.equal(buildCompatibilityRequest(withEmptyRows([named('CPU', '직접 입력 CPU')])), null)
  const cpu = named('CPU', 'CPU', { catalogProductId: 'cpu-1' })
  const board = named('MOTHERBOARD', '보드', { catalogProductId: 'board-1' })
  const ram1 = named('RAM', '램1', { catalogProductId: 'ram-1', quantity: 2 })
  const ramBad = named('RAM', '수량 오류', { quantity: NaN })
  const ram2 = named('RAM', '램2', { catalogProductId: 'ram-2', quantity: 1 })
  const drafts = withEmptyRows([cpu, board, ram1, ramBad, ram2])
  assert.deepEqual(buildCompatibilityRequest(drafts), {
    cpuProductId: 'cpu-1', motherboardProductId: 'board-1',
    ram: [{ catalogProductId: 'ram-1', quantity: 2 }, { catalogProductId: 'ram-2', quantity: 1 }],
  })
  const warnings = incompatibleByDraft(drafts, { status: 'INCOMPATIBLE', checks: [
    { code: 'CPU_SOCKET', status: 'INCOMPATIBLE', message: '소켓 다름', affectedFields: ['cpuProductId', 'motherboardProductId'] },
    { code: 'MOTHERBOARD_RAM_TYPE_1', status: 'INCOMPATIBLE', message: 'DDR 다름', affectedFields: ['motherboardProductId', 'ram[1].catalogProductId'] },
    { code: 'CPU_BIOS', status: 'NEEDS_CHECK', message: 'BIOS 확인', affectedFields: ['cpuProductId'] },
  ] })
  assert.deepEqual(warnings.get(cpu.key), ['소켓 다름'])
  assert.deepEqual(warnings.get(board.key), ['소켓 다름', 'DDR 다름'])
  assert.deepEqual(warnings.get(ram2.key), ['DDR 다름'])
  assert.equal(warnings.has(ram1.key), false)
  assert.equal(warnings.has(ramBad.key), false)
})
