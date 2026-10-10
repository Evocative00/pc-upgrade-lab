import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { Miniflare } from "miniflare";
import { localWorkerOptions } from "../scripts/miniflare-options.mjs";
import "./snapshot-validation.test.mjs";
import "./approved-retail-version.test.mjs";
import "./reference-prices.test.mjs";

const workerRoot = fileURLToPath(new URL("../", import.meta.url));
const catalog = JSON.parse(await readFile(new URL("../generated/catalog.json", import.meta.url), "utf8"));
const contracts = JSON.parse(await readFile(new URL("../generated/contract-fixtures.json", import.meta.url), "utf8"));
const fullCatalog = JSON.parse(await readFile(new URL("../generated/catalog-v2.json", import.meta.url), "utf8"));
const fullContracts = JSON.parse(await readFile(new URL("../generated/contract-fixtures-v2.json", import.meta.url), "utf8"));
const syntheticToken = "1".repeat(64);
const authorization = `Bearer ${syntheticToken}`;
const validId = catalog.products[0].identity.canonicalId;
const sampleQuery = `canonicalIds=${validId}`;
const fixedNow = contracts.fixtures[0].now;
let fixedClockWorker;
let productionWorker;
let productionScript;

function options(script, bindings = { SHARED_PRICES_API_TOKEN: syntheticToken }) {
  return localWorkerOptions(script, bindings);
}

async function bundle(entry) {
  const result = await build({ absWorkingDir: workerRoot, entryPoints: [entry], bundle: true,
    format: "esm", platform: "browser", target: "es2022", write: false });
  return result.outputFiles[0].text;
}

function request(path = `/api/v1/prices?${sampleQuery}`, init = {}) {
  return fixedClockWorker.dispatchFetch(`http://worker.test${path}`, {
    ...init,
    headers: { Authorization: authorization, "X-Test-Now": fixedNow, ...init.headers },
  });
}

function secureHeaders(response) {
  assert.equal(response.headers.get("Content-Type"), "application/json; charset=utf-8");
  assert.equal(response.headers.get("Cache-Control"), "no-store");
  assert.equal(response.headers.get("X-Content-Type-Options"), "nosniff");
  assert.equal(response.headers.get("Access-Control-Allow-Origin"), null);
}

before(async () => {
  assert.equal(contracts.schemaVersion, 1);
  assert.equal(contracts.catalogVersion, catalog.catalogVersion);
  assert.equal(contracts.fixtures.length, 53);
  assert.equal(fullCatalog.schemaVersion, 2);
  assert.equal(fullCatalog.products.length, 307);
  assert.equal(fullContracts.catalogVersion, fullCatalog.catalogVersion);
  assert.equal(fullContracts.schemaVersion, 2);
  productionScript = await bundle("src/index.ts");
  const testScript = await bundle("tests/runtime-entry.ts");
  fixedClockWorker = new Miniflare(options(testScript));
  productionWorker = new Miniflare(options(productionScript));
  await Promise.all([fixedClockWorker.ready, productionWorker.ready]);
});

after(async () => {
  await Promise.all([fixedClockWorker?.dispose(), productionWorker?.dispose()]);
});

// Expectations are generated from actual Java HTTP responses, not from this implementation.
for (const fixture of contracts.fixtures) {
  test(`Java HTTP contract: ${fixture.name}`, async () => {
    const response = await request(`/api/v1/prices?canonicalIds=${fixture.canonicalIds.join(",")}`, {
      headers: { "X-Test-Now": fixture.now },
    });
    secureHeaders(response);
    assert.equal(response.status, fixture.errorStatus ?? 200);
    const body = await response.json();
    if (fixture.expected !== null) assert.deepEqual(body, fixture.expected);
    else assert.deepEqual(body, { error: "INVALID_CANONICAL_IDS" });
  });
}

for (const fixture of fullContracts.fixtures) {
  test(`Java full catalog HTTP contract: ${fixture.name}`, async () => {
    const response = await request(`/api/v2/prices?canonicalIds=${fixture.canonicalIds.join(",")}`, {
      headers: { "X-Test-Now": fixture.now },
    });
    secureHeaders(response);
    assert.equal(response.status, fixture.errorStatus ?? 200);
    const body = await response.json();
    if (fixture.expected !== null) assert.deepEqual(body, fixture.expected);
    else assert.deepEqual(body, { error: "INVALID_CANONICAL_IDS" });
    assert.ok(new TextEncoder().encode(JSON.stringify(body)).length <= 65536);
  });
}

