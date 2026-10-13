// 팀이 검토한 GPU 상대 성능 자료(JSON)에서 src/features/planner/gpuRelativeScores.ts를 만든다.
//
// 사용법 (frontend 폴더, Node 24):
//   node scripts/build-gpu-relative-scores.mjs ../data/benchmark/gpu-relative-performance-YYYY-MM-DD.json
//
// 입력 JSON은 칩셋별 'GTX 1060 6GB = 100' 상대값과 출처를 담는다. 자료의 원문 표는 저장하지 않는다.
import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { isSpecificHardwareModel, normalizeHardwareModel } from '../src/features/planner/benchmarkComparison.ts'

// 게임 공식 사양의 표기가 실제 제품명과 다른 경우. 왼쪽 표기로도 오른쪽 칩셋 값을 찾는다.
const ALIASES = { 'GeForce 560': 'GeForce GTX 560' }

const [inputPath] = process.argv.slice(2)
if (!inputPath) {
  console.error('사용법: node scripts/build-gpu-relative-scores.mjs <gpu-relative-performance.json>')
  process.exit(1)
}
const input = JSON.parse(readFileSync(inputPath, 'utf8'))
if (!/^\d{4}-\d{2}-\d{2}$/.test(input.checkedOn ?? '')) throw new Error('checkedOn(YYYY-MM-DD)이 필요합니다.')
const sources = new Map((input.sources ?? []).map((source) => [source.id, source]))
for (const source of sources.values()) {
  if (!source.url?.startsWith('https://')) throw new Error(`출처 URL이 올바르지 않습니다: ${source.id}`)
}

// 명시된 VRAM 용량과 메모리 종류를 보존한다. 다른 변형을 기본 칩셋 점수로 바꾸지 않는다.
const byKey = new Map()
for (const chipset of input.chipsets ?? []) {
  const { name, relative, sourceId } = chipset
  if (!isSpecificHardwareModel(name) || !(Number.isFinite(relative) && relative > 0)) {
    throw new Error(`잘못된 항목: ${JSON.stringify(chipset)}`)
  }
  if (!sources.has(sourceId)) throw new Error(`알 수 없는 출처: ${name} → ${sourceId}`)
  const key = normalizeHardwareModel(name)
  const current = byKey.get(key)
  if (current) throw new Error(`같은 모델의 근거가 중복됩니다: ${name}`)
  byKey.set(key, chipset)
}

const rows = [...byKey.values()].map((row) => ({
  name: row.name,
  relative: Math.round(row.relative * 10) / 10,
  sourceId: row.sourceId,
  confidence: row.confidence ?? 'medium',
  // 개별 모델을 다른 측정값에서 추정한 항목은 같은 시험판의 직접 측정값과도 섞지 않는다.
  comparisonGroup: /decompress|sibling-scaled/i.test(row.note ?? '')
    ? `${row.sourceId}:estimate:${normalizeHardwareModel(row.name)}` : row.sourceId,
}))

// 여러 변형이 같은 시험판에 실제로 수록됐을 때만 관측된 최솟값으로 일반 칩셋 별칭을 만든다.
// 하나의 6GB/GDDR6 행만으로 미수록 3GB/GDDR5까지 지원한다고 추정하지 않는다.
const variantGroups = new Map()
for (const row of rows) {
  const baseName = row.name.replace(/\s*\((?:G?DDR\d\w*)\)$/i, '').replace(/\s+\d{1,2}\s*GB$/i, '')
  const key = normalizeHardwareModel(baseName)
  const group = variantGroups.get(key) ?? { baseName, rows: [] }
  group.rows.push(row)
  variantGroups.set(key, group)
}
for (const [key, group] of variantGroups) {
  if (group.rows.length < 2 || byKey.has(key) || new Set(group.rows.map((row) => row.comparisonGroup)).size !== 1) continue
  const lowest = group.rows.reduce((a, b) => a.relative < b.relative ? a : b)
  rows.push({ ...lowest, name: group.baseName })
}
for (const [alias, target] of Object.entries(ALIASES)) {
  const row = rows.find((item) => normalizeHardwareModel(item.name) === normalizeHardwareModel(target))
  if (row) rows.push({ ...row, name: alias })
}
rows.sort((a, b) => a.relative - b.relative || a.name.localeCompare(b.name))

const keys = rows.map((row) => normalizeHardwareModel(row.name))
if (new Set(keys).size !== keys.length) throw new Error('정규화 후 이름이 겹치는 GPU가 있습니다.')

// 많이 쓰인 출처 순으로 정렬한다. 대표 URL은 가장 많은 칩셋의 근거가 된 출처다.
const sourceCounts = rows.reduce((counts, row) => counts.set(row.sourceId, (counts.get(row.sourceId) ?? 0) + 1), new Map())
const usedSources = [...sourceCounts.entries()].sort((a, b) => b[1] - a[1]).map(([id]) => sources.get(id))
// 화면에 짧게 쓰는 발행처 이름. 'Tom's Hardware GPU 계층표 2026' → 'Tom's Hardware'
const publishers = [...new Set(usedSources.map((source) => source.name.split(/\s+(?:GPU|G3D)\b/)[0]))]
const output = `// 자동 생성 파일. 직접 수정하지 말고 scripts/build-gpu-relative-scores.mjs로 다시 만든다.
// 기존 GPU 자료의 모델별 점수. 출처·시험판이 모두 같은 항목끼리만 비교한다.
// 확인일 ${input.checkedOn}. 명시된 VRAM/메모리 변형을 보존하고 미수록 변형은 비교하지 않는다.
import type { BenchmarkDataset } from './benchmarkComparison.ts'

export const GPU_SCORE_SOURCES = ${JSON.stringify(usedSources.map(({ id, name, url, note }) => ({ id, name, url, note: note ?? '' })), null, 2)} as const

// 발행처·시험판마다 비교 그룹을 분리한다. 공통 기준 GPU로 환산됐어도 서로 다른 시험을 섞지 않는다.
export const GPU_SCORE_BASIS: Record<string, { sourceId: string; confidence: string; comparisonGroup: string }> = {
${rows.map((row) => `  ${JSON.stringify(row.name)}: { sourceId: ${JSON.stringify(row.sourceId)}, confidence: ${JSON.stringify(row.confidence)}, comparisonGroup: ${JSON.stringify(row.comparisonGroup)} },`).join('\n')}
}

export const GPU_RELATIVE_DATASET: BenchmarkDataset = {
  kind: 'GPU',
  metric: 'GPU_3D',
  publisher: ${JSON.stringify(publishers.join(' · '))},
  edition: 'GPU 공개 점수 자료 · 모델별 출처·시험판 분리 · 확인일 ${input.checkedOn}',
  sourceUrl: ${JSON.stringify(usedSources[0].url)},
  checkedOn: '${input.checkedOn}',
  scores: {
${rows.map((row) => `    ${JSON.stringify(row.name)}: ${row.relative},`).join('\n')}
  },
  scoreGroups: Object.fromEntries(Object.entries(GPU_SCORE_BASIS).map(([name, basis]) => [name, basis.comparisonGroup])),
}
`
const target = fileURLToPath(new URL('../src/features/planner/gpuRelativeScores.ts', import.meta.url))
writeFileSync(target, output)
console.log(`GPU ${rows.length}종 저장 (출처 ${usedSources.length}개): ${target}`)
