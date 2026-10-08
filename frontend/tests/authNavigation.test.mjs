import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'

// 브라우저 없는 Node 테스트에서 실제 TSX의 이벤트·라우트·초안 코드를 실행한다.
// React 훅과 JSX 렌더링, 수집기 등 관련 없는 하위 화면만 대체한다.
const sourceRoot = fileURLToPath(new URL('../src/', import.meta.url))

function harness({ blockedStorage = false, detail = null, user = null, logoutStatus = 404, createResponse = null } = {}) {
  let active = null
  const hook = () => active.index++
  const effect = (callback, deps) => {
    const i = hook()
    const previous = active.values[i]
    if (!previous || !deps || deps.some((value, index) => value !== previous.deps[index])) {
      previous?.cleanup?.()
      active.values[i] = { deps, cleanup: callback() }
    }
  }
  const react = {
    useState(initial) {
      const i = hook()
      if (!(i in active.values)) active.values[i] = typeof initial === 'function' ? initial() : initial
      const owner = active
      return [owner.values[i], (next) => {
        owner.values[i] = typeof next === 'function' ? next(owner.values[i]) : next
      }]
    },
    useRef(initial) {
      const i = hook()
      if (!(i in active.values)) active.values[i] = { current: initial }
      return active.values[i]
    },
    useId: () => `id-${hook()}`,
    useEffect: effect,
    useLayoutEffect: effect,
    useSyncExternalStore(_subscribe, snapshot) { hook(); return snapshot() },
  }
  const jsx = { jsx: (type, props) => ({ type, props }), jsxs: (type, props) => ({ type, props }), Fragment: 'Fragment' }
  const values = new Map()
  const storage = {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => {
      if (blockedStorage) throw new Error('storage disabled')
      values.set(key, value)
    },
    removeItem: (key) => values.delete(key),
  }
  const window = { location: { hash: '#/pcs/new' }, confirm: () => true, addEventListener() {}, removeEventListener() {} }
  const calls = []
  const fetcher = async (url, init) => {
    calls.push({ url, method: init?.method ?? 'GET' })
    if (url === '/api/auth/me' && user) return new Response(JSON.stringify(user), { status: 200 })
    if (url === '/api/auth/logout') return new Response(null, { status: logoutStatus })
    if (url === '/api/pcs' && init?.method === 'POST' && createResponse) return createResponse()
    if (detail && url === `/api/pcs/${detail.id}` && (!init?.method || init.method === 'GET')) {
      return new Response(JSON.stringify(detail), { status: 200 })
    }
    return new Response(JSON.stringify({ code: 'UNAUTHORIZED', message: '로그인이 필요합니다.' }), {
      status: String(url).startsWith('/api/auth') ? 404 : 401,
    })
  }
  const globals = { sessionStorage: storage, fetch: fetcher }
  const cache = new Map()
  const unrelated = {
    'PcScanPanel.tsx': ['PcScanPanel'], 'PartRow.tsx': ['PartRow'],
    'PartStatusBadge.tsx': ['PartStatusBadge', 'PartStatusLegend'], 'BackendStatus.tsx': ['BackendStatus'],
    'PcDetailPage.tsx': ['PcDetailPage'], 'PcListPage.tsx': ['PcListPage'],
  }
  function load(relative) {
    const filename = path.resolve(sourceRoot, relative)
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
      if (specifier.endsWith('.png')) return { default: specifier }
      const dependency = path.resolve(path.dirname(filename), specifier)
      const names = unrelated[path.basename(dependency)]
      if (names) return Object.fromEntries(names.map((name) => [name, Object.assign(() => null, { displayName: name })]))
      return load(dependency)
    }
    new Function('require', 'exports', 'module', 'globalThis', 'window', 'document', code)(
      require, module.exports, module, globals, window, { cookie: '' },
    )
    return module.exports
  }
  function render(component, props = {}, owner = { values: [], index: 0 }) {
    owner.index = 0
    active = owner
    const tree = component(props)
    active = null
    return { tree, owner }
  }
  function unmount({ owner }) {
    owner.values.forEach((value) => value?.cleanup?.())
  }
  return { load, render, unmount, window, calls, storage }
}

function elements(node) {
  if (node == null) return []
  if (Array.isArray(node)) return node.flatMap(elements)
  return typeof node === 'object' ? [node, ...elements(node.props?.children)] : []
}
function find(tree, predicate) {
  const found = elements(tree).find(predicate)
  assert.ok(found, 'expected rendered element')
  return found
}
const nameInput = (tree) => find(tree, (e) => e.type === 'input' && e.props.placeholder?.includes('집 데스크톱'))

