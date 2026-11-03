import assert from 'node:assert/strict'
import test from 'node:test'
import type { CompatibilityResult } from '../src/features/builder/compatibility.ts'
import { compatCounts, compatibilityPairs } from '../src/features/planner/planCompatibility.ts'

const result = (checks: [string, 'COMPATIBLE' | 'NEEDS_CHECK' | 'INCOMPATIBLE', string][]): CompatibilityResult => ({
  status: checks.some(([, s]) => s === 'INCOMPATIBLE') ? 'INCOMPATIBLE' : checks.some(([, s]) => s === 'NEEDS_CHECK') ? 'NEEDS_CHECK' : 'COMPATIBLE',
  checks: checks.map(([code, status, message]) => ({ code, status, message, affectedFields: [] })),
})

const sample = result([
  ['CPU_SOCKET', 'COMPATIBLE', 'CPU와 메인보드 소켓가 일치합니다: AM4'],
  ['CPU_BIOS', 'NEEDS_CHECK', 'CPU·보드 리비전·스테핑의 지원 항목을 확인한 뒤 BIOS 조건을 검사해야 합니다.'],
  ['MOTHERBOARD_RAM_TYPE_0', 'COMPATIBLE', '메인보드와 RAM의 DDR 종류가 일치합니다: DDR4'],
  ['RAM_SLOT_COUNT', 'COMPATIBLE', '실제 장착 모듈 2개 / 메인보드 슬롯 4개: 슬롯 수 이내입니다.'],
  ['GPU_PAIR_SELECTION', 'NEEDS_CHECK', '파워 또는 케이스를 선택하면 그래픽카드의 전원·장착 조건을 함께 검사할 수 있습니다.'],
])

test('검사 항목을 상태별로 세고, 자료 부족(BIOS 등)은 확인 필요가 아닌 참고로 센다', () => {
  assert.deepEqual(compatCounts(sample), { COMPATIBLE: 3, INFO: 1, NEEDS_CHECK: 1, INCOMPATIBLE: 0 })
  assert.deepEqual(compatCounts(null), { COMPATIBLE: 0, INFO: 0, NEEDS_CHECK: 0, INCOMPATIBLE: 0 })
})

test('검사 항목을 부품 조합별 한 줄로 묶고, 파워·케이스 미입력 줄을 더한다', () => {
  const pairs = compatibilityPairs(sample, ['PSU', 'CASE'], true)
  assert.deepEqual(pairs.map((pair) => [pair.label, pair.status, pair.reason]), [
    ['CPU ↔ 메인보드', 'COMPATIBLE', 'AM4 일치'],
    ['CPU ↔ 메인보드 BIOS', 'INFO', '구매 전 보드 BIOS 지원 목록 확인 권장'],
    ['메모리 ↔ 메인보드', 'COMPATIBLE', 'DDR4 일치'],
    ['그래픽카드 ↔ 파워', 'NEEDS_CHECK', '파워 미입력'],
    ['그래픽카드 ↔ 케이스', 'NEEDS_CHECK', '케이스 미입력'],
  ])
})

test('조합 안에 비호환이 있으면 그 줄은 비호환이고 원문 이유를 보여 준다', () => {
  const bad = result([
    ['MOTHERBOARD_RAM_TYPE_0', 'INCOMPATIBLE', '메인보드는 DDR4, RAM은 DDR5라 장착할 수 없습니다.'],
    ['RAM_SLOT_COUNT', 'COMPATIBLE', '슬롯 수 이내입니다.'],
  ])
  assert.deepEqual(compatibilityPairs(bad, [], false), [
    { label: '메모리 ↔ 메인보드', status: 'INCOMPATIBLE', reason: '메인보드는 DDR4, RAM은 DDR5라 장착할 수 없습니다.' },
  ])
})

test('조합 안에 참고와 확인 필요가 섞이면 확인 필요를 따른다', () => {
  const mixed = result([
    ['CPU_RAM_CAPACITY', 'NEEDS_CHECK', '총 RAM 용량 또는 CPU의 공표 최대 메모리 용량을 확인해야 합니다.'],
    ['CPU_RAM_TYPE_0', 'NEEDS_CHECK', 'RAM 종류를 확인해야 합니다.'],
  ])
  assert.equal(compatibilityPairs(mixed, [], false)[0].status, 'NEEDS_CHECK')
  const infoOnly = result([['CPU_RAM_CAPACITY', 'NEEDS_CHECK', '총 RAM 용량 또는 CPU의 공표 최대 메모리 용량을 확인해야 합니다.']])
  assert.deepEqual(compatibilityPairs(infoOnly, [], false)[0], { label: '메모리 ↔ CPU', status: 'INFO', reason: '최대 메모리 정보 없음' })
})
