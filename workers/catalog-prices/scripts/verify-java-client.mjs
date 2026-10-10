import { build } from "esbuild";
import { Miniflare } from "miniflare";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";
import { localWorkerOptions } from "./miniflare-options.mjs";

const workerRoot = fileURLToPath(new URL("../", import.meta.url));
const repositoryRoot = fileURLToPath(new URL("../../../", import.meta.url));
const result = await build({ absWorkingDir: workerRoot, entryPoints: ["src/index.ts"], bundle: true,
  format: "esm", platform: "browser", target: "es2022", write: false });
const worker = new Miniflare(localWorkerOptions(result.outputFiles[0].text,
  { SHARED_PRICES_API_TOKEN: "1".repeat(64) }));
try {
  const baseUrl = (await worker.ready).origin;
  if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(baseUrl)) throw new Error("Loopback probe URL required");
  const env = { ...process.env, CATALOG_SHARED_PRICES_API_TOKEN: "1".repeat(64) };
  const args = ["--project-dir", "backend", "verifyWorkerPriceClient", `-PworkerProbeBaseUrl=${baseUrl}`];
  const output = process.platform === "win32"
    ? await promisify(execFile)(process.env.ComSpec ?? "cmd.exe", ["/d", "/s", "/c",
      `backend\\gradlew.bat ${args.join(" ")}`], { cwd: repositoryRoot, env, maxBuffer: 8 * 1024 * 1024 })
    : await promisify(execFile)("./backend/gradlew", args, { cwd: repositoryRoot, env, maxBuffer: 8 * 1024 * 1024 });
  process.stdout.write(output.stdout);
  process.stderr.write(output.stderr);
} finally {
  await worker.dispose();
}
