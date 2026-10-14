import type { PartInput, PartType } from '../pc-scan/types.ts'
import { isSpecificHardwareModel, normalizeHardwareModel } from './benchmarkComparison.ts'
import type { GameRequirements, RequirementTier } from './gameRequirements.ts'
import { comparePartScore, formatRatio } from './partScores.ts'

export type Verdict = 'PASS' | 'FAIL' | 'UNKNOWN'
export type Check = { label: string; required: string; actual: string; verdict: Verdict; reason: string }
export type TierDiagnosis = { verdict: Verdict; checks: Check[] }
const GIB = 1024 ** 3

function partsOf(parts: PartInput[], type: PartType): PartInput[] {
  // 잘못된 개수도 미확인 항목으로 남겨 다른 장치의 용량에 더하지 않는다.
  return parts.filter((part) => part.type === type && part.displayName.trim() !== '')
}

function validQuantity(part: PartInput): boolean {
  return Number.isSafeInteger(part.quantity) && part.quantity >= 1 && part.quantity <= 64
}

function knownBytes(part: PartInput, key: string): number | null {
  const value = part.specs[key]
  return validQuantity(part) && typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : null
}

function matchesModel(part: PartInput, model: string): boolean {
  return isSpecificHardwareModel(model) && normalizeHardwareModel(part.displayName) === normalizeHardwareModel(model)
}

function memoryThreshold(part: PartInput, requirement: RequirementTier): number | null {
  const modelThresholds = Object.entries(requirement.gpuVramGiBByModel ?? {})
    .filter(([model]) => matchesModel(part, model)).map(([, value]) => value)
  const threshold = modelThresholds.length > 0 ? Math.max(...modelThresholds) : requirement.vramGiB
  const valid = typeof threshold === 'number' && Number.isFinite(threshold) &&
    (threshold > 0 || threshold === 0 && modelThresholds.length > 0)
  return valid ? threshold : null
}

function deviceMemoryVerdict(part: PartInput, requirement: RequirementTier): Verdict {
  const threshold = memoryThreshold(part, requirement)
  if (!validQuantity(part) || threshold === null) return 'UNKNOWN'
  // 공식 예시 내장 GPU의 예외는 용량 측정이나 추정을 뜻하지 않는다.
  if (threshold === 0) return 'PASS'
  const bytes = knownBytes(part, 'vramBytes')
  if (bytes === null) return 'UNKNOWN'
  return bytes >= threshold * GIB ? 'PASS' : 'FAIL'
}

function modelCheck(label: 'CPU' | 'GPU', available: PartInput[], requirement: RequirementTier): Check {
  const examples = label === 'CPU' ? requirement.cpu : requirement.gpu
  const actual = available.map((part) => part.displayName).join(', ') || '미입력'
  const required = examples.join(' / ')
  const matches = available.filter((part) => validQuantity(part) && examples.some((example) => matchesModel(part, example)))
  const needsMemory = label === 'GPU' && (requirement.vramGiB !== undefined || requirement.gpuVramGiBByModel !== undefined)
  const matched = matches.find((part) => !needsMemory || deviceMemoryVerdict(part, requirement) === 'PASS')
  if (matched) {
    return { label, required, actual, verdict: 'PASS',
      reason: needsMemory ? memoryThreshold(matched, requirement) === 0
        ? '공식 예시 내장 GPU 모델을 확인했습니다. 별도 전용 VRAM 조건은 적용하지 않습니다.'
        : '공식 예시 모델과 필요한 VRAM을 같은 GPU에서 확인했습니다.'
        : '공식 예시와 동일한 모델명이 확인되었습니다.' }
  }
  if (matches.length > 0 && needsMemory && matches.length === available.length &&
    matches.every((part) => deviceMemoryVerdict(part, requirement) === 'FAIL')) {
    return { label, required, actual, verdict: 'FAIL', reason: '공식 예시 GPU 모델에 필요한 VRAM이 기준보다 적습니다.' }
  }
  const reason = available.length === 0 ? '부품이 입력되지 않았습니다.'
    : matches.length > 0 && needsMemory ? '공식 예시 모델과 필요한 VRAM을 같은 GPU에서 확인할 수 없습니다.'
    : available.some((part) => !validQuantity(part)) ? '부품 개수는 1~64 사이의 정수여야 합니다.'
    : null
  if (reason !== null) return { label, required, actual, verdict: 'UNKNOWN', reason }
  // ARM CPU(Apple M 시리즈, Snapdragon 등)는 Blender 점수가 있어도 Windows 데스크톱 게임 비교 대상이 아니다.
  if (label === 'CPU' && available.some((part) => /\b(?:apple\s+m\d|snapdragon|qualcomm|oryon)\b/i.test(part.displayName))) {
    return { label, required, actual, verdict: 'UNKNOWN', reason: 'ARM CPU는 Windows 데스크톱 게임 사양과 비교하지 않습니다.' }
  }
  // 모델이 공식 예시와 다르면 공개 벤치마크 배율로 판정한다. 공식 동일 모델 확인이 아니라는 점을 문구로 밝힌다.
  // GPU의 VRAM 조건은 별도의 VRAM 행에서 확인한다.
  const score = comparePartScore(label, available, examples)
  if (score.status === 'UNKNOWN') return { label, required, actual, verdict: 'UNKNOWN', reason: score.reason }
  const kind = label === 'CPU' ? 'CPU 처리 성능' : 'GPU 게임 성능'
  const basis = score.bridged ? '다른 시험 표를 환산한 벤치마크 비교' : '같은 시험 표의 벤치마크 비교'
  return { label, required, actual, verdict: score.status === 'ABOVE_BASELINE' ? 'PASS' : 'FAIL',
    reason: `${kind}이 기준 ${score.baselineModel}의 ${formatRatio(Number(score.ratio))}입니다(${basis}). 공식 동일 모델 확인이 아닌 추정이며 예상 FPS가 아닙니다.` }
}

