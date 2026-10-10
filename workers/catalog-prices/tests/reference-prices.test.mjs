import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { Miniflare } from "miniflare";
import { localWorkerOptions } from "../scripts/miniflare-options.mjs";
import { assertReferenceContentHash, assertReferencePublicationPolicy, assertApprovedReferenceCopy } from "../scripts/export-reference-preview.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const active = JSON.parse(await readFile(new URL("../generated/catalog-retail-approved.json", import.meta.url), "utf8"));
const references = JSON.parse(await readFile(new URL("../../../data/catalog-shared/reference-prices-preview-2026-10-10.json", import.meta.url), "utf8"));
const generatedReferences = JSON.parse(await readFile(new URL("../generated/reference-prices-preview.json", import.meta.url), "utf8"));
let approvedReferences;
try { approvedReferences = JSON.parse(await readFile(new URL("../../../data/catalog-shared/active-reference-prices.json", import.meta.url), "utf8")); }
catch (error) { if (error.code !== "ENOENT") throw error; }
const id = references.products[0].identity.canonicalId;
const token = "1".repeat(64);
const unknown = "00000000-0000-0000-0000-000000000000";
const now = new Date(Date.parse(references.generatedAt) + 1000).toISOString();
const path = "/api/v1/reference-prices";
let preview, disabled, ordinary, uppercase, published, production, validateReferenceSnapshot;

async function bundle(entry, platform = "browser", referenceFixture) {
  const result = await build({ absWorkingDir: root, entryPoints: [entry], bundle: true,
    format: "esm", platform, target: "es2022", write: false,
    ...(referenceFixture === undefined ? {} : { plugins: [{ name: "reference-publication-fixture", setup(api) {
      api.onLoad({ filter: /reference-prices-preview\.json$/ }, () => ({ contents: JSON.stringify(referenceFixture), loader: "json" }));
    } }] }) });
  return result.outputFiles[0].text;
}
function request(worker = preview, suffix = `?canonicalIds=${id}`, options = {}) {
  return worker.dispatchFetch(`http://worker.test${path}${suffix}`, { ...options,
    headers: { Authorization: `Bearer ${token}`, "X-Test-Now": now, ...options.headers } });
}
function clone() { return structuredClone(references); }
function sourceEntry(value, predicate) { return value.products.find(entry => predicate(entry)); }
function estimated(value) { return sourceEntry(value, entry => entry.referenceEstimate.basis === "SIMILAR_PART_ESTIMATE"); }
function knownQuote(value) {
  for (const entry of value.products) for (const quote of entry.referenceEstimate.sourceQuotes)
    if (quote.canonicalId !== null) return { entry, quote };
  throw new Error("Expected known catalog source quote");
}

before(async () => {
  const validation = await bundle("src/reference-validation.ts", "node");
  ({ validateReferenceSnapshot } = await import(`data:text/javascript;base64,${Buffer.from(validation).toString("base64")}`));
  // The preserved original review tests the false gate independently of the currently published generated artifact.
  const script = await bundle("tests/runtime-entry.ts", "browser", references);
  preview = new Miniflare(localWorkerOptions(script,
    { SHARED_PRICES_API_TOKEN: token, REFERENCE_PRICES_PREVIEW_ENABLED: "true" }));
  disabled = new Miniflare(localWorkerOptions(script, { SHARED_PRICES_API_TOKEN: token }));
  ordinary = new Miniflare(localWorkerOptions(script,
    { SHARED_PRICES_API_TOKEN: "2".repeat(64), REFERENCE_PRICES_PREVIEW_ENABLED: "true" }));
  uppercase = new Miniflare(localWorkerOptions(script,
    { SHARED_PRICES_API_TOKEN: token, REFERENCE_PRICES_PREVIEW_ENABLED: "TRUE" }));
  // A virtual reviewed publication exercises the approved path without writing the actual preview or price artifacts.
  const approvedFixture = { ...structuredClone(references), publicationApproved: true };
  const approvedScript = await bundle("tests/runtime-entry.ts", "browser", approvedFixture);
  published = new Miniflare(localWorkerOptions(approvedScript, { SHARED_PRICES_API_TOKEN: "2".repeat(64) }));
  const productionScript = await bundle("src/index.ts");
  production = new Miniflare(localWorkerOptions(productionScript, { SHARED_PRICES_API_TOKEN: "2".repeat(64) }));
  await Promise.all([preview.ready, disabled.ready, ordinary.ready, uppercase.ready, published.ready, production.ready]);
});
after(async () => { await Promise.all([preview?.dispose(), disabled?.dispose(), ordinary?.dispose(), uppercase?.dispose(), published?.dispose(), production?.dispose()]); });

