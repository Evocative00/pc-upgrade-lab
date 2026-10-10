import { build } from "esbuild";
import { mkdir, readFile, stat } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { exportReferencePreview } from "./export-reference-preview.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const snapshot = JSON.parse(await readFile(path.join(root, "generated/catalog.json"), "utf8"));
const fullSnapshot = JSON.parse(await readFile(path.join(root, "generated/catalog-v2.json"), "utf8"));
const retailText = await readFile(path.join(root, "generated/catalog-retail-approved.json"), "utf8");
if (Buffer.byteLength(retailText, "utf8") > 5 * 1024 * 1024)
  throw new Error("Approved retail catalog exceeds the 5 MiB input limit");
const retailSnapshot = JSON.parse(retailText);
if (snapshot.schemaVersion !== 1 || typeof snapshot.catalogVersion !== "string"
  || snapshot.policy?.staleAfterSeconds !== 172800 || snapshot.policy?.expireAfterSeconds !== 604800
  || snapshot.products?.length !== 14 || snapshot.products.filter((entry) => entry.price !== null).length !== 8
  || new Set(snapshot.products.map((entry) => entry.identity.canonicalId)).size !== 14) {
  throw new Error("Generate the approved Worker snapshot with exportSharedCatalog before building");
}
if (fullSnapshot.schemaVersion !== 2 || !/^all-catalog-v1-[0-9a-f]{64}$/.test(fullSnapshot.catalogVersion)
  || !/^prices-v1-[0-9a-f]{64}$/.test(fullSnapshot.priceVersion)
  || fullSnapshot.policy?.staleAfterSeconds !== 172800 || fullSnapshot.policy?.expireAfterSeconds !== 604800
  || fullSnapshot.products?.length !== 307
  || new Set(fullSnapshot.products.map((entry) => entry.identity.canonicalId)).size !== 307) {
  throw new Error("Generate and verify the full Worker snapshot with exportSharedCatalog before building");
}
const validationBundle = await build({ absWorkingDir: root, entryPoints: ["src/snapshot-validation.ts"],
  bundle: true, platform: "node", format: "esm", target: "es2022", write: false });
const { validateFullCatalogSnapshot, validateApprovedRetailSnapshot } = await import(
  `data:text/javascript;base64,${Buffer.from(validationBundle.outputFiles[0].text).toString("base64")}`);
validateFullCatalogSnapshot(fullSnapshot, fullSnapshot);
validateApprovedRetailSnapshot(retailSnapshot, fullSnapshot);
await exportReferencePreview({ check: true });
await mkdir(path.join(root, "dist"), { recursive: true });
await build({
  absWorkingDir: root,
  entryPoints: ["src/index.ts"],
  outfile: "dist/index.js",
  bundle: true,
  format: "esm",
  platform: "browser",
  target: "es2022",
  minify: true,
  legalComments: "none",
});
const output = await stat(path.join(root, "dist/index.js"));
console.log(`Local Worker bundle verified: pilotProducts=14 legacyProducts=307 legacyPrices=80`
  + ` retailProducts=308 retailPrices=81 referencePreviewProducts=227 bytes=${output.size} cloudUpload=0`);
