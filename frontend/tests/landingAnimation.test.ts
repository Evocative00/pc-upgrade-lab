import assert from 'node:assert/strict'
import test from 'node:test'
import { advanceProgress, desktopFramePlan, frameAtProgress, FrameCache, parseAnimationManifest, poseWindow, anchorPoseIndexes, readyFrame } from '../src/features/landing/landingAnimation.ts'

const manifest = () => ({
  version: 4, frameCount: 4,
  variants: { desktop: { width: 1600, height: 1200, poster: 'poster-desktop.webp' }, mobile: { width: 960, height: 720, poster: 'poster-mobile.webp' } },
  poses: [0, 1].map(id => ({ id: `pose-${id}`, desktop: `desktop/pose-000${id}.webp`, mobile: `mobile/pose-000${id}.webp` })),
  frames: [0, 1, 1, 0], references: [{ product: 'Reference', feature: 'Shape', url: 'https://manufacturer.example/product' }],
})

test('같은 포즈를 공유하는 왕복 시퀀스를 읽고 첫·끝의 조립 상태를 유지한다', () => {
  const parsed = parseAnimationManifest(manifest())
  assert.ok(parsed)
  assert.equal(parsed.frames[frameAtProgress(0, 4)], parsed.frames[frameAtProgress(1, 4)])
  assert.equal(parsed.poses.length, 2)
})

test('누락된 포즈·잘못된 프레임 수·외부 이미지·잘못된 variant를 받아들이지 않는다', () => {
  const missing = manifest(); missing.frames[2] = 9
  assert.equal(parseAnimationManifest(missing), null)
  assert.equal(parseAnimationManifest({ ...manifest(), frameCount: 480 }), null)
  const external = manifest(); external.poses[0].desktop = 'https://example.com/pose-0000.webp'
  assert.equal(parseAnimationManifest(external), null)
  const crossed = manifest(); crossed.poses[0].mobile = crossed.poses[0].desktop
  assert.equal(parseAnimationManifest(crossed), null)
})

test('참고 링크는 HTTPS만 남기고 잘못된 링크 때문에 렌더를 버리지 않는다', () => {
  const value = manifest(); value.references.push({ product: 'Unsafe', feature: 'Shape', url: 'javascript:alert(1)' })
  assert.equal(parseAnimationManifest(value)?.references.length, 1)
})

test('음수·초과·비유한 진행률을 경계 안으로 제한하고 fractional index를 정수로 선택한다', () => {
  assert.equal(frameAtProgress(-1, 480), 0)
  assert.equal(frameAtProgress(2, 480), 479)
  assert.equal(frameAtProgress(Number.NaN, 480), 0)
  assert.equal(frameAtProgress(.5, 480), 240)
  assert.equal(frameAtProgress(.5, 1), 0)
})

test('60Hz와 120Hz에서 같은 시간이 지나면 scrub 응답이 같다', () => {
  let sixty = 0, oneTwenty = 0
  for (let i = 0; i < 6; i++) sixty = advanceProgress(sixty, 1, 100 / 6)
  for (let i = 0; i < 12; i++) oneTwenty = advanceProgress(oneTwenty, 1, 100 / 12)
  assert.ok(Math.abs(sixty - oneTwenty) < 1e-10)
})

test('큰 스크롤 이동도 한 번에 목표로 점프하지 않고 양방향으로 이어진다', () => {
  const forward = advanceProgress(0, 1, 1000 / 60)
  assert.ok(forward > 0 && forward < .15)
  assert.ok(Math.abs(advanceProgress(1, 0, 1000 / 60) - (1 - forward)) < 1e-10)
})

test('기준 포즈는 부분 로딩에서도 양 끝과 중앙을 덮고 포즈 간격을 제한한다', () => {
  const indexes = anchorPoseIndexes(240)
  assert.equal(indexes.length, 64)
  assert.equal(new Set(indexes).size, 64)
  assert.deepEqual(indexes.slice(0, 3), [0, 239, 118])
  const sorted = [...indexes].sort((a, b) => a - b)
  assert.ok(sorted.every((index, position) => !position || index - sorted[position - 1] <= 4))
  assert.deepEqual(anchorPoseIndexes(1), [0])
  assert.deepEqual(anchorPoseIndexes(0), [])
  assert.deepEqual(anchorPoseIndexes(Number.NaN), [])
})

