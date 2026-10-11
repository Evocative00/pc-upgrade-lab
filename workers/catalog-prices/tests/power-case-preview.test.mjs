import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { Miniflare } from "miniflare";
import { localWorkerOptions } from "../scripts/miniflare-options.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const read = async path => JSON.parse(await readFile(new URL(path, import.meta.url), "utf8"));
const legacy = await read("../generated/catalog-v2.json");
const base = await read("../generated/catalog-retail-approved.json");
const pilot = await read("../generated/catalog.json");
const references = await read("../generated/reference-prices-preview.json");
const sourceBytes = await readFile(new URL("../../../data/catalog-review/power-case-pilot-2026-10-11.json", import.meta.url));
const sourceText = sourceBytes.toString("utf8").replace(/^\uFEFF/, "").replaceAll("\r\n", "\n");
const review = JSON.parse(sourceText);
const stageVersion = `all-catalog-v1-${"a".repeat(64)}`;
const token = "1".repeat(64);
const now = Date.parse("2026-10-11T07:00:00Z");

function fixture() {
  const additions = review.products.map(product => ({ key: product.key,
    sourceIdentity: { sourceName: product.sources[0].sourceName, externalId: product.sources[0].externalId },
    officialEvidenceUrls: product.sources.map(source => source.sourceUrl),
    entry: { identity: { canonicalId: product.canonicalId, type: product.type, manufacturer: product.manufacturer,
      modelName: product.modelName, partNumber: product.partNumber, identityKind: product.identityKind,
      role: "PURCHASE_CANDIDATE", verificationStatus: "UNVERIFIED", active: false, saleUnit: "PRODUCT", moduleCount: null },
      price: structuredClone(product.currentPrice) } }));
  return { schemaVersion: 1, mode: "STAGED_PREVIEW_ONLY", publicationApproved: false, databaseApplyApproved: false,
    sourceFile: "data/catalog-review/power-case-pilot-2026-10-11.json",
    sourceSha256: createHash("sha256").update(sourceText, "utf8").digest("hex"),
    baseCatalogVersion: base.catalogVersion, basePriceVersion: base.priceVersion, additions,
    catalog: { ...structuredClone(base), catalogVersion: stageVersion, priceVersion: `prices-v1-${"b".repeat(64)}`,
      products: [...structuredClone(base.products), ...structuredClone(additions.map(item => item.entry))] },
    references: { ...structuredClone(references), catalogVersion: stageVersion, publicationApproved: false } };
}
const stage = fixture();
const newIds = stage.additions.map(item => item.entry.identity.canonicalId);
const observed = stage.additions.find(item => item.entry.price !== null);
const held = stage.additions.find(item => item.entry.price === null);
const compiled = await build({ absWorkingDir: root, entryPoints: ["src/power-case-preview-validation.ts"],
  bundle: true, platform: "node", format: "esm", write: false, target: "es2022" });
