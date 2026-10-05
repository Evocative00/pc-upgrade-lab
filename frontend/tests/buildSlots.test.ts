import assert from 'node:assert/strict'
import test from 'node:test'
import {
  assignSlots, chipLabel, priceTotal, ramLabel, ramSlotCount, slotOfDraft,
} from '../src/features/builder/buildSlots.ts'
import { createManualDraft } from '../src/features/pc/partDraft.ts'
import type { PartDraft } from '../src/features/pc/types.ts'

const named = (type: PartDraft['type'], displayName: string, extra: Partial<PartDraft> = {}): PartDraft =>
  ({ ...createManualDraft(type), displayName, ...extra })

test('CPU 이름은 굵은 줄과 작은 줄로 나눈다', () => {
  assert.deepEqual(chipLabel('Ryzen 5 5500GT'), ['RYZEN 5', '5500GT'])
  assert.deepEqual(chipLabel('i5'), ['I5', ''])
})

test('메모리 라벨과 채울 슬롯 수. 모르는 값을 0으로 쓰지 않는다', () => {
  assert.deepEqual(ramLabel({ moduleCapacityBytes: 8 * 1024 ** 3, memoryType: 'DDR4' }), ['8GB', 'DDR4'])
  assert.deepEqual(ramLabel({ capacityBytes: 16 * 1024 ** 3 }), ['16GB', ''])
  assert.deepEqual(ramLabel(null), ['용량 미확인', ''])
  assert.equal(ramSlotCount([1], undefined), 1)
  assert.equal(ramSlotCount([1], 2), 2)
  assert.equal(ramSlotCount([1, 1, 1], 1), 2)
  assert.equal(ramSlotCount([Number.NaN], undefined), 0)
})

test('빈 항목은 자리에 넣지 않고, 저장장치는 고른 자리 → 입력 순서로 배치한다', () => {
  const ssd = named('STORAGE', 'A SSD')
  const hdd = named('STORAGE', 'B HDD', { visualSlot: 'HDD' })
  const extra = named('STORAGE', 'C')
  const drafts = [createManualDraft('CPU'), named('GPU', 'RTX'), ssd, hdd, extra]
  const slots = assignSlots(drafts)
  assert.equal(slots.CPU, undefined)
  assert.equal(slots.GPU?.displayName, 'RTX')
  assert.equal(slots.SSD, ssd)
  assert.equal(slots.HDD, hdd)
  assert.equal(slotOfDraft(drafts, hdd), 'HDD')
  assert.equal(slotOfDraft(drafts, extra), 'SSD')
})

test('가격 합계는 확정 가격만 더하고 미확정·미연결은 개수로 센다', () => {
  assert.deepEqual(priceTotal([
    { amountKrw: 100000, status: 'CONFIRMED', updatedAt: '' },
    { amountKrw: null, status: 'UNCONFIRMED', updatedAt: '' },
    null,
  ]), { confirmedKrw: 100000, unconfirmed: 2 })
})
