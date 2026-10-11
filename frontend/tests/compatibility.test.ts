import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import ts from 'typescript'
import {
  buildCompatibilityRequest, checkCompatibility, incompatibleByDraft, localCompatibilityChecks, mergeCompatibilityChecks,
  type CompatibilityRequest, type CompatibilityResult,
} from '../src/features/builder/compatibility.ts'
import { createManualDraft } from '../src/features/pc/partDraft.ts'
import type { PartDraft } from '../src/features/pc/types.ts'
import type { CompatibilityState } from '../src/features/builder/useCompatibility.ts'

const named = (type: PartDraft['type'], id: string, extra: Partial<PartDraft> = {}): PartDraft => ({
  ...createManualDraft(type), displayName: id, catalogProductId: id, matchStatus: 'MATCHED', ...extra,
})
const selection = () => [named('CPU', 'cpu'), named('MOTHERBOARD', 'board'), named('RAM', 'ram')]
const result = (status: CompatibilityResult['status']): CompatibilityResult => ({
  status, checks: [{ code: 'CPU_SOCKET', status, message: status, affectedFields: ['cpuProductId', 'motherboardProductId'] }],
})
const tick = () => new Promise((resolve) => setImmediate(resolve))

test('새 부품의 존재와 미연결을 구분하고 기존 CPU·보드·RAM 요청은 보존한다', () => {
  assert.deepEqual(buildCompatibilityRequest(selection()), {
    cpuProductId: 'cpu', motherboardProductId: 'board', ram: [{ catalogProductId: 'ram', quantity: 1 }],
  })
  assert.equal(buildCompatibilityRequest([createManualDraft('GPU')]), null)
  const manual = named('PSU', '직접 입력한 파워', { catalogProductId: null, matchStatus: 'UNMATCHED' })
  assert.deepEqual(buildCompatibilityRequest([manual]), {
    cpuProductId: null, motherboardProductId: null, ram: [],
    gpuProductId: null, psuProductId: null, caseProductId: null, presentComponents: ['PSU'],
  })
  const expanded = buildCompatibilityRequest([...selection(), named('GPU', 'gpu'), named('PSU', 'psu'), named('CASE', 'case')])!
  assert.equal(expanded.gpuProductId, 'gpu')
  assert.equal(expanded.psuProductId, 'psu')
  assert.equal(expanded.caseProductId, 'case')
  assert.deepEqual(expanded.presentComponents, ['GPU', 'PSU', 'CASE'])
})

test('복수 GPU·파워·케이스나 단일 항목의 복수 수량을 하나로 검사하지 않는다', () => {
  for (const [type, field] of [['GPU', 'gpuProductId'], ['PSU', 'psuProductId'], ['CASE', 'caseProductId']] as const) {
    for (const rows of [[named(type, 'first'), named(type, 'second')], [named(type, 'quantity', { quantity: 2 })]]) {
      const request = buildCompatibilityRequest(rows)!
      assert.equal(request[field], null)
      assert.equal(localCompatibilityChecks(rows)[0].status, 'NEEDS_CHECK')
      assert.equal(mergeCompatibilityChecks(result('COMPATIBLE'), localCompatibilityChecks(rows)).status, 'NEEDS_CHECK')
    }
  }
})

test('확인된 새 부품의 불가 판정은 관련 행에 연결된다', () => {
  const gpu = named('GPU', 'gpu'), psu = named('PSU', 'psu'), pcCase = named('CASE', 'case')
  const warnings = incompatibleByDraft([gpu, psu, pcCase], { status: 'INCOMPATIBLE', checks: [{
    code: 'GPU_CASE_LENGTH', status: 'INCOMPATIBLE', message: '카드가 너무 깁니다.', affectedFields: ['gpuProductId', 'caseProductId'],
  }] })
  assert.deepEqual(warnings.get(gpu.key), ['카드가 너무 깁니다.'])
  assert.deepEqual(warnings.get(pcCase.key), ['카드가 너무 깁니다.'])
  assert.equal(warnings.has(psu.key), false)
})

test('GPU·파워 검사에서 미연결 CPU·보드가 사라져 전체 초안을 호환으로 표시하지 않는다', () => {
  const hardware = [named('GPU', 'gpu'), named('PSU', 'psu')]
  assert.deepEqual(localCompatibilityChecks(hardware), [])
  for (const type of ['CPU', 'MOTHERBOARD'] as const) {
    const unlinked = named(type, '이름만 입력', { catalogProductId: null, matchStatus: 'UNMATCHED' })
    const local = localCompatibilityChecks([...hardware, unlinked])
    assert.equal(local.length, 1)
    assert.equal(local[0].code, `${type}_CATALOG_LINK`)
    assert.equal(mergeCompatibilityChecks(result('COMPATIBLE'), local).status, 'NEEDS_CHECK')
    assert.equal(mergeCompatibilityChecks(result('INCOMPATIBLE'), local).status, 'INCOMPATIBLE')
  }
})

