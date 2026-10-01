import assert from 'node:assert/strict'
import test from 'node:test'
import { chipLabel, priceTotal, ramLabel, ramSlotCount, type Build } from '../src/features/builder/buildSlots.ts'
import type { CatalogProduct } from '../src/features/catalog/catalogTypes.ts'

test('CPU 이름은 굵은 줄과 작은 줄로 나눈다', () => {
  assert.deepEqual(chipLabel('Ryzen 5 5500GT'), ['RYZEN 5', '5500GT'])
  assert.deepEqual(chipLabel('i5'), ['I5', ''])
})

test('메모리 라벨과 채울 슬롯 수. 모르는 값을 0으로 쓰지 않는다', () => {
  assert.deepEqual(ramLabel({ moduleCapacityBytes: 8 * 1024 ** 3, memoryType: 'DDR4' }), ['8GB', 'DDR4'])
  assert.deepEqual(ramLabel(null), ['용량 미확인', ''])
  assert.equal(ramSlotCount({ moduleCount: 1 }), 1)
  assert.equal(ramSlotCount({ moduleCount: 2 }), 2)
  assert.equal(ramSlotCount(null), 1)
})

test('가격 합계는 확정 가격만 더하고 미확정은 개수로 센다', () => {
  const product = (amountKrw: number | null): CatalogProduct => ({
    id: String(amountKrw), type: 'CPU', manufacturer: 'm', modelName: 'x', partNumber: null,
    verificationStatus: 'PARTIAL', active: true, createdAt: '', updatedAt: '',
    referencePrice: { amountKrw, status: amountKrw === null ? 'UNCONFIRMED' : 'CONFIRMED', updatedAt: '' },
  })
  const build: Build = { CPU: { product: product(100000), specs: null }, GPU: { product: product(null), specs: null } }
  assert.deepEqual(priceTotal(build), { confirmedKrw: 100000, unconfirmed: 1 })
})
