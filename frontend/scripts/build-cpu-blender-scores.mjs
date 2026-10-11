// Blender Open Data 원본(jsonl)에서 CPU 점수표 src/features/planner/cpuBlenderScores.ts를 만든다.
//
// 사용법 (frontend 폴더, Node 24):
//   1. https://opendata.blender.org/snapshots/opendata-latest.zip 을 받아 압축을 푼다.
//   2. node scripts/build-cpu-blender-scores.mjs <압축을 푼 opendata-*.jsonl 경로> <스냅샷 날짜 YYYY-MM-DD>
//
// 원본 데이터는 CC0이다. 저장소에는 원본(약 1.9GB)을 넣지 않고 생성 결과만 커밋한다.
import { createReadStream, writeFileSync } from 'node:fs'
import { createInterface } from 'node:readline'
import { fileURLToPath } from 'node:url'
import { isSpecificHardwareModel, normalizeHardwareModel } from '../src/features/planner/benchmarkComparison.ts'

// 기준 버전. 다른 버전(3.0 이상, 같은 3개 장면)의 측정값은 버전별 보정 계수로 이 버전 기준으로 환산한다.
// 보정 계수 = 두 버전에서 모두 충분히 측정된 CPU들의 (기준 버전 점수 ÷ 해당 버전 점수) 중앙값.
const BLENDER_VERSION = '4.5'
const SCENES = ['monster', 'junkshop', 'classroom']
// 장면마다 이 횟수 이상 측정된 CPU만 넣는다. 한두 번의 측정값으로 순위를 정하지 않는다.
const MIN_RUNS_PER_SCENE = 3
// 보정 계수를 계산할 때 필요한 최소 CPU 수. 이보다 적은 버전은 사용하지 않는다.
const MIN_CALIBRATION_PAIRS = 30

const [inputPath, snapshotDate] = process.argv.slice(2)
if (!inputPath || !/^\d{4}-\d{2}-\d{2}$/.test(snapshotDate ?? '')) {
  console.error('사용법: node scripts/build-cpu-blender-scores.mjs <opendata-*.jsonl> <YYYY-MM-DD>')
  process.exit(1)
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b)
  const mid = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2
}

function versionKey(version) {
  const [major, minor] = version.split('.').map(Number)
  return Number.isInteger(major) && Number.isInteger(minor) && major >= 3 ? `${major}.${minor}` : null
}

// 정규화 키 → { 버전 → 장면별 분당 샘플 수 목록, 원본 이름별 등장 횟수 }
const byModel = new Map()
let records = 0

const lines = createInterface({ input: createReadStream(inputPath), crlfDelay: Infinity })
for await (const line of lines) {
  if (!line.trim()) continue
  records += 1
  let record
  try { record = JSON.parse(line) } catch { continue }
  const runs = Array.isArray(record.data) ? record.data : [record.data]
  for (const run of runs) {
    const version = versionKey(run?.blender_version?.version ?? '')
    const scene = run?.scene?.label
    const score = run?.stats?.samples_per_minute
    const device = run?.device_info?.compute_devices?.[0]?.name
    if (run?.device_info?.device_type !== 'CPU' || !version) continue
    // 다중 소켓 서버는 같은 CPU 이름으로 두 배 가까운 점수가 나오므로 제외한다.
    if ((run.system_info?.num_cpu_sockets ?? 1) !== 1) continue
    if (!SCENES.includes(scene) || !(Number.isFinite(score) && score > 0) || typeof device !== 'string') continue
    const name = device.replace(/\s+/g, ' ').trim()
    if (!isSpecificHardwareModel(name)) continue
    const key = normalizeHardwareModel(name)
    if (!key) continue
    const entry = byModel.get(key) ?? { versions: new Map(), names: new Map() }
    const scenes = entry.versions.get(version) ?? Object.fromEntries(SCENES.map((s) => [s, []]))
    scenes[scene].push(score)
    entry.versions.set(version, scenes)
    entry.names.set(name, (entry.names.get(name) ?? 0) + 1)
    byModel.set(key, entry)
  }
}