test('새 부품 검사에 구형 백엔드 결과를 호환으로 표시하지 않는다', async () => {
  const request = buildCompatibilityRequest([named('GPU', 'gpu')])!
  await assert.rejects(checkCompatibility(request, new AbortController().signal,
    async () => Response.json({ ...result('COMPATIBLE'), scope: 'CPU_MOTHERBOARD_RAM_V1', fullPcCompatibilityChecked: false })), /백엔드 버전/)
  const expected = { ...result('NEEDS_CHECK'), scope: 'CPU_MOTHERBOARD_RAM_GPU_PSU_CASE_V2', fullPcCompatibilityChecked: false }
  assert.deepEqual(await checkCompatibility(request, new AbortController().signal, async () => Response.json(expected)), expected)
})

test('잘못된 RAM 수량은 서버 요청에서 빠져도 확인 필요로 남고 불가 판정을 덮어쓰지 않는다', () => {
  for (const quantity of [NaN, 0, -1, 65, 1.5, Infinity]) {
    const drafts = [...selection(), named('RAM', 'invalid RAM', { quantity })]
    const request = buildCompatibilityRequest(drafts)!
    assert.equal(request.ram.length, 1)
    const local = localCompatibilityChecks(drafts)
    assert.equal(local.length, 1)
    assert.match(local[0].message, /invalid RAM/)
    const incomplete = mergeCompatibilityChecks(result('COMPATIBLE'), local)
    assert.equal(incomplete.status, 'NEEDS_CHECK')
    assert.equal(incomplete.checks[0].code, 'CPU_SOCKET')
    assert.equal(mergeCompatibilityChecks(result('INCOMPATIBLE'), local).status, 'INCOMPATIBLE')
  }
})

test('MANUAL·AUTO CPU와 보드가 여러 행이면 첫 행만 임의로 검사하지 않는다', () => {
  for (const type of ['CPU', 'MOTHERBOARD'] as const) {
    const detected = named(type, 'detected', { source: 'AUTO' })
    const manual = named(type, 'manual', { catalogProductId: null, matchStatus: 'UNMATCHED' })
    for (const rows of [[manual, detected], [detected, manual]]) {
      const drafts = [...rows, ...selection().filter((draft) => draft.type !== type)]
      const request = buildCompatibilityRequest(drafts)!
      const field = type === 'CPU' ? 'cpuProductId' : 'motherboardProductId'
      assert.equal(request[field], null)
      assert.equal(localCompatibilityChecks(drafts)[0].code, `${type}_SELECTION`)
      assert.equal(mergeCompatibilityChecks(result('COMPATIBLE'), localCompatibilityChecks(drafts)).status, 'NEEDS_CHECK')
      assert.equal(incompatibleByDraft(drafts, result('INCOMPATIBLE')).has(detected.key), false)
    }
  }
})

test('미연결 RAM도 수량을 보존해 검사하고 RAM 항목이 64개를 넘으면 확인 필요로 알린다', () => {
  const drafts = [...selection().filter((draft) => draft.type !== 'RAM'), named('RAM', 'manual RAM', {
    catalogProductId: null, matchStatus: 'UNMATCHED', quantity: 4,
  })]
  assert.deepEqual(buildCompatibilityRequest(drafts)!.ram, [{ catalogProductId: null, quantity: 4 }])
  const tooMany = [...drafts.filter((draft) => draft.type !== 'RAM'), ...Array.from({ length: 65 }, (_, i) => named('RAM', `ram-${i}`))]
  assert.deepEqual(buildCompatibilityRequest(tooMany)!.ram, [])
  assert.equal(localCompatibilityChecks(tooMany)[0].code, 'RAM_SELECTION_COUNT')
  assert.equal(mergeCompatibilityChecks(result('COMPATIBLE'), localCompatibilityChecks(tooMany)).status, 'NEEDS_CHECK')
})

test('유효한 RAM의 응답 인덱스는 수량 오류 행을 건너뛴 실제 요청과 일치한다', () => {
  const good = named('RAM', 'good')
  const invalid = named('RAM', 'invalid', { quantity: NaN })
  const drafts = [...selection().filter((draft) => draft.type !== 'RAM'), invalid, good]
  const warnings = incompatibleByDraft(drafts, {
    status: 'INCOMPATIBLE', checks: [{ code: 'RAM_TYPE', status: 'INCOMPATIBLE', message: 'DDR mismatch', affectedFields: ['ram[0].catalogProductId'] }],
  })
  assert.deepEqual(warnings.get(good.key), ['DDR mismatch'])
  assert.equal(warnings.has(invalid.key), false)
})

