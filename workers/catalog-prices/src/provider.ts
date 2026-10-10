import exportedSnapshot from "../generated/catalog.json";
import exportedFullSnapshot from "../generated/catalog-v2.json";
import exportedRetailSnapshot from "../generated/catalog-retail-approved.json";
import exportedReferenceSnapshot from "../generated/reference-prices-preview.json";
import type { CatalogSnapshot, PriceItem, ReferenceItem, WorkerEnv } from "./contract";
import { validateApprovedRetailSnapshot, validateFullCatalogSnapshot } from "./snapshot-validation";
import { validateReferenceSnapshot } from "./reference-validation";

const TOKEN_PATTERN = /^[0-9a-fA-F]{64}$/;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const CATALOG_VERSION_PATTERN = /^all-catalog-v1-[0-9a-f]{64}$/;
const NANOSECONDS_PER_SECOND = 1_000_000_000n;
const NANOSECONDS_PER_MILLISECOND = 1_000_000n;
const tokenEncoder = new TextEncoder();
// Keep the reviewed pilot endpoint available for teammates using the previous backend.
const catalogs = new Map([
  ["/api/v1/prices", indexed(exportedSnapshot as CatalogSnapshot)],
  ["/api/v2/prices", indexed(validateFullCatalogSnapshot(exportedFullSnapshot,
    exportedFullSnapshot as CatalogSnapshot))],
]);
const approvedRetail = indexed(validateApprovedRetailSnapshot(exportedRetailSnapshot,
  exportedFullSnapshot as CatalogSnapshot));
const approvedFullCatalogs = new Map([
  [exportedFullSnapshot.catalogVersion, catalogs.get("/api/v2/prices")!],
  [approvedRetail.snapshot.catalogVersion, approvedRetail],
]);
const references = validateReferenceSnapshot(exportedReferenceSnapshot, approvedRetail.snapshot);
const referenceProducts = new Map(references.products.map(entry => [entry.identity.canonicalId, entry.referenceEstimate]));
const referenceGeneratedAt = instantNanoseconds(references.generatedAt);
const PREVIEW_REFERENCE_TOKEN = "1".repeat(64);

function indexed(snapshot: CatalogSnapshot) {
  return {
    snapshot,
    products: new Map(snapshot.products.map((entry) => [entry.identity.canonicalId, {
      ...entry,
      observedAtNanoseconds: entry.price === null ? null : instantNanoseconds(entry.price.observedAt),
    }])),
    staleNanoseconds: BigInt(snapshot.policy.staleAfterSeconds) * NANOSECONDS_PER_SECOND,
    expireNanoseconds: BigInt(snapshot.policy.expireAfterSeconds) * NANOSECONDS_PER_SECOND,
  };
}

function instantNanoseconds(value: string): bigint {
  const parts = /^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z$/.exec(value);
  if (parts === null) throw new Error("Invalid approved snapshot timestamp");
  const secondsMilliseconds = Date.parse(`${parts[1]}Z`);
  if (!Number.isFinite(secondsMilliseconds)) throw new Error("Invalid approved snapshot timestamp");
  return BigInt(secondsMilliseconds) * NANOSECONDS_PER_MILLISECOND
    + BigInt((parts[2] ?? "").padEnd(9, "0"));
}

function json(body: unknown, status: number, extraHeaders?: Record<string, string>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
      ...extraHeaders,
    },
  });
}

function authenticated(authorization: string | null, secret: string): boolean {
  if (authorization === null || !/^Bearer [0-9a-fA-F]{64}$/.test(authorization)) return false;
  const supplied = tokenEncoder.encode(authorization.substring("Bearer ".length));
  // Workers' Web Crypto extension compares equal-length bytes without a timing leak.
  const subtle = crypto.subtle as SubtleCrypto & {
    timingSafeEqual(left: Uint8Array, right: Uint8Array): boolean;
  };
  return subtle.timingSafeEqual(supplied, tokenEncoder.encode(secret));
}