test("actual 227 references cover only active unpriced identities and have the exact content hash", () => {
  assert.equal(validateReferenceSnapshot(references, active), references);
  assertReferenceContentHash(references);
  assert.equal(references.publicationApproved, false);
  assert.equal(references.products.length, 227);
  assert.equal(active.products.filter(entry => entry.price !== null).length, 81);
  const bases = references.products.reduce((counts, entry) => {
    const key = entry.referenceEstimate.basis; counts[key] = (counts[key] ?? 0) + 1; return counts;
  }, {});
  assert.deepEqual(bases, { SIMILAR_PART_ESTIMATE: 219, MODEL_RETAIL_REFERENCE: 5, LAUNCH_PRICE: 3 });
});

test("generated references match the explicit approved copy while the original review stays unpublished", () => {
  assert.equal(references.publicationApproved, false);
  assert.deepEqual(generatedReferences, approvedReferences ?? references);
  assertReferenceContentHash(generatedReferences);
  if (approvedReferences !== undefined) assertApprovedReferenceCopy(references, approvedReferences);
});
test("an approved copy cannot change amounts, versions, dates, identities or review metadata", () => {
  for (const mutate of [value => { value.products[0].referenceEstimate.amountKrw++; },
    value => { value.referenceVersion = "references-v1-" + "0".repeat(64); },
    value => { value.generatedAt = now; }, value => { value.products[0].identity.modelName += " changed"; },
    value => { value.products[0].referenceEstimate.basis = "LAUNCH_PRICE"; }]) {
    const candidate = { ...structuredClone(references), publicationApproved: true };
    mutate(candidate); assert.throws(() => assertApprovedReferenceCopy(references, candidate), /exact unpublished review/);
  }
});

test("the default production entry authenticates and serves the actual published reference artifact", async () => {
  const base = await production.ready;
  const unauthorized = await fetch(new URL(`${path}?canonicalIds=${id}`, base));
  assert.equal(unauthorized.status, 401);
  const referenceMap = new Map(generatedReferences.products.map(entry => [entry.identity.canonicalId, entry.referenceEstimate]));
  if (!generatedReferences.publicationApproved) {
    const response = await fetch(new URL(`${path}?canonicalIds=${id}`, base), {
      headers: { Authorization: `Bearer ${"2".repeat(64)}` } });
    assert.equal(response.status, 503); return;
  }
  const results = [];
  for (let start = 0; start < active.products.length; start += 100) {
    const entries = active.products.slice(start, start + 100);
    const response = await fetch(new URL(`${path}?canonicalIds=${entries.map(entry => entry.identity.canonicalId).join(",")}`, base), {
      headers: { Authorization: `Bearer ${"2".repeat(64)}`, "X-Catalog-Version": active.catalogVersion } });
    assert.equal(response.status, 200); const body = await response.json();
    assert.equal(body.catalogVersion, active.catalogVersion); assert.equal(body.referenceVersion, generatedReferences.referenceVersion);
    for (let i = 0; i < entries.length; i++) {
      assert.deepEqual(body.items[i].product, entries[i].identity);
      assert.deepEqual(body.items[i].referenceEstimate, referenceMap.get(entries[i].identity.canonicalId) ?? null);
      assert.equal(body.items[i].status, entries[i].price === null ? "OK" : "NO_REFERENCE");
    }
    results.push(...body.items);
  }
  assert.equal(results.filter(item => item.status === "OK").length, 227);
  assert.equal(results.filter(item => item.status === "NO_REFERENCE").length, 81);
});