// 한 버전 안에서 장면마다 충분히 측정됐을 때의 점수(장면별 중앙값 합)
function versionScore(scenes) {
  if (SCENES.some((scene) => scenes[scene].length < MIN_RUNS_PER_SCENE)) return null
  return SCENES.reduce((sum, scene) => sum + median(scenes[scene]), 0)
}

const ratios = new Map()
for (const entry of byModel.values()) {
  const base = entry.versions.get(BLENDER_VERSION) && versionScore(entry.versions.get(BLENDER_VERSION))
  if (!base) continue
  for (const [version, scenes] of entry.versions) {
    const other = versionScore(scenes)
    if (other) ratios.set(version, [...(ratios.get(version) ?? []), base / other])
  }
}
const factors = new Map([...ratios].filter(([, values]) => values.length >= MIN_CALIBRATION_PAIRS)
  .map(([version, values]) => [version, median(values)]))
if (factors.get(BLENDER_VERSION) !== 1) throw new Error(`기준 버전 ${BLENDER_VERSION}의 측정값이 부족합니다.`)

const rows = []
for (const entry of byModel.values()) {
  // 보정 가능한 모든 버전의 측정값을 기준 버전 값으로 환산해 장면별로 합친다.
  const pooled = Object.fromEntries(SCENES.map((s) => [s, []]))
  for (const [version, scenes] of entry.versions) {
    const factor = factors.get(version)
    if (!factor) continue
    for (const scene of SCENES) pooled[scene].push(...scenes[scene].map((value) => value * factor))
  }
  const score = versionScore(pooled)
  if (score === null) continue
  // 표시 이름은 가장 많이 보고된 원본 이름에서 클록 표기만 뺀다. 정규화 키는 원본과 같다.
  const [rawName] = [...entry.names.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))[0]
  const displayName = rawName.replace(/\s*@\s*[\d.]+\s*GHz$/i, '')
  rows.push({ displayName, score: Math.round(score * 10) / 10, runs: Math.min(...SCENES.map((s) => pooled[s].length)) })
}
rows.sort((a, b) => a.displayName.localeCompare(b.displayName))
const factorText = [...factors].sort((a, b) => a[0].localeCompare(b[0], undefined, { numeric: true }))
  .map(([version, factor]) => `${version}=${factor.toFixed(3)}`).join(', ')

const scores = rows.map((row) => `    ${JSON.stringify(row.displayName)}: ${row.score},`).join('\n')
const runs = rows.map((row) => `    ${JSON.stringify(row.displayName)}: ${row.runs},`).join('\n')
const output = `// 자동 생성 파일. 직접 수정하지 말고 scripts/build-cpu-blender-scores.mjs로 다시 만든다.
// 출처: Blender Open Data (CC0), 스냅샷 ${snapshotDate}, 원본 레코드 ${records}건.
// 점수: Blender CPU 렌더링에서 장면 ${SCENES.join('·')}의 분당 샘플 수 중앙값 합 (${BLENDER_VERSION} 기준).
// 다른 버전 측정값은 아래 보정 계수를 곱해 ${BLENDER_VERSION} 기준으로 환산했다. 환산 오차는 대략 ±10%다.
// 보정 계수: ${factorText}
// 단일 소켓 측정만 사용하고, 장면마다 ${MIN_RUNS_PER_SCENE}회 이상 측정된 CPU만 포함한다.
import type { BenchmarkDataset } from './benchmarkComparison.ts'

export const CPU_BLENDER_DATASET: BenchmarkDataset = {
  kind: 'CPU',
  metric: 'CPU_MULTI_THREAD',
  publisher: 'Blender Open Data',
  edition: 'Blender CPU ${BLENDER_VERSION} 기준 환산 · ${SCENES.length}개 장면 중앙값 합 · 스냅샷 ${snapshotDate}',
  sourceUrl: 'https://opendata.blender.org/',
  checkedOn: '${snapshotDate}',
  scores: {
${scores}
  },
  // 장면별 측정 횟수 중 가장 작은 값. 화면에서 표본 크기를 함께 보여 주는 데 쓴다.
  sampleCounts: {
${runs}
  },
}
`
const target = fileURLToPath(new URL('../src/features/planner/cpuBlenderScores.ts', import.meta.url))
writeFileSync(target, output)
console.log(`레코드 ${records}건 → CPU ${rows.length}종 저장: ${target}`)