test('호환성 요청은 CSRF 쿠키를 POST 헤더로 보내고 실제 장착 수량을 직렬화한다', async () => {
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'document')
  Object.defineProperty(globalThis, 'document', { configurable: true, value: { cookie: 'XSRF-TOKEN=test%2Bcsrf' } })
  try {
    const request = buildCompatibilityRequest(selection())!
    const signal = new AbortController().signal
    const value = await checkCompatibility(request, signal, async (url, options) => {
      assert.equal(url, '/api/compatibility/check')
      assert.equal(options?.method, 'POST')
      assert.equal(options?.signal, signal)
      const headers = new Headers(options?.headers)
      assert.equal(headers.get('X-XSRF-TOKEN'), 'test+csrf')
      assert.equal(headers.get('Content-Type'), 'application/json')
      assert.deepEqual(JSON.parse(String(options?.body)), request)
      return Response.json(result('NEEDS_CHECK'))
    })
    assert.equal(value.status, 'NEEDS_CHECK')
  } finally {
    if (previous) Object.defineProperty(globalThis, 'document', previous)
    else Reflect.deleteProperty(globalThis, 'document')
  }
})

test('문자열 아닌 상태·검사 누락·상충하는 종합 상태를 응답으로 받아 표시하지 않는다', async () => {
  const malformed = [
    { status: ['COMPATIBLE'], checks: result('COMPATIBLE').checks },
    { status: 'COMPATIBLE', checks: [{ ...result('COMPATIBLE').checks[0], status: ['COMPATIBLE'] }] },
    { status: 'COMPATIBLE', checks: [] },
    { status: 'COMPATIBLE', checks: result('NEEDS_CHECK').checks },
    { status: 'COMPATIBLE', checks: [{ ...result('COMPATIBLE').checks[0], affectedFields: [0] }] },
  ]
  for (const payload of malformed) {
    await assert.rejects(checkCompatibility(buildCompatibilityRequest(selection())!, new AbortController().signal,
      async () => Response.json(payload)), /응답 형식/)
  }
})

test('local API 부재와 HTTP 실패를 호환 판정으로 표시하지 않는다', async () => {
  const request = buildCompatibilityRequest(selection())!
  await assert.rejects(checkCompatibility(request, new AbortController().signal,
    async () => Response.json({}, { status: 404 })), /local 프로필/)
  await assert.rejects(checkCompatibility(request, new AbortController().signal,
    async () => Response.json({}, { status: 403 })), /HTTP 403/)
})