test('실제 그림 영역의 픽셀을 확보하고 큰 letterbox에서도 DPR을 낮추지 않는다', () => {
  const normal = desktopFramePlan(1046, 1180, 1, 1600, 1200)
  assert.equal(normal.width, 1088); assert.equal(normal.height, 816)
  assert.equal(normal.anchorLimit, 64)
  const retina = desktopFramePlan(1046, 1180, 2, 1600, 1200)
  assert.equal(retina.pixelRatio, 2)
  assert.equal(retina.canvasWidth, 2092); assert.equal(retina.canvasHeight, 2360)
  assert.equal(retina.width, 1600); assert.equal(retina.height, 1200)
  assert.equal(desktopFramePlan(1047, 1180, 1, 1600, 1200).width, normal.width)
})

test('큰 화면에서는 원본 크기와 디코딩 메모리 예산 안에서 기준 포즈 수를 줄인다', () => {
  for (const source of [[1600, 1200], [4096, 4096]]) {
    const plan = desktopFramePlan(3840, 2160, 3, source[0], source[1])
    assert.ok(plan.width <= source[0] && plan.height <= source[1])
    assert.ok(plan.width * plan.height * 4 * (plan.anchorLimit + plan.nearbyLimit) <= 320 * 1024 * 1024)
    assert.ok(plan.anchorLimit >= 2 && plan.anchorLimit < 64)
    assert.equal(anchorPoseIndexes(240, plan.anchorLimit).length, plan.anchorLimit)
  }
})

test('목표 이미지가 로딩 중이면 준비된 포즈로 진행하고 이전 표시보다 후퇴하지 않는다', () => {
  const frames = [0, 1, 2, 3, 4, 5], available = new Set([0, 2, 4])
  assert.equal(readyFrame(frames, 3, pose => available.has(pose)), 2)
  assert.equal(readyFrame(frames, 3, pose => available.has(pose), 2), 2)
  assert.equal(readyFrame(frames, 3, pose => pose === 0, 2), null)
  assert.equal(readyFrame(frames, 3, pose => available.has(pose), 5, -1), 4)
  assert.equal(readyFrame(frames, 3, () => false), null)
})

test('같은 포즈의 역순 구간도 타임라인 위치로 선택하고 방향 전환을 허용한다', () => {
  const frames = [0, 1, 2, 3, 3, 2, 1, 0]
  assert.equal(readyFrame(frames, 5, pose => pose === 3, 4), 4)
  assert.equal(readyFrame(frames, 5, pose => pose === 1, 7, -1), 6)
  assert.equal(readyFrame(frames, 2, pose => pose === 2, 6, -1), 2)
  assert.equal(readyFrame([], 0, () => true), null)
})

test('역방향 scrub은 넘치지 않고 정지 위치로 수렴하며 긴 pause와 잘못된 시간을 제한한다', () => {
  let progress = 1
  for (let i = 0; i < 100; i++) progress = advanceProgress(progress, .2, 16.67)
  assert.equal(progress, .2)
  assert.equal(advanceProgress(.5, 1, Number.NaN), .5)
  assert.equal(advanceProgress(0, 1, 10000), advanceProgress(0, 1, 50))
})

test('왕복 경계에서 같은 포즈를 중복 decode하지 않고 양방향 가까운 포즈를 우선한다', () => {
  assert.deepEqual(poseWindow([0, 1, 2, 2, 1, 0], 2, 3), [2, 1, 0])
  assert.deepEqual(poseWindow([0, 1, 2, 3, 4], 2, 3, -1), [2, 1, 3])
})

test('LRU는 현재 읽은 포즈를 남기고 오래된 bitmap을 즉시 해제한다', () => {
  const closed: number[] = [], cache = new FrameCache<number>(2, value => closed.push(value))
  cache.set('a', 1); cache.set('b', 2); cache.get('a'); cache.set('c', 3)
  assert.deepEqual(closed, [2]); assert.equal(cache.has('a'), true); assert.equal(cache.size, 2)
  cache.clear(); cache.clear()
  assert.deepEqual(closed, [2, 1, 3])
})

test('동일 포즈 cache 교체와 invalid 한도에서도 bitmap을 누적하지 않는다', () => {
  const closed: number[] = [], cache = new FrameCache<number>(Number.NaN, value => closed.push(value))
  cache.set('a', 1); cache.set('a', 2); cache.set('b', 3)
  assert.equal(cache.size, 1); assert.deepEqual(closed, [1, 2])
})
