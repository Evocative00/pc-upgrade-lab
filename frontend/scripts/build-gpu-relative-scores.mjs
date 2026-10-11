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

// Windows는 GPU 이름에 VRAM 용량을 붙이지 않아 'RTX 3060 12GB'와 '8GB'를 구분할 수 없다.
// 정규화 이름이 같은 변형은 낮은 값을 사용한다. VRAM 용량은 공식 사양 카드에서 따로 비교한다.
const byKey = new Map()
for (const chipset of input.chipsets ?? []) {
  const { name, relative, sourceId } = chipset
  if (!isSpecificHardwareModel(name) || !(Number.isFinite(relative) && relative > 0)) {
    throw new Error(`잘못된 항목: ${JSON.stringify(chipset)}`)
  }
  if (!sources.has(sourceId)) throw new Error(`알 수 없는 출처: ${name} → ${sourceId}`)
  const key = normalizeHardwareModel(name)
  const current = byKey.get(key)
  const variants = [...(current?.variants ?? []), name]
  if (!current || relative < current.relative) byKey.set(key, { ...chipset, variants })
  else current.variants = variants
}

const rows = [...byKey.values()].map((row) => ({
  // Windows 이름에 없는 VRAM 용량·메모리 종류 표기는 빼고 저장한다. 예: 'GTX 1650 (GDDR6)' → 'GTX 1650'
  name: row.name.replace(/\s*\((?:G?DDR\d\w*)\)$/i, '').replace(/\s+\d{1,2}\s*GB$/i, ''),
  relative: Math.round(row.relative * 10) / 10,
  sourceId: row.sourceId,
  confidence: row.confidence ?? 'medium',
  variants: row.variants,
}))
for (const [alias, target] of Object.entries(ALIASES)) {
  const row = rows.find((item) => normalizeHardwareModel(item.name) === normalizeHardwareModel(target))
  if (row) rows.push({ ...row, name: alias, variants: [alias] })
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
// 칩셋별 게임 성능 상대값. GeForce GTX 1060 6GB = 100 기준이며 화면에는 비교 대상 대비 배율만 보여 준다.
// 확인일 ${input.checkedOn}. 같은 칩셋의 VRAM 변형은 낮은 값을 사용한다.
import type { BenchmarkDataset } from './benchmarkComparison.ts'

export const GPU_SCORE_SOURCES = ${JSON.stringify(usedSources.map(({ id, name, url, note }) => ({ id, name, url, note: note ?? '' })), null, 2)} as const

export const GPU_RELATIVE_DATASET: BenchmarkDataset = {
  kind: 'GPU',
  metric: 'GPU_3D',
  publisher: ${JSON.stringify(publishers.join(' · '))},
  edition: 'GPU 게임 성능 상대값 · GTX 1060 6GB = 100 · 확인일 ${input.checkedOn}',
  sourceUrl: ${JSON.stringify(usedSources[0].url)},
  checkedOn: '${input.checkedOn}',
  scores: {
${rows.map((row) => `    ${JSON.stringify(row.name)}: ${row.relative},`).join('\n')}
  },
}

// 모델별 출처와 신뢰도. 화면이나 검토에서 근거를 보여 줄 때 쓴다.
export const GPU_SCORE_BASIS: Record<string, { sourceId: string; confidence: string }> = {
${rows.map((row) => `  ${JSON.stringify(row.name)}: { sourceId: ${JSON.stringify(row.sourceId)}, confidence: ${JSON.stringify(row.confidence)} },`).join('\n')}
}
`
const target = fileURLToPath(new URL('../src/features/planner/gpuRelativeScores.ts', import.meta.url))
writeFileSync(target, output)
console.log(`GPU ${rows.length}종 저장 (출처 ${usedSources.length}개): ${target}`)
