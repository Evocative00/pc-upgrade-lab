// Blender Open Data 검색 결과(장치별 중앙값)에서 GPU 렌더링 점수표 src/features/planner/gpuBlenderScores.ts를 만든다.
//
// 사용법 (frontend 폴더, Node 24):
//   1. 아래 주소를 OPTIX·HIP·ONEAPI 각각 받아 한 폴더에 <백엔드>.json으로 저장한다.
//      https://opendata.blender.org/benchmarks/query/?blender_version=4.5.0&compute_type=OPTIX&group_by=device_name&response_type=datatables
//   2. node scripts/build-gpu-blender-scores.mjs ../data/benchmark/blender-gpu-YYYY-MM-DD YYYY-MM-DD
//
// 점수 단위는 CPU 점수표(cpuBlenderScores.ts)와 같은 Blender 4.5 분당 샘플 수 중앙값이라 CPU와 GPU를 직접 비교할 수 있다.
// 게임 성능에는 쓰지 않는다(RTX 레이트레이싱 하드웨어 때문에 렌더링 점수가 게임 성능과 크게 다름). Blender 렌더링 전용이다.
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { normalizeHardwareModel } from '../src/features/planner/benchmarkComparison.ts'

// 제조사별로 Blender가 기본 권장하는 렌더 방식만 쓴다. NVIDIA는 CUDA보다 빠른 OptiX를 쓴다.
const BACKENDS = [
  { file: 'OPTIX.json', vendor: /nvidia|geforce|quadro|rtx/i, label: 'OptiX' },
  { file: 'HIP.json', vendor: /amd|radeon/i, label: 'HIP' },
  { file: 'ONEAPI.json', vendor: /intel|arc/i, label: 'oneAPI' },
]
// 측정 횟수가 이보다 적은 장치는 넣지 않는다.
const MIN_RUNS = 3

const [folder, snapshotDate] = process.argv.slice(2)
if (!folder || !/^\d{4}-\d{2}-\d{2}$/.test(snapshotDate ?? '')) {
  console.error('사용법: node scripts/build-gpu-blender-scores.mjs <검색 결과 폴더> <YYYY-MM-DD>')
  process.exit(1)
}

// 정규화 키 → 측정 횟수가 가장 많은 행. 같은 GPU가 'NVIDIA GeForce RTX 3060'·'GeForce RTX 3060'처럼 여러 이름으로 보고된다.
const byKey = new Map()
for (const backend of BACKENDS) {
  const { rows } = JSON.parse(readFileSync(join(folder, backend.file), 'utf8'))
  for (const [rawName, median, runs] of rows) {
    const name = String(rawName).replace(/\s+/g, ' ').trim()
    // 데스크톱 전용 서비스라 노트북 GPU는 넣지 않는다. 가상화 분할 GPU(A10-8Q 등)도 제외한다.
    if (/laptop|mobile|max-q|\bgrid\b|-\d+q\b/i.test(name) || !backend.vendor.test(name)) continue
    // 'Intel Arc Graphics'처럼 모델 번호가 없는 내장 그래픽 이름은 어떤 칩인지 특정할 수 없어 제외한다.
    if (!/\d{3}/.test(name)) continue
    if (!(Number.isFinite(median) && median > 0) || runs < MIN_RUNS) continue
    const key = normalizeHardwareModel(name)
    if (!key) continue
    const current = byKey.get(key)
    if (!current || runs > current.runs) byKey.set(key, { name, median, runs, backend: backend.label })
  }
}

const rows = [...byKey.values()].map((row) => ({
  // 표시 이름은 제조사 접두어만 뺀다. 정규화 키는 원본과 같다.
  name: row.name.replace(/^(?:NVIDIA|AMD|Intel\(R\))\s+/i, '').replace(/\(TM\)|\(R\)/g, '').replace(/\s+/g, ' ').trim(),
  score: Math.round(row.median * 10) / 10, runs: row.runs, backend: row.backend,
})).sort((a, b) => a.name.localeCompare(b.name))
const keys = rows.map((row) => normalizeHardwareModel(row.name))
if (new Set(keys).size !== keys.length) throw new Error('정규화 후 이름이 겹치는 GPU가 있습니다.')

const output = `// 자동 생성 파일. 직접 수정하지 말고 scripts/build-gpu-blender-scores.mjs로 다시 만든다.
// 출처: Blender Open Data (공개 데이터), Blender 4.5.0 장치별 중앙값, 확인일 ${snapshotDate}.
// 점수: 분당 샘플 수 중앙값(CPU 점수표와 같은 단위). NVIDIA OptiX·AMD HIP·Intel oneAPI. 데스크톱 GPU, ${MIN_RUNS}회 이상 측정.
// Blender 렌더링 성능 전용이며 게임 성능 비교에는 쓰지 않는다.
import type { BenchmarkDataset } from './benchmarkComparison.ts'

export const GPU_BLENDER_DATASET: BenchmarkDataset = {
  kind: 'GPU',
  metric: 'GPU_RENDER',
  publisher: 'Blender Open Data',
  edition: 'Blender 4.5 GPU 렌더링 · 장치별 중앙값 · 확인일 ${snapshotDate}',
  sourceUrl: 'https://opendata.blender.org/',
  checkedOn: '${snapshotDate}',
  scores: {
${rows.map((row) => `    ${JSON.stringify(row.name)}: ${row.score},`).join('\n')}
  },
  sampleCounts: {
${rows.map((row) => `    ${JSON.stringify(row.name)}: ${row.runs},`).join('\n')}
  },
}

// 모델별 렌더 방식(OptiX/HIP/oneAPI).
export const GPU_BLENDER_BACKEND: Record<string, string> = {
${rows.map((row) => `  ${JSON.stringify(row.name)}: ${JSON.stringify(row.backend)},`).join('\n')}
}
`
const target = fileURLToPath(new URL('../src/features/planner/gpuBlenderScores.ts', import.meta.url))
writeFileSync(target, output)
console.log(`GPU ${rows.length}종 저장: ${target}`)
