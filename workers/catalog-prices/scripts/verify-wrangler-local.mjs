import assert from "node:assert/strict";
import { spawn, execFile } from "node:child_process";
import { once } from "node:events";
import { readFile, unlink, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { createServer } from "node:net";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

const require = createRequire(import.meta.url);
const root = fileURLToPath(new URL("../", import.meta.url));
const wrangler = path.join(path.dirname(require.resolve("wrangler/package.json")), "bin/wrangler.js");
const devVars = path.join(root, ".dev.vars");
const syntheticToken = "1".repeat(64);
const catalog = JSON.parse(await readFile(path.join(root, "generated/catalog.json"), "utf8"));
const fullCatalog = JSON.parse(await readFile(path.join(root, "generated/catalog-v2.json"), "utf8"));
const reservation = createServer();
reservation.listen(0, "127.0.0.1");
await once(reservation, "listening");
const port = reservation.address().port;
await new Promise((resolve, reject) => reservation.close((error) => error ? reject(error) : resolve()));
let child;
let createdDevVars = false;
let captured = "";
try {
  // Exclusive creation preserves an existing private file; its contents are never read.
  await writeFile(devVars, `SHARED_PRICES_API_TOKEN=${syntheticToken}\n`, { flag: "wx" });
  createdDevVars = true;
  child = spawn(process.execPath, [wrangler, "dev", "--local", "--ip", "127.0.0.1", "--port", String(port),
    "--show-interactive-dev-session=false"], {
    cwd: root,
    stdio: ["ignore", "pipe", "pipe"],
    env: { ...process.env, CLOUDFLARE_CF_FETCH_ENABLED: "false", WRANGLER_SEND_METRICS: "false",
      CLOUDFLARE_LOAD_DEV_VARS_FROM_DOT_ENV: "false" },
  });
  child.stdout.on("data", (data) => { captured += data.toString(); });
  child.stderr.on("data", (data) => { captured += data.toString(); });
  let startupError;
  child.on("error", (error) => { startupError = error; });
  const url = `http://127.0.0.1:${port}/api/v1/prices?canonicalIds=${catalog.products
    .map((entry) => entry.identity.canonicalId).join(",")}`;
  const deadline = Date.now() + 20000;
  let response;
  while (Date.now() < deadline) {
    if (startupError || child.exitCode !== null) throw new Error("Wrangler local server failed to start");
    try {
      response = await fetch(url, { headers: { Authorization: `Bearer ${syntheticToken}` },
        signal: AbortSignal.timeout(500) });
      break;
    } catch { await delay(100); }
  }
  if (!response) throw new Error("Wrangler local server startup timed out");
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.items.length, 14);
  assert.equal(body.items.filter((item) => item.price !== null).length, 8);
  assert.equal(body.catalogVersion, catalog.catalogVersion);
  const fullItems = [];
  for (let start = 0; start < fullCatalog.products.length; start += 50) {
    const ids = fullCatalog.products.slice(start, start + 50).map((entry) => entry.identity.canonicalId);
    const fullResponse = await fetch(`http://127.0.0.1:${port}/api/v2/prices?canonicalIds=${ids.join(",")}`, {
      headers: { Authorization: `Bearer ${syntheticToken}` },
    });
    assert.equal(fullResponse.status, 200);
    const full = await fullResponse.json();
    assert.equal(full.catalogVersion, fullCatalog.catalogVersion);
    assert.equal(full.priceVersion, fullCatalog.priceVersion);
    fullItems.push(...full.items);
  }
  assert.equal(fullItems.length, 307);
  assert.equal(fullItems.filter((item) => item.price !== null).length,
    fullCatalog.products.filter((entry) => entry.price !== null).length);
  const unauthenticated = await fetch(url);
  assert.equal(unauthenticated.status, 401);
  await delay(100);
  assert.equal(captured.includes(syntheticToken), false, "Wrangler logs must conceal secret values");
  assert.match(captured, /SHARED_PRICES_API_TOKEN/);
  assert.match(captured, /hidden|redacted|masked/i);
  console.log(`Wrangler local config verified: pilotProducts=14 fullProducts=307 prices=${fullItems
    .filter((item) => item.price !== null).length} unauthorized=401 tokenLog=masked cloudUpload=0`);
} catch (error) {
  if (error.code === "EEXIST") throw new Error("Private .dev.vars already exists; preserve it and use npm test instead");
  // Captured logs stay in memory and are never dumped, even after a failure.
  throw error;
} finally {
  try {
    if (child?.pid && child.exitCode === null) {
      if (process.platform === "win32") {
        await promisify(execFile)("taskkill", ["/PID", String(child.pid), "/T", "/F"]);
      } else {
        child.kill("SIGINT");
        await once(child, "exit");
      }
    }
  } finally {
    if (createdDevVars) await unlink(devVars);
  }
}
