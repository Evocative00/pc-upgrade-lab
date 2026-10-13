import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'

// 실제 TSX 이벤트를 실행한다. 서버·카탈로그·수집기만 대체하고 화면별 훅 상태와
// 자식 컴포넌트의 마운트/정리를 유지해 목록 이동과 검사 결과 보존을 확인한다.
const sourceRoot = fileURLToPath(new URL('../src/', import.meta.url))
const GIB = 1024 ** 3

function harness({ list = async () => ({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }) } = {}) {
  let active = null
  let nextOwnerId = 0
  let scanMounts = 0
  let scanUnmounts = 0
  const owners = new Map()
  const cache = new Map()
  const calls = []
  const storageValues = new Map()
  const storage = {
    getItem: (key) => storageValues.get(key) ?? null,
    setItem: (key, value) => storageValues.set(key, value),
    removeItem: (key) => storageValues.delete(key),
  }
  const effect = (callback, deps) => {
    const i = active.index++
    const previous = active.values[i]
    if (!previous || !deps || deps.some((value, index) => value !== previous.deps[index])) {
      previous?.cleanup?.()
      active.values[i] = { deps, cleanup: callback() }
    }
  }
  const react = {
    useState(initial) {
      const i = active.index++
      if (!(i in active.values)) active.values[i] = typeof initial === 'function' ? initial() : initial
      const owner = active
      return [owner.values[i], (next) => {
        owner.values[i] = typeof next === 'function' ? next(owner.values[i]) : next
      }]
    },
    useRef(initial) {
      const i = active.index++
      if (!(i in active.values)) active.values[i] = { current: initial }
      return active.values[i]
    },
    useId: () => `id-${active.id}-${active.index++}`,
    useEffect: effect,
    useLayoutEffect: effect,
  }
  const jsx = {
    jsx: (type, props, key) => ({ type, props, key }),
    jsxs: (type, props, key) => ({ type, props, key }),
    Fragment: 'Fragment',
  }
  const stubs = {
    'authStore.ts': { useAuth: () => ({ status: 'signedIn', user: { id: 1 } }), authKey: () => 'user-1' },
    'pcApi.ts': { pcRepository: { async list(page, size) { calls.push({ page, size }); return list(page, size) } } },
    'useCompatibility.ts': { useCompatibility: () => ({ status: 'idle' }) },
    'PcBuilder.tsx': { PcBuilder: () => null },
    'CatalogPicker.tsx': { CatalogPicker: () => null },
    'PartStatusBadge.tsx': { PartStatusBadge: () => null, PartStatusLegend: () => null },
    'UpgradePlanner.tsx': { UpgradePlanner: ({ onBackToPc }) => jsx.jsx('section', {
      'data-planner': true, children: jsx.jsx('button', { type: 'button', onClick: onBackToPc, children: '내 PC 수정' }),
    }) },
    'usePcScan.ts': { usePcScan() {
      const [complete, setComplete] = react.useState(false)
      react.useEffect(() => { scanMounts++; return () => { scanUnmounts++ } }, [])
      return {
        status: complete ? 'COMPLETED' : 'IDLE', error: null,
        result: complete ? { parts: [part('CPU', '수집한 CPU')], warnings: [] } : null,
        prepare: () => setComplete(true), reset: () => setComplete(false),
      }
    } },
  }
  function resolve(filename) {
    if (path.extname(filename)) return filename
    return `${filename}.ts`
  }
  function load(relative) {
    const filename = resolve(path.resolve(sourceRoot, relative))
    const stub = stubs[path.basename(filename)]
    if (stub) return stub
    if (cache.has(filename)) return cache.get(filename).exports
    const module = { exports: {} }
    cache.set(filename, module)
    const code = ts.transpileModule(readFileSync(filename, 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX },
    }).outputText
    const require = (specifier) => {
      if (specifier === 'react') return react
      if (specifier === 'react/jsx-runtime') return jsx
      if (specifier.endsWith('.css')) return {}
      return load(path.resolve(path.dirname(filename), specifier))
    }
    new Function('require', 'exports', 'module', 'globalThis', 'window', 'document', code)(
      require, module.exports, module, { sessionStorage: storage },
      { confirm: () => true, location: { hash: '#/upgrade' } }, { cookie: '' },
    )
    return module.exports
  }
  function paint(component, props = {}) {
    const visited = new Set()
    function expand(node, location) {
      if (node == null || typeof node !== 'object') return node
      if (Array.isArray(node)) return node.map((child, index) => expand(child, `${location}/${child?.key ?? index}`))
      if (typeof node.type === 'function') {
        if (node.key !== undefined && node.key !== null) location += `/key-${node.key}`
        let owner = owners.get(location)
        if (owner && owner.type !== node.type) {
          owner.values.forEach((value) => value?.cleanup?.())
          owners.delete(location)
          owner = null
        }
        owner ??= { type: node.type, values: [], index: 0, id: nextOwnerId++ }
        owner.index = 0
        owners.set(location, owner)
        visited.add(location)
        active = owner
        const rendered = node.type(node.props)
        active = null
        return expand(rendered, `${location}/render`)
      }
      return { ...node, props: { ...node.props, children: expand(node.props?.children, `${location}/children`) } }
    }
    const tree = expand(jsx.jsx(component, props), 'root')
    for (const [location, owner] of owners) {
      if (!visited.has(location)) {
        owner.values.forEach((value) => value?.cleanup?.())
        owners.delete(location)
      }
    }
    return tree
  }
  return { load, paint, calls, scanCounts: () => ({ mounts: scanMounts, unmounts: scanUnmounts }) }
}

