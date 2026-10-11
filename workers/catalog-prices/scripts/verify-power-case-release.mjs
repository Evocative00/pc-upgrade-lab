import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { Miniflare } from "miniflare";
import { localWorkerOptions } from "./miniflare-options.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const repository = resolve(root, "../..");
const normalize = text => text.replace(/^\uFEFF/, "").replaceAll("\r\n", "\n");
const read = async path => {
  const text = normalize(await readFile(path, "utf8"));
  assert.ok(Buffer.byteLength(text, "utf8") <= 5 * 1024 * 1024, "bounded public release input");
  return JSON.parse(text);
};
const digest = value => createHash("sha256").update(JSON.stringify(value), "utf8").digest("hex");

/** Read-only release verification; exports remain the Java exporter's responsibility. */
export async function verifyPowerCaseRelease({ runtime = false } = {}) {
  const [base, baseReferences, catalog, references, approval, sourceApproval, sourceReferences, stageManifest] =
    await Promise.all([
      read(resolve(root, "generated/catalog-retail-approved.json")),
      read(resolve(root, "generated/reference-prices-preview.json")),
      read(resolve(root, "generated/catalog-power-case-approved.json")),
      read(resolve(root, "generated/reference-prices-power-case-approved.json")),
      read(resolve(root, "generated/approved-power-case-release.json")),
      read(resolve(repository, "data/catalog-shared/approved-power-case-extension-2026-10-11.json")),
      read(resolve(repository, "data/catalog-shared/approved-power-case-reference-prices-2026-10-11.json")),
      read(resolve(repository, "data/catalog-shared/power-case-stage-2026-10-11.json")),
    ]);
  const compiled = await build({ absWorkingDir: root, entryPoints: ["src/power-case-preview-validation.ts"],
    bundle: true, platform: "node", format: "esm", write: false, target: "es2022" });
  const { validateApprovedPowerCaseSnapshots, POWER_CASE_REVIEW_SHA256 } = await import(
    `data:text/javascript;base64,${Buffer.from(compiled.outputFiles[0].text).toString("base64")}`);
  const reviewText = normalize(await readFile(resolve(repository, "data/catalog-review/power-case-pilot-2026-10-11.json"), "utf8"));
  assert.equal(createHash("sha256").update(reviewText, "utf8").digest("hex"), POWER_CASE_REVIEW_SHA256);
  const review = JSON.parse(reviewText);
  assert.equal(review.publicationApproved, false);
  assert.equal(review.databaseApplyApproved, false);
  assert.equal(stageManifest.publicationApproved, false);
  assert.equal(stageManifest.databaseApplyApproved, false);
  assert.equal(stageManifest.mode, "STAGED_PREVIEW_ONLY");
  assert.deepEqual(approval, sourceApproval, "generated approval must match the separate source decision");
  assert.deepEqual(references, sourceReferences, "generated references must match the separate approved source");
  validateApprovedPowerCaseSnapshots(catalog, references, approval, base, baseReferences);
  assert.equal(catalog.catalogVersion, `all-catalog-v1-${digest(catalog.products.map(entry => entry.identity))}`);
  assert.equal(catalog.priceVersion, `prices-v1-${digest(catalog.products)}`);
  assert.equal(references.referenceVersion, `references-v1-${digest(references.products)}`);
  if (runtime) await verifyRuntime(catalog, references, base, baseReferences);
  const report = { publicationApproved: approval.publicationApproved, autoActivation: approval.autoActivation,
    products: catalog.products.length, currentPrices: catalog.products.filter(entry => entry.price !== null).length,
    references: references.products.length, heldPrices: 5, catalogVersion: catalog.catalogVersion,
    priceVersion: catalog.priceVersion, referenceVersion: references.referenceVersion,
    runtimeVerified: runtime, databaseAccess: 0, cloudUpload: 0 };
  console.log(JSON.stringify(report));
  return report;
}

