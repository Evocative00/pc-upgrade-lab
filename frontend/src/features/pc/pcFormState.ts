import { isRecord, isString } from '../../lib/pageState.ts'
import { PART_TYPES } from './partCategories.ts'
import type { PartDraft } from './types.ts'
import { SLOTS, type PickTarget } from '../builder/buildSlots.ts'

export function isPickTarget(value: unknown): value is PickTarget | null {
  return value === null || isRecord(value) && SLOTS.some((slot) => slot.id === value.slot) &&
    (value.draftKey === null || isString(value.draftKey))
}

// API 검증과 달리 작성 도중의 잘못된 수량·용량도 복원해야 한다.
export function isPartDrafts(value: unknown): value is PartDraft[] {
  return Array.isArray(value) && value.every((part: unknown) => {
    if (!isRecord(part) || !PART_TYPES.some((info) => info.type === part.type)) return false
    return isString(part.key) && isString(part.displayName) &&
      (part.rawName === null || isString(part.rawName)) && typeof part.quantity === 'number' &&
      ['AUTO', 'MANUAL'].includes(String(part.source)) &&
      ['MATCHED', 'UNMATCHED'].includes(String(part.matchStatus)) &&
      (part.catalogProductId === null || isString(part.catalogProductId)) &&
      (part.catalogModelId == null || isString(part.catalogModelId)) &&
      (part.recognitionLevel == null || ['SPEC_GROUP', 'MODEL', 'PHYSICAL_VARIANT'].includes(String(part.recognitionLevel))) &&
      typeof part.edited === 'boolean' && typeof part.persisted === 'boolean' &&
      (part.capacityText === undefined || isString(part.capacityText)) &&
      (part.visualSlot === undefined || part.visualSlot === 'SSD' || part.visualSlot === 'HDD') &&
      isRecord(part.specs) && Object.values(part.specs).every((spec) =>
        spec === null || ['string', 'number', 'boolean'].includes(typeof spec))
  }) && new Set(value.map((part: PartDraft) => part.key)).size === value.length
}
