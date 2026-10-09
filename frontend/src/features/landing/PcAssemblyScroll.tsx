import { useEffect, useRef, useState, type ReactNode, type RefObject } from 'react'
import { advanceProgress, anchorPoseIndexes, clampProgress, desktopFramePlan, frameAtProgress, FrameCache, parseAnimationManifest, poseWindow, readyFrame, type AnimationManifest, type DesktopFramePlan } from './landingAnimation.ts'
import './pc-assembly-scroll.css'

const assetBase = '/pc-assembly/'
type Props = { scrollRoot: RefObject<HTMLDivElement | null>; children: ReactNode }

export function PcAssemblyScroll({ scrollRoot, children }: Props) {
  const sectionRef = useRef<HTMLElement>(null), stageRef = useRef<HTMLDivElement>(null), canvasRef = useRef<HTMLCanvasElement>(null)
  const [manifest, setManifest] = useState<AnimationManifest | null>(null)
  const [renderPlan, setRenderPlan] = useState<DesktopFramePlan | null>(null)
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
      root.style.setProperty('--landing-navigation-height', `${offset}px`)
      section.style.setProperty('--assembly-top', `${offset}px`)
    }
    const observer = new ResizeObserver(resize)
    observer.observe(root)
    const navigation = root.querySelector('.landing__tabs')
    if (navigation) observer.observe(navigation)
    resize()
    return () => observer.disconnect()
  }, [scrollRoot])

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas || !manifest) return
    const specification = manifest.variants.desktop
    const resize = () => {
      const rectangle = canvas.getBoundingClientRect()
      const next = desktopFramePlan(rectangle.width, rectangle.height, window.devicePixelRatio || 1, specification.width, specification.height)
      setRenderPlan(previous => previous && previous.width === next.width && previous.height === next.height
        && previous.pixelRatio === next.pixelRatio && previous.nearbyLimit === next.nearbyLimit && previous.anchorLimit === next.anchorLimit ? previous : next)
    }
    let density = window.matchMedia(`(resolution: ${window.devicePixelRatio || 1}dppx)`)
    const densityChange = () => {
      density.removeEventListener('change', densityChange)
      density = window.matchMedia(`(resolution: ${window.devicePixelRatio || 1}dppx)`)
      density.addEventListener('change', densityChange); resize()
    }
    density.addEventListener('change', densityChange)
    const observer = new ResizeObserver(resize); observer.observe(canvas)
    window.addEventListener('resize', resize); resize()
    return () => { observer.disconnect(); window.removeEventListener('resize', resize); density.removeEventListener('change', densityChange) }
  }, [manifest])

  useEffect(() => { if (motionPaused && stageRef.current) stageRef.current.dataset.ready = 'false' }, [motionPaused])

  useEffect(() => {
    const root = scrollRoot.current, section = sectionRef.current, stage = stageRef.current, canvas = canvasRef.current
    if (!manifest || !renderPlan || !root || !section || !stage || !canvas || motionPaused) return
    const context = canvas.getContext('2d', { alpha: false })
    if (!context) return
    const specification = manifest.variants.desktop, limit = renderPlan.nearbyLimit
    const anchorIndexes = anchorPoseIndexes(manifest.poses.length, renderPlan.anchorLimit), anchorSet = new Set(anchorIndexes)
    const cache = new FrameCache<ImageBitmap>(limit, image => image.close())
    const anchors = new FrameCache<ImageBitmap>(anchorIndexes.length, image => image.close())
    const jobs = new Map<string, AbortController>(), anchorJobs = new Map<string, AbortController>(), failures = new Map<string, number>()
    let disposed = false, visible = false, raf = 0, previousTime = 0, progress = 0, target = 0, desired = 0, drawn = -1, direction = 1
    let retryTimer = 0, retryAt = 0
    let drawnBitmap: ImageBitmap | null = null
    let windowIndexes: number[] = []
    stage.dataset.decodedFrameWidth = String(renderPlan.width); stage.dataset.decodedFrameHeight = String(renderPlan.height)
    const reportCache = () => {
      stage.dataset.decodedCacheCount = String(cache.size + anchors.size)
      stage.dataset.anchorCacheCount = String(anchors.size); stage.dataset.nearbyCacheCount = String(cache.size)
      delete stage.dataset.previewCacheCount; delete stage.dataset.highResolutionCacheCount
      stage.dataset.decodedCacheBytes = String((cache.size + anchors.size) * renderPlan.width * renderPlan.height * 4)
    }
    const hasPose = (index: number) => cache.has(manifest.poses[index].id) || anchors.has(manifest.poses[index].id)
    const measure = () => {
      const rootRect = root.getBoundingClientRect(), sectionRect = section.getBoundingClientRect()
      const top = Number.parseFloat(section.style.getPropertyValue('--assembly-top')) || 0
      target = clampProgress((rootRect.top + top - sectionRect.top) / Math.max(1, sectionRect.height - stage.clientHeight))
      stage.dataset.progress = target.toFixed(5)
    }
    const active = () => visible && !document.hidden && !disposed
    const wake = (continuing = false) => {
      if (active() && !raf) {
        if (!continuing) previousTime = 0
        raf = requestAnimationFrame(tick)
      }
    }
    const load = (index: number) => {
      const pose = manifest.poses[index], anchor = anchorSet.has(index), pool = anchor ? anchors : cache, pending = anchor ? anchorJobs : jobs
      const key = pose.id
      if (pending.size >= 2 || pool.has(pose.id) || pending.has(pose.id) || (failures.get(key) ?? 0) > performance.now()) return
      const controller = new AbortController(); pending.set(pose.id, controller)
      void fetch(assetBase + pose.desktop, { signal: controller.signal }).then(async response => {
          if (!response.ok) throw new Error('Image unavailable')
          const blob = await response.blob()
          if (controller.signal.aborted || !active()) return
          const bitmap = await createImageBitmap(blob, { resizeWidth: renderPlan.width, resizeHeight: renderPlan.height, resizeQuality: 'high' })
          if (controller.signal.aborted || !active() || pending.get(pose.id) !== controller) { bitmap.close(); return }
          if (bitmap.width !== renderPlan.width || bitmap.height !== renderPlan.height) { bitmap.close(); throw new Error('Image size mismatch') }
          pool.set(pose.id, bitmap); failures.delete(key); reportCache(); wake()
        }).catch(() => { if (!controller.signal.aborted && !disposed) failures.set(key, performance.now() + 5000) })
          .finally(() => {
            if (pending.get(pose.id) === controller) { pending.delete(pose.id); wake() }
          })
    }
    const pump = () => {
      if (!active()) return
      // Complete in-flight requests; the next rAF always dequeues from the latest position.
      for (const index of windowIndexes) load(index)
      const pose = manifest.frames[desired]
      const closest = anchorIndexes.filter(index => !anchors.has(manifest.poses[index].id) && !anchorJobs.has(manifest.poses[index].id)
        && (failures.get(manifest.poses[index].id) ?? 0) <= performance.now())
        .sort((a, b) => Math.abs(a - pose) - Math.abs(b - pose))[0]
      if (closest !== undefined) load(closest)
      for (const index of anchorIndexes) {
        if (anchorJobs.size >= 2) break
        load(index)
      }
      // Retry a temporarily failed exact image even if the user has stopped scrolling.
      const exact = manifest.poses[manifest.frames[frameAtProgress(target, manifest.frameCount)]]
      const deadline = cache.has(exact.id) || anchors.has(exact.id) ? 0 : failures.get(exact.id) ?? 0
      if (deadline !== retryAt) {
        window.clearTimeout(retryTimer); retryAt = deadline
        retryTimer = deadline > performance.now() ? window.setTimeout(() => { retryAt = 0; retryTimer = 0; wake() }, deadline - performance.now()) : 0
      }
    }
    function tick(timestamp: number) {
      raf = 0
      if (!active()) return
      measure()
      progress = advanceProgress(progress, target, previousTime ? timestamp - previousTime : 16.67)
      previousTime = timestamp
      const next = frameAtProgress(progress, manifest!.frameCount)
      if (next !== desired) direction = next > desired ? 1 : -1
      desired = next
      const targetFrame = frameAtProgress(target, manifest!.frameCount)
      windowIndexes = [...new Set([manifest!.frames[desired], manifest!.frames[targetFrame], ...poseWindow(manifest!.frames, desired, limit - 1, direction)])]
      stage!.dataset.targetFrame = String(frameAtProgress(target, manifest!.frameCount))
      stage!.dataset.scrubProgress = progress.toFixed(5)
      const ready = readyFrame(manifest!.frames, desired, hasPose, drawn, direction)
      const pose = ready === null ? null : manifest!.poses[manifest!.frames[ready]]
      const bitmap = pose === null ? undefined : anchors.get(pose.id) ?? cache.get(pose.id)
      if (bitmap && (drawn !== ready || drawnBitmap !== bitmap)) {
        const begin = performance.now(), scale = Math.min(canvas!.width / bitmap.width, canvas!.height / bitmap.height)
        const width = Math.round(bitmap.width * scale), height = Math.round(bitmap.height * scale)
        context!.fillStyle = '#101214'; context!.fillRect(0, 0, canvas!.width, canvas!.height)
        context!.imageSmoothingEnabled = true; context!.imageSmoothingQuality = 'high'
        context!.drawImage(bitmap, Math.round((canvas!.width - width) / 2), Math.round((canvas!.height - height) / 2), width, height)
        drawn = ready!; drawnBitmap = bitmap
        stage!.dataset.frame = String(drawn); stage!.dataset.quality = 'high'
        stage!.dataset.buffer = anchorSet.has(manifest!.frames[drawn]) ? 'anchor' : 'nearby'
        stage!.dataset.ready = 'true'; stage!.dataset.renderMs = (performance.now() - begin).toFixed(2)
      }
      pump()
      if (Math.abs(progress - target) > 0.00001) wake(true)
    }
    const resume = () => {
      if (!active()) return
      measure(); progress = target; previousTime = 0; drawn = -1; drawnBitmap = null; wake()
    }
    const pause = () => {
      cancelAnimationFrame(raf); raf = 0; previousTime = 0
      window.clearTimeout(retryTimer); retryTimer = 0; retryAt = 0
      for (const pending of [jobs, anchorJobs]) { pending.forEach(controller => controller.abort()); pending.clear() }
      cache.clear(); anchors.clear(); drawnBitmap = null; reportCache()
    }
    const onVisibility = () => { if (document.hidden) pause(); else resume() }
    const onScroll = () => wake()
    const onResize = () => {
      const rectangle = canvas.getBoundingClientRect()
      const plan = desktopFramePlan(rectangle.width, rectangle.height, window.devicePixelRatio || 1, specification.width, specification.height)
      const width = plan.canvasWidth, height = plan.canvasHeight
      if (canvas.width !== width || canvas.height !== height) {
        const previous = drawnBitmap
        stage.dataset.ready = 'false'
        canvas.width = width; canvas.height = height; drawn = -1; drawnBitmap = null
        if (previous && previous.width && previous.height) {
          const scale = Math.min(width / previous.width, height / previous.height)
          const imageWidth = Math.round(previous.width * scale), imageHeight = Math.round(previous.height * scale)
          context.fillStyle = '#101214'; context.fillRect(0, 0, width, height)
          context.imageSmoothingEnabled = true; context.imageSmoothingQuality = 'high'
          context.drawImage(previous, Math.round((width - imageWidth) / 2), Math.round((height - imageHeight) / 2), imageWidth, imageHeight)
          stage.dataset.ready = 'true'
        }
      }
      wake()
    }
    const observer = new IntersectionObserver(entries => {
      visible = entries.some(entry => entry.isIntersecting)
      if (visible) resume(); else pause()
    }, { root, rootMargin: '0px 0px 100% 0px', threshold: 0 })
    const resize = new ResizeObserver(onResize); resize.observe(canvas)
    observer.observe(section)
    root.addEventListener('scroll', onScroll, { passive: true }); document.addEventListener('visibilitychange', onVisibility); window.addEventListener('resize', onResize)
    onResize()
    return () => {
      disposed = true; pause(); observer.disconnect(); resize.disconnect(); cache.clear(); jobs.clear()
      root.removeEventListener('scroll', onScroll); document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('resize', onResize)
      stage.dataset.decodedCacheCount = '0'; stage.dataset.decodedCacheBytes = '0'
    }
  }, [manifest, motionPaused, renderPlan, scrollRoot])

  const poster = manifest?.variants.desktop.poster ?? 'poster-desktop.webp'
  return (
    <>
      <section className={`assembly-scroll${motionPaused ? ' assembly-scroll--still' : ''}${reduced && motionOptIn ? ' assembly-scroll--motion-enabled' : ''}`} ref={sectionRef}>
        <div className="assembly-stage" ref={stageRef} data-ready="false">
          <div className="assembly-copy">{children}{reduced && <button className="assembly-motion-toggle" type="button" onClick={() => setMotionOptIn(value => !value)}>{motionOptIn ? '정지 장면으로 보기' : '조립 애니메이션 보기'}</button>}</div>
          <div className="assembly-visual"><img className="assembly-poster" src={assetBase + poster} alt="블랙과 실버의 유리 PC 케이스와 은은한 아이스 블루 조명" fetchPriority="high" onError={event => { event.currentTarget.style.opacity = '0' }} onLoad={event => { event.currentTarget.style.opacity = '' }} /><canvas ref={canvasRef} aria-hidden="true" /></div>
          <span className="assembly-scroll-hint" aria-hidden="true">스크롤하며 살펴보세요 <span>↓</span></span>
        </div>
      </section>
      {manifest !== null && manifest.references.length > 0 && <details className="landing__references"><summary>형태 참고</summary><p>실제 제품의 외형을 참고해 자체 제작한 3D 장면입니다.</p><ul>{manifest.references.map(reference => <li key={reference.url}><a href={reference.url} target="_blank" rel="noopener noreferrer">{reference.product}</a><span> · {reference.feature}</span></li>)}</ul></details>}
    </>
  )
}
