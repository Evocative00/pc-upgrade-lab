import assert from 'node:assert/strict'
import test from 'node:test'
import { bootTimeline, finalBootLines, hasSeenBoot, markBootSeen, progressCells, progressLine } from '../src/features/landing/bootScript.ts'
import { assembleSequence } from '../src/features/landing/landingAnimation.ts'
import { assemblyStepAt, nameInitial, providerLabel, virtualBuildSlots } from '../src/features/landing/landingContent.ts'

const lineText = (line: { tag: string; text: string; fill: string; empty: string; post: string }) => line.tag + line.text + line.fill + line.empty + line.post

test('진행률 바는 0%부터 100%까지 칸 수가 같고 빈 칸도 같은 문자다', () => {
  for (let n = 0; n <= progressCells; n++) {
    const line = progressLine(n)
    assert.equal(line.fill.length + line.empty.length, progressCells)
    assert.equal(line.fill + line.empty, '█'.repeat(progressCells))
  }
  assert.equal(progressLine(0).post, '] 0%')
  assert.equal(progressLine(progressCells).post, '] 100%')
  assert.equal(progressLine(99).fill.length, progressCells)
})

test('출력 마지막 상태는 모션 감소 설정의 전체 로그와 같다', () => {
  const { frames, finishDelay } = bootTimeline()
  const last = frames[frames.length - 1].lines
  assert.deepEqual(last.map(lineText), finalBootLines().map(lineText))
  assert.ok(frames.every((frame) => frame.delay > 0))
  assert.ok(finishDelay > 0)
})

test('명령과 소개 문구는 한 글자씩 타이핑되고 [ OK ] 줄만 태그를 가진다', () => {
  const { frames } = bootTimeline()
  const texts = frames.map((frame) => frame.lines.map(lineText).join('\n'))
  assert.ok(texts.some((text) => text.endsWith('C:\\pc-upgrade-lab>pc')))
  assert.ok(texts.some((text) => text.endsWith('> 나만의 PC를,')))
  const tagged = finalBootLines().filter((line) => line.tag !== '')
  assert.deepEqual(tagged.map((line) => line.tag), ['[ OK ]', '[ OK ]', '[ OK ]'])
})

test('로딩 표시 여부는 세션 저장소에 남기고, 저장소 오류에도 화면을 막지 않는다', () => {
  const values = new Map<string, string>()
  const storage = { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => { values.set(key, value) } }
  assert.equal(hasSeenBoot(storage), false)
  markBootSeen(storage)
  assert.equal(hasSeenBoot(storage), true)
  const broken = { getItem: () => { throw new Error('blocked') }, setItem: () => { throw new Error('blocked') } }
  assert.equal(hasSeenBoot(broken), false)
  assert.doesNotThrow(() => markBootSeen(broken))
  assert.equal(hasSeenBoot(null), false)
})

test('조립 구간은 왕복 시퀀스의 분해 → 조립 절반만 쓴다', () => {
  assert.deepEqual(assembleSequence([0, 1, 2, 2, 1, 0]), [2, 1, 0])
  assert.deepEqual(assembleSequence([0, 1, 2]), [2])
  assert.deepEqual(assembleSequence([]), [])
})

test('스크롤 진행도를 STEP 1~3으로 나눈다', () => {
  assert.equal(assemblyStepAt(0), 0)
  assert.equal(assemblyStepAt(0.33), 0)
  assert.equal(assemblyStepAt(0.34), 1)
  assert.equal(assemblyStepAt(0.66), 1)
  assert.equal(assemblyStepAt(0.67), 2)
  assert.equal(assemblyStepAt(1), 2)
  assert.equal(assemblyStepAt(Number.NaN), 0)
})

test('회원 표시와 가상 조립 예시는 지어낸 가격 없이 자리 표시를 유지한다', () => {
  assert.equal(providerLabel('google'), 'Google 계정으로 로그인')
  assert.equal(providerLabel('KAKAO'), '카카오 계정으로 로그인')
  assert.equal(providerLabel('github'), 'github 계정으로 로그인')
  assert.equal(nameInitial(' 김민준'), '김')
  assert.equal(nameInitial(''), '?')
  assert.ok(virtualBuildSlots.every((row) => row.price === '[현재가]' || row.price === '—'))
  assert.equal(virtualBuildSlots.filter((row) => row.warn).length, 1)
})
