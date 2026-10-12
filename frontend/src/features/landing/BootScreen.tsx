import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { bootCountdownSeconds, bootTimeline, finalBootLines, type BootLine } from './bootScript.ts'
import './boot.css'

type Props = {
  // Enter·클릭·SKIP·카운트다운 종료 중 먼저 일어난 한 번만 호출된다.
  onDone: () => void
}

const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

// 기다리는 동안 랜딩 첫 화면 이미지를 미리 받아 둔다.
const heroImage = '/pc-assembly/poster-desktop.webp'

// 첫 진입 때 cmd처럼 서비스 소개를 출력한 뒤 랜딩으로 넘어가는 화면.
export function BootScreen({ onDone }: Props) {
  const [reduced] = useState(reducedMotion)
  const [lines, setLines] = useState<BootLine[]>(() => (reduced ? finalBootLines() : []))
  const [done, setDone] = useState(reduced)
  const [count, setCount] = useState(bootCountdownSeconds)
  const logRef = useRef<HTMLElement>(null)
  const enterRef = useRef<HTMLButtonElement>(null)
  const finished = useRef(false)
  const onDoneRef = useRef(onDone)

  useEffect(() => {
    onDoneRef.current = onDone
  }, [onDone])

  const finish = useCallback(() => {
    if (finished.current) return
    finished.current = true
    onDoneRef.current()
  }, [])

  useEffect(() => {
    const image = new Image()
    image.src = heroImage
  }, [])

  // 로그 출력. 모션 감소 설정이면 처음부터 전체 로그를 보여 준다.
  useEffect(() => {
    if (reduced) return
    const { frames, finishDelay } = bootTimeline()
    const timers: number[] = []
    let at = 0
    for (const frame of frames) {
      at += frame.delay
      timers.push(window.setTimeout(() => setLines(frame.lines), at))
    }
    timers.push(window.setTimeout(() => setDone(true), at + finishDelay))
    return () => timers.forEach((timer) => window.clearTimeout(timer))
  }, [reduced])

  // 출력이 끝나면 5→1 카운트다운 뒤 넘어간다.
  useEffect(() => {
    if (!done) return
    let remaining = bootCountdownSeconds
    const timer = window.setInterval(() => {
      remaining -= 1
      if (remaining <= 0) {
        window.clearInterval(timer)
        finish()
        return
      }
      setCount(remaining)
    }, 1000)
    return () => window.clearInterval(timer)
  }, [done, finish])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Enter') return
      event.preventDefault()
      finish()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [finish])

  // 줄이 화면을 넘으면 맨 아래로 바로 이동한다.
  useLayoutEffect(() => {
    const log = logRef.current
    if (log) log.scrollTop = log.scrollHeight
  }, [lines, done])

  useEffect(() => {
    if (done) enterRef.current?.focus({ preventScroll: true })
  }, [done])

  return (
    <div className="boot" onClick={finish}>
      <button className="boot__skip" type="button" onClick={finish}>[SKIP]</button>
      <main className="boot__log" ref={logRef} aria-busy={!done}>
        <h1 className="visually-hidden">PC 업그레이드 실험실 시작 화면</h1>
        {lines.map((item, index) => (
          <div className="boot__line" key={index}>
            {item.tag !== '' && <span className="boot__tag">{item.tag}</span>}
            {item.text}
            {item.fill !== '' && <span>{item.fill}</span>}
            {item.empty !== '' && <span className="boot__bar-empty">{item.empty}</span>}
            {item.post}
            {!done && index === lines.length - 1 && <span className="boot__cursor" aria-hidden="true" />}
          </div>
        ))}
        {done && (
          <>
            <div className="boot__line">
              <button className="boot__enter" type="button" ref={enterRef} onClick={finish}>
                PRESS ENTER ▸ or wait 5 sec... <span aria-live="polite">{count}</span>
                <span className="boot__cursor boot__cursor--enter" aria-hidden="true" />
              </button>
            </div>
            <div className="boot__line">(or click)</div>
          </>
        )}
      </main>
    </div>
  )
}
