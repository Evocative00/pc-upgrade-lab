import assert from 'node:assert/strict'
import test from 'node:test'
import { createCatalogModelClient } from '../src/features/catalog/catalogModelClient.ts'
import { createStorageSupportClient } from '../src/features/catalog/storageSupportClient.ts'
import type { CatalogModel } from '../src/features/catalog/catalogTypes.ts'
import { applyCatalogModelSelection, priceTotal } from '../src/features/builder/buildSlots.ts'
import { getPartStatus, linkCatalog, linkCatalogModel, planScanApply, renameDraft, toDraft,
  toPartInputs, toPersistedDraft, unlinkCatalog, validatePcRequest } from '../src/features/pc/partDraft.ts'
import type { PartInput } from '../src/features/pc-scan/types.ts'
import { createHttpPcRepository, isPcRequest } from '../src/features/pc/pcRepository.ts'
import { loadDraft, saveDraft } from '../src/features/auth/draftBridge.ts'

const part: PartInput = { type: 'RAM', displayName: '검출 RAM', rawName: '원문 RAM', quantity: 2,
  source: 'AUTO', catalogProductId: null, matchStatus: 'UNMATCHED', specs: { capacityBytes: 16 * 1024 ** 3 } }
const model: CatalogModel = { id: 'model-id', canonicalId: 'canonical-id', type: 'RAM', manufacturer: 'Samsung',
  modelName: 'DDR5 16GB 규격군', kind: 'RAM_SPEC_GROUP', role: 'INSTALLED_PC_REFERENCE',
  verificationStatus: 'PARTIAL', family: null, series: null, createdAt: '2026-10-09T00:00:00Z', updatedAt: '2026-10-09T00:00:00Z' }
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })

test('RAM 규격군은 모델로 왕복 저장하고 판매 묶음·가격으로 확정하지 않는다', () => {
  const draft = linkCatalogModel(toDraft(part), model)
  const inputs = toPartInputs([draft])
  assert.equal(getPartStatus(draft), 'model')
  assert.equal(inputs[0].recognitionLevel, 'SPEC_GROUP')
  assert.equal(inputs[0].catalogProductId, null)
  assert.equal(inputs[0].matchStatus, 'UNMATCHED')
  assert.equal(inputs[0].quantity, 2)
  assert.deepEqual(inputs[0].specs, part.specs)
  assert.equal(inputs[0].rawName, part.rawName)
  assert.equal(toPersistedDraft(JSON.parse(JSON.stringify(inputs[0]))).catalogModelId, model.id)
  assert.deepEqual(validatePcRequest('내 PC', [draft]), [])
  assert.deepEqual(priceTotal([{ draft, detail: null }]), { currentKrw: 0, pricedItems: 0, unpriced: 1, quantityNeedsCheck: 0 })
  assert.equal(planScanApply([draft], { schemaVersion: 1, collectorVersion: 'test', collectedAt: '2026-10-09T00:00:00Z',
    parts: [part], warnings: [] }).editedReplaced, 1)
})

test('직접 이름 수정·연결 해제·다른 상품 선택은 이전 모델 확인을 남기지 않는다', () => {
  const draft = linkCatalogModel(toDraft(part), model)
  for (const next of [renameDraft(draft, '다른 RAM'), unlinkCatalog(draft),
    linkCatalog(draft, { id: 'other-product', modelName: '다른 묶음', type: 'RAM', modelId: 'other-model' })]) {
    assert.equal(next.catalogModelId, null)
    assert.equal(next.recognitionLevel, null)
    assert.equal(next.quantity, 2)
    assert.equal(next.rawName, part.rawName)
  }
})

test('모델 확인 필드는 로그인 초안과 PC API에 보존되며 불일치 상태는 거부한다', async () => {
  const request = { name: '모델 확인 PC', parts: toPartInputs([linkCatalogModel(toDraft(part), model)]) }
  const items = new Map<string, string>()
  const storage = { getItem: (key: string) => items.get(key) ?? null,
    setItem: (key: string, value: string) => { items.set(key, value) }, removeItem: (key: string) => { items.delete(key) } }
  assert.equal(saveDraft(request, 42, storage), true)
  assert.deepEqual(loadDraft(storage)?.request, request)
  assert.equal(loadDraft(storage)?.pcId, 42)
  const detail = { ...request, id: 42, createdAt: '2026-10-09T00:00:00Z', updatedAt: '2026-10-09T00:00:00Z' }
  const repository = createHttpPcRepository((async (_url, options) => {
    assert.deepEqual(JSON.parse(String(options?.body)), request)
    return new Response(JSON.stringify(detail), { status: 201 })
  }) as typeof fetch)
  assert.deepEqual(await repository.create(request), detail)
  assert.equal(isPcRequest({ ...request, parts: [{ ...request.parts[0], recognitionLevel: null }] }), false)
  assert.equal(isPcRequest({ ...request, parts: [{ ...request.parts[0], recognitionLevel: 'PHYSICAL_VARIANT' }] }), false)
})

