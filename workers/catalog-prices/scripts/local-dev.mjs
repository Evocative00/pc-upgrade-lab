import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const workerRoot = fileURLToPath(new URL("../", import.meta.url));
const wranglerRoot = path.dirname(require.resolve("wrangler/package.json"));
const child = spawn(process.execPath, [path.join(wranglerRoot, "bin/wrangler.js"),
  "dev", "--local", "--ip", "127.0.0.1", "--port", "8787"], {
  cwd: workerRoot,
  stdio: "inherit",
  env: {
    ...process.env,
    CLOUDFLARE_CF_FETCH_ENABLED: "false",
    WRANGLER_SEND_METRICS: "false",
    CLOUDFLARE_LOAD_DEV_VARS_FROM_DOT_ENV: "false",
  },
});
child.on("exit", (code) => { process.exitCode = code ?? 1; });
child.on("error", () => { process.exitCode = 1; });