const schemaMutations = [
  ["missing reference", value => value.products.pop()],
  ["duplicate reference", value => { value.products[1] = value.products[0]; }],
  ["reference for an existing current price", value => { value.products[0].identity = active.products.find(entry => entry.price !== null).identity; }],
  ["changed identity", value => { value.products[0].identity.active = true; }],
  ["changed target unit", value => { value.products[0].referenceEstimate.saleUnit = "RAM_KIT"; }],
  ["extra estimate field", value => { value.products[0].referenceEstimate.observedAt = now; }],
  ["zero amount", value => { value.products[0].referenceEstimate.amountKrw = 0; }],
  ["decimal amount", value => { value.products[0].referenceEstimate.amountKrw = 1.5; }],
  ["amount overflow", value => { value.products[0].referenceEstimate.amountKrw = 1_000_000_000_000; }],
  ["estimate disguised as verified", value => { estimated(value).referenceEstimate.confidence = "VERIFIED_MODEL"; }],
  ["estimate disguised as exact", value => { estimated(value).referenceEstimate.identityScope = "EXACT_PRODUCT"; }],
  ["estimate using direct method", value => { estimated(value).referenceEstimate.method = "DIRECT_MODEL_QUOTE"; }],
  ["model reference disguised as exact SKU", value => { sourceEntry(value,
    entry => entry.referenceEstimate.basis === "MODEL_RETAIL_REFERENCE").referenceEstimate.identityScope = "EXACT_PRODUCT"; }],
  ["launch reference disguised as exact SKU", value => { sourceEntry(value,
    entry => entry.referenceEstimate.basis === "LAUNCH_PRICE").referenceEstimate.identityScope = "EXACT_PRODUCT"; }],
  ["historical reference using model scope", value => { const estimate = sourceEntry(value,
    entry => entry.referenceEstimate.basis === "MODEL_RETAIL_REFERENCE").referenceEstimate;
    estimate.basis = "HISTORICAL_RETAIL"; estimate.identityScope = "MODEL"; }],
  ["verified quote carries estimate range", value => { const estimate = sourceEntry(value,
    entry => entry.referenceEstimate.basis === "MODEL_RETAIL_REFERENCE").referenceEstimate;
    estimate.rangeLowKrw = estimate.amountKrw; estimate.rangeHighKrw = estimate.amountKrw; }],
  ["missing estimate range", value => { const estimate = estimated(value).referenceEstimate; estimate.rangeLowKrw = null; estimate.rangeHighKrw = null; }],
  ["incomplete range", value => { estimated(value).referenceEstimate.rangeHighKrw = null; }],
  ["range excludes value", value => { const estimate = estimated(value).referenceEstimate; estimate.rangeLowKrw = estimate.amountKrw + 1; }],
  ["future reviewedAt", value => { value.products[0].referenceEstimate.reviewedAt = now; }],
  ["invalid calendar date", value => { value.products[0].referenceEstimate.sourceDate = "2023-02-29"; }],
  ["future source date", value => { value.products[0].referenceEstimate.sourceQuotes[0].sourceDate = "2099-01-01"; }],
  ["empty source quotes", value => { value.products[0].referenceEstimate.sourceQuotes = []; }],
  ["four source quotes", value => { const quotes = value.products[0].referenceEstimate.sourceQuotes; value.products[0].referenceEstimate.sourceQuotes = Array(4).fill(quotes[0]); }],
  ["duplicated source quote", value => { const quotes = value.products[0].referenceEstimate.sourceQuotes; quotes.push(structuredClone(quotes[0])); }],
  ["source without HTTPS", value => { value.products[0].referenceEstimate.sourceQuotes[0].sourceUrl = "http://example.org/quote"; }],
  ["source credentials", value => { value.products[0].referenceEstimate.sourceQuotes[0].sourceUrl = "https://user:pass@example.org/quote"; }],
  ["source empty userinfo", value => { value.products[0].referenceEstimate.sourceQuotes[0].sourceUrl = "https://@example.org/quote"; }],
  ["source malformed URI escape", value => { value.products[0].referenceEstimate.sourceQuotes[0].sourceUrl = "https://example.org/%zz"; }],
  ["source backslash normalized by URL", value => { value.products[0].referenceEstimate.sourceQuotes[0].sourceUrl = "https://example.org/\\quote"; }],
  ["source canonical unknown", value => { knownQuote(value).quote.canonicalId = unknown; }],
  ["source model mismatch", value => { knownQuote(value).quote.modelName += " wrong variant"; }],
  ["source unit mismatch", value => { knownQuote(value).quote.saleUnit = "RAM_KIT"; }],
  ["estimate cites itself", value => { const entry = estimated(value); Object.assign(entry.referenceEstimate.sourceQuotes[0], {
    canonicalId: entry.identity.canonicalId, modelName: entry.identity.modelName,
    saleUnit: entry.identity.saleUnit, moduleCount: entry.identity.moduleCount }); }],
  ["external RAM source with invalid count", value => { const entry = sourceEntry(value,
    entry => entry.identity.type === "RAM" && entry.referenceEstimate.sourceQuotes.some(quote => quote.canonicalId === null));
    entry.referenceEstimate.sourceQuotes.find(quote => quote.canonicalId === null).moduleCount = 0; }],
  ["direct RAM source unit differs", value => { const entry = sourceEntry(value,
    entry => entry.identity.type === "RAM" && entry.identity.saleUnit === "RAM_KIT");
    Object.assign(entry.referenceEstimate, { basis: "MODEL_RETAIL_REFERENCE", identityScope: "MODEL",
      confidence: "VERIFIED_MODEL", method: "DIRECT_MODEL_QUOTE", rangeLowKrw: null, rangeHighKrw: null });
    entry.referenceEstimate.sourceQuotes = [{ ...entry.referenceEstimate.sourceQuotes[0],
      canonicalId: null, saleUnit: "PRODUCT", moduleCount: 1 }]; }],
  ["missing quote source unit", value => { delete value.products[0].referenceEstimate.sourceQuotes[0].saleUnit; }],
  ["unknown top-level fields", value => { value.prices = []; }],
  ["wrong catalog version", value => { value.catalogVersion = "all-catalog-v1-" + "0".repeat(64); }],
];
for (const [name, mutate] of schemaMutations) test(`reference schema rejects ${name}`, () => {
  const value = clone(); mutate(value); assert.throws(() => validateReferenceSnapshot(value, active), /Invalid reference price snapshot/);
});

