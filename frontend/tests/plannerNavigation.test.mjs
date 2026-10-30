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

function harness({ list = async () => ({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
  realPlanner = false, realCatalog = false, storageValues = new Map(), initialHash = '#/pcs/new',
  searchCatalog = async () => ({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0, attributions: [] }),
  searchModels = async () => ({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
} = {}) {
  let active = null
  let nextOwnerId = 0
  let scanMounts = 0
  let scanUnmounts = 0
  const owners = new Map()
  const cache = new Map()
  const calls = []
  const history = [initialHash]
  let cursor = 0
  const window = { confirm: () => true, location: {
    replace(next) { history[cursor] = next },
    get hash() { return history[cursor] },
    set hash(next) {
      if (next === history[cursor]) return
      history.splice(++cursor)
      history[cursor] = next
    },
  } }
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
    useSyncExternalStore(_subscribe, snapshot) { active.index++; return snapshot() },
  }
  const jsx = {
    jsx: (type, props, key) => ({ type, props, key }),
    jsxs: (type, props, key) => ({ type, props, key }),
    Fragment: 'Fragment',
  }
  const stubs = {
    'authStore.ts': { useAuth: () => ({ status: 'signedIn', user: { id: 1 } }), authKey: () => 'user-1' },
    'pcApi.ts': { pcRepository: { async list(page, size) { calls.push({ page, size }); return list(page, size) } } },
    'catalogClient.ts': { catalogClient: { async search(type, keyword, options) {
      calls.push({ type, keyword, page: options.page })
      return searchCatalog(type, keyword, options)
    } } },
    'catalogModelClient.ts': { catalogModelClient: { async search(type, keyword, options) {
      calls.push({ type, keyword, page: options.page })
      return searchModels(type, keyword, options)
    } } },
    'useCompatibility.ts': { useCompatibility: () => ({ status: 'idle' }) },
    'PcBuilder.tsx': { PcBuilder: () => null },
    'CatalogPicker.tsx': { CatalogPicker: () => null },
    'PartStatusBadge.tsx': { PartStatusBadge: () => null, PartStatusLegend: () => null },
    'UpgradePlanner.tsx': { UpgradePlanner: ({ onBackToPc }) => jsx.jsx('section', {
      'data-planner': true, children: jsx.jsx('button', { type: 'button', onClick: onBackToPc, children: '내 PC 수정' }),
    }) },
    'usePcScan.ts': { usePcScan() {
      const [complete, setComplete] = react.useState(false)
      // 실제 훅처럼 같은 결과 객체를 유지한다. 패널은 같은 결과를 한 번만 입력란에 반영한다.
      const [scanned] = react.useState(() => ({ parts: [part('CPU', '수집한 CPU')], warnings: [] }))
      react.useEffect(() => { scanMounts++; return () => { scanUnmounts++ } }, [])
      return {
        status: complete ? 'COMPLETED' : 'IDLE', error: null,
        result: complete ? scanned : null,
        prepare: () => setComplete(true), reset: () => setComplete(false),
      }
    } },
  }
  if (realPlanner) delete stubs['UpgradePlanner.tsx']
  if (realCatalog) delete stubs['CatalogPicker.tsx']
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
      window, { cookie: '' },
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
  return { load, paint, calls, storageValues, window,
    back: () => { if (cursor > 0) cursor-- }, forward: () => { if (cursor + 1 < history.length) cursor++ },
    scanCounts: () => ({ mounts: scanMounts, unmounts: scanUnmounts }) }
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

test('계획 왕복은 자동 인식 결과와 부품 초안을 유지하고 Enter로 저장하지 않는다', async () => {
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
  assert.match(text(hidden), /입력란에 반영했습니다/)
  button(tree, '내 PC 수정').props.onClick()
  tree = h.paint(PcForm, props)
  assert.match(text(tree), /수집한 CPU/)
  assert.match(text(tree), /입력란에 반영했습니다/)
  assert.deepEqual(h.scanCounts(), { mounts: 1, unmounts: 0 })
  assert.equal(bridge.saveActiveDraft(), true)
  // 불러온 CPU는 한 번만 자동 반영되고, 직접 입력한 RAM은 그대로 남는다.
  const saved = bridge.loadDraft().request
  assert.equal(saved.name, initial.name)
  assert.deepEqual(saved.parts.map((item) => item.displayName).sort(), ['8GiB RAM', '수집한 CPU'])
})

test('플랜 조건은 브라우저 뒤로가기·앞으로가기·다른 화면 왕복·새로고침 후 복원된다', () => {
  const h = harness({ realPlanner: true })
  const { PcForm } = h.load('features/pc/components/PcForm.tsx')
  const props = { initial: { name: 'PC A', parts: [part('GPU', 'GeForce RTX 3060')] },
    submitLabel: '저장', onCancel() {}, onSubmit() { assert.fail('복원 중 서버 저장 금지') } }
  let tree = h.paint(PcForm, props)
  button(tree, '업그레이드 계획 살펴보기').props.onClick()
  tree = h.paint(PcForm, props)
  button(tree, '게임').props.onClick()
  tree = h.paint(PcForm, props)
  button(tree, '발로란트').props.onClick()
  tree = h.paint(PcForm, props)
  find(tree, (node) => node.props?.['aria-label'] === '업그레이드 예산').props.onChange({ target: { value: '1200000' } })
  tree = h.paint(PcForm, props)
  button(tree, '진단 결과 보기 →').props.onClick()
  assert.match(h.window.location.hash, /step=3/)
  assert.match(text(h.paint(PcForm, props)), /현재 구성 진단/)
  h.back()
  tree = h.paint(PcForm, props)
  assert.equal(button(tree, '발로란트').props['aria-pressed'], true)
  assert.equal(find(tree, (node) => node.props?.['aria-label'] === '업그레이드 예산').props.value, '1200000')
  h.forward()
  assert.match(text(h.paint(PcForm, props)), /현재 구성 진단/)
  h.paint(() => null)
  assert.match(text(h.paint(PcForm, props)), /현재 구성 진단/)

  const reloaded = harness({ realPlanner: true, storageValues: h.storageValues, initialHash: h.window.location.hash })
  const ReloadedForm = reloaded.load('features/pc/components/PcForm.tsx').PcForm
  const empty = { ...props, initial: { name: '', parts: [] } }
  assert.match(text(reloaded.paint(ReloadedForm, empty)), /현재 구성 진단/)
  reloaded.window.location.hash = '#/pcs/new?step=2'
  tree = reloaded.paint(ReloadedForm, empty)
  assert.equal(button(tree, '발로란트').props['aria-pressed'], true)
  assert.equal(find(tree, (node) => node.props?.['aria-label'] === '업그레이드 예산').props.value, '1200000')
  reloaded.window.location.hash = '#/pcs/new'
  tree = reloaded.paint(ReloadedForm, empty)
  assert.equal(find(tree, (node) => node.props?.placeholder === '예: 집 데스크톱').props.value, 'PC A')
  assert.ok(elements(tree).some((node) => node.type === 'input' && node.props.value === 'GeForce RTX 3060'))
})

test('예전 진단 주소로 앞으로 가도 복원한 부품 수량이 잘못되었으면 폼에서 보완한다', () => {
  const h = harness({ realPlanner: true, initialHash: '#/pcs/new?step=3' })
  const { PcForm } = h.load('features/pc/components/PcForm.tsx')
  const tree = h.paint(PcForm, { initial: { name: 'PC', parts: [part('RAM', 'RAM', {}, NaN)] },
    submitLabel: '저장', onCancel() {}, onSubmit() {} })
  assert.equal(elements(tree).some((node) => node.type === 'h1' && text(node) === '현재 구성 진단'), false)
  assert.match(text(find(tree, (node) => node.props?.role === 'alert')), /수량은 1~64 사이 정수/)
})

test('초안이 없는 진단 주소는 입력 화면으로 정리하고 첫 부품을 적을 때 플랜을 열지 않는다', () => {
  const h = harness({ realPlanner: true, initialHash: '#/pcs/new?step=3' })
  const { PcForm } = h.load('features/pc/components/PcForm.tsx')
  const props = { initial: { name: '', parts: [] }, submitLabel: '저장', onCancel() {}, onSubmit() {} }
  let tree = h.paint(PcForm, props)
  assert.equal(h.window.location.hash, '#/pcs/new')
  const cpu = find(tree, (node) => node.type === 'div' && node.props?.className?.startsWith('part-row ') &&
    elements(node).some((child) => child.type === 'label' && text(child) === 'CPU'))
  find(cpu, (node) => node.type === 'input').props.onChange({ target: { value: 'Ryzen 5 5600' } })
  tree = h.paint(PcForm, props)
  assert.equal(elements(tree).some((node) => node.type === 'h1' && text(node) === '예산과 목적을 정해주세요'), false)
  assert.equal(button(tree, '업그레이드 계획 살펴보기').props.disabled, false)
})

test('부품 검색은 작성 검색어와 마지막 검색 페이지를 구분해 복원하고 결과를 다시 조회한다', async () => {
  const h = harness({ realCatalog: true, searchCatalog: async (_type, _keyword, options) => ({
    items: [], page: options.page, size: 20, totalElements: 42, totalPages: 3, attributions: [],
  }) })
  const { CatalogPicker } = h.load('features/catalog/CatalogPicker.tsx')
  const props = { stateKey: 'pc:new:catalog:CPU:', type: 'CPU', initialQuery: '', onSelect() {}, onClose() {} }
  let tree = h.paint(CatalogPicker, props)
  find(tree, (node) => node.type === 'input' && node.props.type === 'search').props.onChange({ target: { value: 'Ryzen' } })
  tree = h.paint(CatalogPicker, props)
  button(tree, '검색').props.onClick()
  h.paint(CatalogPicker, props)
  await settle()
  tree = h.paint(CatalogPicker, props)
  button(tree, '다음').props.onClick()
  h.paint(CatalogPicker, props)
  await settle()
  h.paint(() => null)
  h.paint(CatalogPicker, props)
  await settle()
  tree = h.paint(CatalogPicker, props)
  assert.equal(find(tree, (node) => node.type === 'input' && node.props.type === 'search').props.value, 'Ryzen')
  assert.match(text(tree), /2 \/ 3 페이지/)
  assert.deepEqual(h.calls.map(({ keyword, page }) => [keyword, page]), [['Ryzen', 0], ['Ryzen', 1], ['Ryzen', 1]])
  find(tree, (node) => node.type === 'input' && node.props.type === 'search').props.onChange({ target: { value: '검색 전 입력' } })
  h.paint(() => null)
  tree = h.paint(CatalogPicker, props)
  assert.equal(find(tree, (node) => node.type === 'input' && node.props.type === 'search').props.value, '검색 전 입력')
  assert.match(text(tree), /검색어를 입력하거나/)
  assert.equal(h.calls.length, 3)
})

test('모델만 확인 모드의 검색어·페이지도 새로고침 후 다시 조회한다', async () => {
  const options = { realCatalog: true, searchModels: async (_type, _keyword, options) => ({
    items: [], page: options.page, size: 20, totalElements: 42, totalPages: 3,
  }) }
  const h = harness(options)
  const { CatalogPicker } = h.load('features/catalog/CatalogPicker.tsx')
  const props = { stateKey: 'pc:new:catalog:RAM:', type: 'RAM', initialQuery: '',
    onSelect() {}, onSelectModel() {}, onClose() {} }
  let tree = h.paint(CatalogPicker, props)
  button(tree, '모델만 확인').props.onClick()
  tree = h.paint(CatalogPicker, props)
  find(tree, (node) => node.type === 'input' && node.props.type === 'search').props.onChange({ target: { value: 'DDR5' } })
  tree = h.paint(CatalogPicker, props)
  button(tree, '검색').props.onClick()
  h.paint(CatalogPicker, props)
  await settle()
  tree = h.paint(CatalogPicker, props)
  button(tree, '다음').props.onClick()
  h.paint(CatalogPicker, props)
  await settle()
  const fresh = harness({ ...options, storageValues: h.storageValues })
  const FreshPicker = fresh.load('features/catalog/CatalogPicker.tsx').CatalogPicker
  fresh.paint(FreshPicker, props)
  await settle()
  tree = fresh.paint(FreshPicker, props)
  assert.equal(button(tree, '모델만 확인').props['aria-pressed'], true)
  assert.equal(find(tree, (node) => node.type === 'input' && node.props.type === 'search').props.value, 'DDR5')
  assert.match(text(tree), /2 \/ 3/)
  assert.deepEqual(fresh.calls.map(({ keyword, page }) => [keyword, page]), [['DDR5', 1]])
})

for (const pageFile of ['features/planner/UpgradeEntryPages.tsx', 'features/pc/pages/PcListPage.tsx']) {
  test(`${pageFile}의 PC 목록 페이지는 화면 왕복·새로고침 후 유지된다`, async () => {
    const options = { list: async (page) => ({ items: [savedPc], page, size: 20, totalElements: 41, totalPages: 3 }) }
    const h = harness(options)
    const module = h.load(pageFile)
    const ListPage = module.UpgradeEntryPage ?? module.PcListPage
    h.paint(ListPage)
    await settle()
    button(h.paint(ListPage), pageFile.includes('planner') ? '다음' : '다음 페이지').props.onClick()
    h.paint(ListPage)
    await settle()
    h.paint(() => null)
    h.paint(ListPage)
    await settle()
    assert.equal(h.calls.at(-1).page, 1)
    const fresh = harness({ ...options, storageValues: h.storageValues })
    const freshModule = fresh.load(pageFile)
    fresh.paint(freshModule.UpgradeEntryPage ?? freshModule.PcListPage)
    await settle()
    assert.equal(fresh.calls[0].page, 1)
  })
}