test("full catalog is central, and preserves legacy classification and observation timestamps", async () => {
  const results = [];
  const base = await productionWorker.ready;
  for (let start = 0; start < fullCatalog.products.length; start += 50) {
    const entries = fullCatalog.products.slice(start, start + 50);
    const response = await fetch(new URL(`/api/v2/prices?canonicalIds=${entries
      .map((entry) => entry.identity.canonicalId).join(",")}`, base), {
      headers: { Authorization: authorization },
    });
    assert.equal(response.status, 200);
    secureHeaders(response);
    const body = await response.json();
    assert.equal(body.schemaVersion, 2);
    assert.equal(body.catalogVersion, fullCatalog.catalogVersion);
    assert.equal(body.priceVersion, fullCatalog.priceVersion);
    for (let index = 0; index < entries.length; index++) {
      assert.deepEqual(body.items[index].product, entries[index].identity);
      assert.deepEqual(body.items[index].price, entries[index].price);
    }
    results.push(...body.items);
  }
  assert.equal(results.length, 307);
  const expectedPrices = fullCatalog.products.filter((entry) => entry.price !== null).length;
  assert.equal(results.filter((item) => item.status === "OK").length, expectedPrices);
  assert.equal(results.filter((item) => item.status === "NO_PRICE").length, 307 - expectedPrices);
  assert.equal(results.filter((item) => item.product.identityKind === "LEGACY_UNCLASSIFIED").length, 293);
  assert.equal(results.some((item) => item.status === "UNKNOWN_PRODUCT"), false);
});

test("production bundle serves real loopback HTTP and preserves approved observations", async () => {
  const base = await productionWorker.ready;
  assert.equal(base.hostname, "127.0.0.1");
  const beforeNow = Date.now();
  const response = await fetch(new URL(`/api/v1/prices?canonicalIds=${catalog.products
    .map((entry) => entry.identity.canonicalId).join(",")}`, base), {
    headers: { Authorization: authorization, "X-Test-Now": "1970-01-01T00:00:00Z" },
  });
  const afterNow = Date.now();
  assert.equal(response.status, 200);
  secureHeaders(response);
  const body = await response.json();
  assert.equal(body.catalogVersion, catalog.catalogVersion);
  assert.equal(body.items.length, 14);
  assert.equal(body.items.filter((item) => item.price !== null).length, 8);
  // Node and the separate workerd process read independent wall clocks.
  const servedAt = Date.parse(body.servedAt);
  assert.ok(servedAt >= beforeNow - 1000 && servedAt <= afterNow + 1000,
    `Live runtime clock differs: before=${servedAt - beforeNow}ms after=${servedAt - afterNow}ms`);
  for (let i = 0; i < catalog.products.length; i++) {
    assert.deepEqual(body.items[i].product, catalog.products[i].identity);
    assert.deepEqual(body.items[i].price, catalog.products[i].price);
  }
  assert.equal(JSON.stringify(body).includes(syntheticToken), false);
  assert.equal(productionScript.includes("X-Test-Now"), false);
});

test("missing and malformed Worker secrets fail closed without disclosing their value", async () => {
  for (const secret of [undefined, "", "bad-secret", "a".repeat(63), "g".repeat(64), "a".repeat(65)]) {
    const worker = new Miniflare(options(productionScript, secret === undefined ? {} : { SHARED_PRICES_API_TOKEN: secret }));
    try {
      const response = await worker.dispatchFetch(`http://worker.test/api/v1/prices?${sampleQuery}`, {
        headers: { Authorization: authorization },
      });
      assert.equal(response.status, 503);
      secureHeaders(response);
      assert.deepEqual(await response.json(), { error: "SERVICE_UNAVAILABLE" });
    } finally { await worker.dispose(); }
  }
});

test("uppercase hex tokens are supported while comparison remains case sensitive", async () => {
  const worker = new Miniflare(options(productionScript, { SHARED_PRICES_API_TOKEN: "A".repeat(64) }));
  try {
    const accepted = await worker.dispatchFetch(`http://worker.test/api/v1/prices?${sampleQuery}`, {
      headers: { Authorization: `Bearer ${"A".repeat(64)}` },
    });
    assert.equal(accepted.status, 200);
    const rejected = await worker.dispatchFetch(`http://worker.test/api/v1/prices?${sampleQuery}`, {
      headers: { Authorization: `Bearer ${"a".repeat(64)}` },
    });
    assert.equal(rejected.status, 401);
  } finally { await worker.dispose(); }
});

