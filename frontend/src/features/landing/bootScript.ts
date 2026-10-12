// 첫 진입 로딩 화면(BootScreen)의 cmd 출력 순서와 시간. 화면과 분리해 테스트한다.
export type BootLine = {
  tag: string
  text: string
  fill: string
  empty: string
  post: string
}

type ScriptStep =
  | { kind: 'out'; text: string; tag?: string; fast?: boolean }
  | { kind: 'type'; prefix: string; text: string; speed: number }
  | { kind: 'progress' }

// 기능 소개를 cmd 출력처럼 연출한다. 실제 서버 연결이나 스캔 완료 상태를 표시하지 않는다.
const script: ScriptStep[] = [
  { kind: 'out', text: 'PC Upgrade Lab [Version 1.0]', fast: true },
  { kind: 'out', text: '(c) PC 업그레이드 실험실. 로그인 없이 바로 시작할 수 있습니다.', fast: true },
  { kind: 'out', text: '', fast: true },
  { kind: 'type', prefix: 'C:\\pc-upgrade-lab>', text: 'pc-upgrade-lab --start', speed: 28 },
  { kind: 'out', text: '[INFO] PC 업그레이드 실험실의 기능을 소개합니다.' },
  { kind: 'out', text: '[LOAD] catalog: CPU / GPU / MB / RAM / SSD / PSU / CASE' },
  { kind: 'out', tag: '[ OK ]', text: ' compatibility engine ready  // CPU·메인보드·RAM·GPU·파워·케이스 검사' },
  { kind: 'out', tag: '[ OK ]', text: ' price information           // 국내 현재 상품가·참고가 구분' },
  { kind: 'out', tag: '[ OK ]', text: ' Windows PC scan             // 내 PC 사양 자동 인식' },
  { kind: 'progress' },
  { kind: 'out', text: '' },
  { kind: 'type', prefix: '', text: '> 나만의 PC를, 더 선명하게.', speed: 40 },
  { kind: 'type', prefix: '', text: '> 부품 하나부터 다음 업그레이드까지.', speed: 40 },
  { kind: 'out', text: '', fast: true },
  { kind: 'out', text: '', fast: true },
  { kind: 'out', text: '', fast: true },
]

export const progressCells = 14
// 출력이 끝난 뒤 랜딩으로 넘어가기까지 기다리는 초.
export const bootCountdownSeconds = 5

const line = (text: string, tag = ''): BootLine => ({ tag, text, fill: '', empty: '', post: '' })

// 빈 칸도 █로 그리고 색만 바꾼다. ░는 글꼴에 따라 폭이 달라 바 길이가 틀어진다.
export function progressLine(filled: number): BootLine {
  const n = Math.max(0, Math.min(progressCells, Math.floor(filled)))
  return {
    tag: '',
    text: '[LOAD] assembling 3D scene... [',
    fill: '█'.repeat(n),
    empty: '█'.repeat(progressCells - n),
    post: `] ${Math.round((n / progressCells) * 100)}%`,
  }
}

// 모션 감소 설정에서 한 번에 보여 줄 전체 로그.
export function finalBootLines(): BootLine[] {
  return script.map((step) => {
    if (step.kind === 'progress') return progressLine(progressCells)
    if (step.kind === 'type') return line(step.prefix + step.text)
    return line(step.text, step.tag)
  })
}

export type BootFrame = { delay: number; lines: BootLine[] }

// 각 화면 상태와 그 직전 대기 시간(ms). 마지막 상태 뒤 finishDelay가 지나면 출력이 끝난다.
export function bootTimeline(): { frames: BootFrame[]; finishDelay: number } {
  const frames: BootFrame[] = []
  const lines: BootLine[] = []
  let wait = 200
  const emit = () => {
    frames.push({ delay: wait, lines: lines.map((item) => ({ ...item })) })
  }

  for (const step of script) {
    if (step.kind === 'out') {
      lines.push(line(step.text, step.tag))
      emit()
      wait = step.fast ? 40 : 220
    } else if (step.kind === 'type') {
      lines.push(line(step.prefix))
      emit()
      wait = 300
      for (let n = 1; n <= step.text.length; n++) {
        lines[lines.length - 1] = line(step.prefix + step.text.slice(0, n))
        emit()
        wait = step.speed
      }
      wait = 260
    } else {
      lines.push(progressLine(0))
      emit()
      for (let n = 1; n <= progressCells; n++) {
        wait = 100
        lines[lines.length - 1] = progressLine(n)
        emit()
      }
      wait = 220
    }
  }

  return { frames, finishDelay: 340 }
}

// 로딩은 세션마다 한 번만 보여 준다. 저장소를 쓸 수 없으면 매번 보여 주는 쪽으로 동작한다.
const seenKey = 'pc-upgrade-lab.boot-seen'

type SessionStore = Pick<Storage, 'getItem' | 'setItem'>

function sessionStore(): SessionStore | null {
  try {
    return globalThis.sessionStorage ?? null
  } catch {
    return null
  }
}

export function hasSeenBoot(storage: SessionStore | null = sessionStore()): boolean {
  try {
    return storage?.getItem(seenKey) === '1'
  } catch {
    return false
  }
}

export function markBootSeen(storage: SessionStore | null = sessionStore()) {
  try {
    storage?.setItem(seenKey, '1')
  } catch {
    // 저장하지 못해도 화면 전환은 계속한다.
  }
}
