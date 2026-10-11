import type { PartInput } from '../pc-scan/types.ts'
import { diagnoseGame } from './gameDiagnosis.ts'
import { GAME_REQUIREMENTS } from './gameRequirements.ts'
import type { ScoredPart } from './internalScores.ts'
import { comparePartScore, type ScoreBasis } from './partScores.ts'

export type PrototypeRecommendation = {
  status: 'FOCUS' | 'UNKNOWN' | 'NO_DEFICIT'
  part: 'CPU' | 'GPU' | 'RAM' | null
  basis: 'OFFICIAL_CAPACITY' | ScoreBasis | null
  reason: string
  games: string[]
}

type Candidate = {
  part: 'CPU' | 'GPU' | 'RAM'
  basis: 'OFFICIAL_CAPACITY' | ScoreBasis
  gap: number
  games: Set<string>
}

/** 제품 선정이 아닌 단일 점검 우선순위. 예산·FPS·교체 후 호환성은 계산하지 않는다. */
export function recommendPrototypeFocus(parts: PartInput[], selectedGames: string[]): PrototypeRecommendation {
  const candidates = new Map<string, Candidate>()
  let hasUnknown = selectedGames.length === 0

  function add(part: Candidate['part'], basis: Candidate['basis'], game: string, gap = 0) {
    const key = `${part}:${basis}`
    const candidate = candidates.get(key) ?? { part, basis, gap: 0, games: new Set<string>() }
    candidate.games.add(game)
    candidate.gap = Math.max(candidate.gap, gap)
    candidates.set(key, candidate)
  }

  for (const name of selectedGames) {
    const game = Object.hasOwn(GAME_REQUIREMENTS, name) ? GAME_REQUIREMENTS[name] : undefined
    if (!game) { hasUnknown = true; continue }
    const checks = diagnoseGame(parts, game).recommended.checks
    for (const [label, part] of [['RAM', 'RAM'], ['VRAM', 'GPU']] as const) {
      const verdict = checks.find((check) => check.label === label)?.verdict
      if (verdict === 'FAIL') add(part, 'OFFICIAL_CAPACITY', name)
      if (verdict === 'UNKNOWN') hasUnknown = true
    }
    for (const kind of ['CPU', 'GPU'] as ScoredPart[]) {
      const result = comparePartScore(kind, parts, game.recommended[kind.toLowerCase() as 'cpu' | 'gpu'])
      if (result.status === 'BELOW_BASELINE') add(kind, result.basis, name, result.gap)
      if (result.status === 'UNKNOWN') hasUnknown = true
    }
  }

  const order: Record<Candidate['part'], number> = { GPU: 0, RAM: 1, CPU: 2 }
  const first = [...candidates.values()].sort((a, b) =>
    Number(b.basis === 'OFFICIAL_CAPACITY') - Number(a.basis === 'OFFICIAL_CAPACITY') ||
    b.games.size - a.games.size || b.gap - a.gap || order[a.part] - order[b.part])[0]

  if (!first) return hasUnknown
    ? { status: 'UNKNOWN', part: null, basis: null,
      reason: '입력 정보나 점수·등급 자료가 부족해 교체 우선순위를 정할 수 없습니다.', games: [] }
    : { status: 'NO_DEFICIT', part: null, basis: null,
      reason: '확인 가능한 권장 용량, CPU 벤치마크 점수, GPU 등급에서 부족 항목을 찾지 못했습니다. 교체가 불필요하다는 확정 판정은 아닙니다.', games: [] }

  const label = first.part === 'RAM' ? '메모리' : first.part === 'GPU' ? '그래픽카드' : 'CPU'
  const games = [...first.games]
  const reason = first.basis === 'OFFICIAL_CAPACITY'
    ? `${games.join(', ')}의 공식 권장 용량보다 현재 확인된 ${label} 용량이 작아 먼저 점검합니다.`
    : first.basis === 'BENCHMARK_SCORE'
      ? `${games.join(', ')}의 공식 권장 예시와 Blender CPU 벤치마크 점수로 비교했을 때 ${label} 점수가 낮아 먼저 점검합니다.`
      : `${games.join(', ')}의 공식 권장 예시를 팀 내부 등급으로 바꿔 비교했을 때 ${label} 등급이 낮아 먼저 점검합니다.`
  return { status: 'FOCUS', part: first.part, basis: first.basis, reason, games }
}
