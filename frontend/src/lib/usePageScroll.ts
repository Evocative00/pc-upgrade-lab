import { useEffect } from 'react'
import { isRecord, pageStateStore } from './pageState.ts'
import { useLocationHash } from './router.ts'

type Position = { x: number; y: number }
const isPosition = (value: unknown): value is Position => isRecord(value) &&
  Number.isFinite(value.x) && Number(value.x) >= 0 && Number.isFinite(value.y) && Number(value.y) >= 0

// API 응답으로 화면 높이가 늦게 늘어나는 경우에도 원래 읽던 위치로 돌아간다.
export function usePageScroll(identity: string) {
  const hash = useLocationHash()
  useEffect(() => {
    const store = pageStateStore()
    const epoch = store.getEpoch()
    const key = `scroll:${hash || '#/'}`
    const target = store.read(key, { x: 0, y: 0 }, isPosition)
    const landing = document.querySelector<HTMLElement>('.landing')
    const read = (): Position => landing
      ? { x: landing.scrollLeft, y: landing.scrollTop } : { x: window.scrollX, y: window.scrollY }
    const scroll = (position: Position) => (landing ?? window).scrollTo({
      left: position.x, top: position.y, behavior: 'instant',
    })
    const previousRestoration = window.history.scrollRestoration
    window.history.scrollRestoration = 'manual'
    let restoring = true
    let frame = 0

    function save() {
      if (!restoring && window.location.hash === hash) store.write(key, read(), target, isPosition, epoch)
    }
    function restore() {
      if (!restoring) return
      scroll(target)
      const actual = read()
      if (Math.abs(actual.y - target.y) < 2 && Math.abs(actual.x - target.x) < 2) restoring = false
    }
    function resize() {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(restore)
    }
    function stop() { restoring = false }
    function keydown(event: KeyboardEvent) {
      if (['ArrowDown', 'ArrowUp', 'PageDown', 'PageUp', 'Home', 'End', ' '].includes(event.key)) stop()
    }
    const observer = new ResizeObserver(resize)
    observer.observe(landing ?? document.body)
    const main = document.querySelector('main')
    if (!landing && main) observer.observe(main)
    restore()
    window.addEventListener('scroll', save, true)
    window.addEventListener('pagehide', save)
    window.addEventListener('wheel', stop, { passive: true })
    window.addEventListener('touchstart', stop, { passive: true })
    window.addEventListener('pointerdown', stop)
    window.addEventListener('keydown', keydown)
    return () => {
      observer.disconnect()
      cancelAnimationFrame(frame)
      window.history.scrollRestoration = previousRestoration
      window.removeEventListener('scroll', save, true)
      window.removeEventListener('pagehide', save)
      window.removeEventListener('wheel', stop)
      window.removeEventListener('touchstart', stop)
      window.removeEventListener('pointerdown', stop)
      window.removeEventListener('keydown', keydown)
    }
  }, [hash, identity])
}
