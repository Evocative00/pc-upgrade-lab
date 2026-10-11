import assert from "node:assert/strict";
import { before, after, test } from "node:test";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { Miniflare } from "miniflare";
import { localWorkerOptions } from "../scripts/miniflare-options.mjs";
import { verifyPowerCaseRelease } from "../scripts/verify-power-case-release.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const read = async name => JSON.parse(await readFile(new URL(`../generated/${name}`, import.meta.url), "utf8"));
const [base, baseReferences, catalog, references, approval, contracts] = await Promise.all([
  read("catalog-retail-approved.json"), read("reference-prices-preview.json"), read("catalog-power-case-approved.json"),
  read("reference-prices-power-case-approved.json"), read("approved-power-case-release.json"),
  read("contract-fixtures-power-case-approved.json"),
]);
const additions = catalog.products.filter(entry => !base.products.some(item => item.identity.canonicalId === entry.identity.canonicalId));
const token = "4".repeat(64);
const compiled = await build({ absWorkingDir: root, entryPoints: ["src/power-case-preview-validation.ts"],
  bundle: true, platform: "node", format: "esm", write: false, target: "es2022" });
const { validateApprovedPowerCaseSnapshots } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled.outputFiles[0].text).toString("base64")}`);

test("the separate publication decision matches generated/source files, approved scope and unchanged review evidence", async () => {
  const report = await verifyPowerCaseRelease();
  assert.equal(report.products, 315);
  assert.equal(report.currentPrices, 83);
  assert.equal(report.references, 227);
  assert.equal(report.heldPrices, 5);
  assert.equal(additions.length, 7);
  assert.equal(additions.filter(entry => entry.price !== null).length, 2);
  assert.deepEqual(references.products, baseReferences.products);
  assert.equal(references.referenceVersion, baseReferences.referenceVersion);
});

test("publication refuses altered approval, price evidence, automatic activation or broadened reference contents", () => {
  for (const mutate of [
    value => { value.approval.decision = "OTHER_APPROVAL"; },
    value => { value.approval.publicationApproved = false; },
    value => { value.approval.databaseApplyApproved = false; },
    value => { value.approval.autoActivation = true; },
    value => { value.approval.sourceSha256 = "0".repeat(64); },
    value => { value.approval.newProducts++; },
    value => { value.approval.canonicalIds.reverse(); },
    value => { value.approval.priceObservations[0].amountKrw = 72600; },
    value => { value.references.publicationApproved = false; },
    value => { value.references.products[0].referenceEstimate.sourceQuotes[0].amountKrw++; },
    value => { value.catalog.products.find(entry => entry.price !== null).price.amountKrw++; },
    value => { value.catalog.products.find(entry => additions.some(item => item.identity.canonicalId === entry.identity.canonicalId)).identity.active = true; },
    value => { value.catalog.catalogVersion = base.catalogVersion; },
  ]) {
    const value = structuredClone({ catalog, references, approval }); mutate(value);
    assert.throws(() => validateApprovedPowerCaseSnapshots(value.catalog, value.references, value.approval, base, baseReferences));
  }
});

let runtime;
before(async () => {
  const bundled = await build({ absWorkingDir: root, entryPoints: ["tests/runtime-entry.ts"], bundle: true,
    platform: "browser", format: "esm", write: false, target: "es2022" });
  runtime = new Miniflare(localWorkerOptions(bundled.outputFiles[0].text, { SHARED_PRICES_API_TOKEN: token }));
  await runtime.ready;
});
after(async () => { await runtime?.dispose(); });

function request(ids, version = catalog.catalogVersion, path = "/api/v2/prices", now) {
  return runtime.dispatchFetch(`https://worker.test${path}?canonicalIds=${ids.join(",")}`, { headers: {
    Authorization: `Bearer ${token}`, ...(version === null ? {} : { "X-Catalog-Version": version }),
    ...(now === undefined ? {} : { "X-Test-Now": now }),
  } });
}

for (const fixture of contracts.fixtures) {
  test(`Java approved PSU/CASE HTTP contract: ${fixture.name}`, async () => {
    const response = await request(fixture.canonicalIds, catalog.catalogVersion, "/api/v2/prices", fixture.now);
    assert.equal(response.status, fixture.errorStatus ?? 200);
    if (fixture.expected !== null) assert.deepEqual(await response.json(), fixture.expected);
  });
}

test("the existing ordinary team-token contract publishes 227 preserved references for 315 and no new estimates", async () => {
  const now = new Date(Date.parse(references.generatedAt) + 1000).toISOString();
  const quotes = new Map(references.products.map(entry => [entry.identity.canonicalId, entry.referenceEstimate]));
  const results = [];
  for (let start = 0; start < catalog.products.length; start += 100) {
    const entries = catalog.products.slice(start, start + 100);
    const response = await request(entries.map(entry => entry.identity.canonicalId), catalog.catalogVersion,
      "/api/v1/reference-prices", now);
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.catalogVersion, catalog.catalogVersion);
    assert.equal(body.referenceVersion, references.referenceVersion);
    body.items.forEach((item, index) => {
      assert.deepEqual(item.product, entries[index].identity);
      assert.deepEqual(item.referenceEstimate, quotes.get(item.canonicalId) ?? null);
    });
    results.push(...body.items);
  }
  assert.equal(results.filter(item => item.status === "OK").length, 227);
  assert.equal(results.filter(item => item.status === "NO_REFERENCE").length, 88);
  for (const item of results.filter(item => additions.some(entry => entry.identity.canonicalId === item.canonicalId)))
    assert.equal(item.referenceEstimate, null);
  for (const version of [null, base.catalogVersion]) {
    const response = await request(additions.map(entry => entry.identity.canonicalId), version, "/api/v1/reference-prices", now);
    assert.equal(response.status, 200);
    assert.equal((await response.json()).catalogVersion, base.catalogVersion);
  }
});
