import { CatalogApiError } from './catalogClient.ts'
import { createCatalogRead, isNullableText, isRecord, isText, isWebUrl } from './catalogRead.ts'

export type StorageSlotRule = {
  ruleKey: string; cpuCondition: string | null; biosCondition: string | null
  effect: 'DISABLED' | 'LANES_REDUCED' | 'PORT_SHARED' | 'CONDITIONAL_SUPPORT' | 'UNSTRUCTURED'
  affectedSlotKey: string | null; rawCondition: string
}
export type StorageSlot = {
  slotKey: string; connectorType: 'M2' | 'SATA'; connectorKey: string | null
  supportedLengthCodes: string[]; supportedBusInterfaces: string[]; supportedProtocols: string[]
  maxPcieVersion: string | null; maxPcieLanes: number | null; sataVersion: string | null
  laneSource: string | null; nvmeBootSupport: boolean | null; notes: string | null; rules: StorageSlotRule[]
}
export type StorageSupportProfile = {
  revisionKey: string; revisionScope: 'MODEL' | 'EXACT'; hardwareRevision: string | null
  completeDataKnown: boolean; conditions: string | null; slots: StorageSlot[]
  sources: { sourceUrl: string; checkedAt: string; sourceRevision: string | null
    documentLocation: string | null; supportedFacts: string }[]
}
export type StorageSupport = {
  motherboardProductId: string; dataAvailable: boolean; completeDataKnown: boolean
  profiles: StorageSupportProfile[]; note: string
}
const codes = (value: unknown, allowed: string[]) => Array.isArray(value) &&
  value.length <= 32 && value.every((item: unknown) => typeof item === 'string' && allowed.includes(item)) &&
  new Set(value).size === value.length
function isRule(value: unknown): boolean {
  return isRecord(value) && isText(value.ruleKey, 64) && isNullableText(value.cpuCondition) &&
    isNullableText(value.biosCondition) && isNullableText(value.affectedSlotKey) && isText(value.rawCondition, 4000) &&
    ['DISABLED', 'LANES_REDUCED', 'PORT_SHARED', 'CONDITIONAL_SUPPORT', 'UNSTRUCTURED'].includes(String(value.effect))
}
function isSlot(value: unknown): boolean {
  return isRecord(value) && isText(value.slotKey, 64) && ['M2', 'SATA'].includes(String(value.connectorType)) &&
    (value.connectorKey === null || ['B', 'M', 'B_M'].includes(String(value.connectorKey))) &&
    codes(value.supportedLengthCodes, ['2230', '2242', '2260', '2280', '22110']) &&
    codes(value.supportedBusInterfaces, ['PCIE', 'SATA']) && codes(value.supportedProtocols, ['NVME', 'ATA']) &&
    isNullableText(value.maxPcieVersion) && (value.maxPcieLanes === null ||
      Number.isSafeInteger(value.maxPcieLanes) && Number(value.maxPcieLanes) > 0) &&
    isNullableText(value.sataVersion) && (value.laneSource === null || ['CPU', 'CHIPSET'].includes(String(value.laneSource))) &&
    (value.nvmeBootSupport === null || typeof value.nvmeBootSupport === 'boolean') && isNullableText(value.notes) &&
    Array.isArray(value.rules) && value.rules.length <= 100 && value.rules.every(isRule)
}
function isProfile(value: unknown): boolean {
  return isRecord(value) && isText(value.revisionKey, 64) &&
    (value.revisionScope === 'MODEL' && value.hardwareRevision === null ||
      value.revisionScope === 'EXACT' && isText(value.hardwareRevision, 48)) &&
    typeof value.completeDataKnown === 'boolean' && isNullableText(value.conditions) &&
    Array.isArray(value.slots) && value.slots.length <= 100 && value.slots.every(isSlot) &&
    Array.isArray(value.sources) && value.sources.length > 0 && value.sources.length <= 100 &&
    value.sources.every((source: unknown) => isRecord(source) && isWebUrl(source.sourceUrl) &&
      isText(source.checkedAt) && Number.isFinite(Date.parse(source.checkedAt)) &&
      isNullableText(source.sourceRevision) && isNullableText(source.documentLocation) && isText(source.supportedFacts, 4000))
}
function isSupport(value: unknown): value is StorageSupport {
  return isRecord(value) && isText(value.motherboardProductId, 128) &&
    typeof value.dataAvailable === 'boolean' && typeof value.completeDataKnown === 'boolean' && isText(value.note, 4000) &&
    Array.isArray(value.profiles) && value.profiles.length <= 100 && value.profiles.every(isProfile) &&
    value.dataAvailable === (value.profiles.length > 0) && (value.dataAvailable || value.completeDataKnown === false)
}
export function createStorageSupportClient(fetcher: typeof fetch = globalThis.fetch) {
  const read = createCatalogRead(fetcher)
  return {
    async get(id: string, signal?: AbortSignal): Promise<StorageSupport> {
      const result = await read(`/api/catalog/products/${encodeURIComponent(id)}/storage-support`, isSupport, signal)
      if (result.motherboardProductId !== id) throw new CatalogApiError('선택한 메인보드와 슬롯 자료가 일치하지 않습니다.',
        200, 'INVALID_RESPONSE')
      return result
    },
  }
}
export const storageSupportClient = createStorageSupportClient()