function elements(node) {
  if (node == null) return []
  if (Array.isArray(node)) return node.flatMap(elements)
  return typeof node === 'object' ? [node, ...elements(node.props?.children)] : []
}
function text(node) {
  if (node == null || typeof node === 'boolean') return ''
  if (Array.isArray(node)) return node.map(text).join('')
  return typeof node === 'object' ? text(node.props?.children) : String(node)
}
function find(tree, predicate) {
  const found = elements(tree).find(predicate)
  assert.ok(found, 'expected rendered element')
  return found
}
const button = (tree, label) => find(tree, (node) => node.type === 'button' && text(node) === label)
const settle = () => new Promise((done) => setImmediate(done))
function part(type, displayName, specs = {}, quantity = 1) {
  return { type, displayName, rawName: null, specs, quantity, source: 'MANUAL', catalogProductId: null, matchStatus: 'UNMATCHED' }
}
const savedPc = { id: 1, name: '남아 있는 PC', createdAt: '2026-10-13', updatedAt: '2026-10-13' }

for (const remainingPages of [0, 1]) {
  test(`저장 PC 목록이 ${remainingPages}페이지로 줄어도 빈 다음 페이지에서 이전으로 돌아간다`, async () => {
    const h = harness({ list: async (page) => page === 0
      ? { items: [savedPc], page, size: 20, totalElements: 21, totalPages: 2 }
      : { items: [], page, size: 20, totalElements: remainingPages, totalPages: remainingPages } })
    const { UpgradeEntryPage } = h.load('features/planner/UpgradeEntryPages.tsx')
    h.paint(UpgradeEntryPage)
    await settle()
    let tree = h.paint(UpgradeEntryPage)
    button(tree, '다음').props.onClick()
    tree = h.paint(UpgradeEntryPage)
    assert.equal(button(tree, '이전').props.disabled, false)
    await settle()
    tree = h.paint(UpgradeEntryPage)
    assert.match(text(tree), /이 페이지에 PC가 없습니다/)
    assert.equal(button(tree, '다음').props.disabled, true)
    button(tree, '이전').props.onClick()
    h.paint(UpgradeEntryPage)
    await settle()
    assert.match(text(h.paint(UpgradeEntryPage)), /남아 있는 PC/)
    assert.deepEqual(h.calls.map((call) => call.page), [0, 1, 0])
  })
}

test('다음 페이지 요청이 실패해도 이전 페이지를 다시 조회할 수 있다', async () => {
  const h = harness({ list: async (page) => {
    if (page === 1) throw new Error('연결 실패')
    return { items: [savedPc], page, size: 20, totalElements: 21, totalPages: 2 }
  } })
  const { UpgradeEntryPage } = h.load('features/planner/UpgradeEntryPages.tsx')
  h.paint(UpgradeEntryPage)
  await settle()
  button(h.paint(UpgradeEntryPage), '다음').props.onClick()
  h.paint(UpgradeEntryPage)
  await settle()
  const failed = h.paint(UpgradeEntryPage)
  assert.match(text(failed), /연결 실패/)
  assert.equal(button(failed, '다음').props.disabled, true)
  button(failed, '이전').props.onClick()
  h.paint(UpgradeEntryPage)
  await settle()
  assert.match(text(h.paint(UpgradeEntryPage)), /남아 있는 PC/)
})

