import { build } from "esbuild";
import { Miniflare } from "miniflare";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";
import { localWorkerOptions } from "./miniflare-options.mjs";
import { exportReferencePreview } from "./export-reference-preview.mjs";

const workerRoot = fileURLToPath(new URL("../", import.meta.url));
const repositoryRoot = fileURLToPath(new URL("../../../", import.meta.url));
await exportReferencePreview({ check: true });
const compiled = await build({ absWorkingDir: workerRoot, entryPoints: ["src/index.ts"], bundle: true,
  format: "esm", platform: "browser", target: "es2022", write: false });
const previewToken = "1".repeat(64);
const worker = new Miniflare(localWorkerOptions(compiled.outputFiles[0].text,
  { SHARED_PRICES_API_TOKEN: previewToken, REFERENCE_PRICES_PREVIEW_ENABLED: "true" }));
try {
  const baseUrl = (await worker.ready).origin;
  if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(baseUrl)) throw new Error("Loopback reference preview URL required");
  const env = { ...process.env, REFERENCE_PREVIEW_TOKEN: previewToken };
  const args = ["--project-dir", "backend", "verifyReferencePriceClient", `-PworkerProbeBaseUrl=${baseUrl}`];
  const output = process.platform === "win32"
    ? await promisify(execFile)(process.env.ComSpec ?? "cmd.exe", ["/d", "/s", "/c",
      `backend\\gradlew.bat ${args.join(" ")}`], { cwd: repositoryRoot, env, maxBuffer: 8 * 1024 * 1024 })
    : await promisify(execFile)("./backend/gradlew", args, { cwd: repositoryRoot, env, maxBuffer: 8 * 1024 * 1024 });
  process.stdout.write(output.stdout); process.stderr.write(output.stderr);
} finally { await worker.dispose(); }
