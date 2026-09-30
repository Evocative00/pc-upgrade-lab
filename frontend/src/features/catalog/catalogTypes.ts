import type { PartType } from '../pc-scan/types.ts'

export type CatalogProduct = {
  id: string
  type: PartType
  manufacturer: string
  modelName: string
  partNumber: string | null
  verificationStatus: 'UNVERIFIED' | 'PARTIAL' | 'CORE_VERIFIED'
  active: boolean
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