function clickLink(h, link) {
  let prevented = false
  link.props.onClick?.({ preventDefault: () => { prevented = true } })
  if (!prevented) h.window.location.hash = link.props.href
  return prevented
}

test('인증 API가 없어도 저장 401 후 상단 로그인 왕복은 최신 이름과 부품을 복원하며 자동 저장하지 않는다', async () => {
  const h = harness()
  const pages = h.load('features/pc/pages/PcFormPages.tsx')
  const { PcForm } = h.load('features/pc/components/PcForm.tsx')
  const auth = h.load('features/auth/authStore.ts')
  const bridge = h.load('features/auth/draftBridge.ts')
  const { LoginPage } = h.load('features/auth/pages/LoginPages.tsx')
  const app = h.render(h.load('App.tsx').default)
  const AuthStatus = find(app.tree, (e) => e.type?.name === 'AuthStatus').type
  await auth.refreshAuth()
  assert.equal(auth.getAuthState().status, 'unavailable')
  let page = h.render(pages.PcNewPage)
  let props = find(page.tree, (e) => e.type === PcForm).props
  let form = h.render(PcForm, props)
  nameInput(form.tree).props.onChange({ target: { value: 'v1 original' } })
  const row = find(form.tree, (e) => e.type?.displayName === 'PartRow' && e.props.draft.type === 'CPU')
  row.props.onChange({ ...row.props.draft, displayName: 'Ryzen 5 5600' })
  form = h.render(PcForm, props, form.owner)
  await form.tree.props.onSubmit({ preventDefault() {} })
  assert.equal(auth.getAuthState().status, 'signedOut')
  assert.equal(h.window.location.hash, '#/login')
  assert.equal(bridge.loadDraft().request.name, 'v1 original')
  h.unmount(form)
  h.unmount(page)
  const firstLogin = h.render(LoginPage)
  clickLink(h, find(firstLogin.tree, (e) => e.type === 'a' && e.props.className === 'back-link'))
  page = h.render(pages.PcNewPage)
  props = find(page.tree, (e) => e.type === PcForm).props
  form = h.render(PcForm, props)
  nameInput(form.tree).props.onChange({ target: { value: 'v2 latest' } })
  const restoredRow = find(form.tree, (e) => e.type?.displayName === 'PartRow' && e.props.draft.type === 'CPU')
  restoredRow.props.onChange({ ...restoredRow.props.draft, displayName: 'Ryzen 7 7700' })
  form = h.render(PcForm, props, form.owner)
  const header = h.render(AuthStatus)
  clickLink(h, find(header.tree, (e) => e.type === 'a'))
  assert.equal(h.window.location.hash, '#/login')
  h.unmount(form)
  h.unmount(page)
  const secondLogin = h.render(LoginPage)
  clickLink(h, find(secondLogin.tree, (e) => e.type === 'a' && e.props.className === 'back-link'))
  const restored = find(h.render(pages.PcNewPage).tree, (e) => e.type === PcForm).props.initial
  assert.equal(restored.name, 'v2 latest')
  assert.equal(restored.parts[0].displayName, 'Ryzen 7 7700')
  assert.equal(h.calls.filter((call) => call.url === '/api/pcs').length, 1)
})

test('임시 보관 실패는 상단 로그인 이동을 막고 오류를 표시하며 입력을 남긴다', () => {
  const h = harness({ blockedStorage: true })
  const { PcForm } = h.load('features/pc/components/PcForm.tsx')
  const { PcNewPage } = h.load('features/pc/pages/PcFormPages.tsx')
  const app = h.render(h.load('App.tsx').default)
  const AuthStatus = find(app.tree, (e) => e.type?.name === 'AuthStatus').type
  h.load('features/auth/authStore.ts').markSignedOut()
  const props = find(h.render(PcNewPage).tree, (e) => e.type === PcForm).props
  let form = h.render(PcForm, props)
  nameInput(form.tree).props.onChange({ target: { value: '보존할 이름' } })
  form = h.render(PcForm, props, form.owner)
  let header = h.render(AuthStatus)
  assert.equal(clickLink(h, find(header.tree, (e) => e.type === 'a')), true)
  header = h.render(AuthStatus, {}, header.owner)
  assert.match(find(header.tree, (e) => e.props?.role === 'alert').props.children, /임시 보관하지 못했습니다/)
  assert.equal(h.window.location.hash, '#/pcs/new')
  assert.equal(nameInput(form.tree).props.value, '보존할 이름')
})

