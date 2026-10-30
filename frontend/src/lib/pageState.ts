type StateStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
type Validator<T> = (value: unknown) => value is T
export type InitialState<T> = T | (() => T)
export type StateUpdate<T> = T | ((current: T) => T)

const KEY = 'pc-upgrade-lab:page-state:v1'

function defaultStorage(): StateStorage | null {
  try { return globalThis.sessionStorage ?? null } catch { return null }
}

// NaN은 입력 도중 비워 둔 수량이나 잘못된 용량에도 쓰인다. null(미확인)로 바꾸지 않는다.
function encode(_key: string, value: unknown) {
  return typeof value === 'number' && !Number.isFinite(value)
    ? { $pageNumber: String(value) } : value
}

function decode(_key: string, value: unknown) {
  if (isRecord(value) && Object.keys(value).length === 1 && '$pageNumber' in value) {
    if (value.$pageNumber === 'NaN') return NaN
    if (value.$pageNumber === 'Infinity') return Infinity
    if (value.$pageNumber === '-Infinity') return -Infinity
  }
  return value
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export const isString = (value: unknown): value is string => typeof value === 'string'
export const isBoolean = (value: unknown): value is boolean => typeof value === 'boolean'
export const isPageNumber = (value: unknown): value is number =>
  Number.isSafeInteger(value) && Number(value) >= 0

// 서버 응답·토큰·진행 중 요청을 넣지 않고 화면에서 편집한 값만 저장한다.
export function createPageStateStore(storage: StateStorage | null = defaultStorage()) {
  const listeners = new Set<() => void>()
  const values = new Map<string, unknown>()
  const invalidations = new Map<string, number>()
  let epoch = 0
  let owner = 'guest'
  try {
    const saved: unknown = JSON.parse(storage?.getItem(KEY) ?? 'null', decode)
    if (isRecord(saved) && typeof saved.owner === 'string' && isRecord(saved.values)) {
      owner = saved.owner
      Object.entries(saved.values).forEach(([key, value]) => values.set(key, value))
    }
  } catch { /* 손상되거나 막힌 저장소는 빈 상태로 시작한다. */ }

  function persist() {
    try {
      // 회원 구분과 값은 한 번에 저장해 쓰기 실패 때 소유자만 바뀌는 일을 막는다.
      storage?.setItem(KEY, JSON.stringify({ owner, values: Object.fromEntries(values) }, encode))
    } catch {
      // 이전 값으로 잘못 복구하지 않게 오래된 저장본을 지우고 메모리로 이어 간다.
      try { storage?.removeItem(KEY) } catch { /* 저장소 자체가 차단됨 */ }
    }
  }

  function notify() { listeners.forEach((listener) => listener()) }
  function getGeneration(key: string) {
    return [...invalidations].filter(([prefix]) => key.startsWith(prefix))
      .map(([prefix, count]) => `${prefix}=${count}`).join('|')
  }
  function capturePrefix(prefix: string) {
    return `${epoch}:${getGeneration(prefix)}:${JSON.stringify([...values].filter(([key]) => key.startsWith(prefix)), encode)}`
  }
  function clearPrefix(prefix: string) {
    invalidations.set(prefix, (invalidations.get(prefix) ?? 0) + 1)
    for (const key of values.keys()) if (key.startsWith(prefix)) values.delete(key)
    persist()
    // 호출 화면은 초기화 시 재마운트하거나 저장·삭제 후 이동한다.
  }

  return {
    has: (key: string) => values.has(key),
    read<T>(key: string, initial: InitialState<T>, valid: Validator<T>, expectedEpoch = epoch, generation = getGeneration(key)): T {
      if (expectedEpoch !== epoch || generation !== getGeneration(key)) {
        return typeof initial === 'function' ? (initial as () => T)() : initial
      }
      const cached = values.get(key)
      if (valid(cached)) return cached
      const fallback = typeof initial === 'function' ? (initial as () => T)() : initial
      values.set(key, fallback)
      return fallback
    },
    write<T>(key: string, update: StateUpdate<T>, initial: InitialState<T>, valid: Validator<T>, expectedEpoch = epoch, generation = getGeneration(key)) {
      // 이전 회원 화면의 늦은 응답·이벤트가 새 회원의 임시 상태를 다시 만들지 않는다.
      if (expectedEpoch !== epoch || generation !== getGeneration(key)) return
      const current = this.read(key, initial, valid)
      const next = typeof update === 'function' ? (update as (current: T) => T)(current) : update
      values.set(key, next)
      persist()
      notify()
    },
    capturePrefix,
    clearPrefix,
    clearPrefixIfUnchanged(prefix: string, snapshot: string) {
      if (capturePrefix(prefix) === snapshot) clearPrefix(prefix)
    },
    syncOwner(next: string) {
      if (owner === next) return
      // 비회원이 로그인할 때는 작성 중인 구성을 이어 간다. 회원 이탈·전환은 비운다.
      if (owner !== 'guest') {
        epoch++
        values.clear()
      }
      owner = next
      persist()
      notify()
    },
    getEpoch: () => epoch,
    getOwner: () => owner,
    getGeneration,
    subscribe(listener: () => void) {
      listeners.add(listener)
      return () => { listeners.delete(listener) }
    },
  }
}

// 첫 접근 시 생성하므로 인증과 화면이 같은 브라우저 저장소를 공유한다.
let store: ReturnType<typeof createPageStateStore> | undefined
export function pageStateStore() { return store ??= createPageStateStore() }
export const pcStateKey = (id: number | null) => `pc:${id ?? 'new'}:`
