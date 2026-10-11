import type { CatalogSnapshot, ReferenceCatalogSnapshot } from "./contract";

export const POWER_CASE_REVIEW_SHA256 = "e3575f9217ea88797ceec8788adf8f86e1440f2521c672e41a4ba42fc17e5830";
const SOURCE_FILE = "data/catalog-review/power-case-pilot-2026-10-11.json";
const CATALOG_VERSION = /^all-catalog-v1-[0-9a-f]{64}$/;
const PRICE_VERSION = /^prices-v1-[0-9a-f]{64}$/;

interface StageAddition {
  key: string;
  sourceIdentity: { sourceName: "MANUFACTURER"; externalId: string };
  officialEvidenceUrls: string[];
  entry: CatalogSnapshot["products"][number];
}

export interface PowerCaseStageEnvelope {
  schemaVersion: 1;
  mode: "STAGED_PREVIEW_ONLY";
  publicationApproved: false;
  databaseApplyApproved: false;
  sourceFile: string;
  sourceSha256: string;
  baseCatalogVersion: string;
  basePriceVersion: string;
  additions: StageAddition[];
  catalog: CatalogSnapshot;
  references: ReferenceCatalogSnapshot;
}

export interface ApprovedPowerCaseRelease {
  schemaVersion: 1;
  decision: "APPROVED_POWER_CASE_RELEASE_2026_10_11";
  databaseApplyApproved: true;
  publicationApproved: true;
  autoActivation: false;
  stageManifestFile: string;
  sourceFile: string;
  sourceSha256: string;
  newProducts: 7;
  newPrices: 2;
  preservedProducts: 308;
  preservedCurrentPrices: 81;
  preservedReferences: 227;
  catalogVersion: string;
  priceVersion: string;
  referenceVersion: string;
  referencesFile: string;
  canonicalIds: string[];
  priceObservations: Record<string, unknown>[];
}

function addition(key: string, canonicalId: string, type: "PSU" | "CASE", manufacturer: string,
  modelName: string, partNumber: string | null, externalId: string, officialEvidenceUrls: string[],
  price: CatalogSnapshot["products"][number]["price"] = null): StageAddition {
  return { key, sourceIdentity: { sourceName: "MANUFACTURER", externalId }, officialEvidenceUrls,
    entry: { identity: { canonicalId, type, manufacturer, modelName, partNumber,
      identityKind: "PHYSICAL_VARIANT", role: "PURCHASE_CANDIDATE", verificationStatus: "UNVERIFIED",
      active: false, saleUnit: "PRODUCT", moduleCount: null }, price } };
}

// This opt-in scope is pinned to the seven reviewed variants, not a general PSU/case extension.
const REVIEWED_ADDITIONS = [
  addition("psu-msi-mag-a650bn", "b398c8d7-2959-398b-90ac-136b4d00ee1c", "PSU", "MSI", "MAG A650BN", null,
    "MAG A650BN", ["https://www.msi.com/Power-Supply/MAG-A650BN/Specification"],
    { amountKrw: 75600, sourceName: "DANAWA", sourceUrl: "https://prod.danawa.com/info/?pcode=15283025",
      observedAt: "2026-10-11T06:42:09.48Z" }),
  addition("psu-msi-mag-a750gl-pcie5-ii", "dbf4bdec-6871-39bf-8765-338795b644e8", "PSU", "MSI",
    "MAG A750GL PCIE5 II", null, "MAG A750GL PCIE5 II", [
      "https://www.msi.com/Power-Supply/MAG-A750GL-PCIE5-II/Specification",
      "https://www.msi.com/Power-Supply/MAG-A750GL-PCIE5-II/", "https://pl-store.msi.com/products/mag-a750gl-pcie5-ii"]),
  addition("psu-msi-mag-a850gl-pcie5-ii", "0d0ffb4a-7815-37c1-a988-0b0ce0ba6917", "PSU", "MSI",
    "MAG A850GL PCIE5 II", null, "MAG A850GL PCIE5 II", [
      "https://www.msi.com/Power-Supply/MAG-A850GL-PCIE5-II/Specification",
      "https://www.msi.com/Power-Supply/MAG-A850GL-PCIE5-II"]),
  addition("psu-corsair-sf1000-cp-9020257-kr", "339c869d-4db6-38c4-a32a-cc488058616f", "PSU", "Corsair",
    "SF1000 Platinum (2024)", "CP-9020257-KR", "CP-9020257-KR", [
      "https://www.corsair.com/kr/ko/p/psu/cp-9020257-kr/sf-series-sf1000-fully-modular-80-plus-platinum-sfx-power-supply-kr-cp-9020257-kr",
      "https://www.corsair.com/kr/ko/explorer/diy-builder/power-supply-units/sf1000-platinum-atx-31-vs-sf1000l-what-are-the-differences/"]),
  addition("case-fractal-pop-air-black-solid-fd-c-poa1a-01", "5ffeb16d-2a45-331f-9630-3de784ef358e", "CASE",
    "Fractal Design", "Pop Air Black Solid", "FD-C-POA1A-01", "FD-C-POA1A-01", [
      "https://www.fractal-design.com/products/cases/pop-series/pop-air/pop-air-black-solid/",
      "https://www.fractal-design.com/app/uploads/2022/06/Pop-Air-RGB-Pop-Air_Product-Sheet_EN.pdf"],
    { amountKrw: 149000, sourceName: "DANAWA", sourceUrl: "https://prod.danawa.com/info/?pcode=17357669",
      observedAt: "2026-10-11T06:42:09.811Z" }),
  addition("case-asus-prime-ap201-mesh-black-90dc00g0-b39000", "084baffd-2ae4-3ada-b49d-3c1457d0b73c", "CASE",
    "ASUS", "Prime AP201 Mesh Black", "90DC00G0-B39000", "90DC00G0-B39000", [
      "https://estore.asus.com/es/rog/es/es-product/90dc00g0-b39000-asus-prime-ap201-microatx-case.html",
      "https://www.asus.com/sg/motherboards-components/cases/prime/asus-prime-ap201-microatx-case/techspec/",
      "https://www.asus.com/za/support/faq/1048059/"]),
  addition("case-cooler-master-nr200-black-mcb-nr200-knnn-s00", "c3306ba5-4f2d-3d66-865b-4650cd4f363d", "CASE",
    "Cooler Master", "MasterBox NR200 Black", "MCB-NR200-KNNN-S00", "MCB-NR200-KNNN-S00", [
      "https://www.coolermaster.com/ko-kr/products/masterbox-nr200.html"]),
];

