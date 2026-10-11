import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { Miniflare } from "miniflare";
import { localWorkerOptions } from "../scripts/miniflare-options.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const read = async name => JSON.parse(await readFile(new URL(`../generated/${name}`, import.meta.url), "utf8"));
const legacy = await read("catalog-v2.json");
const retail = await read("catalog-retail-approved.json");
const pilot = await read("catalog.json");
const baseIds = new Set(legacy.products.map(entry => entry.identity.canonicalId));
const addition = retail.products.find(entry => !baseIds.has(entry.identity.canonicalId));
const token = "4".repeat(64);
const now = new Date(Math.max(...retail.products.filter(entry => entry.price !== null)
  .map(entry => Date.parse(entry.price.observedAt))) + 3600_000).toISOString();
const compiled = await build({ absWorkingDir: root, entryPoints: ["src/snapshot-validation.ts"],
  bundle: true, platform: "node", format: "esm", write: false, target: "es2022" });
const { validateApprovedRetailSnapshot, validateFullCatalogSnapshot } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled.outputFiles[0].text).toString("base64")}`);

test("approved retail input preserves the entire legacy release and requires exactly one new priced board", () => {
  assert.equal(validateApprovedRetailSnapshot(retail, legacy), retail);
  assert.equal(retail.products.length, 308);
  assert.equal(retail.products.filter(entry => entry.price !== null).length, 81);
  assert.ok(addition);
  for (const entry of legacy.products)
    assert.deepEqual(retail.products.find(proposed => proposed.identity.canonicalId === entry.identity.canonicalId), entry);
  for (const mutate of [
    candidate => { candidate.catalogVersion = legacy.catalogVersion; },
    candidate => { candidate.priceVersion = legacy.priceVersion; },
    candidate => { candidate.products.find(entry => entry.identity.canonicalId === addition.identity.canonicalId).price = null; },
    candidate => { candidate.products.pop(); },
    candidate => { candidate.products.find(entry => entry.price !== null && baseIds.has(entry.identity.canonicalId)).price.amountKrw++; },
    candidate => { candidate.products.find(entry => entry.price === null && baseIds.has(entry.identity.canonicalId)).price = structuredClone(addition.price); },
  ]) {
    const candidate = structuredClone(retail); mutate(candidate);
    assert.throws(() => validateApprovedRetailSnapshot(candidate, legacy));
  }
});

test("the approved release pins the exact ASUS identity and reviewed price while generic previews remain available", () => {
  for (const [section, patch] of [
    ["identity", { canonicalId: "00000000-0000-0000-0000-00000000bb01" }],
    ["identity", { manufacturer: "Other manufacturer" }],
    ["identity", { modelName: "TUF GAMING B860M-PLUS WIFI" }],
    ["identity", { role: "BOTH" }],
    ["price", { amountKrw: 275351 }],
    ["price", { sourceName: "ICODA", sourceUrl: "https://usr.icoda.co.kr/item/view/123456" }],
    ["price", { sourceUrl: "https://prod.danawa.com/info/?pcode=74255379" }],
    ["price", { observedAt: "2026-10-10T11:57:37.759Z" }],
  ]) {
    const candidate = structuredClone(retail);
    const changed = candidate.products.find(entry => entry.identity.canonicalId === addition.identity.canonicalId);
    Object.assign(changed[section], patch);
    assert.equal(validateFullCatalogSnapshot(candidate, legacy, { allowReviewedBoardAdditions: true }), candidate);
    assert.throws(() => validateApprovedRetailSnapshot(candidate, legacy), /approved ASUS/);
  }
});

let runtime;
before(async () => {
  const result = await build({ absWorkingDir: root, stdin: { resolveDir: root, loader: "ts", contents: `
    import { handlePriceRequest } from "./src/provider";
    export default { fetch(request, env) {
      return handlePriceRequest(request, env, Date.parse(${JSON.stringify(now)}));
    } };` }, bundle: true, platform: "browser", format: "esm", target: "es2022", write: false });
  runtime = new Miniflare(localWorkerOptions(result.outputFiles[0].text, { SHARED_PRICES_API_TOKEN: token }));
  await runtime.ready;
});
after(async () => { await runtime?.dispose(); });

async function request(ids, version, path = "/api/v2/prices") {
  const target = `${path}?canonicalIds=${ids.join(",")}`;
  const options = { headers: { Authorization: `Bearer ${token}`,
    ...(version === undefined ? {} : { "X-Catalog-Version": version }) } };
  // Miniflare dispatchFetch drops empty values; a real HTTP request preserves an explicitly empty header.
  if (version === "") return fetch(new URL(target, await runtime.ready), options);
  return runtime.dispatchFetch(`http://worker.test${target}`, options);
}