test('회원 PC 편집 초안은 원래 PC ID를 유지하고 로그인 이동으로 서버에 저장하지 않는다', async () => {
  const detail = { id: 17, name: '회원 A PC', createdAt: '2026-10-04', updatedAt: '2026-10-04', parts: [] }
  const h = harness({ detail })
  const { PcForm } = h.load('features/pc/components/PcForm.tsx')
  const { PcEditPage } = h.load('features/pc/pages/PcFormPages.tsx')
  let page = h.render(PcEditPage, { id: 17 })
  await new Promise((resolve) => setImmediate(resolve))
  page = h.render(PcEditPage, { id: 17 }, page.owner)
  const props = find(page.tree, (e) => e.type === PcForm).props
  assert.equal(props.pcId, 17)
  let form = h.render(PcForm, props)
  nameInput(form.tree).props.onChange({ target: { value: '편집 중인 회원 A PC' } })
  form = h.render(PcForm, props, form.owner)
  const bridge = h.load('features/auth/draftBridge.ts')
  assert.equal(bridge.saveActiveDraft(), true)
  assert.equal(bridge.loadDraft().pcId, 17)
  assert.equal(bridge.loadDraft().request.name, '편집 중인 회원 A PC')
  assert.equal(h.calls.filter((call) => call.method !== 'GET').length, 0)
  h.unmount(form)
  assert.equal(bridge.saveActiveDraft(), true)
  assert.equal(bridge.loadDraft().pcId, 17)
})

test('상단 로그아웃 실패는 오류를 표시하고 회원과 현재 화면을 유지한다', async () => {
  const user = { id: 1, name: '회원 A', email: null, provider: 'google' }
  const h = harness({ user, logoutStatus: 500 })
  h.window.location.hash = '#/pcs/17'
  const app = h.render(h.load('App.tsx').default)
  const AuthStatus = find(app.tree, (e) => e.type?.name === 'AuthStatus').type
  const auth = h.load('features/auth/authStore.ts')
  await auth.refreshAuth()
  let header = h.render(AuthStatus)
  await find(header.tree, (e) => e.type === 'button').props.onClick()
  header = h.render(AuthStatus, {}, header.owner)
  assert.deepEqual(auth.getAuthState(), { status: 'signedIn', user })
  assert.equal(h.window.location.hash, '#/pcs/17')
  assert.match(find(header.tree, (e) => e.props?.role === 'alert').props.children, /HTTP 500/)
  assert.equal(find(header.tree, (e) => e.type === 'button').props.disabled, false)
})

test('새 PC A의 늦은 저장 성공은 그 사이 작성한 새 PC B의 최신 초안을 지우지 않는다', async () => {
  let finishCreate
  const delayed = new Promise((resolve) => { finishCreate = resolve })
  const h = harness({ createResponse: () => delayed })
  const bridge = h.load('features/auth/draftBridge.ts')
  const { PcForm } = h.load('features/pc/components/PcForm.tsx')
  const { PcNewPage } = h.load('features/pc/pages/PcFormPages.tsx')
  const request = {
    name: '새 PC A',
    parts: [{ type: 'CPU', displayName: 'Ryzen 5 5600', rawName: null, quantity: 1,
      source: 'MANUAL', catalogProductId: null, matchStatus: 'UNMATCHED', specs: {} }],
  }
  bridge.saveDraft(request, null)
  const pageA = h.render(PcNewPage)
  const formA = h.render(PcForm, find(pageA.tree, (e) => e.type === PcForm).props)
  const saving = formA.tree.props.onSubmit({ preventDefault() {} })
  h.unmount(formA)
  h.unmount(pageA)
  const pageB = h.render(PcNewPage)
  const propsB = find(pageB.tree, (e) => e.type === PcForm).props
  let formB = h.render(PcForm, propsB)
  nameInput(formB.tree).props.onChange({ target: { value: '새 PC B' } })
  formB = h.render(PcForm, propsB, formB.owner)
  assert.equal(bridge.saveActiveDraft(), true)
  finishCreate(new Response(JSON.stringify({ ...request, id: 17, createdAt: '2026-10-04', updatedAt: '2026-10-04' }), { status: 201 }))
  await saving
  assert.equal(bridge.loadDraft().request.name, '새 PC B')
  assert.equal(bridge.loadDraft().pcId, null)
  assert.equal(h.window.location.hash, '#/pcs/new')
})
