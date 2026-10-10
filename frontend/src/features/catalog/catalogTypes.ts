import type { PartType } from '../pc-scan/types.ts'

export type CurrentPrice = {
  amountKrw: number
  sourceName: string
  sourceUrl: string
  observedAt: string
}

export type CatalogProduct = {
  id: string
  type: PartType
  manufacturer: string
  modelName: string
  partNumber: string | null
  canonicalId?: string | null
  modelId?: string | null
  identityKind?: 'LEGACY_UNCLASSIFIED' | 'MODEL_REFERENCE' | 'PHYSICAL_VARIANT' | 'RETAIL_KIT'
  role?: 'UNASSIGNED' | 'INSTALLED_PC_REFERENCE' | 'PURCHASE_CANDIDATE' | 'BOTH'
  verificationStatus: 'UNVERIFIED' | 'PARTIAL' | 'CORE_VERIFIED'
  active: boolean
  currentPrice: CurrentPrice | null
  referencePrice: {
    amountKrw: number | null
    status: 'UNCONFIRMED' | 'INSUFFICIENT_HISTORY' | 'CONFIRMED'
    updatedAt: string
  }
  createdAt: string
  updatedAt: string
}

export type CatalogAttribution = {
  name: string
  notice: string
  url: string
  license: string
  licenseUrl: string
}

export type CatalogPage = {
  items: CatalogProduct[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  attributions: CatalogAttribution[]
}

export type CatalogSource = {
  sourceName: 'BUILDCORES' | 'MANUFACTURER' | 'MANUAL'
  externalId: string | null
  sourceRevision: string | null
  sourceUrl: string
  retrievedAt: string
}

export type PowerConnector = { connectorType: string; connectorCount: number }
export type CatalogSpecification = Record<string, string | number | boolean | null | PowerConnector[]>

export type CatalogDetail = {
  product: CatalogProduct
  specification: CatalogSpecification
  sources: CatalogSource[]
  attributions: CatalogAttribution[]
}

export type CatalogModel = {
  id: string
  canonicalId: string
  type: PartType
  manufacturer: string
  modelName: string
  kind: 'CPU_MODEL' | 'GPU_CHIP_MODEL' | 'BOARD_MODEL' | 'RAM_MODULE_MODEL' | 'RAM_SPEC_GROUP' | 'STORAGE_MODEL'
  role: 'UNASSIGNED' | 'INSTALLED_PC_REFERENCE' | 'PURCHASE_CANDIDATE' | 'BOTH'
  verificationStatus: 'UNVERIFIED' | 'PARTIAL' | 'CORE_VERIFIED'
  family: string | null
  series: string | null
  createdAt: string
  updatedAt: string
}

export type CatalogModelPage = {
  items: CatalogModel[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type CatalogModelSource = CatalogSource & { reviewScope: string }

export type CatalogModelProductCandidate = Pick<CatalogProduct,
  'id' | 'canonicalId' | 'type' | 'manufacturer' | 'modelName' | 'partNumber' | 'identityKind' | 'role'>

export type CatalogModelDetail = {
  model: CatalogModel
  aliases: { rawAlias: string; normalizedAlias: string; evidence: CatalogModelSource }[]
  sources: CatalogModelSource[]
  productCandidates: CatalogModelProductCandidate[]
}
