import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";

const root = fileURLToPath(new URL("../", import.meta.url));
const reviewSource = new URL("../../../data/catalog-shared/reference-prices-preview-2026-10-10.json", import.meta.url);
const approvedSource = new URL("../../../data/catalog-shared/active-reference-prices.json", import.meta.url);
const target = new URL("../generated/reference-prices-preview.json", import.meta.url);

export function assertReferenceContentHash(snapshot) {
  const expected = "references-v1-" + createHash("sha256").update(JSON.stringify(snapshot.products), "utf8").digest("hex");
  if (snapshot.referenceVersion !== expected) throw new Error("Reference preview content hash differs");
}

export function assertReferencePublicationPolicy(snapshot, { check = false, allowApproved = false } = {}) {
  if (snapshot.publicationApproved !== false && !check && !allowApproved)
    throw new Error("Publishing an approved artifact requires the explicit --approved export option");
}

export function assertApprovedReferenceCopy(review, approved) {
  if (review.publicationApproved !== false || approved.publicationApproved !== true
    || ["schemaVersion", "catalogVersion", "referenceVersion", "generatedAt", "products"]
      .some(key => JSON.stringify(review[key]) !== JSON.stringify(approved[key])))
    throw new Error("Approved references must preserve the exact unpublished review except its publication flag");
}

async function publicInput(source) {
  const text = (await readFile(source, "utf8")).replace(/^\uFEFF/, "").replaceAll("\r\n", "\n");
  if (Buffer.byteLength(text, "utf8") > 2 * 1024 * 1024) throw new Error("Reference input exceeds 2 MiB");
  return { text, snapshot: JSON.parse(text) };
}

/** Read-only builds prefer the explicitly approved source; a missing file alone selects the original review. */
async function selectedSource({ check, allowApproved }) {
  const review = await publicInput(reviewSource);
  if (review.snapshot.publicationApproved !== false) throw new Error("The original reference review must remain unpublished");
  if (!check && !allowApproved) return { ...review, review: review.snapshot };
  let approved;
  try { approved = await publicInput(approvedSource); }
  catch (error) {
    if (check && !allowApproved && error.code === "ENOENT") return { ...review, review: review.snapshot };
    throw error;
  }
  assertApprovedReferenceCopy(review.snapshot, approved.snapshot);
  return { ...approved, review: review.snapshot };
}

/** Only the separate preview artifact is created; approved prices and fixtures are never written. */
export async function exportReferencePreview({ check = false, allowApproved = false } = {}) {
  const { text, snapshot, review } = await selectedSource({ check, allowApproved });
  const active = JSON.parse(await readFile(new URL("../generated/catalog-retail-approved.json", import.meta.url), "utf8"));
  const compiled = await build({ absWorkingDir: root, entryPoints: ["src/reference-validation.ts"],
    bundle: true, platform: "node", format: "esm", target: "es2022", write: false });
  const { validateReferenceSnapshot } = await import(
    `data:text/javascript;base64,${Buffer.from(compiled.outputFiles[0].text).toString("base64")}`);
  validateReferenceSnapshot(review, active);
  validateReferenceSnapshot(snapshot, active);
  assertReferencePublicationPolicy(snapshot, { check, allowApproved });
  if (Date.parse(snapshot.generatedAt) > Date.now()) throw new Error("Reference preview generation time is in the future");
  assertReferenceContentHash(snapshot);
  const output = text.endsWith("\n") ? text : text + "\n";
  if (check) {
    if ((await readFile(target, "utf8")).replaceAll("\r\n", "\n") !== output)
      throw new Error("Generated reference preview differs; run export-reference-preview first");
  } else await writeFile(target, output, "utf8");
  console.log(`Reference preview ${check ? "verified" : "exported"}: products=${snapshot.products.length}`
    + ` publicationApproved=${snapshot.publicationApproved} databaseAccess=0 cloudUpload=0 version=${snapshot.referenceVersion}`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const options = process.argv.slice(2);
  if (new Set(options).size !== options.length || options.some(option => !["--check", "--approved"].includes(option)))
    throw new Error("Use node scripts/export-reference-preview.mjs [--check] [--approved]");
  await exportReferencePreview({ check: options.includes("--check"), allowApproved: options.includes("--approved") });
}