async function verifyRuntime(catalog, references, base, baseReferences) {
  const now = Math.max(Date.parse(references.generatedAt), ...catalog.products
    .filter(entry => entry.price !== null).map(entry => Date.parse(entry.price.observedAt))) + 3600_000;
  const bundled = await build({ absWorkingDir: root, stdin: { loader: "ts", resolveDir: root, contents: `
    import { handlePriceRequest } from "./src/provider";
    export default { fetch(request, env) { return handlePriceRequest(request, env, ${now}); } };` },
    bundle: true, platform: "browser", format: "esm", target: "es2022", write: false });
  const token = "4".repeat(64);
  const worker = new Miniflare(localWorkerOptions(bundled.outputFiles[0].text, { SHARED_PRICES_API_TOKEN: token }));
  try {
    await worker.ready;
    const request = (ids, version, path = "/api/v2/prices", authenticate = true) => worker.dispatchFetch(
      `https://worker.test${path}?canonicalIds=${ids.join(",")}`, { headers: {
        ...(authenticate ? { Authorization: `Bearer ${token}` } : {}),
        ...(version === undefined ? {} : { "X-Catalog-Version": version }) } });
    const legacy = await read(resolve(root, "generated/catalog-v2.json"));
    const pilot = await read(resolve(root, "generated/catalog.json"));
    for (const [snapshot, version, path] of [[legacy, undefined, "/api/v2/prices"],
      [legacy, legacy.catalogVersion, "/api/v2/prices"], [base, base.catalogVersion, "/api/v2/prices"],
      [catalog, catalog.catalogVersion, "/api/v2/prices"], [pilot, catalog.catalogVersion, "/api/v1/prices"]]) {
      for (let start = 0; start < snapshot.products.length; start += 100) {
        const entries = snapshot.products.slice(start, start + 100);
        const response = await request(entries.map(entry => entry.identity.canonicalId), version, path);
        assert.equal(response.status, 200);
        const body = await response.json();
        assert.equal(body.catalogVersion, snapshot.catalogVersion);
        assert.equal(body.priceVersion, snapshot.priceVersion);
        body.items.forEach((item, index) => {
          assert.deepEqual(item.product, entries[index].identity);
          assert.deepEqual(item.price, entries[index].price);
        });
      }
    }
    for (const [snapshot, reference, version] of [[base, baseReferences, undefined],
      [base, baseReferences, base.catalogVersion], [catalog, references, catalog.catalogVersion]]) {
      const quoteMap = new Map(reference.products.map(entry => [entry.identity.canonicalId, entry.referenceEstimate]));
      const results = [];
      for (let start = 0; start < snapshot.products.length; start += 100) {
        const entries = snapshot.products.slice(start, start + 100);
        const response = await request(entries.map(entry => entry.identity.canonicalId), version, "/api/v1/reference-prices");
        assert.equal(response.status, 200);
        const body = await response.json();
        assert.equal(body.catalogVersion, snapshot.catalogVersion);
        assert.equal(body.referenceVersion, reference.referenceVersion);
        body.items.forEach((item, index) => {
          assert.deepEqual(item.product, entries[index].identity);
          assert.deepEqual(item.referenceEstimate, quoteMap.get(entries[index].identity.canonicalId) ?? null);
        });
        results.push(...body.items);
      }
      assert.equal(results.filter(item => item.status === "OK").length, 227);
    }
    const ids = catalog.products.map(entry => entry.identity.canonicalId).slice(0, 100);
    assert.equal((await request(ids, catalog.catalogVersion, "/api/v2/prices", false)).status, 401);
    assert.equal((await request(ids, catalog.catalogVersion, "/api/v1/reference-prices", false)).status, 401);
  } finally { await worker.dispose(); }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args.length > 1 || args.some(arg => arg !== "--runtime"))
    throw new Error("Use node scripts/verify-power-case-release.mjs [--runtime]");
  await verifyPowerCaseRelease({ runtime: args.includes("--runtime") });
}
