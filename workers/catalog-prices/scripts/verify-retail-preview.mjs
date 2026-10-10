import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import { Miniflare } from 'miniflare';
import { localWorkerOptions } from './miniflare-options.mjs';

// Fixed ignored preview files only. This command never changes generated/ or accesses Cloudflare.
const root = fileURLToPath(new URL('../', import.meta.url));
const preview = new URL('../../../backend/build/catalog-retail-preview/worker/', import.meta.url);
const snapshot = JSON.parse(await readFile(new URL('catalog-v2.json', preview), 'utf8'));
const contracts = JSON.parse(await readFile(new URL('contract-fixtures-v2.json', preview), 'utf8'));
assert.equal(snapshot.catalogVersion, contracts.catalogVersion);
const token = '2'.repeat(64); // Synthetic local binding, never the team secret.
const bundle = await build({
  stdin: { resolveDir: root, loader: 'ts', contents: `
    import { createPreviewPriceRequestHandler } from './src/provider';
    const handler = createPreviewPriceRequestHandler(${JSON.stringify(snapshot)});
    export default { fetch(request, env) {
      return handler(request, env, Date.parse(request.headers.get('X-Test-Now')));
    }};
  ` },
  bundle: true, platform: 'browser', format: 'esm', target: 'es2022', write: false,
});
const runtime = new Miniflare(localWorkerOptions(bundle.outputFiles[0].text,
  { SHARED_PRICES_API_TOKEN: token }));
try {
  await runtime.ready;
  for (const fixture of contracts.fixtures) {
    const response = await runtime.dispatchFetch(
      'http://worker.test/api/v2/prices?canonicalIds=' + fixture.canonicalIds.join(','),
      { headers: { Authorization: 'Bearer ' + token, 'X-Test-Now': fixture.now } });
    assert.equal(response.status, fixture.errorStatus ?? 200, fixture.name);
    assert.deepEqual(await response.json(), fixture.expected ?? { error: 'INVALID_CANONICAL_IDS' }, fixture.name);
  }
  console.log(`Retail preview Java HTTP fixtures passed: ${contracts.fixtures.length}; products=${snapshot.products.length}; databaseAccess=0 cloudUpload=0`);
} finally {
  await runtime.dispose();
}