function capacityCheck(label: string, available: PartInput[], requiredGiB: number): Check {
  const values = available.map((part) => {
    const bytes = knownBytes(part, 'capacityBytes')
    const total = bytes === null ? null : bytes * part.quantity
    return total !== null && Number.isSafeInteger(total) ? total : null
  })
  const known = values.filter((value): value is number => value !== null)
  const capacityBytes = known.reduce((sum, value) => sum + value, 0)
  const hasUnknown = known.length !== available.length || !Number.isSafeInteger(capacityBytes)
  const required = `${requiredGiB}GiB 이상`
  if (!Number.isSafeInteger(capacityBytes)) {
    return { label, required, actual: '용량 계산 확인 필요', verdict: 'UNKNOWN', reason: '입력된 개수와 용량으로 정확한 합계를 계산할 수 없습니다.' }
  }
  const actual = available.length === 0 ? '미입력' : known.length === 0 ? '용량·개수 미확인'
    : `${(capacityBytes / GIB).toFixed(1)}GiB${hasUnknown ? ' + 용량·개수 미확인 항목' : ''}`
  const verdict: Verdict = Number.isFinite(requiredGiB) && requiredGiB > 0 && capacityBytes >= requiredGiB * GIB ? 'PASS'
    : !hasUnknown && available.length > 0 && Number.isFinite(requiredGiB) && requiredGiB > 0 ? 'FAIL' : 'UNKNOWN'
  const reason = verdict === 'PASS' ? '개수와 장치별 용량을 확인한 RAM 모듈만 합산해 기준 이상입니다.'
    : verdict === 'FAIL' ? '확인된 RAM 총 용량이 기준보다 적습니다.' : '용량이나 개수가 미확인인 항목이 있어 단정할 수 없습니다.'
  return { label, required, actual, verdict, reason }
}

function gpuMemoryCheck(available: PartInput[], requirement: RequirementTier): Check {
  const results = available.map((part) => deviceMemoryVerdict(part, requirement))
  const thresholds = Object.entries(requirement.gpuVramGiBByModel ?? {})
  const required = thresholds.length > 0 ? thresholds.map(([model, gib]) =>
    gib === 0 ? `${model}: 별도 전용 VRAM 조건 없음` : `${model} ${gib}GiB 이상`).join(' / ')
    : `${requirement.vramGiB}GiB 이상`
  const actual = available.map((part) => {
    if (memoryThreshold(part, requirement) === 0 && validQuantity(part)) return `${part.displayName}: 별도 전용 VRAM 조건 없음`
    const bytes = knownBytes(part, 'vramBytes')
    return `${part.displayName}: ${bytes === null ? '용량·개수 미확인' : `${(bytes / GIB).toFixed(1)}GiB`}`
  }).join(', ') || '미입력'
  const verdict: Verdict = results.includes('PASS') ? 'PASS' : results.length > 0 && results.every((value) => value === 'FAIL') ? 'FAIL' : 'UNKNOWN'
  const exempt = available.find((part, index) => results[index] === 'PASS' && memoryThreshold(part, requirement) === 0)
  return { label: 'VRAM', required, actual, verdict,
    reason: verdict === 'PASS' ? exempt
      ? '공식 예시 내장 GPU에는 별도 전용 VRAM 조건이 없습니다. 용량을 측정하거나 추정한 결과가 아닙니다.'
      : '한 GPU에서 해당 모델의 VRAM 기준 이상임을 확인했습니다. 서로 다른 GPU 용량은 합치지 않습니다.'
      : verdict === 'FAIL' ? '입력된 GPU마다 필요한 VRAM 기준보다 적습니다.' : 'GPU 모델별 용량과 개수를 함께 확인해야 합니다.' }
}

export function diagnoseTier(parts: PartInput[], requirement: RequirementTier): TierDiagnosis {
  const cpu = partsOf(parts, 'CPU'), gpu = partsOf(parts, 'GPU'), ram = partsOf(parts, 'RAM')
  const checks = [modelCheck('CPU', cpu, requirement), modelCheck('GPU', gpu, requirement),
    capacityCheck('RAM', ram, requirement.ramGiB)]
  if (requirement.vramGiB !== undefined || requirement.gpuVramGiBByModel !== undefined) checks.push(gpuMemoryCheck(gpu, requirement))
  const verdict: Verdict = checks.some((check) => check.verdict === 'FAIL') ? 'FAIL'
    : checks.every((check) => check.verdict === 'PASS') ? 'PASS' : 'UNKNOWN'
  return { verdict, checks }
}

export function diagnoseGame(parts: PartInput[], game: GameRequirements) {
  return { minimum: diagnoseTier(parts, game.minimum), recommended: diagnoseTier(parts, game.recommended) }
}