function requireValid(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(`Invalid power/case preview stage: ${message}`);
}
function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
function exactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  return Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key));
}
function same(left: unknown, right: unknown): boolean {
  if (Object.is(left, right)) return true;
  if (Array.isArray(left) && Array.isArray(right))
    return left.length === right.length && left.every((item, index) => same(item, right[index]));
  if (record(left) && record(right)) return exactKeys(left, Object.keys(right))
    && Object.keys(right).every(key => same(left[key], right[key]));
  return false;
}
function normalizedPrice(entry: unknown): unknown {
  if (!record(entry) || !record(entry.price) || typeof entry.price.observedAt !== "string") return entry;
  // Java's Instant removes trailing fractional zeroes; preserve the same UTC instant, not a new observation.
  return { ...entry, price: { ...entry.price,
    observedAt: entry.price.observedAt.replace(/(\.\d*?[1-9])0+Z$/, "$1Z") } };
}

/** Separately approved release; the original false-only review/stage is never promoted in place. */
export function validateApprovedPowerCaseSnapshots(catalog: unknown, references: unknown, approval: unknown,
  base: CatalogSnapshot, approvedReferences: ReferenceCatalogSnapshot) {
  const prices = REVIEWED_ADDITIONS.filter(item => item.entry.price !== null).map(item => ({
    canonicalId: item.entry.identity.canonicalId, ...item.entry.price!,
  }));
  const expected = {
    schemaVersion: 1, decision: "APPROVED_POWER_CASE_RELEASE_2026_10_11",
    databaseApplyApproved: true, publicationApproved: true, autoActivation: false,
    stageManifestFile: "data/catalog-shared/power-case-stage-2026-10-11.json",
    sourceFile: SOURCE_FILE, sourceSha256: POWER_CASE_REVIEW_SHA256,
    newProducts: 7, newPrices: 2, preservedProducts: 308, preservedCurrentPrices: 81, preservedReferences: 227,
    catalogVersion: "all-catalog-v1-6bd04f97b7cba4fe30dc5113d15db56847026a744cf6c5f7006da62a1e9e140a",
    priceVersion: "prices-v1-dfa1885e2f5d29b7c747eb4c3a730467aa4b78f70830c9d682e2be104825c09b",
    referenceVersion: "references-v1-0a60f7fa4c600e99ea6c4c4c74b3398f1c6ebd168ff13ef3af73f9c2ebcc0dfd",
    referencesFile: "data/catalog-shared/approved-power-case-reference-prices-2026-10-11.json",
    canonicalIds: REVIEWED_ADDITIONS.map(item => item.entry.identity.canonicalId), priceObservations: prices,
  };
  requireValid(record(approval) && Array.isArray(approval.priceObservations), "approval fields");
  const normalized = { ...approval, priceObservations: approval.priceObservations.map(price =>
    record(price) && typeof price.observedAt === "string" ? { ...price,
      observedAt: price.observedAt.replace(/(\.\d*?[1-9])0+Z$/, "$1Z") } : price) };
  requireValid(same(normalized, expected), "exact separate publication approval required");
  requireValid(record(catalog) && record(references) && catalog.catalogVersion === expected.catalogVersion
    && catalog.priceVersion === expected.priceVersion && references.catalogVersion === expected.catalogVersion
    && references.referenceVersion === expected.referenceVersion && references.publicationApproved === true
    && Array.isArray(catalog.products),
    "approved publication versions or reference gate");
  const entries = new Map(catalog.products.map(entry => {
    requireValid(record(entry) && record(entry.identity) && typeof entry.identity.canonicalId === "string", "approved catalog entry");
    return [entry.identity.canonicalId, entry] as const;
  }));
  const stage = validatePowerCaseStage({ schemaVersion: 1, mode: "STAGED_PREVIEW_ONLY",
    publicationApproved: false, databaseApplyApproved: false, sourceFile: SOURCE_FILE,
    sourceSha256: POWER_CASE_REVIEW_SHA256, baseCatalogVersion: base.catalogVersion, basePriceVersion: base.priceVersion,
    additions: REVIEWED_ADDITIONS.map(item => ({ ...item, entry: entries.get(item.entry.identity.canonicalId) })),
    catalog, references: { ...references, publicationApproved: false } },
  base, approvedReferences);
  return { catalog: stage.catalog, references: references as unknown as ReferenceCatalogSnapshot,
    approval: approval as unknown as ApprovedPowerCaseRelease };
}

