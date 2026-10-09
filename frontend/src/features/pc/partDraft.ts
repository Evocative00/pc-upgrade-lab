import type { PartInput, PartType, ScanResult } from '../pc-scan/types.ts'
import { PART_TYPES, getPartTypeInfo } from './partCategories.ts'
import type { PartDraft } from './types.ts'

export type PartStatus = 'linked' | 'model' | 'auto' | 'manual' | 'empty'

export const PART_STATUS_LABEL: Record<PartStatus, string> = {
  linked: '카탈로그 연결',
  model: '모델 확인 · 상품 미연결',
  auto: '자동 인식 · 미연결',
  manual: '직접 입력 · 미연결',
  empty: '미입력',
}

export function getPartStatus(part: PartInput): PartStatus {
  if (part.displayName.trim() === '') {
    return 'empty'
  }

  if (part.matchStatus === 'MATCHED') {
    return 'linked'
  }

  if (part.catalogModelId) return 'model'

  return part.source === 'AUTO' ? 'auto' : 'manual'
}

export function toDraft(part: PartInput): PartDraft {
  return { ...part, key: crypto.randomUUID(), edited: false, persisted: false }
}

// API에는 과거 편집 여부가 없다. 저장된 AUTO 부품은 보완한 값일 수 있어 교체 전 확인한다.
// 새 스캔 결과는 toDraft를 써야 단순 반복 적용 때 불필요한 확인창이 뜨지 않는다.
export function toPersistedDraft(part: PartInput): PartDraft {
  return { ...toDraft(part), persisted: true }
}

export function createManualDraft(type: PartType): PartDraft {
  return toDraft({
    type,
    displayName: '',
    rawName: null,
    quantity: 1,
    source: 'MANUAL',
    catalogProductId: null,
    matchStatus: 'UNMATCHED',
    specs: {},
  })
}

// 표시 이름을 바꾸면 기존 카탈로그 연결은 맞지 않을 수 있으므로 해제한다.
// 자동 인식 항목이어도 source와 rawName은 그대로 둔다 (원문 보존 규칙).
export function renameDraft(draft: PartDraft, displayName: string): PartDraft {
  return {
    ...draft,
    displayName,
    catalogProductId: null,
    matchStatus: 'UNMATCHED',
    ...(draft.catalogModelId !== undefined ? { catalogModelId: null } : {}),
    ...(draft.recognitionLevel !== undefined ? { recognitionLevel: null } : {}),
    edited: true,
  }
}

export function linkCatalog(
  draft: PartDraft,
  product: { id: string; modelName: string; type: PartType; modelId?: string | null },
): PartDraft {
  if (product.type !== draft.type || !product.id.trim() || product.id.length > 128 ||
    !product.modelName.trim() || product.modelName.length > 255) {
    throw new Error('현재 항목에 연결할 수 없는 부품입니다.')
  }
  // 장착 수량과 수집 제원은 사용자 PC의 사실이다. 카탈로그의 RAM 묶음 수량 등을 복사하지 않는다.
  return {
    ...draft,
    displayName: product.modelName,
    catalogProductId: product.id,
    matchStatus: 'MATCHED',
    ...(draft.catalogModelId !== undefined || draft.recognitionLevel !== undefined
      ? { catalogModelId: null, recognitionLevel: null } : {}),
    edited: true,
  }
}

export function unlinkCatalog(draft: PartDraft): PartDraft {
  return { ...draft, catalogProductId: null, matchStatus: 'UNMATCHED',
    ...(draft.catalogModelId !== undefined ? { catalogModelId: null } : {}),
    ...(draft.recognitionLevel !== undefined ? { recognitionLevel: null } : {}), edited: true }
}

