import assert from 'node:assert/strict'
import test from 'node:test'
import type { PartInput, PartType } from '../src/features/pc-scan/types.ts'
import { estimatePremiere, hasQuickSync } from '../src/features/planner/premiereEstimate.ts'
import { diagnoseProgram } from '../src/features/planner/workloadEstimate.ts'

const GIB = 1024 ** 3
function part(type: PartType, displayName: string, specs: PartInput['specs'] = {}, quantity = 1): PartInput {
  return { type, displayName, rawName: null, specs, quantity, source: 'MANUAL', catalogProductId: null, matchStatus: 'UNMATCHED' }
}
const desktop = [part('CPU', '13th Gen Intel(R) Core(TM) i5-13600KF'),
  part('GPU', 'GeForce RTX 4060 Ti VENTUS 2X BLACK 16G OC'), part('RAM', 'DDR5', { capacityBytes: 16 * GIB }, 2)]

test('해상도가 높을수록 예상 내보내기 시간이 길어진다', () => {
  const facts = { cpuScore: 300, gpuRelative: 300, vramGiB: 12, quickSync: false }
  const [fhd, uhd, eight] = (['FHD', '4K', '8K'] as const).map((resolution) => estimatePremiere(resolution, facts))
  assert.ok(Number(fhd.exportHigh) < Number(uhd.exportHigh))
  assert.ok(Number(uhd.exportHigh) < Number(eight.exportHigh))
})

test('8K 내보내기는 GPU보다 CPU 성능 영향이 크고, 4K는 GPU 영향이 더 크다', () => {
  const base = { cpuScore: 300, gpuRelative: 300, vramGiB: 16, quickSync: false }
  const ratio = (resolution: '4K' | '8K', key: 'cpuScore' | 'gpuRelative') =>
    Number(estimatePremiere(resolution, base).exportHigh) / Number(estimatePremiere(resolution, { ...base, [key]: 600 }).exportHigh)
  assert.ok(ratio('8K', 'cpuScore') > ratio('8K', 'gpuRelative'))
  assert.ok(ratio('4K', 'gpuRelative') > ratio('4K', 'cpuScore'))
})

test('Quick Sync는 내장 그래픽이 있는 Intel CPU에만 있다', () => {
  assert.equal(hasQuickSync('12th Gen Intel(R) Core(TM) i5-12400'), true)
  assert.equal(hasQuickSync('13th Gen Intel(R) Core(TM) i5-13600KF'), false)
  assert.equal(hasQuickSync('AMD Ryzen 9 7950X 16-Core Processor'), false)
  assert.equal(hasQuickSync(null), null)
})

test('재생 수준은 해상도별 기준으로 원활·가끔 끊김·프록시 권장을 낸다', () => {
  const strong = { cpuScore: 560, gpuRelative: 676, vramGiB: 24, quickSync: false }
  const weak = { cpuScore: 110, gpuRelative: 140, vramGiB: 6, quickSync: false }
  assert.equal(estimatePremiere('8K', strong).playback, 'SMOOTH')
  assert.equal(estimatePremiere('FHD', weak).playback, 'SMOOTH')
  assert.equal(estimatePremiere('8K', weak).playback, 'PROXY')
  assert.match(estimatePremiere('8K', weak).playbackDetail, /VRAM\(16GB 필요\)/)
})

test('Premiere 진단은 고른 해상도로 사양과 예상 시간을 보여 준다', () => {
  const result = diagnoseProgram('Premiere Pro', desktop, 'MEDIUM', '8K')!
  assert.equal(result.levels[2].level, '8K 편집')
  assert.equal(result.levels[2].verdict, 'FAIL')
  assert.match(result.estimates[0].title, /8K 10분 영상/)
  assert.match(result.estimates[0].value, /^약 /)
  assert.ok(['원활', '가끔 끊김', '프록시 권장'].includes(result.estimates[1].value))
})
