import type { PartInput, PartType } from '../pc-scan/types.ts'
import { isSpecificHardwareModel, normalizeHardwareModel } from './benchmarkComparison.ts'
import type { GameRequirements, RequirementTier } from './gameRequirements.ts'

export type Verdict = 'PASS' | 'FAIL' | 'UNKNOWN'
export type Check = { label: string; required: string; actual: string; verdict: Verdict; reason: string }
export type TierDiagnosis = { verdict: Verdict; checks: Check[] }
const GIB = 1024 ** 3

function partsOf(parts: PartInput[], type: PartType): PartInput[] {
  return parts.filter((part) => part.type === type && part.displayName.trim() !== '' && part.quantity > 0)
}

function modelCheck(label: string, available: PartInput[], examples: string[]): Check {
  const actual = available.map((part) => part.displayName).join(', ') || '미입력'
  const matches = available.some((part) => examples.some((example) =>
    isSpecificHardwareModel(example) && normalizeHardwareModel(part.displayName) === normalizeHardwareModel(example)))
  return { label, required: examples.join(' / '), actual, verdict: matches ? 'PASS' : 'UNKNOWN',
    reason: matches ? '공식 예시와 동일한 모델명이 확인되었습니다.' : available.length === 0
      ? '부품이 입력되지 않았습니다.'
      : '다른 모델의 성능 우열은 검증된 비교 데이터가 없어 판정하지 않습니다.' }
}

function capacityCheck(label: string, available: PartInput[], requiredGiB: number, key: string,
  aggregate: 'sum' | 'max'): Check {
  const known = available.filter((part) => {
    const value = part.specs[key]
    return typeof value === 'number' && Number.isFinite(value) && value > 0
  })
  const knownBytes = known.map((part) => Number(part.specs[key]) * (aggregate === 'sum' ? part.quantity : 1))
  // RAM 모듈은 합산하지만, 서로 다른 GPU의 VRAM을 합산할 수는 없다.
  const capacityBytes = aggregate === 'sum' ? knownBytes.reduce((sum, value) => sum + value, 0)
    : Math.max(0, ...knownBytes)
  const hasUnknown = known.length !== available.length
  const actual = available.length === 0 ? '미입력' : known.length === 0 ? '용량 미입력'
    : `${(capacityBytes / GIB).toFixed(1)}GiB${hasUnknown ? ' + 용량 미확인 항목' : ''}`
  const verdict: Verdict = capacityBytes >= requiredGiB * GIB ? 'PASS'
    : !hasUnknown && available.length > 0 ? 'FAIL' : 'UNKNOWN'
  const reason = verdict === 'PASS' ? '확인된 용량만으로 기준 이상입니다.'
    : verdict === 'FAIL' ? '입력된 총 용량이 기준보다 적습니다.' : '용량이 입력되지 않은 항목이 있어 단정할 수 없습니다.'
  return { label, required: `${requiredGiB}GiB 이상`, actual, verdict, reason }
}

export function diagnoseTier(parts: PartInput[], requirement: RequirementTier): TierDiagnosis {
  const cpu = partsOf(parts, 'CPU')
  const gpu = partsOf(parts, 'GPU')
  const ram = partsOf(parts, 'RAM')
  const checks = [modelCheck('CPU', cpu, requirement.cpu), modelCheck('GPU', gpu, requirement.gpu),
    capacityCheck('RAM', ram, requirement.ramGiB, 'capacityBytes', 'sum')]
  if (requirement.vramGiB !== undefined) {
    checks.push(capacityCheck('VRAM', gpu, requirement.vramGiB, 'vramBytes', 'max'))
  }
  const verdict: Verdict = checks.some((check) => check.verdict === 'FAIL') ? 'FAIL'
    : checks.every((check) => check.verdict === 'PASS') ? 'PASS' : 'UNKNOWN'
  return { verdict, checks }
}

export function diagnoseGame(parts: PartInput[], game: GameRequirements) {
  return { minimum: diagnoseTier(parts, game.minimum), recommended: diagnoseTier(parts, game.recommended) }
}
