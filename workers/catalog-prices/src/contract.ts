export interface PriceIdentity {
  canonicalId: string;
  type: string;
  manufacturer: string;
  modelName: string;
  partNumber: string | null;
  identityKind: string;
  role: string;
  verificationStatus: string;
  active: boolean;
  saleUnit: string;
  moduleCount: number | null;
}

export interface CurrentPrice {
  amountKrw: number;
  sourceName: string;
  sourceUrl: string;
  observedAt: string;
}

export interface CatalogSnapshot {
  schemaVersion: number;
  catalogVersion: string;
  priceVersion?: string;
  policy: { staleAfterSeconds: number; expireAfterSeconds: number };
  products: { identity: PriceIdentity; price: CurrentPrice | null }[];
}

export interface PriceItem {
  canonicalId: string;
  product: PriceIdentity | null;
  price: CurrentPrice | null;
  status: "OK" | "NO_PRICE" | "UNKNOWN_PRODUCT";
  freshness: "FRESH" | "STALE" | "EXPIRED" | "NO_PRICE";
}

export interface WorkerEnv {
  SHARED_PRICES_API_TOKEN?: string;
  REFERENCE_PRICES_PREVIEW_ENABLED?: string;
}

export interface ReferenceSourceQuote {
  canonicalId: string | null;
  modelName: string;
  amountKrw: number;
  sourceName: string;
  sourceUrl: string;
  sourceDate: string | null;
  saleUnit: "PRODUCT" | "RAM_KIT";
  moduleCount: number | null;
}

export interface ReferenceEstimate {
  amountKrw: number;
  basis: "HISTORICAL_RETAIL" | "MODEL_RETAIL_REFERENCE" | "LAUNCH_PRICE" | "SIMILAR_PART_ESTIMATE";
  identityScope: "EXACT_PRODUCT" | "MODEL" | "SIMILAR_SPEC";
  saleUnit: "PRODUCT" | "RAM_KIT";
  moduleCount: number | null;
  sourceDate: string | null;
  reviewedAt: string;
  confidence: "VERIFIED_MODEL" | "ESTIMATED";
  method: "DIRECT_MODEL_QUOTE" | "OFFICIAL_MODEL_LAUNCH" | "SPEC_NEIGHBOUR_MEDIAN";
  rangeLowKrw: number | null;
  rangeHighKrw: number | null;
  sourceQuotes: ReferenceSourceQuote[];
  notes: string;
}

export interface ReferenceCatalogSnapshot {
  schemaVersion: 1;
  catalogVersion: string;
  referenceVersion: string;
  generatedAt: string;
  publicationApproved: boolean;
  products: { identity: PriceIdentity; referenceEstimate: ReferenceEstimate }[];
}

export interface ReferenceItem {
  canonicalId: string;
  product: PriceIdentity | null;
  referenceEstimate: ReferenceEstimate | null;
  status: "OK" | "NO_REFERENCE" | "UNKNOWN_PRODUCT";
}
