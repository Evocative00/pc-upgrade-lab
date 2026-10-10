import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { Miniflare } from "miniflare";
import { localWorkerOptions } from "./miniflare-options.mjs";

// Public files and a synthetic loopback binding only: no Gradle, database, private files or cloud calls.
if (process.argv.length !== 4 || process.argv[2] !== "--fixtures")
  throw new Error("Use node scripts/verify-approved-retail-versions.mjs --fixtures <Java-retail-contract-fixtures.json>");
const root = fileURLToPath(new URL("../", import.meta.url));
async function readJson(path) {
  const value = await readFile(path, "utf8");
  if (Buffer.byteLength(value, "utf8") > 5 * 1024 * 1024) throw new Error("Public fixture exceeds 5 MiB");
  return JSON.parse(value);
}
const pilot = await readJson(new URL("../generated/contract-fixtures.json", import.meta.url));
const legacy = await readJson(new URL("../generated/contract-fixtures-v2.json", import.meta.url));
const approved = await readJson(new URL("../generated/catalog-retail-approved.json", import.meta.url));
const retail = await readJson(process.argv[3]);
assert.equal(retail.schemaVersion, 2);
assert.equal(retail.catalogVersion, approved.catalogVersion);
assert.ok(Array.isArray(retail.fixtures) && retail.fixtures.length > 0);
const token = "5".repeat(64);
const result = await build({ absWorkingDir: root,
  stdin: { resolveDir: root, loader: "ts", contents: `
    import { handlePriceRequest } from "./src/provider";
    export default { fetch(request, env) {
      return handlePriceRequest(request, env, Date.parse(request.headers.get("X-Test-Now")));
    } };` }, bundle: true, platform: "browser", format: "esm", target: "es2022", write: false });
const runtime = new Miniflare(localWorkerOptions(result.outputFiles[0].text,
  { SHARED_PRICES_API_TOKEN: token }));
let checked = 0;
try {
  await runtime.ready;
  for (const { name, contracts, path, version } of [
    { name: "pilot", contracts: pilot, path: "/api/v1/prices" },
    { name: "legacy-no-header", contracts: legacy, path: "/api/v2/prices" },
    { name: "legacy-version-header", contracts: legacy, path: "/api/v2/prices", version: legacy.catalogVersion },
    { name: "approved-retail-version-header", contracts: retail, path: "/api/v2/prices", version: retail.catalogVersion },
  ]) {
    for (const fixture of contracts.fixtures) {
      const label = `${name}/${fixture.name}`;
      const response = await runtime.dispatchFetch(
        `http://worker.test${path}?canonicalIds=${fixture.canonicalIds.join(",")}`, {
          headers: { Authorization: `Bearer ${token}`, "X-Test-Now": fixture.now,
            ...(version === undefined ? {} : { "X-Catalog-Version": version }) },
        });
      assert.equal(response.status, fixture.errorStatus ?? 200, label);
      assert.equal(response.headers.get("Cache-Control"), "no-store", label);
      const body = await response.json();
      if (fixture.expected !== null) {
        assert.equal(body.catalogVersion, fixture.expected.catalogVersion, `${label}/catalogVersion`);
        assert.equal(body.priceVersion, fixture.expected.priceVersion, `${label}/priceVersion`);
      }
      assert.deepEqual(body, fixture.expected ?? { error: "INVALID_CANONICAL_IDS" }, label);
      checked++;
    }
  }
  console.log(JSON.stringify({ javaHttpFixturesPassed: checked,
    pilot: pilot.fixtures.length, legacyWithoutHeader: legacy.fixtures.length,
    legacyWithHeader: legacy.fixtures.length, retailWithHeader: retail.fixtures.length,
    databaseAccess: 0, cloudUpload: 0, externalHttpRequests: 0 }));
} finally {
  await runtime.dispose();
}