test("missing or legacy version headers serve unchanged 307/80 while the new header serves 308/81", async () => {
  for (const [snapshot, version] of [[legacy, undefined], [legacy, legacy.catalogVersion], [retail, retail.catalogVersion]]) {
    const results = [];
    for (let start = 0; start < snapshot.products.length; start += 100) {
      const entries = snapshot.products.slice(start, start + 100);
      const response = await request(entries.map(entry => entry.identity.canonicalId), version);
      assert.equal(response.status, 200);
      assert.equal(response.headers.get("Cache-Control"), "no-store");
      const body = await response.json();
      assert.equal(body.schemaVersion, 2);
      assert.equal(body.catalogVersion, snapshot.catalogVersion);
      assert.equal(body.priceVersion, snapshot.priceVersion);
      body.items.forEach((item, index) => {
        assert.deepEqual(item.product, entries[index].identity);
        assert.deepEqual(item.price, entries[index].price);
      });
      results.push(...body.items);
    }
    assert.equal(results.length, snapshot.products.length);
    assert.equal(results.filter(item => item.price !== null).length, snapshot === legacy ? 80 : 81);
  }
});

test("the new retail product is unknown to legacy clients and priced only for the approved new version", async () => {
  for (const version of [undefined, legacy.catalogVersion]) {
    const response = await request([addition.identity.canonicalId], version);
    assert.equal(response.status, 200);
    assert.deepEqual((await response.json()).items, [{ canonicalId: addition.identity.canonicalId,
      product: null, price: null, status: "UNKNOWN_PRODUCT", freshness: "NO_PRICE" }]);
  }
  const response = await request([addition.identity.canonicalId], retail.catalogVersion);
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.deepEqual(body.items[0].product, addition.identity);
  assert.deepEqual(body.items[0].price, addition.price);
  assert.equal(body.items[0].status, "OK");
  assert.equal(body.items[0].freshness, "FRESH");
});

test("blank, repeated, malformed and unsupported version headers fail without a catalog fallback", async () => {
  for (const version of ["", "not-a-catalog-version", legacy.catalogVersion.toUpperCase(),
    `${legacy.catalogVersion},${retail.catalogVersion}`, `${retail.catalogVersion}, ${retail.catalogVersion}`,
    `all-catalog-v1-${"f".repeat(65)}`]) {
    const response = await request([addition.identity.canonicalId], version);
    assert.equal(response.status, 400, JSON.stringify(version));
    assert.deepEqual(await response.json(), { error: "INVALID_CATALOG_VERSION" });
  }
  const repeated = await runtime.dispatchFetch(
    `http://worker.test/api/v2/prices?canonicalIds=${addition.identity.canonicalId}`, {
      headers: [["Authorization", `Bearer ${token}`], ["X-Catalog-Version", retail.catalogVersion],
        ["X-Catalog-Version", retail.catalogVersion]],
    });
  assert.equal(repeated.status, 400);
  assert.deepEqual(await repeated.json(), { error: "INVALID_CATALOG_VERSION" });
  const unknown = await request([addition.identity.canonicalId], `all-catalog-v1-${"f".repeat(64)}`);
  assert.equal(unknown.status, 409);
  assert.deepEqual(await unknown.json(), { error: "CATALOG_VERSION_CONFLICT" });
});

test("version selection cannot bypass authentication or change the v1 pilot contract", async () => {
  const unauthenticated = await runtime.dispatchFetch(
    `http://worker.test/api/v2/prices?canonicalIds=${addition.identity.canonicalId}`, {
      headers: { "X-Catalog-Version": retail.catalogVersion },
    });
  assert.equal(unauthenticated.status, 401);
  assert.deepEqual(await unauthenticated.json(), { error: "UNAUTHORIZED" });
  const response = await request(pilot.products.map(entry => entry.identity.canonicalId), retail.catalogVersion, "/api/v1/prices");
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.schemaVersion, 1);
  assert.equal(body.catalogVersion, pilot.catalogVersion);
  assert.equal(body.items.length, 14);
  assert.equal(body.items.filter(item => item.price !== null).length, 8);
  body.items.forEach((item, index) => {
    assert.deepEqual(item.product, pilot.products[index].identity);
    assert.deepEqual(item.price, pilot.products[index].price);
  });
});