test("changing otherwise valid notes still fails the content hash", () => {
  const value = clone(); value.products[0].referenceEstimate.notes += " 설명";
  assert.throws(() => assertReferenceContentHash(value), /content hash differs/);
});
test("RAM estimates retain both normalized target and original source kit units", () => {
  const entry = references.products.find(entry => entry.identity.type === "RAM"
    && entry.identity.saleUnit === "PRODUCT" && entry.referenceEstimate.basis === "SIMILAR_PART_ESTIMATE"
    && entry.referenceEstimate.sourceQuotes.some(quote => quote.saleUnit === "RAM_KIT"));
  assert.ok(entry);
  assert.equal(entry.referenceEstimate.moduleCount, 1);
  assert.ok(entry.referenceEstimate.sourceQuotes.some(quote => quote.moduleCount === 2));
});

test("all 308 products preserve 81 current-price exclusions and expose exactly 227 separate references", async () => {
  const received = [];
  const referenceMap = new Map(references.products.map(entry => [entry.identity.canonicalId, entry.referenceEstimate]));
  for (let start = 0; start < active.products.length; start += 100) {
    const entries = active.products.slice(start, start + 100);
    const response = await request(preview, `?canonicalIds=${entries.map(entry => entry.identity.canonicalId).join(",")}`,
      { headers: { "X-Catalog-Version": active.catalogVersion } });
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("Cache-Control"), "no-store");
    assert.equal(response.headers.get("X-Content-Type-Options"), "nosniff");
    assert.equal(response.headers.get("Access-Control-Allow-Origin"), null);
    const body = await response.json();
    assert.deepEqual(Object.keys(body), ["schemaVersion", "catalogVersion", "referenceVersion", "servedAt", "items"]);
    assert.equal(body.schemaVersion, 1); assert.equal(body.catalogVersion, active.catalogVersion);
    assert.equal(body.referenceVersion, references.referenceVersion); assert.equal(body.servedAt, now);
    for (let i = 0; i < entries.length; i++) {
      assert.deepEqual(body.items[i].product, entries[i].identity);
      assert.deepEqual(body.items[i].referenceEstimate, referenceMap.get(entries[i].identity.canonicalId) ?? null);
      assert.equal(body.items[i].status, entries[i].price === null ? "OK" : "NO_REFERENCE");
      assert.equal(Object.hasOwn(body.items[i], "price"), false);
    }
    received.push(...body.items);
  }
  assert.equal(received.length, 308);
  assert.equal(received.filter(item => item.status === "OK").length, 227);
  assert.equal(received.filter(item => item.status === "NO_REFERENCE").length, 81);
});