const { validatePowerCaseStage } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled.outputFiles[0].text).toString("base64")}`);

test("the pinned review adds exactly seven variants and two current quotes while preserving the existing payloads", () => {
  assert.equal(validatePowerCaseStage(stage, base, references), stage);
  assert.equal(stage.catalog.products.length, 315);
  assert.equal(stage.catalog.products.filter(entry => entry.price !== null).length, 83);
  assert.equal(stage.references.products.length, 227);
  assert.deepEqual(stage.references.products, references.products);
  assert.equal(stage.references.referenceVersion, references.referenceVersion);
  assert.equal(stage.references.generatedAt, references.generatedAt);
  for (const entry of base.products)
    assert.deepEqual(stage.catalog.products.find(item => item.identity.canonicalId === entry.identity.canonicalId), entry);
  const normalized = structuredClone(stage);
  for (const section of [normalized.additions.map(item => item.entry), normalized.catalog.products]) {
    const entry = section.find(item => item.identity.canonicalId === observed.entry.identity.canonicalId);
    entry.price.observedAt = "2026-10-11T06:42:09.48Z";
  }
  assert.equal(validatePowerCaseStage(normalized, base, references), normalized);
});

test("an envelope cannot authorize a broader catalog, a different SKU, another price or invented held-price evidence", () => {
  for (const mutate of [
    value => { value.publicationApproved = true; },
    value => { value.databaseApplyApproved = true; },
    value => { value.mode = "APPROVED"; },
    value => { value.sourceSha256 = "0".repeat(64); },
    value => { value.sourceFile = "other-review.json"; },
    value => { value.basePriceVersion = legacy.priceVersion; },
    value => { value.additions[0].sourceIdentity.externalId = "MAG A650BN PCIE5 III"; },
    value => { value.additions[0].officialEvidenceUrls.push("https://example.com/spec"); },
    value => { value.additions[0].key = "unreviewed-power-supply"; },
    value => { value.additions.push(structuredClone(value.additions[0])); },
    value => { value.additions[1] = structuredClone(value.additions[0]); },
    value => { value.catalog.catalogVersion = base.catalogVersion; },
    value => { value.catalog.priceVersion = base.priceVersion; },
    value => { value.catalog.policy.staleAfterSeconds++; },
    value => { value.catalog.products.pop(); },
    value => { value.catalog.products[0] = value.catalog.products[1]; },
  ]) {
    const value = structuredClone(stage); mutate(value);
    assert.throws(() => validatePowerCaseStage(value, base, references));
  }
  for (const patch of [
    { canonicalId: "00000000-0000-0000-0000-000000000077" }, { type: "MOTHERBOARD" },
    { modelName: "MAG A650BN PCIE5 III" }, { partNumber: "UNREVIEWED-SKU" }, { active: true },
    { verificationStatus: "VERIFIED" }, { role: "BOTH" }, { saleUnit: "RAM_KIT", moduleCount: 2 },
  ]) {
    const value = structuredClone(stage);
    Object.assign(value.additions[0].entry.identity, patch);
    Object.assign(value.catalog.products.find(entry => entry.identity.canonicalId === newIds[0]).identity, patch);
    assert.throws(() => validatePowerCaseStage(value, base, references));
  }
  for (const patch of [{ amountKrw: 72600 }, { sourceUrl: "https://prod.danawa.com/info/?pcode=99916766" },
    { sourceName: "ICODA", sourceUrl: "https://usr.icoda.co.kr/item/view/123456" },
    { observedAt: "2026-10-11T06:42:09.481Z" }]) {
    const value = structuredClone(stage);
    Object.assign(value.additions[0].entry.price, patch);
    Object.assign(value.catalog.products.find(entry => entry.identity.canonicalId === newIds[0]).price, patch);
    assert.throws(() => validatePowerCaseStage(value, base, references));
  }
  const withHeldQuote = structuredClone(stage);
  withHeldQuote.additions.find(item => item.key === held.key).entry.price = structuredClone(observed.entry.price);
  withHeldQuote.catalog.products.find(item => item.identity.canonicalId === held.entry.identity.canonicalId).price =
    structuredClone(observed.entry.price);
  assert.throws(() => validatePowerCaseStage(withHeldQuote, base, references));
});

test("existing quotes, nulls and every reference's scope and source evidence survive staging unchanged", () => {
  for (const mutate of [
    value => { value.catalog.products.find(entry => base.products.some(item => item.identity.canonicalId === entry.identity.canonicalId)
      && entry.price !== null).price.amountKrw++; },
    value => { value.catalog.products.find(entry => entry.price === null).price = structuredClone(observed.entry.price); },
    value => { value.catalog.products[0].identity.active = !value.catalog.products[0].identity.active; },
    value => { value.references.publicationApproved = true; },
    value => { value.references.referenceVersion = `references-v1-${"f".repeat(64)}`; },
    value => { value.references.generatedAt = "2026-10-11T07:00:00Z"; },
    value => { value.references.products[0].referenceEstimate.amountKrw++; },
    value => { value.references.products[0].referenceEstimate.sourceQuotes[0].sourceUrl = "https://example.com/quote"; },
    value => { value.references.products.push({ identity: held.entry.identity,
      referenceEstimate: structuredClone(value.references.products[0].referenceEstimate) }); },
  ]) {
    const value = structuredClone(stage); mutate(value);
    assert.throws(() => validatePowerCaseStage(value, base, references));
  }
});

let preview, production, disabled;
before(async () => {
  const result = await build({ absWorkingDir: root, stdin: { resolveDir: root, loader: "ts", contents: `
    import { createPowerCasePreviewPriceRequestHandler } from "./src/provider";
    const input = ${JSON.stringify(stage)};
    const handler = createPowerCasePreviewPriceRequestHandler(input);
    input.catalog.products.find(entry => entry.identity.canonicalId === ${JSON.stringify(newIds[0])}).price.amountKrw = 1;
    export default { fetch(request, env) { return handler(request, env, ${now}); } };` },
    bundle: true, platform: "browser", format: "esm", target: "es2022", write: false });
  preview = new Miniflare(localWorkerOptions(result.outputFiles[0].text,
    { SHARED_PRICES_API_TOKEN: token, REFERENCE_PRICES_PREVIEW_ENABLED: "true" }));
  disabled = new Miniflare(localWorkerOptions(result.outputFiles[0].text, { SHARED_PRICES_API_TOKEN: token }));
  const existing = await build({ absWorkingDir: root, stdin: { resolveDir: root, loader: "ts", contents: `
    import { handlePriceRequest } from "./src/provider";
    export default { fetch(request, env) { return handlePriceRequest(request, env, ${now}); } };` },
    bundle: true, platform: "browser", format: "esm", target: "es2022", write: false });
  production = new Miniflare(localWorkerOptions(existing.outputFiles[0].text,
    { SHARED_PRICES_API_TOKEN: token, REFERENCE_PRICES_PREVIEW_ENABLED: "true" }));
  await Promise.all([preview.ready, disabled.ready, production.ready]);
});
after(async () => { await Promise.all([preview?.dispose(), disabled?.dispose(), production?.dispose()]); });

function request(worker, ids, version, path = "/api/v2/prices", options = {}) {
  return worker.dispatchFetch(`https://worker.test${path}?canonicalIds=${ids.join(",")}`, { ...options,
    headers: { Authorization: `Bearer ${token}`, ...(version === undefined ? {} : { "X-Catalog-Version": version }),
      ...options.headers } });
}

