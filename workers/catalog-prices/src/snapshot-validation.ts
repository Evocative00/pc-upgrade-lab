import type { CatalogSnapshot, CurrentPrice, PriceIdentity } from "./contract";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const POSITIVE_RETAIL_ID = /^[1-9][0-9]{0,15}$/;
const IDENTITY_KEYS = ["canonicalId", "type", "manufacturer", "modelName", "partNumber",
  "identityKind", "role", "verificationStatus", "active", "saleUnit", "moduleCount"] as const;

function requireValid(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(`Invalid approved catalog snapshot: ${message}`);
}

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function exactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  const actual = Object.keys(value);
  return actual.length === keys.length && keys.every((key) => Object.hasOwn(value, key));
}

function nonblank(value: unknown, maximum = 512): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= maximum
    && value.trim() === value && !/[\u0000-\u001f\u007f]/.test(value);
}

/** Returns the sale-page ID; an optional reviewed offer ID must match exactly. */
export function validatePriceSource(sourceName: string, sourceUrl: string, externalId?: string): string {
  requireValid(typeof sourceUrl === "string" && sourceUrl.length <= 2048
    && sourceUrl.trim() === sourceUrl && !/[\u0000-\u0020\u007f]/.test(sourceUrl), "price source URL");
  let saleId: string | undefined;
  if (sourceName === "DANAWA") {
    saleId = /^https:\/\/prod\.danawa\.com\/info\/\?pcode=([0-9]{1,128})$/.exec(sourceUrl)?.[1];
  } else if (sourceName === "SAMSUNG_CNH") {
    saleId = /^https:\/\/www\.samsungzip\.shop\/goods\/goods_view\.php\?goodsNo=([0-9]{1,128})$/.exec(sourceUrl)?.[1];
  } else if (sourceName === "ICODA") {
    saleId = /^https:\/\/usr\.icoda\.co\.kr\/item\/view\/([0-9]+)$/.exec(sourceUrl)?.[1];
    requireValid(saleId !== undefined && POSITIVE_RETAIL_ID.test(saleId), "ICODA sale ID");
  } else if (sourceName === "COMPUZONE") {
    const query = /^https:\/\/www\.compuzone\.co\.kr\/product\/product_detail\.htm\?([^#]+)$/.exec(sourceUrl)?.[1];
    requireValid(query !== undefined, "COMPUZONE URL");
    const seen = new Set<string>();
    for (const pair of query.split("&")) {
      const part = /^(ProductNo|DivNo|MediumDivNo)=([0-9]{1,16})$/.exec(pair);
      requireValid(part !== null && part[1] !== undefined && part[2] !== undefined, "COMPUZONE query");
      const [, key, value] = part;
      requireValid(key !== undefined && value !== undefined && !seen.has(key), "duplicate COMPUZONE query");
      seen.add(key);
      if (key === "ProductNo") saleId = value;
    }
    requireValid(saleId !== undefined && POSITIVE_RETAIL_ID.test(saleId), "COMPUZONE sale ID");
  }
  requireValid(saleId !== undefined, "unapproved price source");
  requireValid(externalId === undefined || externalId === saleId, "price source ID mismatch");
  return saleId;
}

function validInstant(value: unknown): boolean {
  if (!nonblank(value, 40)) return false;
  const match = /^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.\d{1,9})?Z$/.exec(value);
  if (match?.[1] === undefined) return false;
  const millis = Date.parse(`${match[1]}Z`);
  return Number.isFinite(millis) && new Date(millis).toISOString().substring(0, 19) === match[1];
}

function validateIdentity(value: unknown): asserts value is PriceIdentity {
  requireValid(record(value) && exactKeys(value, IDENTITY_KEYS), "identity fields");
  requireValid(nonblank(value.canonicalId, 36) && value.canonicalId.length === 36
    && UUID.test(value.canonicalId), "canonical ID");
  for (const key of ["type", "manufacturer", "modelName", "identityKind", "role", "verificationStatus", "saleUnit"])
    requireValid(nonblank(value[key]), `identity ${key}`);
  requireValid(value.partNumber === null || nonblank(value.partNumber, 256), "part number");
  requireValid(typeof value.active === "boolean", "identity active");
  if (value.type === "RAM") {
    requireValid(Number.isSafeInteger(value.moduleCount)
      && ((value.saleUnit === "PRODUCT" && value.moduleCount === 1)
        || (value.saleUnit === "RAM_KIT" && Number(value.moduleCount) >= 2
          && Number(value.moduleCount) <= 16)), "RAM sale unit");
  } else {
    requireValid(value.saleUnit === "PRODUCT" && value.moduleCount === null, "product sale unit");
  }
}

function validatePrice(value: unknown): asserts value is CurrentPrice | null {
  if (value === null) return;
  requireValid(record(value) && exactKeys(value, ["amountKrw", "sourceName", "sourceUrl", "observedAt"]), "price fields");
  requireValid(Number.isSafeInteger(value.amountKrw) && Number(value.amountKrw) > 0
    && Number(value.amountKrw) <= 999_999_999_999, "price amount");
  requireValid(typeof value.sourceName === "string" && typeof value.sourceUrl === "string", "price source");
  validatePriceSource(value.sourceName, value.sourceUrl);
  requireValid(validInstant(value.observedAt), "price observation time");
}

/** Public export validation; underlying sale-configuration evidence is checked by the Java loader. */
export function validateFullCatalogSnapshot(candidate: unknown, approvedBase: CatalogSnapshot,
  { allowReviewedBoardAdditions = false }: { allowReviewedBoardAdditions?: boolean } = {}): CatalogSnapshot {
  requireValid(record(candidate) && exactKeys(candidate,
    ["schemaVersion", "catalogVersion", "priceVersion", "policy", "products"]), "snapshot fields");
  requireValid(candidate.schemaVersion === 2 && nonblank(candidate.catalogVersion)
    && /^all-catalog-v1-[0-9a-f]{64}$/.test(candidate.catalogVersion), "catalog version");
  requireValid(nonblank(candidate.priceVersion) && /^prices-v1-[0-9a-f]{64}$/.test(candidate.priceVersion), "price version");
  requireValid(record(candidate.policy) && exactKeys(candidate.policy, ["staleAfterSeconds", "expireAfterSeconds"])
    && candidate.policy.staleAfterSeconds === 172800 && candidate.policy.expireAfterSeconds === 604800, "freshness policy");
  requireValid(approvedBase.schemaVersion === 2 && approvedBase.products.length === 307
    && new Set(approvedBase.products.map((entry) => entry.identity.canonicalId)).size === 307, "known 307-product base");
  requireValid(Array.isArray(candidate.products) && candidate.products.length >= 307
    && candidate.products.length <= 1000, "base product count");
  requireValid(allowReviewedBoardAdditions || candidate.products.length === 307, "board additions require explicit preview");
  const base = new Map(approvedBase.products.map((entry) => [entry.identity.canonicalId, entry]));
  const seen = new Set<string>();
  for (const entry of candidate.products) {
    requireValid(record(entry) && exactKeys(entry, ["identity", "price"]), "product fields");
    validateIdentity(entry.identity);
    const identity = entry.identity;
    requireValid(!seen.has(identity.canonicalId), "duplicate canonical ID");
    seen.add(identity.canonicalId);
    const known = base.get(identity.canonicalId);
    if (known !== undefined) {
      requireValid(IDENTITY_KEYS.every((key) => identity[key] === known.identity[key]), "existing identity changed");
    } else {
      requireValid(allowReviewedBoardAdditions && identity.type === "MOTHERBOARD"
        && identity.identityKind === "PHYSICAL_VARIANT"
        && (identity.role === "PURCHASE_CANDIDATE" || identity.role === "BOTH")
        && identity.partNumber === null && !identity.active && identity.verificationStatus === "UNVERIFIED"
        && identity.saleUnit === "PRODUCT" && identity.moduleCount === null, "unapproved appended sale-product classification");
    }
    validatePrice(entry.price);
    if (known !== undefined && (allowReviewedBoardAdditions || identity.identityKind === "MODEL_REFERENCE"
      || identity.role === "INSTALLED_PC_REFERENCE")) {
      // Preview extensions preserve all approved prices and nulls; references cannot receive new quotes.
      const actual = entry.price;
      const approved = known.price;
      requireValid(actual === null ? approved === null : approved !== null
        && (["amountKrw", "sourceName", "sourceUrl", "observedAt"] as const)
          .every((key) => actual[key] === approved[key]),
        allowReviewedBoardAdditions ? "existing price changed" : "model reference quote changed");
    }
  }
  requireValid([...base.keys()].every((id) => seen.has(id)), "existing identity missing");
  return candidate as unknown as CatalogSnapshot;
}

/** The approved retail release appends exactly one priced board to the preserved 307/80 release. */
export function validateApprovedRetailSnapshot(candidate: unknown, approvedBase: CatalogSnapshot): CatalogSnapshot {
  const snapshot = validateFullCatalogSnapshot(candidate, approvedBase, { allowReviewedBoardAdditions: true });
  requireValid(approvedBase.products.filter((entry) => entry.price !== null).length === 80,
    "known 80-price base");
  requireValid(snapshot.products.length === 308
    && snapshot.products.filter((entry) => entry.price !== null).length === 81,
  "approved retail release must contain 308 products and 81 prices");
  requireValid(snapshot.catalogVersion !== approvedBase.catalogVersion
    && snapshot.priceVersion !== approvedBase.priceVersion, "retail release requires distinct versions");
  const addition = snapshot.products.find(entry => entry.identity.canonicalId === "f5d69333-0ab1-382d-bc72-9434811daccf");
  requireValid(addition !== undefined && addition.identity.manufacturer === "ASUS"
    && addition.identity.modelName === "TUF GAMING B860-PLUS WIFI"
    && addition.identity.saleUnit === "PRODUCT" && addition.identity.role === "PURCHASE_CANDIDATE",
  "approved ASUS sale product differs from the authorized release");
  requireValid(addition.price !== null && addition.price.amountKrw === 275350
    && addition.price.sourceName === "DANAWA"
    && addition.price.sourceUrl === "https://prod.danawa.com/info/?pcode=74255378"
    && addition.price.observedAt === "2026-10-10T11:57:37.758Z",
  "approved ASUS quote differs from the authorized observation");
  return snapshot;
}
