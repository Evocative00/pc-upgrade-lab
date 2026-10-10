import type { PartType } from '../pc-scan/types.ts'

export type CurrentPrice = {
  amountKrw: number
  sourceName: string
  sourceUrl: string
  observedAt: string
}

export type ReferenceEstimate = {
  amountKrw: number
  basis: 'HISTORICAL_RETAIL' | 'MODEL_RETAIL_REFERENCE' | 'LAUNCH_PRICE' | 'SIMILAR_PART_ESTIMATE'
  identityScope: 'EXACT_PRODUCT' | 'MODEL' | 'SIMILAR_SPEC'
  saleUnit: 'PRODUCT' | 'RAM_KIT'
  moduleCount: number | null
  sourceDate: string | null
  reviewedAt: string
  confidence: 'VERIFIED_MODEL' | 'ESTIMATED'
  method: 'DIRECT_MODEL_QUOTE' | 'OFFICIAL_MODEL_LAUNCH' | 'SPEC_NEIGHBOUR_MEDIAN'
  rangeLowKrw: number | null
  rangeHighKrw: number | null
  sourceQuotes: { canonicalId: string | null; modelName: string; amountKrw: number;
    sourceName: string; sourceUrl: string; sourceDate: string | null;
    saleUnit: 'PRODUCT' | 'RAM_KIT'; moduleCount: number | null }[]
  notes: string
}

export type CatalogPriceStatus = {
  origin: 'LOCAL' | 'SHARED'
  lookupStatus: 'OK' | 'NO_PRICE' | 'NOT_IN_SCOPE' | 'UNKNOWN_PRODUCT' | 'UNAVAILABLE'
  freshness: 'FRESH' | 'STALE' | 'EXPIRED' | 'NO_PRICE' | null
  catalogVersion: string | null
  checkedAt: string | null
  lastSuccessAt: string | null
  includedInTotal: boolean
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
  referenceEstimate?: ReferenceEstimate | null
  priceStatus?: CatalogPriceStatus | null
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