function canonicalIds(rawQuery: string | null): string[] | null {
  if (rawQuery === null || rawQuery.length > 8192 || rawQuery.includes("&")) return null;
  let decoded: string;
  try { decoded = decodeURIComponent(rawQuery.replaceAll("+", " ")); }
  catch { return null; }
  if (!decoded.startsWith("canonicalIds=") || decoded.indexOf("=", 13) !== -1) return null;
  const values = decoded.substring("canonicalIds=".length).split(",");
  if (values.length < 1 || values.length > 100) return null;
  const seen = new Set<string>();
  for (const id of values) {
    if (!UUID_PATTERN.test(id) || seen.has(id)) return null;
    seen.add(id);
  }
  return values;
}

/** Same public price contract as the Java publisher; no writes or outbound requests. */
export function handlePriceRequest(request: Request, env: WorkerEnv, nowMilliseconds: number): Response {
  return handleCatalogPriceRequest(request, env, nowMilliseconds, catalogs, approvedFullCatalogs);
}

/** Opt-in, in-memory preview only. Production continues to use its generated approved snapshots. */
export function createPreviewPriceRequestHandler(candidate: unknown) {
  // Own the preview data so a caller cannot change identities or amounts after validation.
  const snapshot = validateFullCatalogSnapshot(structuredClone(candidate), exportedFullSnapshot as CatalogSnapshot,
    { allowReviewedBoardAdditions: true });
  const previewCatalogs = new Map([
    ["/api/v1/prices", catalogs.get("/api/v1/prices")!],
    ["/api/v2/prices", indexed(snapshot)],
  ]);
  const previewVersions = new Map([
    [exportedFullSnapshot.catalogVersion, catalogs.get("/api/v2/prices")!],
    [snapshot.catalogVersion, previewCatalogs.get("/api/v2/prices")!],
  ]);
  return (request: Request, env: WorkerEnv, nowMilliseconds: number): Response =>
    handleCatalogPriceRequest(request, env, nowMilliseconds, previewCatalogs, previewVersions);
}

function handleCatalogPriceRequest(request: Request, env: WorkerEnv, nowMilliseconds: number,
  selectedCatalogs: typeof catalogs, versionedFullCatalogs: typeof approvedFullCatalogs): Response {
  const secret = env.SHARED_PRICES_API_TOKEN;
  if (typeof secret !== "string" || !TOKEN_PATTERN.test(secret))
    return json({ error: "SERVICE_UNAVAILABLE" }, 503);
  if (!authenticated(request.headers.get("Authorization"), secret))
    return json({ error: "UNAUTHORIZED" }, 401, { "WWW-Authenticate": "Bearer" });

  const url = new URL(request.url);
  if (url.pathname === "/api/v1/reference-prices") return handleReferenceRequest(request, env, secret, url, nowMilliseconds);
  let catalog = selectedCatalogs.get(url.pathname);
  if (catalog === undefined) return json({ error: "NOT_FOUND" }, 404);
  if (request.method !== "GET")
    return json({ error: "METHOD_NOT_ALLOWED" }, 405, { Allow: "GET" });

  if (url.pathname === "/api/v2/prices") {
    const requestedVersion = request.headers.get("X-Catalog-Version");
    if (requestedVersion !== null) {
      if (!CATALOG_VERSION_PATTERN.test(requestedVersion))
        return json({ error: "INVALID_CATALOG_VERSION" }, 400);
      const selected = versionedFullCatalogs.get(requestedVersion);
      if (selected === undefined) return json({ error: "CATALOG_VERSION_CONFLICT" }, 409);
      catalog = selected;
    }
  }
  const { snapshot, products, staleNanoseconds, expireNanoseconds } = catalog;

  const ids = canonicalIds(url.search === "" ? null : url.search.substring(1));
  if (ids === null) return json({ error: "INVALID_CANONICAL_IDS" }, 400);
  if (!Number.isSafeInteger(nowMilliseconds)) return json({ error: "SERVICE_UNAVAILABLE" }, 503);
  const nowNanoseconds = BigInt(nowMilliseconds) * NANOSECONDS_PER_MILLISECOND;
  const items: PriceItem[] = [];
  for (const canonicalId of ids) {
    const entry = products.get(canonicalId);
    if (entry === undefined) {
      items.push({ canonicalId, product: null, price: null, status: "UNKNOWN_PRODUCT", freshness: "NO_PRICE" });
    } else if (entry.price === null || entry.observedAtNanoseconds === null) {
      items.push({ canonicalId, product: entry.identity, price: null, status: "NO_PRICE", freshness: "NO_PRICE" });
    } else {
      const age = nowNanoseconds - entry.observedAtNanoseconds;
      if (age < 0n) return json({ error: "SERVICE_UNAVAILABLE" }, 503);
      items.push({
        canonicalId,
        product: entry.identity,
        price: entry.price,
        status: "OK",
        freshness: age >= expireNanoseconds ? "EXPIRED" : age >= staleNanoseconds ? "STALE" : "FRESH",
      });
    }
  }
  return json({
    schemaVersion: snapshot.schemaVersion,
    catalogVersion: snapshot.catalogVersion,
    ...(snapshot.schemaVersion === 2 ? { priceVersion: snapshot.priceVersion } : {}),
    servedAt: new Date(nowMilliseconds).toISOString().replace(/\.000Z$/, "Z"),
    policy: snapshot.policy,
    items,
  }, 200);
}

