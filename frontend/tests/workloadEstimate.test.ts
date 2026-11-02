import assert from 'node:assert/strict'
import test from 'node:test'
import type { PartInput, PartType } from '../src/features/pc-scan/types.ts'
import { PROGRAMS } from '../src/features/planner/plannerGoal.ts'
import { PROGRAM_PROFILES, WORKLOAD_SOURCES } from '../src/features/planner/workloadData.ts'
import { diagnoseProgram, pcFacts } from '../src/features/planner/workloadEstimate.ts'

const GIB = 1024 ** 3
function part(type: PartType, displayName: string, specs: PartInput['specs'] = {}, quantity = 1): PartInput {
  return { type, displayName, rawName: null, specs, quantity, source: 'MANUAL', catalogProductId: null, matchStatus: 'UNMATCHED' }
}
const desktop = [part('CPU', '13th Gen Intel(R) Core(TM) i5-13600KF', { cores: 14, logicalProcessors: 20 }),
  part('GPU', 'GeForce RTX 4060 Ti VENTUS 2X BLACK 16G OC'), part('RAM', 'DDR5 16GB', { capacityBytes: 16 * GIB }, 2)]

test('영상·개발 프로그램 목록 전부 공식 사양 자료와 출처를 가진다', () => {
  for (const program of [...PROGRAMS.CREATION, ...PROGRAMS.DEVELOPMENT]) {
    const profile = PROGRAM_PROFILES[program]
    assert.ok(profile, program)
    for (const id of profile.sourceIds) assert.ok(WORKLOAD_SOURCES[id]?.url.startsWith('https://'), `${program} ${id}`)
  }
})

test('RAM은 모듈 수량까지 합치고, VRAM은 제원이 없으면 판매명에서 읽는다', () => {
  const facts = pcFacts(desktop)
  assert.equal(facts.ramGiB, 32)
  assert.equal(facts.vramGiB, 16)
  assert.equal(facts.vramInferred, true)
  assert.equal(pcFacts([part('RAM', 'DDR4', {})]).ramGiB, null)
})

test('작업 규모에 맞는 기준으로 RAM·VRAM 부족을 판정한다', () => {
  const small = [part('GPU', 'GeForce RTX 3060 8GB'), part('RAM', 'DDR4 8GB', { capacityBytes: 8 * GIB }, 2)]
  const premiere = diagnoseProgram('Premiere Pro', small, 'LIGHT', '8K')!
  const heavy = premiere.levels.find((level) => level.level === '8K 편집')!
  assert.equal(heavy.verdict, 'FAIL')
  assert.equal(premiere.levels.find((level) => level.level === '공식 최소')!.verdict, 'PASS')
})

test('Blender는 GPU·CPU 렌더 시간을, GPU 렌더링 미지원이면 CPU 시간만 낸다', () => {
  const [render] = diagnoseProgram('Blender', desktop, 'MEDIUM')!.estimates
  assert.match(render.value, /GPU 약 \d+~\d+초 · CPU 약 \d+~\d+초/)
  const [cpuOnly] = diagnoseProgram('Blender', [part('CPU', 'Ryzen 5 3600'), part('GPU', 'Radeon RX 580 Series')], 'MEDIUM')!.estimates
  assert.match(cpuOnly.value, /^CPU 약/)
})

test('개발 프로그램은 컴파일 시간, 에뮬레이터 수, 컨테이너 수를 낸다', () => {
  const studio = diagnoseProgram('Android Studio', desktop, 'MEDIUM')!
  assert.equal(studio.estimates[0].value, '약 6개')
  assert.match(studio.estimates[1].value, /약 \d+~\d+초/)
  const docker = diagnoseProgram('Docker·가상 머신', desktop, 'MEDIUM')!
  assert.match(docker.estimates[0].value, /컨테이너 약 32개 · VM 약 6대/)
  assert.match(docker.estimates[0].detail, /논리 프로세서 20개/)
})

test('부품을 모르면 숫자를 꾸며내지 않는다', () => {
  for (const estimate of diagnoseProgram('Android Studio', [], 'LIGHT')!.estimates) assert.equal(estimate.known, false)
  assert.equal(diagnoseProgram('없는 프로그램', desktop, 'LIGHT'), null)
})