test("only the exact Bearer header token authorizes access", async () => {
  for (const invalid of [null, "", "Basic abc", syntheticToken, `bearer ${syntheticToken}`,
    `Bearer  ${syntheticToken}`, `Bearer ${"2".repeat(64)}`, `Bearer ${"1".repeat(63)}`,
    `Bearer ${"1".repeat(63)}2`, `Bearer ${syntheticToken}, Bearer ${syntheticToken}`]) {
    const headers = invalid === null ? {} : { Authorization: invalid };
    const response = await fixedClockWorker.dispatchFetch(`http://worker.test/api/v1/prices?${sampleQuery}`, { headers });
    assert.equal(response.status, 401);
    secureHeaders(response);
    assert.equal(response.headers.get("WWW-Authenticate"), "Bearer");
    assert.deepEqual(await response.json(), { error: "UNAUTHORIZED" });
  }
  const queryToken = await fixedClockWorker.dispatchFetch(
    `http://worker.test/api/v1/prices?${sampleQuery}&token=${syntheticToken}`);
  assert.equal(queryToken.status, 401);
  assert.deepEqual(await queryToken.json(), { error: "UNAUTHORIZED" });
});

test("authenticated routes and methods keep the Java publisher contract", async () => {
  for (const path of ["/", "/api/v1/prices/", "/api/v1/%70rices", "/API/v1/prices"]) {
    const response = await request(path);
    assert.equal(response.status, 404);
    assert.deepEqual(await response.json(), { error: "NOT_FOUND" });
  }
  for (const method of ["POST", "PUT", "DELETE", "OPTIONS", "HEAD"]) {
    const response = await request(undefined, { method });
    assert.equal(response.status, 405);
    assert.equal(response.headers.get("Allow"), "GET");
    secureHeaders(response);
    if (method !== "HEAD") assert.deepEqual(await response.json(), { error: "METHOD_NOT_ALLOWED" });
  }
});

test("invalid single-parameter UUID queries fail atomically", async () => {
  const upperCaseId = "abcdef00-0000-0000-0000-000000000000".toUpperCase();
  for (const query of [null, "", "canonicalIds=", "id=" + validId,
    `canonicalIds=${validId}&canonicalIds=${validId}`, `canonicalIds=${validId}%26extra=x`,
    `canonicalIds=${validId}=extra`, `canonicalIds=${validId},`, `canonicalIds=${validId},${validId}`,
    `canonicalIds=${upperCaseId}`, "canonicalIds=0-0-0-0-1", "canonicalIds=%ZZ",
    `canonicalIds=+${validId}`, `canonicalIds=${validId}%00`, `canonicalIds=${"a".repeat(8192)}`]) {
    const response = await request("/api/v1/prices" + (query === null ? "" : "?" + query));
    assert.equal(response.status, 400);
    secureHeaders(response);
    assert.deepEqual(await response.json(), { error: "INVALID_CANONICAL_IDS" });
  }
});

test("encoded query names and separators behave like Java URLDecoder", async () => {
  const ids = catalog.products.slice(0, 2).map((entry) => entry.identity.canonicalId);
  const response = await request("/api/v1/prices?canonical%49ds=" + ids.join("%2C"));
  assert.equal(response.status, 200);
  assert.deepEqual((await response.json()).items.map((item) => item.canonicalId), ids);
});

test("known unpriced and unknown products keep distinct statuses in requested order", async () => {
  const unpriced = catalog.products.find((entry) => entry.price === null).identity.canonicalId;
  const unknown = "00000000-0000-0000-0000-ffffffffffff";
  const response = await request(`/api/v1/prices?canonicalIds=${unknown},${unpriced}`);
  const body = await response.json();
  assert.equal(response.status, 200);
  assert.deepEqual(body.items.map((item) => item.status), ["UNKNOWN_PRODUCT", "NO_PRICE"]);
  assert.deepEqual(body.items.map((item) => item.freshness), ["NO_PRICE", "NO_PRICE"]);
  assert.equal(body.items[0].product, null);
  assert.equal(body.items[1].product.canonicalId, unpriced);
});

test("the provider refuses to publish future observations when its clock is earlier", async () => {
  const priced = catalog.products.find((entry) => entry.price !== null).identity.canonicalId;
  const response = await request(`/api/v1/prices?canonicalIds=${priced}`, {
    headers: { "X-Test-Now": "1970-01-01T00:00:00Z" },
  });
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "SERVICE_UNAVAILABLE" });
});