// 실제 훅을 실행하고 React와 지연 응답·타이머만 대체해 재선택과 취소 경쟁을 검증한다.
function hookHarness() {
  type Effect = { callback: () => void | (() => void); deps: unknown[]; cleanup?: () => void }
  type Pending = { request: CompatibilityRequest; signal: AbortSignal; resolve: (value: CompatibilityResult) => void; reject: (error: Error) => void }
  const values: unknown[] = []
  const effects = new Map<number, Effect>()
  const scheduled = new Set<number>()
  const timers = new Map<number, () => void>()
  const requests: Pending[] = []
  let index = 0
  let nextTimer = 0
  const react = {
    useRef(initial: unknown) { const slot = index++; return values[slot] ??= { current: initial } },
    useState(initial: unknown) {
      const slot = index++
      if (!(slot in values)) values[slot] = initial
      return [values[slot], (next: unknown) => { values[slot] = next }]
    },
    useEffect(callback: Effect['callback'], deps: unknown[]) {
      const slot = index++
      const previous = effects.get(slot)
      if (!previous || deps.some((value, i) => value !== previous.deps[i])) {
        effects.set(slot, { callback, deps, cleanup: previous?.cleanup })
        scheduled.add(slot)
      }
    },
  }
  const code = ts.transpileModule(readFileSync(new URL('../src/features/builder/useCompatibility.ts', import.meta.url), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText
  const module = { exports: {} as Record<string, unknown> }
  const require = (name: string) => name === 'react' ? react : {
    buildCompatibilityRequest, localCompatibilityChecks, mergeCompatibilityChecks,
    checkCompatibility: (request: CompatibilityRequest, signal: AbortSignal) => new Promise<CompatibilityResult>((resolve, reject) => {
      requests.push({ request, signal, resolve, reject })
    }),
  }
  new Function('require', 'exports', 'module', 'setTimeout', 'clearTimeout', code)(require, module.exports, module,
    (callback: () => void) => { const id = ++nextTimer; timers.set(id, callback); return id }, (id: number) => timers.delete(id))
  const hook = module.exports.useCompatibility as (drafts: PartDraft[], enabled: boolean) => CompatibilityState
  function render(drafts: PartDraft[], enabled = true) {
    index = 0
    const value = hook(drafts, enabled)
    for (const slot of scheduled) {
      const effect = effects.get(slot)!
      effect.cleanup?.()
      const cleanup = effect.callback()
      effect.cleanup = typeof cleanup === 'function' ? cleanup : undefined
    }
    scheduled.clear()
    return value
  }
  function fireTimers() { for (const [id, callback] of timers) { timers.delete(id); callback() } }
  return { render, fireTimers, requests }
}

test('오류 수량 RAM만 있으면 API 없이 확인 필요를 표시하고 비활성 화면은 미검사다', () => {
  const h = hookHarness()
  const drafts = [named('RAM', 'invalid', { quantity: NaN })]
  const value = h.render(drafts)
  assert.equal(value.status, 'done')
  if (value.status === 'done') assert.equal(value.result.status, 'NEEDS_CHECK')
  h.fireTimers()
  assert.equal(h.requests.length, 0)
  assert.equal(h.render(drafts, false).status, 'idle')
})

test('누락 수량 오류가 추가되면 같은 서버 요청이어도 다시 검사하고 이전 호환 상태를 숨긴다', async () => {
  const h = hookHarness()
  const drafts = selection()
  h.render(drafts)
  h.fireTimers()
  h.requests[0].resolve(result('COMPATIBLE'))
  await tick()
  assert.equal(h.render(drafts).status, 'done')
  const incomplete = [...drafts, named('RAM', 'invalid', { quantity: NaN })]
  assert.equal(h.render(incomplete).status, 'loading')
  h.fireTimers()
  h.requests[1].resolve(result('COMPATIBLE'))
  await tick()
  const value = h.render(incomplete)
  assert.equal(value.status, 'done')
  if (value.status === 'done') assert.equal(value.result.status, 'NEEDS_CHECK')
})

test('A→B→A 재선택 후 취소된 A 성공·실패 응답은 새 A 결과를 덮어쓰지 않는다', async () => {
  for (const late of ['success', 'error']) {
    const h = hookHarness()
    const first = selection()
    const second = [named('CPU', 'other-cpu'), ...first.slice(1)]
    h.render(first)
    h.fireTimers()
    h.render(second)
    h.render(first)
    h.fireTimers()
    assert.equal(h.requests[0].signal.aborted, true)
    h.requests[1].resolve(result('NEEDS_CHECK'))
    await tick()
    if (late === 'success') h.requests[0].resolve(result('COMPATIBLE'))
    else h.requests[0].reject(new Error('stale error'))
    await tick()
    const value = h.render(first)
    assert.equal(value.status, 'done')
    if (value.status === 'done') assert.equal(value.result.status, 'NEEDS_CHECK')
  }
})

test('같은 구성을 다시 선택할 때 지난 성공·오류는 새 검사가 끝날 때까지 표시하지 않는다', async () => {
  for (const previous of ['success', 'error']) {
    const h = hookHarness()
    const first = selection()
    h.render(first)
    h.fireTimers()
    if (previous === 'success') h.requests[0].resolve(result('COMPATIBLE'))
    else h.requests[0].reject(new Error('old error'))
    await tick()
    assert.equal(h.render(first).status, previous === 'success' ? 'done' : 'error')
    h.render([named('CPU', 'other-cpu'), ...first.slice(1)])
    assert.equal(h.render(first).status, 'loading')
  }
})

test('GPU·파워·케이스 재선택은 재검사하며 취소된 응답을 적용하지 않는다', async () => {
  for (const type of ['GPU', 'PSU', 'CASE'] as const) {
    const h = hookHarness()
    const first = [named(type, 'first')], second = [named(type, 'second')]
    h.render(first); h.fireTimers()
    h.render(second); h.fireTimers()
    assert.equal(h.requests[0].signal.aborted, true)
    h.requests[1].resolve(result('NEEDS_CHECK'))
    await tick()
    h.requests[0].resolve(result('COMPATIBLE'))
    await tick()
    const value = h.render(second)
    assert.equal(value.status, 'done')
    if (value.status === 'done') assert.equal(value.result.status, 'NEEDS_CHECK')
  }
})