test('계획 진입은 잘못된 RAM 수량·용량을 표시하고 이름 없는 유효 구성은 허용한다', () => {
  for (const invalid of [
    part('RAM', '8GiB RAM', { capacityBytes: 8 * GIB }, 1.5),
    part('RAM', '8GiB RAM', { capacityBytes: 8 * GIB }, 0),
    part('RAM', '8GiB RAM', { capacityBytes: 8 * GIB }, 65),
    part('RAM', '8GiB RAM', { capacityBytes: 8 * GIB }, NaN),
    part('RAM', '용량 입력 오류', { capacityBytes: 0 }),
    part('RAM', '용량 입력 오류', { capacityBytes: NaN }),
  ]) {
    const h = harness()
    const { PcForm } = h.load('features/pc/components/PcForm.tsx')
    const props = { initial: { name: '', parts: [invalid] }, submitLabel: '저장', onCancel() {}, onSubmit() { assert.fail('계획은 서버에 저장하지 않는다') } }
    button(h.paint(PcForm, props), '업그레이드 계획 살펴보기').props.onClick()
    const rejected = h.paint(PcForm, props)
    assert.equal(elements(rejected).some((node) => node.props?.['data-planner']), false)
    assert.match(text(find(rejected, (node) => node.type === 'ul' && node.props?.role === 'alert')), /수량은 1~64 사이 정수|용량은 0보다 큰 유효한 값/)
    assert.doesNotMatch(text(rejected), /PC 이름을 입력해 주세요/)
  }

  const h = harness()
  const { PcForm } = h.load('features/pc/components/PcForm.tsx')
  const props = { initial: { name: '', parts: [part('RAM', '8GiB RAM', { capacityBytes: 8 * GIB }, 1.5)] },
    submitLabel: '저장', onCancel() {}, onSubmit() { assert.fail('계획은 서버에 저장하지 않는다') } }
  let tree = h.paint(PcForm, props)
  button(tree, '업그레이드 계획 살펴보기').props.onClick()
  tree = h.paint(PcForm, props)
  const row = find(tree, (node) => node.type === 'div' && node.props?.className?.startsWith('part-row ') &&
    elements(node).some((child) => child.type === 'label' && text(child) === 'RAM'))
  find(row, (node) => node.type === 'input' && node.props.max === 64).props.onChange({ target: { value: '2' } })
  tree = h.paint(PcForm, props)
  button(tree, '업그레이드 계획 살펴보기').props.onClick()
  const accepted = h.paint(PcForm, props)
  assert.ok(elements(accepted).some((node) => node.props?.['data-planner']))
  assert.equal(elements(accepted).some((node) => node.props?.role === 'alert'), false)
})

test('계획 왕복은 미반영 자동 인식 결과와 부품 초안을 유지하고 Enter로 저장하지 않는다', async () => {
  const h = harness()
  const { PcForm } = h.load('features/pc/components/PcForm.tsx')
  const bridge = h.load('features/auth/draftBridge.ts')
  const initial = { name: '작성 중인 PC', parts: [part('RAM', '8GiB RAM', { capacityBytes: 8 * GIB })] }
  let saves = 0
  const props = { initial, submitLabel: '저장', onCancel() {}, onSubmit: async () => { saves++ } }
  let tree = h.paint(PcForm, props)
  button(tree, '내 PC 불러오기').props.onClick()
  tree = h.paint(PcForm, props)
  assert.match(text(tree), /수집한 CPU/)
  button(tree, '업그레이드 계획 살펴보기').props.onClick()
  tree = h.paint(PcForm, props)
  await tree.props.onSubmit({ preventDefault() {} })
  assert.equal(saves, 0)
  const hidden = find(tree, (node) => node.type === 'fieldset' && node.props.hidden === true)
  assert.equal(hidden.props.inert, true)
  assert.ok(elements(hidden).some((node) => node.type === 'button' && text(node) === '입력란에 반영'))
  button(tree, '내 PC 수정').props.onClick()
  tree = h.paint(PcForm, props)
  assert.match(text(tree), /수집한 CPU/)
  assert.ok(button(tree, '입력란에 반영'))
  assert.deepEqual(h.scanCounts(), { mounts: 1, unmounts: 0 })
  assert.equal(bridge.saveActiveDraft(), true)
  assert.deepEqual(bridge.loadDraft().request, initial)
})