test("reference batch 100 accepts a larger evidence body independently of price body limits", async () => {
  const entries = [...references.products].sort((a, b) => Buffer.byteLength(JSON.stringify(b)) - Buffer.byteLength(JSON.stringify(a))).slice(0, 100);
  const response = await request(preview, `?canonicalIds=${entries.map(entry => entry.identity.canonicalId).join(",")}`);
  assert.equal(response.status, 200); const text = await response.text();
  assert.ok(Buffer.byteLength(text, "utf8") > 65536);
  assert.equal(JSON.parse(text).items.length, 100);
});
test("unknown reference ID stays explicit and carries no product or value", async () => {
  const response = await request(preview, `?canonicalIds=${unknown}`); assert.equal(response.status, 200);
  assert.deepEqual((await response.json()).items, [{ canonicalId: unknown, product: null, referenceEstimate: null, status: "UNKNOWN_PRODUCT" }]);
});
test("unpublished references stay disabled without the exact explicit local preview switch", async () => {
  for (const worker of [disabled, uppercase]) {
    const response = await request(worker); assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { error: "SERVICE_UNAVAILABLE" });
  }
});
test("a normal valid team token cannot use the preview environment switch", async () => {
  const response = await request(ordinary, undefined, { headers: { Authorization: `Bearer ${"2".repeat(64)}` } });
  assert.equal(response.status, 503);
});
test("approved publication serves an ordinary authenticated token without a preview switch", async () => {
  const response = await request(published, undefined, { headers: { Authorization: `Bearer ${"2".repeat(64)}` } });
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.referenceVersion, references.referenceVersion);
  assert.deepEqual(body.items[0].referenceEstimate, references.products[0].referenceEstimate);
});
test("approved export stays opt-in while read-only checks accept both publication states", () => {
  assert.doesNotThrow(() => assertReferencePublicationPolicy(references));
  const approved = { ...references, publicationApproved: true };
  assert.throws(() => assertReferencePublicationPolicy(approved), /explicit --approved/);
  assert.doesNotThrow(() => assertReferencePublicationPolicy(approved, { check: true }));
  assert.doesNotThrow(() => assertReferencePublicationPolicy(approved, { allowApproved: true }));
});
test("reference authentication is enforced before preview or product lookup", async () => {
  for (const worker of [preview, disabled]) {
    const response = await request(worker, undefined, { headers: { Authorization: `Bearer ${"3".repeat(64)}` } });
    assert.equal(response.status, 401); assert.equal(response.headers.get("WWW-Authenticate"), "Bearer");
  }
});
test("reference endpoint allows GET only", async () => {
  const response = await request(preview, undefined, { method: "POST" });
  assert.equal(response.status, 405); assert.equal(response.headers.get("Allow"), "GET");
});
for (const suffix of ["", "?other=x", "?canonicalIds=", `?canonicalIds=${id},${id}`,
  `?canonicalIds=${id}&other=1`, `?canonicalIds=${id}&canonicalIds=${unknown}`, "?canonicalIds=%zz",
  "?canonicalIds=" + active.products.slice(0, 101).map(entry => entry.identity.canonicalId).join(",")])
  test(`reference query is rejected atomically: ${suffix.slice(0, 65)}`, async () => {
    const response = await request(preview, suffix); assert.equal(response.status, 400);
    assert.deepEqual(await response.json(), { error: "INVALID_CANONICAL_IDS" });
  });
test("reference rejects mismatched and malformed catalog versions", async () => {
  for (const [version, status] of [["all-catalog-v1-" + "0".repeat(64), 409], ["wrong", 400],
    [active.catalogVersion + "," + active.catalogVersion, 400]]) {
    const response = await request(preview, undefined, { headers: { "X-Catalog-Version": version } });
    assert.equal(response.status, status);
  }
});
test("a reference generation time in the future is unavailable", async () => {
  const response = await request(preview, undefined, { headers: { "X-Test-Now": "2026-01-01T00:00:00Z" } });
  assert.equal(response.status, 503);
});
test("disabled reference publication leaves both existing price endpoints operational", async () => {
  const legacy = JSON.parse(await readFile(new URL("../generated/catalog.json", import.meta.url), "utf8"));
  for (const [endpoint, version, entry] of [["/api/v1/prices", null, legacy.products[0]],
    ["/api/v2/prices", active.catalogVersion, active.products.find(entry => entry.price !== null)]]) {
    const response = await disabled.dispatchFetch(`http://worker.test${endpoint}?canonicalIds=${entry.identity.canonicalId}`, {
      headers: { Authorization: `Bearer ${token}`, "X-Test-Now": now, ...(version ? { "X-Catalog-Version": version } : {}) } });
    assert.equal(response.status, 200); const body = await response.json();
    assert.deepEqual(body.items[0].product, entry.identity); assert.deepEqual(body.items[0].price, entry.price);
    assert.equal(Object.hasOwn(body, "referenceVersion"), false);
  }
});
