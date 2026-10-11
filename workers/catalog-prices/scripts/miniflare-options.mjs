import { convertV4MiniflareOptions, Log, LogLevel } from "miniflare";

/** Official Miniflare v5 adapter for its documented module-script options. */
export function localWorkerOptions(script, bindings) {
  return {
    ...convertV4MiniflareOptions({ name: "catalog-prices-local", modules: true, script,
      compatibilityDate: "2026-10-06", host: "127.0.0.1", port: 0, cf: false,
      log: new Log(LogLevel.ERROR), bindings }),
    telemetry: { enabled: false },
    logRequests: false,
  };
}
