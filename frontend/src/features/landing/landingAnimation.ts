export type VariantName = 'desktop' | 'mobile'
export type AnimationManifest = {
  version: number
  frameCount: number
  variants: Record<VariantName, { width: number; height: number; poster: string }>
  poses: { id: string; desktop: string; mobile: string }[]
  frames: number[]
  references: { product: string; feature: string; url: string }[]
}

const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null
const assetPath = (value: unknown, variant: VariantName): value is string => typeof value === 'string' && new RegExp(`^${variant}/pose-\\d{4}\\.webp$`).test(value)

export function parseAnimationManifest(value: unknown): AnimationManifest | null {
  if (!record(value) || !Number.isSafeInteger(value.version) || !record(value.variants) || !Array.isArray(value.poses) || !Array.isArray(value.frames)) return null
  const variants = {} as AnimationManifest['variants']
  for (const name of ['desktop', 'mobile'] as const) {
    const variant = value.variants[name]
    if (!record(variant) || !Number.isSafeInteger(variant.width) || !Number.isSafeInteger(variant.height)
      || Number(variant.width) < 1 || Number(variant.width) > 4096 || Number(variant.height) < 1 || Number(variant.height) > 4096
      || variant.poster !== `poster-${name}.webp`) return null
    variants[name] = { width: Number(variant.width), height: Number(variant.height), poster: variant.poster }
  }
  const poses: AnimationManifest['poses'] = []
  const ids = new Set<string>()
  for (const pose of value.poses) {
    if (!record(pose) || typeof pose.id !== 'string' || ids.has(pose.id) || !assetPath(pose.desktop, 'desktop') || !assetPath(pose.mobile, 'mobile')) return null
    ids.add(pose.id)
    poses.push({ id: pose.id, desktop: pose.desktop, mobile: pose.mobile })
  }
  if (!poses.length || poses.length > 2000 || !value.frames.length || value.frames.length > 4000
    || value.frameCount !== value.frames.length || value.frames.some(index => !Number.isSafeInteger(index) || index < 0 || index >= poses.length)) return null
  const references: AnimationManifest['references'] = []
  for (const item of Array.isArray(value.references) ? value.references : []) {
    if (!record(item) || typeof item.product !== 'string' || typeof item.feature !== 'string' || typeof item.url !== 'string') continue
    try { if (new URL(item.url).protocol === 'https:') references.push({ product: item.product, feature: item.feature, url: item.url }) } catch { /* Invalid references are omitted. */ }
  }
  return { version: Number(value.version), frameCount: value.frames.length, variants, poses, frames: value.frames, references }
}

export function clampProgress(value: number): number {
  return Number.isFinite(value) ? Math.min(1, Math.max(0, value)) : 0
}

export function frameAtProgress(progress: number, count: number): number {
  return Math.round(clampProgress(progress) * Math.max(0, count - 1))
}

// The time constant is independent of the display's refresh rate.
export function advanceProgress(current: number, target: number, elapsedMs: number): number {
  const from = clampProgress(current), to = clampProgress(target)
  if (Math.abs(to - from) < 0.0001) return to
  return from + (to - from) * (1 - Math.exp(-Math.max(0, Math.min(50, Number.isFinite(elapsedMs) ? elapsedMs : 0)) / 45))
}

export function poseWindow(frames: number[], target: number, count: number, direction = 1): number[] {
  const indexes: number[] = [], seen = new Set<number>()
  const add = (index: number) => {
    if (index >= 0 && index < frames.length && !seen.has(frames[index])) { seen.add(frames[index]); indexes.push(frames[index]) }
  }
  for (let distance = 0; distance < frames.length && indexes.length < count; distance++) {
    add(target + distance * direction)
    if (indexes.length < count) add(target - distance * direction)
  }
  return indexes
}

export class FrameCache<T> {
  private entries = new Map<string, T>()
  private limit: number
  private dispose: (value: T) => void
  constructor(limit: number, dispose: (value: T) => void) { this.limit = Math.max(1, Math.floor(Number.isFinite(limit) ? limit : 1)); this.dispose = dispose }
  get size() { return this.entries.size }
  has(key: string) { return this.entries.has(key) }
  get(key: string): T | undefined {
    const value = this.entries.get(key)
    if (value !== undefined) { this.entries.delete(key); this.entries.set(key, value) }
    return value
  }
  set(key: string, value: T) {
    const previous = this.entries.get(key)
    if (previous !== undefined && previous !== value) this.dispose(previous)
    this.entries.delete(key); this.entries.set(key, value)
    while (this.entries.size > this.limit) {
      const oldest = this.entries.keys().next().value as string
      const evicted = this.entries.get(oldest) as T
      this.entries.delete(oldest); this.dispose(evicted)
    }
  }
  clear() { this.entries.forEach(this.dispose); this.entries.clear() }
}