/** Validate the unpublished envelope without widening the production catalog's allowed product types. */
export function validatePowerCaseStage(candidate: unknown, base: CatalogSnapshot,
  approvedReferences: ReferenceCatalogSnapshot): PowerCaseStageEnvelope {
  requireValid(record(candidate) && exactKeys(candidate, ["schemaVersion", "mode", "publicationApproved",
    "databaseApplyApproved", "sourceFile", "sourceSha256", "baseCatalogVersion", "basePriceVersion",
    "additions", "catalog", "references"]), "envelope fields");
  requireValid(candidate.schemaVersion === 1 && candidate.mode === "STAGED_PREVIEW_ONLY"
    && candidate.publicationApproved === false && candidate.databaseApplyApproved === false, "preview-only gates");
  requireValid(candidate.sourceFile === SOURCE_FILE && candidate.sourceSha256 === POWER_CASE_REVIEW_SHA256,
    "review source pin");
  requireValid(base.products.length === 308 && base.products.filter(entry => entry.price !== null).length === 81,
    "known 308/81 approved base");
  requireValid(candidate.baseCatalogVersion === base.catalogVersion && candidate.basePriceVersion === base.priceVersion,
    "base versions");
  requireValid(Array.isArray(candidate.additions) && candidate.additions.length === 7, "exact seven-product scope");
  const reviewed = new Map(REVIEWED_ADDITIONS.map(item => [item.key, item]));
  const additions = new Map<string, CatalogSnapshot["products"][number]>();
  for (const item of candidate.additions) {
    requireValid(record(item) && exactKeys(item, ["key", "sourceIdentity", "officialEvidenceUrls", "entry"])
      && typeof item.key === "string", "addition fields");
    const expected = reviewed.get(item.key);
    requireValid(expected !== undefined && same({ ...item, entry: normalizedPrice(item.entry) }, expected),
      "unreviewed identity, source evidence or price");
    requireValid(!additions.has(expected.entry.identity.canonicalId), "duplicate addition");
    additions.set(expected.entry.identity.canonicalId, item.entry as CatalogSnapshot["products"][number]);
  }
  requireValid(record(candidate.catalog) && exactKeys(candidate.catalog,
    ["schemaVersion", "catalogVersion", "priceVersion", "policy", "products"]), "catalog fields");
  const snapshot = candidate.catalog;
  requireValid(snapshot.schemaVersion === 2 && typeof snapshot.catalogVersion === "string"
    && CATALOG_VERSION.test(snapshot.catalogVersion) && snapshot.catalogVersion !== base.catalogVersion,
    "distinct stage catalog version");
  requireValid(typeof snapshot.priceVersion === "string" && PRICE_VERSION.test(snapshot.priceVersion)
    && snapshot.priceVersion !== base.priceVersion, "distinct stage price version");
  requireValid(same(snapshot.policy, base.policy) && Array.isArray(snapshot.products)
    && snapshot.products.length === 315, "stage catalog count or policy");
  const preserved = new Map(base.products.map(entry => [entry.identity.canonicalId, entry]));
  const seen = new Set<string>();
  for (const entry of snapshot.products) {
    requireValid(record(entry) && record(entry.identity) && typeof entry.identity.canonicalId === "string", "catalog entry");
    const id = entry.identity.canonicalId;
    requireValid(!seen.has(id), "duplicate catalog identity"); seen.add(id);
    const known = preserved.get(id);
    requireValid(known === undefined ? same(entry, additions.get(id)) : same(entry, known),
      "existing payload changed or catalog addition differs");
  }
  requireValid([...preserved.keys(), ...additions.keys()].every(id => seen.has(id)), "catalog identity missing");
  requireValid(same(candidate.references, { ...approvedReferences, catalogVersion: snapshot.catalogVersion,
    publicationApproved: false }), "227-reference payload must remain unchanged and unpublished");
  return candidate as unknown as PowerCaseStageEnvelope;
}
