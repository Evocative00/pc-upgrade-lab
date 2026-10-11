import { readFile } from "node:fs/promises";
import assert from "node:assert/strict";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";
import { build } from "esbuild";
import { Miniflare } from "miniflare";
import { localWorkerOptions } from "./miniflare-options.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const repository = resolve(root, "../..");
const args = process.argv.slice(2);
if (args.length !== 0 && (args.length !== 2 || args[0] !== "--input"))
  throw new Error("Usage: node scripts/verify-power-case-stage.mjs [--input stage.json]");
const input = args[1] === undefined ? resolve(repository, "backend/build/catalog-power-case-stage/power-case-stage.json")
  : resolve(args[1]);
const read = async path => JSON.parse(await readFile(path, "utf8"));
const [stage, base, references] = await Promise.all([read(input),
  read(resolve(root, "generated/catalog-retail-approved.json")),
  read(resolve(root, "generated/reference-prices-preview.json"))]);
const compiled = await build({ absWorkingDir: root, entryPoints: ["src/power-case-preview-validation.ts"],
  bundle: true, platform: "node", format: "esm", write: false, target: "es2022" });
const { validatePowerCaseStage, POWER_CASE_REVIEW_SHA256 } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled.outputFiles[0].text).toString("base64")}`);
const review = await readFile(resolve(repository, "data/catalog-review/power-case-pilot-2026-10-11.json"));
const normalizedReview = review.toString("utf8").replace(/^\uFEFF/, "").replaceAll("\r\n", "\n");
if (createHash("sha256").update(normalizedReview, "utf8").digest("hex") !== POWER_CASE_REVIEW_SHA256)
  throw new Error("Reviewed power/case source hash changed");
validatePowerCaseStage(stage, base, references);
const digest = value => createHash("sha256").update(JSON.stringify(value)).digest("hex");
assert.equal(stage.catalog.catalogVersion, `all-catalog-v1-${digest(stage.catalog.products.map(entry => entry.identity))}`);
assert.equal(stage.catalog.priceVersion, `prices-v1-${digest(stage.catalog.products)}`);
const now = Math.max(Date.parse(stage.references.generatedAt), ...stage.catalog.products
  .filter(entry => entry.price !== null).map(entry => Date.parse(entry.price.observedAt))) + 3600_000;
const bundled = await build({ absWorkingDir: root, stdin: { loader: "ts", resolveDir: root, contents: `
  import { createPowerCasePreviewPriceRequestHandler } from "./src/provider";
  const handler = createPowerCasePreviewPriceRequestHandler(${JSON.stringify(stage)});
  export default { fetch(request, env) { return handler(request, env, ${now}); } };` },
  bundle: true, platform: "browser", format: "esm", target: "es2022", write: false });
const token = "1".repeat(64);
const runtime = new Miniflare(localWorkerOptions(bundled.outputFiles[0].text,
  { SHARED_PRICES_API_TOKEN: token, REFERENCE_PRICES_PREVIEW_ENABLED: "true" }));
try {
  await runtime.ready;
  const request = (ids, version, path = "/api/v2/prices") => runtime.dispatchFetch(
    `https://worker.test${path}?canonicalIds=${ids.join(",")}`, { headers: { Authorization: `Bearer ${token}`,
      ...(version === undefined ? {} : { "X-Catalog-Version": version }) } });
  for (const snapshot of [base, stage.catalog]) {
    for (let start = 0; start < snapshot.products.length; start += 100) {
      const entries = snapshot.products.slice(start, start + 100);
      const response = await request(entries.map(entry => entry.identity.canonicalId), snapshot.catalogVersion);
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
  for (const version of [base.catalogVersion, stage.catalog.catalogVersion]) {
    for (let start = 0; start < references.products.length; start += 100) {
      const entries = references.products.slice(start, start + 100);
      const response = await request(entries.map(entry => entry.identity.canonicalId), version, "/api/v1/reference-prices");
      assert.equal(response.status, 200);
      const body = await response.json();
      assert.equal(body.catalogVersion, version);
      assert.equal(body.referenceVersion, references.referenceVersion);
      body.items.forEach((item, index) => {
        assert.deepEqual(item.product, entries[index].identity);
        assert.deepEqual(item.referenceEstimate, entries[index].referenceEstimate);
      });
    }
  }
  const ids = stage.additions.map(item => item.entry.identity.canonicalId);
  const referenceResponse = await request(ids, stage.catalog.catalogVersion, "/api/v1/reference-prices");
  assert.equal(referenceResponse.status, 200);
  assert.ok((await referenceResponse.json()).items.every(item => item.status === "NO_REFERENCE" && item.referenceEstimate === null));
  const legacyResponse = await request(ids);
  assert.equal(legacyResponse.status, 200);
  assert.ok((await legacyResponse.json()).items.every(item => item.status === "UNKNOWN_PRODUCT"));
} finally { await runtime.dispose(); }
console.log(JSON.stringify({ mode: stage.mode, publicationApproved: stage.publicationApproved,
  databaseApplyApproved: stage.databaseApplyApproved, catalogVersion: stage.catalog.catalogVersion,
  priceVersion: stage.catalog.priceVersion, products: stage.catalog.products.length,
  currentPrices: stage.catalog.products.filter(entry => entry.price !== null).length,
  preservedReferences: stage.references.products.length }));
