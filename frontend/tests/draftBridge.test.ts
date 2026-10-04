import assert from 'node:assert/strict'
import test from 'node:test'
import { clearDraft, clearDraftFor, clearSavedDraftFor, loadDraft, saveDraft } from '../src/features/auth/draftBridge.ts'
import type { PcRequest } from '../src/features/pc/types.ts'

function memoryStorage() {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value) },
    removeItem: (key: string) => { values.delete(key) },
  }
}

const request: PcRequest = {
  name: '작성 중인 PC',
  parts: [{
    type: 'GPU', displayName: 'RTX 4070', rawName: null, quantity: 1, source: 'MANUAL',
    catalogProductId: 'gpu-1', matchStatus: 'MATCHED', specs: { vramBytes: 12884901888 },
  }],
}

test('로그인 왕복 동안 초안을 보관하고 그대로 복구한다', () => {
  const storage = memoryStorage()
  assert.equal(saveDraft(request, null, storage), true)
  const draft = loadDraft(storage)
  assert.ok(draft)
  assert.deepEqual(draft.request, request)
  assert.equal(draft.pcId, null)
  // 복구만으로 지워지지 않는다. 저장 성공 시에만 지운다.
  assert.ok(loadDraft(storage))
})

test('저장한 구성의 초안만 지우고 다른 PC의 초안은 남긴다', () => {
  const storage = memoryStorage()
  saveDraft(request, 5, storage)
  clearDraftFor(null, storage)
  assert.equal(loadDraft(storage)?.pcId, 5)
  clearDraftFor(5, storage)
  assert.equal(loadDraft(storage), null)
})

test('손상된 초안과 저장소 오류는 초안 없음으로 처리한다', () => {
  const storage = memoryStorage()
  storage.setItem('pc-upgrade-lab:pc-draft:v1', '{not json')
  assert.equal(loadDraft(storage), null)
  storage.setItem('pc-upgrade-lab:pc-draft:v1',
    JSON.stringify({ request: { name: 1, parts: [] }, pcId: null, savedAt: 'x' }))
  assert.equal(loadDraft(storage), null)

  const broken = {
    getItem: () => { throw new Error('blocked') },
    setItem: () => { throw new Error('blocked') },
    removeItem: () => { throw new Error('blocked') },
  }
  assert.equal(saveDraft(request, null, broken), false)
  assert.equal(loadDraft(broken), null)
  assert.doesNotThrow(() => clearDraft(broken))
  assert.equal(saveDraft(request, null, null), false)
})

test('비유한 제원은 null로 바꾸지 않고 보관을 거절하며 이전 정상 초안을 보존한다', () => {
  const storage = memoryStorage()
  assert.equal(saveDraft(request, null, storage), true)
  const original = storage.getItem('pc-upgrade-lab:pc-draft:v1')
  for (const capacityBytes of [NaN, Infinity, -Infinity]) {
    const invalid = {
      ...request,
      parts: request.parts.map((part) => ({
        ...part, specs: { ...part.specs, capacityBytes },
      })),
    }
    assert.equal(saveDraft(invalid, null, storage), false)
    assert.equal(storage.getItem('pc-upgrade-lab:pc-draft:v1'), original)
    assert.deepEqual(loadDraft(storage)?.request, request)
  }
})

test('저장 성공한 동일 구성만 지우며 공백 정리 전 이름도 같은 구성으로 처리한다', () => {
  const storage = memoryStorage()
  saveDraft({ ...request, name: `  ${request.name}  ` }, null, storage)
  clearSavedDraftFor(null, request, storage)
  assert.equal(loadDraft(storage), null)
  saveDraft(request, 5, storage)
  clearSavedDraftFor(6, request, storage)
  assert.equal(loadDraft(storage)?.pcId, 5)
  clearSavedDraftFor(5, request, storage)
  assert.equal(loadDraft(storage), null)
})
