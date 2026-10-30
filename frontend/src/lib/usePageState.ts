import { useState, useSyncExternalStore, type Dispatch, type SetStateAction } from 'react'
import { pageStateStore, type InitialState } from './pageState.ts'

export function usePageState<T>(key: string, initial: InitialState<T>, valid: (value: unknown) => value is T):
  [T, Dispatch<SetStateAction<T>>] {
  const store = pageStateStore()
  const [epoch] = useState(() => store.getEpoch())
  const [generation] = useState(() => store.getGeneration(key))
  const [fallback] = useState(initial)
  const value = useSyncExternalStore(store.subscribe, () => store.read(key, fallback, valid, epoch, generation))
  return [value, (update) => store.write(key, update, fallback, valid, epoch, generation)]
}
