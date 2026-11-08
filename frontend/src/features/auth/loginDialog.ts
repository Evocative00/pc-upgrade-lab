import { useSyncExternalStore } from 'react'

let open = false
const listeners = new Set<() => void>()

function setOpen(next: boolean) {
  if (open === next) return
  open = next
  listeners.forEach((listener) => listener())
}

export function openLogin() {
  setOpen(true)
}

export function closeLogin() {
  setOpen(false)
}

function subscribe(listener: () => void) {
  const onNavigate = () => closeLogin()
  listeners.add(listener)
  window.addEventListener('hashchange', onNavigate)
  return () => {
    listeners.delete(listener)
    window.removeEventListener('hashchange', onNavigate)
  }
}

export function useLoginDialog() {
  return useSyncExternalStore(subscribe, () => open)
}
