import assert from 'node:assert/strict'
import test from 'node:test'
import type { PartInput, PartType } from '../src/features/pc-scan/types.ts'
import { estimateGameFrames, frameText, supportsFrameEstimate } from '../src/features/planner/frameEstimate.ts'
import { FRAME_SOURCES, GAME_FRAME_PROFILES } from '../src/features/planner/gameFrameData.ts'
import { GAME_SUGGESTIONS } from '../src/features/planner/plannerGoal.ts'

function part(type: PartType, displayName: string): PartInput {
  return { type, displayName, rawName: null, specs: {}, quantity: 1, source: 'MANUAL',
    catalogProductId: null, matchStatus: 'UNMATCHED' }
}
const midrange = [part('CPU', '13th Gen Intel(R) Core(TM) i5-13600KF'), part('GPU', 'NVIDIA GeForce RTX 5060 Ti')]

test('기본 게임 5종 모두 예상 프레임 기준점과 출처를 가진다', () => {
  for (const game of GAME_SUGGESTIONS) assert.equal(supportsFrameEstimate(game), true, game)
  for (const profile of Object.values(GAME_FRAME_PROFILES)) {
    for (const point of profile.presets.flatMap((preset) => preset.points)) {
      assert.ok(FRAME_SOURCES[point.sourceId]?.url.startsWith('https://'), point.sourceId)
    }
  }
})

test('5종 모두 중급 데스크톱의 FHD·QHD 예상 프레임 숫자를 낸다', () => {
  for (const game of GAME_SUGGESTIONS) {
    for (const resolution of ['FHD', 'QHD'] as const) {
      for (const estimate of estimateGameFrames(midrange, game, resolution, 60)) {
        assert.notEqual(estimate.status, 'UNKNOWN', `${game} ${resolution}`)
        assert.match(frameText(estimate), /\d+fps/)
      }
    }
  }
})

test('측정값이 있는 GPU는 그 값 근처로 추정한다', () => {
  const [ow2] = estimateGameFrames([part('CPU', 'Ryzen 7 9800X3D'), part('GPU', 'GeForce RTX 5070')], '오버워치 2', 'QHD', 144)
  assert.equal(ow2.status, 'ESTIMATED')
  assert.ok(Number(ow2.low) <= 228 && Number(ow2.high) >= 228)
})

test('해상도가 높을수록 예상 프레임이 낮아진다', () => {
  for (const game of ['리그 오브 레전드', '오버워치 2', '배틀그라운드']) {
    const [fhd] = estimateGameFrames(midrange, game, 'FHD', 60)
    const [uhd] = estimateGameFrames(midrange, game, '4K', 60)
    assert.ok(Number(uhd.high ?? uhd.low) < Number(fhd.high ?? fhd.low), game)
  }
})

test('CPU 영향이 큰 게임은 약한 CPU에서 상한을 두고 안내한다', () => {
  const weak = [part('CPU', 'AMD Ryzen 5 1600 Six-Core Processor'), part('GPU', 'GeForce RTX 4090')]
  const strong = [part('CPU', 'AMD Ryzen 7 9800X3D 8-Core Processor'), part('GPU', 'GeForce RTX 4090')]
  const [weakLol] = estimateGameFrames(weak, '리그 오브 레전드', 'FHD', 144)
  const [strongLol] = estimateGameFrames(strong, '리그 오브 레전드', 'FHD', 144)
  assert.equal(weakLol.cpuLimited, true)
  assert.match(String(weakLol.cpuNote), /CPU/)
  assert.ok(Number(weakLol.high) < Number(strongLol.high))
})

test('목표에 못 미치면 필요한 GPU 배율을, 자료가 없으면 이유를 알려 준다', () => {
  const [, high] = estimateGameFrames([part('GPU', 'GeForce GTX 1660 SUPER VENTUS XS OC')], '사이버펑크 2077', 'FHD', 60)
  assert.equal(high.target, 'BELOW')
  assert.ok(Number(high.requiredGpuMultiplier) > 1)
  const [old4k] = estimateGameFrames([part('GPU', 'GeForce GTX 1660 SUPER')], '오버워치 2', '4K', 60)
  assert.equal(old4k.status, 'UNKNOWN')
  assert.match(old4k.reason, /4K 측정 자료가 없어/)
  assert.equal(estimateGameFrames([], '발로란트', 'FHD', 60)[0].status, 'UNKNOWN')
})