export function linkCatalogModel(draft: PartDraft,
  model: { id: string; modelName: string; type: PartType; kind: string }): PartDraft {
  if (model.type !== draft.type || !model.id.trim() || model.id.length > 128 ||
    !model.modelName.trim() || model.modelName.length > 255) {
    throw new Error('현재 항목에 확인할 수 없는 부품 모델입니다.')
  }
  return { ...draft, displayName: model.modelName, catalogModelId: model.id,
    recognitionLevel: model.kind === 'RAM_SPEC_GROUP' ? 'SPEC_GROUP' : 'MODEL',
    catalogProductId: null, matchStatus: 'UNMATCHED', edited: true }
}

// 장치 1개당 용량. 모르면 0이 아니라 null로 둔다.
export function getCapacityBytes(part: PartInput): number | null {
  const value = part.specs.capacityBytes

  // 잘못된 0을 '미확인'으로 감추지 않는다. 유효성 검사는 별도로 수행한다.
  return typeof value === 'number' ? value : null
}

export function setCapacityBytes(draft: PartDraft, bytes: number | null): PartDraft {
  return {
    ...draft,
    specs: { ...draft.specs, capacityBytes: bytes },
    capacityText: undefined,
    edited: true,
  }
}

export function isKnownCapacityValid(value: unknown): boolean {
  return (
    value === null || value === undefined ||
    (typeof value === 'number' && Number.isFinite(value) && value > 0)
  )
}

export function setCapacityInput(
  draft: PartDraft,
  value: string,
  badInput = false,
): PartDraft {
  const unit = getPartTypeInfo(draft.type).capacityUnit
  if (unit === null) return draft

  // 실제로 비워 둔 경우에만 미확인(null)으로 둔다.
  // 숫자 입력 오류나 반올림된 0은 유지하고 검증에서 막아야 조용히 저장되지 않는다.
  const bytes = badInput
    ? NaN
    : value.trim() === '' ? null : Math.round(Number(value) * unit.bytes)
  return { ...setCapacityBytes(draft, bytes), capacityText: value }
}

export function formatCapacity(part: PartInput): string | null {
  const unit = getPartTypeInfo(part.type).capacityUnit
  const bytes = getCapacityBytes(part)

  if (unit === null || bytes === null) {
    return null
  }

  if (!isKnownCapacityValid(bytes)) {
    return '유효하지 않은 용량'
  }

  return `${Number((bytes / unit.bytes).toFixed(2))} ${unit.label}`
}

// 용량 이외의 제원을 "키: 값" 목록으로. 미확인(null)은 그대로 알린다.
export function formatOtherSpecs(part: PartInput): string[] {
  return Object.entries(part.specs)
    .filter(([key]) => key !== 'capacityBytes')
    .map(([key, value]) => `${key}: ${value === null ? '미확인' : String(value)}`)
}

// 입력 화면용: 부품 종류마다 최소 한 줄이 보이도록 빈 항목을 채우고, 종류 순서로 정렬한다.
export function withEmptyRows(drafts: PartDraft[]): PartDraft[] {
  return PART_TYPES.flatMap(({ type }) => {
    const rows = drafts.filter((draft) => draft.type === type)

    return rows.length > 0 ? rows : [createManualDraft(type)]
  })
}

export function partsOfType<T extends PartInput>(parts: T[], type: PartType): T[] {
  return parts.filter((part) => part.type === type)
}

// 저장용: 빈 항목을 빼고 화면 전용 필드를 제거한다.
export function toPartInputs(drafts: PartDraft[]): PartInput[] {
  return drafts
    .filter((draft) => getPartStatus(draft) !== 'empty')
    .map((draft) => ({
      type: draft.type,
      displayName: draft.displayName.trim(),
      rawName: draft.rawName,
      quantity: draft.quantity,
      source: draft.source,
      catalogProductId: draft.catalogProductId,
      matchStatus: draft.matchStatus,
      ...(draft.catalogModelId !== undefined ? { catalogModelId: draft.catalogModelId } : {}),
      ...(draft.recognitionLevel !== undefined ? { recognitionLevel: draft.recognitionLevel } : {}),
      specs: draft.specs,
    }))
}

