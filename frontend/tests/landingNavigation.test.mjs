import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'

const sourceRoot = fileURLToPath(new URL('../src/', import.meta.url))

// 실제 TSX의 마운트 효과와 모드 전환을 실행한다. 브라우저·이미지 요청·타이머만 대체한다.
function harness({ width = 1440, reduced = false } = {}) {
  const hooks = [], pending = [], modules = new Map(), images = []
  let index = 0
  function effect(callback, deps) {
    const at = index++, previous = hooks[at]
    if (!previous || !deps || deps.some((value, i) => value !== previous.deps[i])) {
      pending.push(() => { previous?.cleanup?.(); hooks[at] = { deps, cleanup: callback() } })
    }
  }
  const react = {
    useState(initial) {
      const at = index++
      if (!(at in hooks)) hooks[at] = typeof initial === 'function' ? initial() : initial
      return [hooks[at], (next) => { hooks[at] = typeof next === 'function' ? next(hooks[at]) : next }]
    },
    useRef(initial) { const at = index++; return hooks[at] ??= { current: initial } },
    useCallback: (callback) => callback,
    useEffect: effect,
    useLayoutEffect: effect,
  }
  const jsx = { jsx: (type, props) => ({ type, props }), jsxs: (type, props) => ({ type, props }), Fragment: 'Fragment' }
  const window = {
    matchMedia: (query) => ({ matches: query.includes('max-width') ? width <= 640 : query.includes('reduced-motion') && reduced,
      addEventListener() {}, removeEventListener() {} }),
    setTimeout: () => 1, clearTimeout() {}, setInterval: () => 1, clearInterval() {},
    addEventListener() {}, removeEventListener() {},
  }
  class Image { set src(value) { images.push(value) } }
  function load(relative) {
    const filename = path.resolve(sourceRoot, relative)
    if (modules.has(filename)) return modules.get(filename).exports
    if (filename.endsWith('LandingAccount.tsx')) return { LandingAccount: () => null }
    const module = { exports: {} }
    modules.set(filename, module)
    const code = ts.transpileModule(readFileSync(filename, 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX },
    }).outputText
    const require = (specifier) => {
      if (specifier === 'react') return react
      if (specifier === 'react/jsx-runtime') return jsx
      if (specifier.endsWith('.css')) return {}
      return load(path.resolve(path.dirname(filename), specifier))
    }
    new Function('require', 'module', 'exports', 'window', 'Image', 'fetch', code)(
      require, module, module.exports, window, Image, async () => ({ ok: false }),
    )
    return module.exports
  }
  return {
    load, images,
    render(Component, props = {}) {
      index = 0
      const tree = Component(props)
      pending.splice(0).forEach((run) => run())
      return tree
    },
    dispose() { hooks.forEach((hook) => hook?.cleanup?.()) },
  }
}

function elements(node) {
  if (node == null || typeof node !== 'object') return []
  if (Array.isArray(node)) return node.flatMap(elements)
  return [node, ...elements(node.props?.children)]
}

for (const width of [320, 375, 640, 641, 1440]) {
  test(`시작 화면 ${width}px에서 모바일은 이미지 미리받기를 생략하고 데스크톱은 유지한다`, () => {
    const h = harness({ width })
    h.render(h.load('features/landing/BootScreen.tsx').BootScreen, { onDone() {} })
    assert.deepEqual(h.images, width <= 640 ? [] : ['/pc-assembly/poster-desktop.webp'])
    h.dispose()
  })
}

test('모바일 랜딩은 조립 컴포넌트를 마운트하지 않고 구성 시작 화면을 바로 제공한다', () => {
  const h = harness({ width: 375 })
  const tree = h.render(h.load('features/landing/LandingPage.tsx').LandingPage)
  assert.ok(elements(tree).some((node) => node.props?.className === 'hero-compact'))
  assert.equal(elements(tree).some((node) => node.type?.name === 'PcAssemblyScroll'), false)
  assert.deepEqual(h.images, [])
  h.dispose()
})

test('동작 줄이기는 완성 장면을 바로 보여 주고 명시적으로 선택했을 때만 애니메이션으로 전환한다', () => {
  const h = harness({ reduced: true })
  const { PcAssemblyScroll } = h.load('features/landing/PcAssemblyScroll.tsx')
  const copies = []
  const props = { scrollRoot: { current: null }, renderCopy: (percent) => { copies.push(percent); return null } }
  let tree = h.render(PcAssemblyScroll, props)
  assert.equal(copies.at(-1), 120)
  assert.ok(elements(tree).some((node) => node.type === 'img' && node.props.alt === '조립이 끝난 PC'))
  assert.equal(elements(tree).some((node) => node.type === 'canvas'), false)
  elements(tree).find((node) => node.type === 'button' && node.props.children === '조립 애니메이션 보기').props.onClick()
  tree = h.render(PcAssemblyScroll, props)
  assert.ok(elements(tree).some((node) => node.type === 'canvas'))
  assert.equal(copies.at(-1), 0)
  elements(tree).find((node) => node.type === 'button' && node.props.children === '정지 장면으로 보기').props.onClick()
  tree = h.render(PcAssemblyScroll, props)
  assert.equal(elements(tree).some((node) => node.type === 'canvas'), false)
  assert.equal(copies.at(-1), 120)
  h.dispose()
})