test("only the explicit opt-in stage version serves 315/83; all earlier price contracts remain exact", async () => {
  for (const [snapshot, version, path] of [[legacy, undefined, "/api/v2/prices"],
    [legacy, legacy.catalogVersion, "/api/v2/prices"], [base, base.catalogVersion, "/api/v2/prices"],
    [pilot, stageVersion, "/api/v1/prices"], [stage.catalog, stageVersion, "/api/v2/prices"]]) {
    const results = [];
    for (let start = 0; start < snapshot.products.length; start += 100) {
      const entries = snapshot.products.slice(start, start + 100);
      const response = await request(preview, entries.map(entry => entry.identity.canonicalId), version, path);
      assert.equal(response.status, 200);
      const body = await response.json();
      assert.equal(body.catalogVersion, snapshot.catalogVersion);
      assert.equal(body.priceVersion, snapshot.priceVersion);
      body.items.forEach((item, index) => {
        assert.deepEqual(item.product, entries[index].identity);
        assert.deepEqual(item.price, entries[index].price);
      });
      results.push(...body.items);
    }
    assert.equal(results.length, snapshot.products.length);
    assert.equal(results.filter(item => item.price !== null).length, snapshot.products.filter(item => item.price !== null).length);
  }
  for (const version of [undefined, base.catalogVersion]) {
    const response = await request(preview, newIds, version);
    assert.equal(response.status, 200);
    assert.ok((await response.json()).items.every(item => item.status === "UNKNOWN_PRODUCT"));
  }
  const unchanged = await request(production, newIds, stageVersion);
  assert.equal(unchanged.status, 409);
  assert.deepEqual(await unchanged.json(), { error: "CATALOG_VERSION_CONFLICT" });
});

test("reference staging rebinds only the catalog version and adds no estimated prices for the seven products", async () => {
  const ids = [...references.products.slice(0, 90).map(entry => entry.identity.canonicalId), ...newIds];
  for (const version of [undefined, base.catalogVersion, stageVersion]) {
    const response = await request(preview, ids, version, "/api/v1/reference-prices");
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.catalogVersion, version === stageVersion ? stageVersion : references.catalogVersion);
    assert.equal(body.referenceVersion, references.referenceVersion);
    body.items.slice(0, 90).forEach((item, index) => {
      assert.deepEqual(item.product, references.products[index].identity);
      assert.deepEqual(item.referenceEstimate, references.products[index].referenceEstimate);
    });
    for (const item of body.items.slice(90)) {
      assert.equal(item.status, version === stageVersion ? "NO_REFERENCE" : "UNKNOWN_PRODUCT");
      assert.equal(item.referenceEstimate, null);
    }
  }
  assert.equal((await request(disabled, newIds, stageVersion, "/api/v1/reference-prices")).status, 503);
  assert.equal((await request(production, newIds, stageVersion, "/api/v1/reference-prices")).status, 409);
});

test("stage selection retains authentication, method, malformed version and unsupported version errors", async () => {
  assert.equal((await request(preview, newIds, stageVersion, "/api/v2/prices",
    { headers: { Authorization: "Bearer invalid" } })).status, 401);
  assert.equal((await request(preview, newIds, stageVersion, "/api/v2/prices", { method: "POST" })).status, 405);
  for (const path of ["/api/v2/prices", "/api/v1/reference-prices"]) {
    assert.equal((await request(preview, newIds, "invalid-version", path)).status, 400);
    assert.equal((await request(preview, newIds, `all-catalog-v1-${"f".repeat(64)}`, path)).status, 409);
  }
});