/** Historical and estimated evidence has its own publication gate and never supplies current quotes. */
function handleReferenceRequest(request: Request, env: WorkerEnv, secret: string, url: URL, nowMilliseconds: number): Response {
  if (request.method !== "GET") return json({ error: "METHOD_NOT_ALLOWED" }, 405, { Allow: "GET" });
  if (!references.publicationApproved
    && !(env.REFERENCE_PRICES_PREVIEW_ENABLED === "true" && secret === PREVIEW_REFERENCE_TOKEN))
    return json({ error: "SERVICE_UNAVAILABLE" }, 503);
  const requestedVersion = request.headers.get("X-Catalog-Version");
  if (requestedVersion !== null) {
    if (!CATALOG_VERSION_PATTERN.test(requestedVersion)) return json({ error: "INVALID_CATALOG_VERSION" }, 400);
    if (requestedVersion !== references.catalogVersion) return json({ error: "CATALOG_VERSION_CONFLICT" }, 409);
  }
  const ids = canonicalIds(url.search === "" ? null : url.search.substring(1));
  if (ids === null) return json({ error: "INVALID_CANONICAL_IDS" }, 400);
  if (!Number.isSafeInteger(nowMilliseconds) || nowMilliseconds < 0 || nowMilliseconds > 8_640_000_000_000_000
    || BigInt(nowMilliseconds) * NANOSECONDS_PER_MILLISECOND < referenceGeneratedAt)
    return json({ error: "SERVICE_UNAVAILABLE" }, 503);
  const items: ReferenceItem[] = ids.map(canonicalId => {
    const product = approvedRetail.products.get(canonicalId)?.identity;
    if (product === undefined) return { canonicalId, product: null, referenceEstimate: null, status: "UNKNOWN_PRODUCT" };
    const referenceEstimate = referenceProducts.get(canonicalId) ?? null;
    return { canonicalId, product, referenceEstimate, status: referenceEstimate === null ? "NO_REFERENCE" : "OK" };
  });
  return json({ schemaVersion: 1, catalogVersion: references.catalogVersion, referenceVersion: references.referenceVersion,
    servedAt: new Date(nowMilliseconds).toISOString().replace(/\.000Z$/, "Z"), items }, 200);
}
