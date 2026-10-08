import { useEffect, useRef, useState, type ReactNode, type RefObject } from 'react'
import { advanceProgress, clampProgress, frameAtProgress, FrameCache, parseAnimationManifest, poseWindow, type AnimationManifest, type VariantName } from './landingAnimation.ts'
import './pc-assembly-scroll.css'

const assetBase = '/pc-assembly/'
type Props = { scrollRoot: RefObject<HTMLDivElement | null>; children: ReactNode }

export function PcAssemblyScroll({ scrollRoot, children }: Props) {
  const sectionRef = useRef<HTMLElement>(null), stageRef = useRef<HTMLDivElement>(null), canvasRef = useRef<HTMLCanvasElement>(null)
  const [manifest, setManifest] = useState<AnimationManifest | null>(null)
  const [variant, setVariant] = useState<VariantName>(() => window.innerWidth <= 900 ? 'mobile' : 'desktop')
  const [reduced, setReduced] = useState(() => window.matchMedia('(prefers-reduced-motion: reduce)').matches)
  const [motionOptIn, setMotionOptIn] = useState(false)
  const motionPaused = reduced && !motionOptIn

  useEffect(() => {
    const controller = new AbortController()
    fetch(`${assetBase}manifest.json`, { signal: controller.signal }).then(response => response.ok ? response.json() : null)
      .then(value => { if (!controller.signal.aborted) setManifest(parseAnimationManifest(value)) }).catch(() => {})
    return () => controller.abort()
  }, [])

  useEffect(() => {
    const preference = window.matchMedia('(prefers-reduced-motion: reduce)')
    const change = () => { setReduced(preference.matches); setMotionOptIn(false) }
    preference.addEventListener('change', change)
    return () => preference.removeEventListener('change', change)
  }, [])

  useEffect(() => {
    const root = scrollRoot.current, section = sectionRef.current, stage = stageRef.current, canvas = canvasRef.current
    if (!root || !section || !stage || !canvas) return
    const resize = () => {
      const navigation = root.querySelector<HTMLElement>('.landing__tabs')
      const offset = root.clientWidth <= 640 ? navigation?.getBoundingClientRect().height ?? 0 : 0
      section.style.setProperty('--assembly-top', `${offset}px`)
      setVariant(root.clientWidth <= 900 ? 'mobile' : 'desktop')
    }
    const observer = new ResizeObserver(resize)
    observer.observe(root)
    const navigation = root.querySelector('.landing__tabs')
    if (navigation) observer.observe(navigation)
    resize()
    return () => observer.disconnect()
  }, [scrollRoot])

  useEffect(() => {
    const root = scrollRoot.current, section = sectionRef.current, stage = stageRef.current, canvas = canvasRef.current
    if (!manifest || !root || !section || !stage || !canvas || motionPaused) return
    const context = canvas.getContext('2d', { alpha: false })
    if (!context) return
    const specification = manifest.variants[variant], limit = variant === 'desktop' ? 24 : 12
    const cache = new FrameCache<ImageBitmap>(limit, image => image.close())
    const jobs = new Map<string, AbortController>(), failures = new Map<string, number>()
    let disposed = false, visible = false, raf = 0, previousTime = 0, progress = 0, target = 0, desired = 0, drawn = -1, direction = 1
    let windowIndexes: number[] = []
    stage.dataset.ready = 'false'
    const measure = () => {
      const rootRect = root.getBoundingClientRect(), sectionRect = section.getBoundingClientRect()
      const top = Number.parseFloat(section.style.getPropertyValue('--assembly-top')) || 0
      target = clampProgress((rootRect.top + top - sectionRect.top) / Math.max(1, sectionRect.height - stage.clientHeight))
      stage.dataset.progress = target.toFixed(5)
    }
    const active = () => visible && !document.hidden && !disposed
    const wake = () => { if (active() && !raf) raf = requestAnimationFrame(tick) }
    const pump = () => {
      if (!active()) return
      const allowed = new Set(windowIndexes.map(index => manifest.poses[index].id))
      for (const [id, controller] of jobs) if (!allowed.has(id)) controller.abort()
      for (const index of windowIndexes) {
        const pose = manifest.poses[index]
        if (jobs.size >= 2) break
        if (cache.has(pose.id) || jobs.has(pose.id) || (failures.get(pose.id) ?? 0) > performance.now()) continue
        const controller = new AbortController(); jobs.set(pose.id, controller)
        void fetch(assetBase + pose[variant], { signal: controller.signal }).then(async response => {
          if (!response.ok) throw new Error('Image unavailable')
          const blob = await response.blob()
          if (controller.signal.aborted || disposed) return
          const bitmap = await createImageBitmap(blob)
          if (controller.signal.aborted || disposed) { bitmap.close(); return }
          if (bitmap.width !== specification.width || bitmap.height !== specification.height) { bitmap.close(); throw new Error('Image size mismatch') }
          cache.set(pose.id, bitmap)
          stage.dataset.decodedCacheCount = String(cache.size)
          stage.dataset.decodedCacheBytes = String(cache.size * specification.width * specification.height * 4)
          wake()
        }).catch(() => { if (!controller.signal.aborted && !disposed) failures.set(pose.id, performance.now() + 5000) })
          .finally(() => { jobs.delete(pose.id); if (!disposed) { wake(); pump() } })
      }
    }
    function tick(timestamp: number) {
      raf = 0
      if (!active()) return
      measure()
      progress = Math.abs(progress - target) > 0.18 ? target : advanceProgress(progress, target, previousTime ? timestamp - previousTime : 16.67)
      previousTime = timestamp
      const next = frameAtProgress(progress, manifest!.frameCount)
      if (next !== desired) direction = next > desired ? 1 : -1
      desired = next
      windowIndexes = poseWindow(manifest!.frames, desired, limit - 1, direction)
      stage!.dataset.targetFrame = String(frameAtProgress(target, manifest!.frameCount))
      stage!.dataset.scrubProgress = progress.toFixed(5)
      const bitmap = cache.get(manifest!.poses[manifest!.frames[desired]].id)
      if (bitmap && drawn !== desired) {
        const begin = performance.now(), scale = Math.min(canvas!.width / bitmap.width, canvas!.height / bitmap.height)
        const width = Math.round(bitmap.width * scale), height = Math.round(bitmap.height * scale)
        context!.fillStyle = '#101214'; context!.fillRect(0, 0, canvas!.width, canvas!.height)
        context!.drawImage(bitmap, Math.round((canvas!.width - width) / 2), Math.round((canvas!.height - height) / 2), width, height)
        drawn = desired
        stage!.dataset.frame = String(desired); stage!.dataset.ready = 'true'; stage!.dataset.renderMs = (performance.now() - begin).toFixed(2)
      }
      pump()
      if (Math.abs(progress - target) > 0.00001) wake()
    }
    const resume = () => {
      if (!active()) return
      measure(); progress = target; previousTime = 0; wake()
    }
    const pause = () => {
      cancelAnimationFrame(raf); raf = 0; previousTime = 0; jobs.forEach(controller => controller.abort()); cache.clear()
      stage.dataset.decodedCacheCount = '0'; stage.dataset.decodedCacheBytes = '0'
    }
    const onVisibility = () => { if (document.hidden) pause(); else resume() }
    const onScroll = () => wake()
    const onResize = () => {
      const rectangle = canvas.getBoundingClientRect()
      const scale = Math.min(window.devicePixelRatio || 1, 2, specification.width / Math.max(1, rectangle.width), specification.height / Math.max(1, rectangle.height))
      const width = Math.max(1, Math.round(rectangle.width * scale)), height = Math.max(1, Math.round(rectangle.height * scale))
      if (canvas.width !== width || canvas.height !== height) {
        stage.dataset.ready = 'false'
        canvas.width = width; canvas.height = height; drawn = -1
      }
      wake()
    }
    const observer = new IntersectionObserver(entries => {
      visible = entries.some(entry => entry.isIntersecting)
      if (visible) resume(); else pause()
    }, { root, rootMargin: '0px', threshold: 0 })
    const resize = new ResizeObserver(onResize); resize.observe(canvas)
    observer.observe(section)
    root.addEventListener('scroll', onScroll, { passive: true }); document.addEventListener('visibilitychange', onVisibility)
    onResize()
    return () => {
      disposed = true; pause(); observer.disconnect(); resize.disconnect(); cache.clear(); jobs.clear()
      root.removeEventListener('scroll', onScroll); document.removeEventListener('visibilitychange', onVisibility)
      stage.dataset.decodedCacheCount = '0'; stage.dataset.decodedCacheBytes = '0'; stage.dataset.ready = 'false'
    }
  }, [manifest, motionPaused, scrollRoot, variant])

  const poster = manifest?.variants[variant].poster ?? `poster-${variant}.webp`
  return (
    <>
      <section className={`assembly-scroll${motionPaused ? ' assembly-scroll--still' : ''}${reduced && motionOptIn ? ' assembly-scroll--motion-enabled' : ''}`} ref={sectionRef}>
        <div className="assembly-stage" ref={stageRef} data-ready="false">
          <div className="assembly-copy">{children}{reduced && <button className="assembly-motion-toggle" type="button" onClick={() => setMotionOptIn(value => !value)}>{motionOptIn ? '정지 장면으로 보기' : '조립 애니메이션 보기'}</button>}</div>
          <div className="assembly-visual"><img className="assembly-poster" src={assetBase + poster} alt="블랙과 실버의 유리 PC 케이스와 은은한 아이스 블루 조명" fetchPriority="high" onError={event => { event.currentTarget.style.opacity = '0' }} onLoad={event => { event.currentTarget.style.opacity = '' }} /><canvas ref={canvasRef} aria-hidden="true" /></div>
          <span className="assembly-scroll-hint" aria-hidden="true">스크롤하며 살펴보세요 <span>↓</span></span>
        </div>
      </section>
      <section className="assembly-details" aria-labelledby="assembly-details-title"><h2 id="assembly-details-title">디테일까지 살펴보세요</h2><div className="assembly-details__grid">{[['motherboard', '메인보드'], ['cooler', '쿨러']].map(([name, label]) => <figure key={name}><img src={`${assetBase}${name}-detail-v4.webp`} alt={`${label} 구조를 가까이 보여 주는 장면`} width="1600" height="1200" loading="lazy" decoding="async" onError={event => { event.currentTarget.style.opacity = '0' }} onLoad={event => { event.currentTarget.style.opacity = '' }} /><figcaption>{label}</figcaption></figure>)}</div></section>
      {manifest !== null && manifest.references.length > 0 && <details className="landing__references"><summary>형태 참고</summary><p>실제 제품의 외형을 참고해 자체 제작한 3D 장면입니다.</p><ul>{manifest.references.map(reference => <li key={reference.url}><a href={reference.url} target="_blank" rel="noopener noreferrer">{reference.product}</a><span> · {reference.feature}</span></li>)}</ul></details>}
    </>
  )
}