// 공통 규격의 입력 제한. 서버도 같은 규칙으로 검증한다.
export function validatePcRequest(name: string, parts: PartInput[]): string[] {
  const errors: string[] = []

  if (name.trim() === '') {
    errors.push('PC 이름을 입력해 주세요.')
  } else if (name.trim().length > 100) {
    errors.push('PC 이름은 100자까지 입력할 수 있습니다.')
  }

  if (parts.length === 0) {
    errors.push('부품을 한 개 이상 입력해 주세요.')
  } else if (parts.length > 64) {
    errors.push('부품은 64개까지 저장할 수 있습니다.')
  }

  for (const part of parts) {
    const label = getPartTypeInfo(part.type).label

    const modelId = part.catalogModelId ?? null
    const level = part.recognitionLevel ?? null
    if (modelId !== null && (!modelId.trim() || modelId.length > 128) ||
      level !== null && !['SPEC_GROUP', 'MODEL', 'PHYSICAL_VARIANT'].includes(level) ||
      modelId === null && level !== null && level !== 'PHYSICAL_VARIANT' ||
      modelId !== null && level === null ||
      level === 'PHYSICAL_VARIANT' && (part.matchStatus !== 'MATCHED' || !part.catalogProductId)) {
      errors.push(`${label}: 모델 확인 수준과 부품 연결을 다시 확인해 주세요.`)
    }

    if (part.displayName.length > 255) {
      errors.push(`${label}: 이름은 255자까지 입력할 수 있습니다.`)
    }

    if (!Number.isInteger(part.quantity) || part.quantity < 1 || part.quantity > 64) {
      errors.push(`${label} "${part.displayName}": 수량은 1~64 사이 정수여야 합니다.`)
    }

    // 백엔드 PartInput과 같은 규칙: 미확인은 null, 알려진 용량은 유한한 양수다.
    for (const key of ['capacityBytes', 'vramBytes']) {
      if (!isKnownCapacityValid(part.specs[key])) {
        const capacityLabel = key === 'vramBytes' ? '그래픽 메모리 용량' : '용량'
        errors.push(`${label} "${part.displayName}": ${capacityLabel}은 0보다 큰 유효한 값이어야 합니다. 모르면 비워 주세요.`)
      }
    }
  }

  return errors
}

export type ScanApplyPlan = {
  next: PartDraft[]
  // 새 결과로 바뀌는 AUTO 항목 중 저장했거나 보완한 항목 수. 0보다 크면 확인을 받는다.
  editedReplaced: number
  applied: number
  // 그대로 둔 직접 입력 항목 수
  keptManual: number
}

// 자동 인식 결과를 입력 폼에 반영하는 계획을 만든다 (docs/week1-contract.md 규칙).
// - 결과에 포함된 종류만 다룬다. 일부 수집 실패로 빠진 종류의 기존 값은 지우지 않는다.
// - 해당 종류의 기존 AUTO 항목과 빈 줄은 교체하고, MANUAL 항목은 보존한다.
export function planScanApply(drafts: PartDraft[], result: ScanResult): ScanApplyPlan {
  const scannedTypes = new Set(result.parts.map((part) => part.type))
  const isReplaced = (draft: PartDraft) =>
    scannedTypes.has(draft.type) &&
    (draft.source === 'AUTO' || getPartStatus(draft) === 'empty')

  const replaced = drafts.filter(isReplaced)
  const kept = drafts.filter((draft) => !isReplaced(draft))

  return {
    next: withEmptyRows([...kept, ...result.parts.map(toDraft)]),
    editedReplaced: replaced.filter(
      (draft) => draft.source === 'AUTO' &&
        (draft.persisted || draft.edited || draft.matchStatus === 'MATCHED'),
    ).length,
    applied: result.parts.length,
    keptManual: kept.filter(
      (draft) => scannedTypes.has(draft.type) && draft.source === 'MANUAL',
    ).length,
  }
}
