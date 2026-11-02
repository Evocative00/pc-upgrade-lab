import { supportsFrameEstimate } from './frameEstimate.ts'
import { isRecord } from '../../lib/pageState.ts'

export type Purpose = 'GAME' | 'CREATION' | 'DEVELOPMENT' | 'OFFICE'
export type Workload = 'LIGHT' | 'MEDIUM' | 'HEAVY'
export type Resolution = 'FHD' | 'QHD' | '4K'
// 영상 편집 해상도(Premiere Pro 예상 성능에 쓴다).
export type VideoResolution = 'FHD' | '4K' | '8K'
export type TargetFps = 30 | 60 | 120 | 144 | 240
export const TARGET_FPS_OPTIONS: TargetFps[] = [30, 60, 120, 144, 240]

export type UpgradeGoal = {
  budgetWon: number
  purpose: Purpose | null
  games: string[]
  resolution: Resolution
  targetFps: TargetFps
  videoResolution: VideoResolution
  programs: string[]
  workload: Workload
}

export const PURPOSES: { value: Purpose; label: string }[] = [
  { value: 'GAME', label: '게임' },
  { value: 'CREATION', label: '영상·그래픽 작업' },
  { value: 'DEVELOPMENT', label: '개발' },
  { value: 'OFFICE', label: '사무·학업' },
]

export const PROGRAMS: Record<Exclude<Purpose, 'GAME'>, string[]> = {
  CREATION: ['Premiere Pro', 'DaVinci Resolve', 'Blender'],
  DEVELOPMENT: ['VS Code', 'Android Studio', 'Unity', 'Docker·가상 머신'],
  OFFICE: ['문서·스프레드시트', '브라우저 탭', '화상 회의'],
}

export const GAME_SUGGESTIONS = ['리그 오브 레전드', '발로란트', '배틀그라운드', '오버워치 2', '사이버펑크 2077']

export const defaultGoal = (): UpgradeGoal => ({
  budgetWon: 800_000,
  purpose: null,
  games: [],
  resolution: 'FHD',
  targetFps: 60,
  videoResolution: '4K',
  programs: [],
  workload: 'MEDIUM',
})

export function isUpgradeGoal(value: unknown): value is UpgradeGoal {
  if (!isRecord(value)) return false
  const choices = (items: unknown) => Array.isArray(items) && items.length <= 3 &&
    items.every((item: unknown) => typeof item === 'string' && item.trim().length > 0 && item.length <= 40)
  return Number.isInteger(value.budgetWon) && Number(value.budgetWon) >= 100_000 && Number(value.budgetWon) <= 2_000_000 &&
    (value.purpose === null || PURPOSES.some((purpose) => purpose.value === value.purpose)) &&
    choices(value.games) && choices(value.programs) && ['FHD', 'QHD', '4K'].includes(String(value.resolution)) &&
    TARGET_FPS_OPTIONS.some((fps) => fps === value.targetFps) && ['LIGHT', 'MEDIUM', 'HEAVY'].includes(String(value.workload)) &&
    // 편집 해상도는 나중에 추가된 값이라, 예전에 저장된 목표에는 없을 수 있다. 없으면 withGoalDefaults가 채운다.
    (value.videoResolution === undefined || ['FHD', '4K', '8K'].includes(String(value.videoResolution)))
}

// 저장된 목표에 나중에 추가된 항목이 없으면 기본값으로 채운다.
export function withGoalDefaults(goal: UpgradeGoal): UpgradeGoal {
  return { ...defaultGoal(), ...goal, videoResolution: goal.videoResolution ?? defaultGoal().videoResolution }
}

export function changePurpose(goal: UpgradeGoal, purpose: Purpose): UpgradeGoal {
  return { ...goal, purpose, games: [], programs: [], workload: 'MEDIUM' }
}

export function toggleChoice(values: string[], value: string, max = 3): string[] {
  const normalized = value.trim()
  if (!normalized || normalized.length > 40) return values
  const existing = values.find((item) => item.toLocaleLowerCase() === normalized.toLocaleLowerCase())
  if (existing) return values.filter((item) => item !== existing)
  return values.length < max ? [...values, normalized] : values
}

export function goalIsReady(goal: UpgradeGoal): boolean {
  return goal.purpose !== null && Number.isInteger(goal.budgetWon) &&
    goal.budgetWon >= 100_000 && goal.budgetWon <= 2_000_000 &&
    (goal.purpose === 'GAME' ? goal.games.length > 0 : goal.programs.length > 0)
}

// 예상 프레임 기준점이 있는 게임만 해상도·프레임 목표를 계산에 쓴다.
export function frameEstimateGames(games: string[]): string[] {
  return games.filter(supportsFrameEstimate)
}

export function goalSummary(goal: UpgradeGoal): string {
  const purpose = PURPOSES.find((item) => item.value === goal.purpose)?.label ?? '용도 미선택'
  if (goal.purpose === 'GAME') {
    const frame = frameEstimateGames(goal.games).length > 0 ? ` · ${goal.resolution} · ${goal.targetFps}fps 목표` : ''
    return `${purpose} · ${goal.games.join(', ') || '게임 미선택'}${frame}`
  }
  const premiere = goal.purpose === 'CREATION' && goal.programs.includes('Premiere Pro')
    ? ` · Premiere ${goal.videoResolution === 'FHD' ? '1080p' : goal.videoResolution}` : ''
  return `${purpose} · ${goal.programs.join(', ') || '프로그램 미선택'} · ${
    { LIGHT: '가벼운 작업', MEDIUM: '보통 작업', HEAVY: '무거운 작업' }[goal.workload]}${premiere}`
}