test('모델 선택 중 삭제된 행·다른 종류는 복원하지 않고 최신 장착 수량을 보존한다', () => {
  const draft = toDraft(part)
  const target = { slot: 'RAM' as const, draftKey: draft.key }
  assert.deepEqual(applyCatalogModelSelection([], target, model), [])
  const current = { ...draft, quantity: 3 }
  assert.equal(applyCatalogModelSelection([current], target, model)[0].quantity, 3)
  const wrong = { ...model, type: 'CPU' as const, kind: 'CPU_MODEL' as const }
  assert.deepEqual(applyCatalogModelSelection([current], target, wrong), [current])
})

test('모델 검색은 GET만 사용하고 유형 불일치·잘못된 모델 종류를 거부한다', async () => {
  const page = { items: [model], page: 0, size: 20, totalElements: 1, totalPages: 1 }
  const client = createCatalogModelClient((async (url, options) => {
    assert.match(String(url), /\/api\/catalog\/models\?type=RAM&q=DDR5/)
    assert.equal(options?.method, 'GET')
    return json(page)
  }) as typeof fetch)
  assert.equal((await client.search('RAM', ' DDR5 ')).items[0].id, model.id)
  await assert.rejects(client.search('CPU', 'DDR5'))
  const invalid = createCatalogModelClient((async () => json({ ...page, items: [{ ...model, kind: 'GPU_CHIP_MODEL' }] })) as typeof fetch)
  await assert.rejects(invalid.search('RAM', ''), /응답 형식/)
  const unassigned = createCatalogModelClient((async () => json({ ...page, items: [{ ...model, role: 'UNASSIGNED' }] })) as typeof fetch)
  assert.equal((await unassigned.search('RAM', '')).items[0].role, 'UNASSIGNED')
})

test('슬롯 근거 없음은 미지원이 아니며 보드 ID 불일치·근거 없는 전체확인은 거부한다', async () => {
  const response = { motherboardProductId: 'board-id', dataAvailable: false, completeDataKnown: false,
    profiles: [], note: '슬롯 지원 여부 미확인' }
  const client = createStorageSupportClient((async () => json(response)) as typeof fetch)
  assert.equal((await client.get('board-id')).completeDataKnown, false)
  await assert.rejects(client.get('different-board'), /일치하지/)
  const invalid = createStorageSupportClient((async () => json({ ...response, completeDataKnown: true })) as typeof fetch)
  await assert.rejects(invalid.get('board-id'), /응답 형식/)
})

test('슬롯 CPU·BIOS·공유 조건과 리비전 근거를 손실 없이 읽는다', async () => {
  const response = { motherboardProductId: 'board-id', dataAvailable: true, completeDataKnown: false,
    note: '조건부 근거', profiles: [{ revisionKey: 'REV:1.0', revisionScope: 'EXACT', hardwareRevision: '1.0',
      completeDataKnown: false, conditions: null, slots: [{ slotKey: 'M2_2', connectorType: 'M2', connectorKey: 'M',
        supportedLengthCodes: ['2280'], supportedBusInterfaces: ['PCIE'], supportedProtocols: ['NVME'],
        maxPcieVersion: '4.0', maxPcieLanes: 4, sataVersion: null, laneSource: 'CHIPSET', nvmeBootSupport: null,
        notes: null, rules: [{ ruleKey: 'share', cpuCondition: '일부 CPU', biosCondition: 'BIOS 버전 확인',
          effect: 'PORT_SHARED', affectedSlotKey: 'SATA_2', rawCondition: 'M2_2 사용 시 SATA_2 공유' }] }],
      sources: [{ sourceUrl: 'https://manufacturer.example/manual', checkedAt: '2026-10-09T00:00:00Z',
        sourceRevision: 'v1', documentLocation: 'Storage p.12', supportedFacts: 'M2_2 공유 조건' }] }] }
  const client = createStorageSupportClient((async () => json(response)) as typeof fetch)
  assert.deepEqual(await client.get('board-id'), response)
})
