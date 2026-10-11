import type { CatalogSnapshot, PriceIdentity, ReferenceCatalogSnapshot, ReferenceEstimate } from "./contract";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const IDENTITY_KEYS = ["canonicalId", "type", "manufacturer", "modelName", "partNumber", "identityKind",
  "role", "verificationStatus", "active", "saleUnit", "moduleCount"] as const;
const ESTIMATE_KEYS = ["amountKrw", "basis", "identityScope", "saleUnit", "moduleCount", "sourceDate",
  "reviewedAt", "confidence", "method", "rangeLowKrw", "rangeHighKrw", "sourceQuotes", "notes"];
const QUOTE_KEYS = ["canonicalId", "modelName", "amountKrw", "sourceName", "sourceUrl", "sourceDate", "saleUnit", "moduleCount"];

function requireValid(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(`Invalid reference price snapshot: ${message}`);
}
function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
function exactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  return Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key));
}
function text(value: unknown, maximum: number): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= maximum
    && value === value.trim() && !/[\u0000-\u001f\u007f]/.test(value);
}
function amount(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 1 && Number(value) <= 999_999_999_999;
}
function instant(value: unknown): value is string {
  if (!text(value, 40)) return false;
  const match = /^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.\d{1,9})?Z$/.exec(value);
  if (match?.[1] === undefined) return false;
  const millis = Date.parse(`${match[1]}Z`);
  return Number.isFinite(millis) && millis >= 0
    && new Date(millis).toISOString().substring(0, 19) === match[1];
}
function nanos(value: string): bigint {
  const match = /^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z$/.exec(value)!;
  return BigInt(Date.parse(`${match[1]}Z`)) * 1_000_000n + BigInt((match[2] ?? "").padEnd(9, "0"));
}
function date(value: unknown, latest: string): boolean {
  if (value === null) return true;
  if (!text(value, 10) || !/^\d{4}-\d\d-\d\d$/.test(value) || value < "1970-01-01" || value > latest) return false;
  const millis = Date.parse(`${value}T00:00:00Z`);
  return Number.isFinite(millis) && new Date(millis).toISOString().substring(0, 10) === value;
}
function sourceUrl(value: unknown): boolean {
  if (!text(value, 2048) || !value.startsWith("https://") || /\s/.test(value)
    || /%(?![0-9a-fA-F]{2})/.test(value) || value.includes("\\")) return false;
  try {
    const url = new URL(value);
    return url.protocol === "https:" && !!url.hostname && !url.username && !url.password
      && !value.substring("https://".length).split(/[/?#]/)[0]!.includes("@");
  } catch { return false; }
}

function validateEstimate(value: unknown, identity: PriceIdentity, catalog: Map<string, CatalogSnapshot["products"][number]>,
  generatedAt: string): asserts value is ReferenceEstimate {
  requireValid(record(value) && exactKeys(value, ESTIMATE_KEYS), "reference estimate fields");
  requireValid(amount(value.amountKrw), "reference amount");
  requireValid(value.saleUnit === identity.saleUnit && value.moduleCount === identity.moduleCount, "reference sales unit differs");
  requireValid(instant(value.reviewedAt) && nanos(value.reviewedAt) <= nanos(generatedAt), "reference review time");
  requireValid(date(value.sourceDate, generatedAt.substring(0, 10)), "reference source date");
  requireValid(text(value.notes, 1000), "reference explanation");
  requireValid(typeof value.basis === "string"
    && ["HISTORICAL_RETAIL", "MODEL_RETAIL_REFERENCE", "LAUNCH_PRICE", "SIMILAR_PART_ESTIMATE"].includes(value.basis)
    && typeof value.identityScope === "string" && ["EXACT_PRODUCT", "MODEL", "SIMILAR_SPEC"].includes(value.identityScope),
  "reference scope and basis");
  const estimated = value.basis === "SIMILAR_PART_ESTIMATE";
  if (estimated) {
    requireValid(value.identityScope === "SIMILAR_SPEC" && value.confidence === "ESTIMATED"
      && value.method === "SPEC_NEIGHBOUR_MEDIAN", "similar reference must remain an estimate");
  } else {
    requireValid(value.identityScope === (value.basis === "HISTORICAL_RETAIL" ? "EXACT_PRODUCT" : "MODEL")
      && value.confidence === "VERIFIED_MODEL"
      && value.method === (value.basis === "LAUNCH_PRICE" ? "OFFICIAL_MODEL_LAUNCH" : "DIRECT_MODEL_QUOTE"),
    "reference basis and method differ");
    requireValid(value.rangeLowKrw === null && value.rangeHighKrw === null, "direct reference must not carry an estimate range");
  }
  requireValid((value.rangeLowKrw === null) === (value.rangeHighKrw === null), "incomplete reference range");
  if (value.rangeLowKrw !== null)
    requireValid(amount(value.rangeLowKrw) && amount(value.rangeHighKrw)
      && value.rangeLowKrw <= value.amountKrw && value.amountKrw <= value.rangeHighKrw, "reference range");
  requireValid(!estimated || value.rangeLowKrw !== null, "estimate requires a source range");
  requireValid(Array.isArray(value.sourceQuotes) && value.sourceQuotes.length >= 1 && value.sourceQuotes.length <= 3,
    "reference requires 1..3 source quotes");
  const seen = new Set<string>();
  for (const quote of value.sourceQuotes) {
    requireValid(record(quote) && exactKeys(quote, QUOTE_KEYS), "source quote fields");
    requireValid(text(quote.modelName, 255) && text(quote.sourceName, 100) && sourceUrl(quote.sourceUrl)
      && amount(quote.amountKrw) && date(quote.sourceDate, generatedAt.substring(0, 10)), "source quote evidence");
    if (identity.type === "RAM") {
      requireValid(quote.saleUnit === "PRODUCT" ? quote.moduleCount === 1
        : quote.saleUnit === "RAM_KIT" && Number.isInteger(quote.moduleCount)
          && Number(quote.moduleCount) >= 1 && Number(quote.moduleCount) <= 64, "RAM source sales unit");
    } else requireValid(quote.saleUnit === "PRODUCT" && quote.moduleCount === null, "non-RAM source sales unit");
    requireValid(estimated || quote.saleUnit === identity.saleUnit && quote.moduleCount === identity.moduleCount,
      "direct reference source sales unit differs");
    const key = JSON.stringify(QUOTE_KEYS.map(field => quote[field]));
    requireValid(!seen.has(key), "duplicate source quote"); seen.add(key);
    if (quote.canonicalId !== null) {
      requireValid(typeof quote.canonicalId === "string" && UUID.test(quote.canonicalId), "source canonical ID");
      const source = catalog.get(quote.canonicalId)?.identity;
      requireValid(source !== undefined && source.type === identity.type && source.modelName === quote.modelName
        && source.saleUnit === quote.saleUnit && source.moduleCount === quote.moduleCount,
      "source identity or sales unit differs");
      requireValid(!estimated || quote.canonicalId !== identity.canonicalId, "similar reference cannot quote itself");
    }
  }
}

/** Content hashes are also checked by the explicit public export/build script before bundling. */
export function referenceVersionInput(snapshot: ReferenceCatalogSnapshot): string {
  return JSON.stringify(snapshot.products);
}

export function validateReferenceSnapshot(candidate: unknown, active: CatalogSnapshot): ReferenceCatalogSnapshot {
  requireValid(record(candidate) && exactKeys(candidate,
    ["schemaVersion", "catalogVersion", "referenceVersion", "generatedAt", "publicationApproved", "products"]), "snapshot fields");
  requireValid(candidate.schemaVersion === 1 && candidate.catalogVersion === active.catalogVersion
    && text(candidate.referenceVersion, 80) && /^references-v1-[0-9a-f]{64}$/.test(candidate.referenceVersion), "reference version");
  requireValid(instant(candidate.generatedAt) && typeof candidate.publicationApproved === "boolean", "reference publication metadata");
  requireValid(active.products.length === 308 && active.products.filter(entry => entry.price !== null).length === 81,
    "known active 308/81 catalog");
  const catalog = new Map(active.products.map(entry => [entry.identity.canonicalId, entry]));
  const missing = new Set(active.products.filter(entry => entry.price === null).map(entry => entry.identity.canonicalId));
  requireValid(Array.isArray(candidate.products) && candidate.products.length === 227, "all 227 unpriced products are required");
  const seen = new Set<string>();
  for (const entry of candidate.products) {
    requireValid(record(entry) && exactKeys(entry, ["identity", "referenceEstimate"])
      && record(entry.identity) && exactKeys(entry.identity, IDENTITY_KEYS), "reference product fields");
    const identity = entry.identity;
    requireValid(typeof identity.canonicalId === "string" && missing.has(identity.canonicalId)
      && !seen.has(identity.canonicalId), "reference product is missing, priced or duplicated");
    const approved = catalog.get(identity.canonicalId)!.identity;
    requireValid(IDENTITY_KEYS.every(key => identity[key] === approved[key]), "reference identity differs");
    seen.add(identity.canonicalId);
    validateEstimate(entry.referenceEstimate, approved, catalog, candidate.generatedAt);
  }
  requireValid([...missing].every(id => seen.has(id)), "unpriced reference product missing");
  return candidate as unknown as ReferenceCatalogSnapshot;
}
