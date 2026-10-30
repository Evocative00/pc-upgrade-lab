import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'

const sourceRoot = fileURLToPath(new URL('../src/', import.meta.url))

function harness() {
  const values = new Map()
  const storage = { getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value), removeItem: (key) => values.delete(key) }
  const events = new Map()
  const frames = new Map()
  const observers = new Set()
  let nextFrame = 0
  let cleanup
  let landing = null
  let maxScroll = 0
  const window = {
    location: { hash: '#/pcs/new' }, history: { scrollRestoration: 'auto' }, scrollX: 0, scrollY: 0,
    scrollTo({ top, left }) { this.scrollY = Math.min(top, maxScroll); this.scrollX = left },
    addEventListener(type, callback) { if (!events.has(type)) events.set(type, new Set()); events.get(type).add(callback) },
    removeEventListener(type, callback) { events.get(type)?.delete(callback) },
  }
  const document = { body: {}, querySelector: (selector) => selector === '.landing' ? landing : null }
  class ResizeObserver {
    constructor(callback) { this.callback = callback; observers.add(this) }
    observe() {}
    disconnect() { observers.delete(this) }
  }
  const cache = new Map()
  function load(relative) {
    const filename = path.resolve(sourceRoot, relative)
    if (cache.has(filename)) return cache.get(filename).exports
    const module = { exports: {} }
    cache.set(filename, module)
    const code = ts.transpileModule(readFileSync(filename, 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
    }).outputText
    const require = (specifier) => {
      if (specifier === 'react') return { useEffect: (callback) => { cleanup = callback() } }
      if (specifier.endsWith('router.ts')) return { useLocationHash: () => window.location.hash }
      return load(path.resolve(path.dirname(filename), specifier))
    }
    new Function('require', 'exports', 'module', 'globalThis', 'window', 'document', 'ResizeObserver',
      'requestAnimationFrame', 'cancelAnimationFrame', code)(
      require, module.exports, module, { sessionStorage: storage }, window, document, ResizeObserver,
      (callback) => { const id = ++nextFrame; frames.set(id, callback); return id }, (id) => frames.delete(id),
    )
    return module.exports
  }
  const store = load('lib/pageState.ts').pageStateStore()
  const { usePageScroll } = load('lib/usePageScroll.ts')
  function fire(type, event = {}) { events.get(type)?.forEach((callback) => callback(event)) }
  return {
    store, window,
    render(hash, { home = false } = {}) {
      cleanup?.()
      window.location.hash = hash
      landing = home ? { scrollTop: 0, scrollLeft: 0, scrollTo({ top, left }) {
        this.scrollTop = top; this.scrollLeft = left
      } } : null
      usePageScroll('guest')
    },
    grow(height) {
      maxScroll = height
      observers.forEach((observer) => observer.callback())
      const pending = [...frames.values()]
      frames.clear()
      pending.forEach((callback) => callback())
    },
    scroll(y) {
      if (landing) landing.scrollTop = y
      else window.scrollY = y
      fire('scroll')
    },
    fire,
    position: () => landing?.scrollTop ?? window.scrollY,
    unmount: () => cleanup?.(),
  }
}
const isPosition = (value) => value !== null && typeof value === 'object' &&
  Number.isFinite(value.x) && Number.isFinite(value.y)

test('비동기로 화면 높이가 늘어나면 읽던 위치를 복원하고 다른 화면의 위치와 섞지 않는다', () => {
  const h = harness()
  h.grow(3000)
  h.render('#/pcs/new?step=3')
  h.scroll(950)
  h.render('#/upgrade')
  assert.equal(h.position(), 0)
  h.scroll(100)
  h.grow(0)
  h.render('#/pcs/new?step=3')
  assert.equal(h.position(), 0)
  h.grow(3000)
  assert.equal(h.position(), 950)
  assert.equal(h.store.read('scroll:#/upgrade', { x: 0, y: 0 }, isPosition).y, 100)
  h.unmount()
  assert.equal(h.window.history.scrollRestoration, 'auto')
})

test('사용자가 복원 대기 중 직접 스크롤하면 뒤늦은 화면 높이 변경이 위치를 빼앗지 않는다', () => {
  const h = harness()
  h.store.write('scroll:#/pcs/new', { x: 0, y: 900 }, { x: 0, y: 0 }, isPosition)
  h.render('#/pcs/new')
  h.fire('wheel')
  h.scroll(120)
  h.grow(3000)
  assert.equal(h.position(), 120)
  assert.equal(h.store.read('scroll:#/pcs/new', { x: 0, y: 0 }, isPosition).y, 120)
})

test('홈페이지 자체 스크롤 영역도 화면을 다시 열면 복원한다', () => {
  const h = harness()
  h.render('#/', { home: true })
  h.scroll(1400)
  h.render('#/pcs/new')
  h.render('#/', { home: true })
  assert.equal(h.position(), 1400)
  assert.equal(h.window.scrollY, 0)
})
