import assert from 'node:assert/strict'
import test from 'node:test'
import { createPageStateStore, isPageNumber, isString, pcStateKey } from '../src/lib/pageState.ts'
import { createManualDraft } from '../src/features/pc/partDraft.ts'
import { isPartDrafts } from '../src/features/pc/pcFormState.ts'
import { defaultGoal, isUpgradeGoal } from '../src/features/planner/plannerGoal.ts'

function storage() {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value) },
    removeItem: (key: string) => { values.delete(key) },
    values,
  }
}

test('새로 만든 앱에서도 PC별 입력·플랜·페이지를 같은 탭 저장소에서 복원한다', () => {
  const tab = storage()
  const first = createPageStateStore(tab)
  first.write(`${pcStateKey(null)}name`, '작성 중인 PC', '', isString)
  first.write(`${pcStateKey(7)}name`, '다른 PC 수정', '', isString)
  first.write(`${pcStateKey(null)}goal`, { ...defaultGoal(), purpose: 'GAME', games: ['발로란트'] }, defaultGoal, isUpgradeGoal)
  first.write('pcs:page', 3, 0, isPageNumber)
  const reloaded = createPageStateStore(tab)
  assert.equal(reloaded.read(`${pcStateKey(null)}name`, '', isString), '작성 중인 PC')
  assert.equal(reloaded.read(`${pcStateKey(7)}name`, '', isString), '다른 PC 수정')
  assert.deepEqual(reloaded.read(`${pcStateKey(null)}goal`, defaultGoal, isUpgradeGoal).games, ['발로란트'])
  assert.equal(reloaded.read('pcs:page', 0, isPageNumber), 3)
  assert.equal(createPageStateStore(storage()).read('pcs:page', 0, isPageNumber), 0)
})

test('입력 중 수량·용량 오류를 null이나 정상 값으로 바꾸지 않고 AUTO 보완 여부도 보존한다', () => {
  const tab = storage()
  const first = createPageStateStore(tab)
  const ram = { ...createManualDraft('RAM'), source: 'AUTO' as const, rawName: '수집 원문',
    displayName: '보완한 RAM', edited: true, quantity: NaN, capacityText: '0', specs: { capacityBytes: NaN } }
  first.write('parts', [ram], [], isPartDrafts)
  const restored = createPageStateStore(tab).read('parts', [], isPartDrafts)[0]
  assert.ok(Number.isNaN(restored.quantity))
  assert.ok(Number.isNaN(restored.specs.capacityBytes))
  assert.equal(restored.capacityText, '0')
  assert.equal(restored.edited, true)
  assert.equal(restored.rawName, '수집 원문')
})

test('저장소 차단·쓰기 실패는 현재 앱의 화면 왕복을 막지 않는다', () => {
  const tab = { getItem() { throw new Error('blocked') }, setItem() { throw new Error('full') }, removeItem() {} }
  const cache = createPageStateStore(tab)
  cache.write('name', '유지할 내용', '', isString)
  cache.write('page', (current) => current + 1, 0, isPageNumber)
  assert.equal(cache.read('name', '', isString), '유지할 내용')
  assert.equal(cache.read('page', 0, isPageNumber), 1)
})

test('손상된 JSON·맞지 않는 저장 형식은 화면 기본값으로 복구한다', () => {
  const tab = storage()
  tab.setItem('pc-upgrade-lab:page-state:v1', '{broken')
  assert.equal(createPageStateStore(tab).read('pcs:page', 0, isPageNumber), 0)
  tab.setItem('pc-upgrade-lab:page-state:v1', JSON.stringify({ owner: 'guest', values: {
    goal: { purpose: 'UNKNOWN' }, parts: [{ type: 'RAM' }], page: -1,
  } }))
  const cache = createPageStateStore(tab)
  assert.deepEqual(cache.read('goal', defaultGoal, isUpgradeGoal), defaultGoal())
  assert.deepEqual(cache.read('parts', [], isPartDrafts), [])
  assert.equal(cache.read('page', 0, isPageNumber), 0)
})

test('초안 초기화는 해당 PC만 비우고 다른 PC의 상태는 유지한다', () => {
  const cache = createPageStateStore(storage())
  for (const id of [null, 1, 10]) cache.write(`${pcStateKey(id)}name`, `PC ${id}`, '', isString)
  cache.clearPrefix(pcStateKey(1))
  assert.equal(cache.read(`${pcStateKey(1)}name`, '', isString), '')
  assert.equal(cache.read(`${pcStateKey(10)}name`, '', isString), 'PC 10')
  assert.equal(cache.read(`${pcStateKey(null)}name`, '', isString), 'PC null')
})

test('비회원 로그인은 초안을 이어가고 로그아웃·다른 회원 로그인은 이전 회원 입력을 비운다', () => {
  const tab = storage()
  const first = createPageStateStore(tab)
  first.write('name', '로그인 전 작성', '', isString)
  first.syncOwner('user-1')
  assert.equal(first.read('name', '', isString), '로그인 전 작성')
  const reload = createPageStateStore(tab)
  reload.syncOwner('user-1')
  assert.equal(reload.read('name', '', isString), '로그인 전 작성')
  const oldEpoch = reload.getEpoch()
  reload.syncOwner('user-2')
  reload.write('name', '이전 회원의 늦은 응답', '', isString, oldEpoch)
  assert.equal(reload.read('name', '', isString), '')
  reload.write('name', '회원 2 입력', '', isString)
  reload.syncOwner('guest')
  assert.equal(createPageStateStore(tab).read('name', '', isString), '')
})

test('저장 성공은 제출 당시 초안만 비우고 새로 작성한 내용·다른 회원 상태는 남긴다', () => {
  const cache = createPageStateStore(storage())
  const key = pcStateKey(null)
  cache.write(`${key}name`, 'PC A', '', isString)
  const savedA = cache.capturePrefix(key)
  cache.write(`${key}name`, 'PC B', '', isString)
  cache.clearPrefixIfUnchanged(key, savedA)
  assert.equal(cache.read(`${key}name`, '', isString), 'PC B')
  const savedB = cache.capturePrefix(key)
  cache.clearPrefixIfUnchanged(key, savedB)
  assert.equal(cache.has(`${key}name`), false)
  cache.syncOwner('user-1')
  cache.write(`${key}name`, '회원 1 PC', '', isString)
  const user1 = cache.capturePrefix(key)
  cache.syncOwner('user-2')
  cache.write(`${key}name`, '회원 1 PC', '', isString)
  cache.clearPrefixIfUnchanged(key, user1)
  assert.equal(cache.has(`${key}name`), true)
})

test('초기화·저장 후 떠나는 이전 폼의 재렌더와 늦은 이벤트가 초안을 다시 만들지 않는다', () => {
  const cache = createPageStateStore(storage())
  const key = `${pcStateKey(null)}name`
  cache.write(key, '저장한 PC', '', isString)
  const generation = cache.getGeneration(key)
  const epoch = cache.getEpoch()
  cache.clearPrefix(pcStateKey(null))
  cache.read(key, '이전 폼 초기값', isString, epoch, generation)
  cache.write(key, '이전 폼 이벤트', '', isString, epoch, generation)
  assert.equal(cache.has(key), false)
  cache.write(key, '새로 작성', '', isString)
  assert.equal(cache.read(key, '', isString), '새로 작성')
})
