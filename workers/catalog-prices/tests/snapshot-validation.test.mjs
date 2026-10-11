import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { Miniflare } from "miniflare";
import { localWorkerOptions } from "../scripts/miniflare-options.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const approved = JSON.parse(await readFile(new URL("../generated/catalog-v2.json", import.meta.url), "utf8"));
const pilot = JSON.parse(await readFile(new URL("../generated/catalog.json", import.meta.url), "utf8"));
const compiled = await build({ absWorkingDir: root, entryPoints: ["src/snapshot-validation.ts"],
  bundle: true, platform: "node", format: "esm", write: false, target: "es2022" });
const { validatePriceSource, validateFullCatalogSnapshot } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled.outputFiles[0].text).toString("base64")}`);
const token = "3".repeat(64);
const boardIds = ["00000000-0000-0000-0000-00000000aa01", "00000000-0000-0000-0000-00000000aa02"];
const now = "2026-10-10T13:00:00Z";
const knownBoard = approved.products.find((entry) => entry.identity.type === "MOTHERBOARD");

function preview() {
  const candidate = structuredClone(approved);
  for (let i = 0; i < boardIds.length; i++) {
    candidate.products.push({ identity: { ...knownBoard.identity, canonicalId: boardIds[i],
      modelName: `Synthetic reviewed board ${i + 1}`, partNumber: null,
      identityKind: "PHYSICAL_VARIANT", role: i === 0 ? "PURCHASE_CANDIDATE" : "BOTH",
      active: false, verificationStatus: "UNVERIFIED",
      saleUnit: "PRODUCT", moduleCount: null },
    price: { amountKrw: 123456 + i, sourceName: i === 0 ? "COMPUZONE" : "ICODA",
      sourceUrl: i === 0
        ? "https://www.compuzone.co.kr/product/product_detail.htm?ProductNo=123456&DivNo=4&MediumDivNo=0"
        : "https://usr.icoda.co.kr/item/view/654321", observedAt: "2026-10-10T12:00:00.123456789Z" } });
  }
  return candidate;
}

test("approved base stays 307 identities and 80 prices; supported source IDs match exactly", () => {
  assert.equal(approved.products.length, 307);
  assert.equal(approved.products.filter((entry) => entry.price !== null).length, 80);
  assert.equal(validateFullCatalogSnapshot(approved, approved), approved);
  const singleModuleRam = approved.products.find((entry) => entry.identity.type === "RAM"
    && entry.identity.saleUnit === "PRODUCT");
  assert.equal(singleModuleRam.identity.moduleCount, 1);
  assert.ok(approved.products.some((entry) => entry.identity.identityKind === "MODEL_REFERENCE"
    && entry.price !== null));
  for (const [name, url, id] of [
    ["DANAWA", "https://prod.danawa.com/info/?pcode=123", "123"],
    ["SAMSUNG_CNH", "https://www.samsungzip.shop/goods/goods_view.php?goodsNo=456", "456"],
    ["COMPUZONE", "https://www.compuzone.co.kr/product/product_detail.htm?ProductNo=123456", "123456"],
    ["COMPUZONE", "https://www.compuzone.co.kr/product/product_detail.htm?MediumDivNo=00&ProductNo=123456&DivNo=4", "123456"],
    ["ICODA", "https://usr.icoda.co.kr/item/view/654321", "654321"],
  ]) {
    assert.equal(validatePriceSource(name, url, id), id);
    assert.throws(() => validatePriceSource(name, url, `${id}0`), /ID mismatch/);
  }
});

test("retail URLs reject duplicate, foreign, encoded, nonpositive and oversized sale IDs", () => {
  const compu = "https://www.compuzone.co.kr/product/product_detail.htm";
  const icoda = "https://usr.icoda.co.kr/item/view/";
  for (const url of [
    `${compu}?ProductNo=0`, `${compu}?ProductNo=01`, `${compu}?ProductNo=-1`,
    `${compu}?ProductNo=12345678901234567`, `${compu}?ProductNo=123&ProductNo=124`,
    `${compu}?ProductNo=123&DivNo=4&DivNo=5`, `${compu}?ProductNo=123&MediumDivNo=4&MediumDivNo=5`,
    `${compu}?ProductNo=123&DivNo=12345678901234567`, `${compu}?ProductNo=123&MediumDivNo=12345678901234567`,
    `${compu}?ProductNo=123&unknown=1`, `${compu}?ProductNo=123&DivNo=`, `${compu}?ProductNo=123&DivNo=-1`,
    `${compu}?ProductNo=123&`, `${compu}?DivNo=4`, `${compu}?%50roductNo=123`, `${compu}?ProductNo=%31`,
    `${compu}?ProductNo=123#details`, `${compu}?ProductNo=123#`,
    `${compu.replace("https:", "http:")}?ProductNo=123`,
    `${compu.replace("www.compuzone.co.kr", "www.compuzone.co.kr:443")}?ProductNo=123`,
    `${compu.replace("www.compuzone.co.kr", "user@www.compuzone.co.kr")}?ProductNo=123`,
    `${compu.replace("www.compuzone.co.kr", "www.compuzone.co.kr.evil.test")}?ProductNo=123`,
    `${compu.replace("/product/", "/other/../product/")}?ProductNo=123`,
    `${compu}?productno=123`, `${compu}?ProductNo=1 2`, `${compu}?ProductNo=123\n`,
  ]) assert.throws(() => validatePriceSource("COMPUZONE", url), undefined, url);
  for (const url of [
    `${icoda}0`, `${icoda}01`, `${icoda}-1`, `${icoda}12345678901234567`,
    `${icoda}123?`, `${icoda}123?ProductNo=123`, `${icoda}123#`, `${icoda}123#details`,
    `${icoda}123/`, `${icoda}%31`, `${icoda}123/../456`,
    `${icoda.replace("https:", "http:")}123`,
    `${icoda.replace("usr.icoda.co.kr", "usr.icoda.co.kr:443")}123`,
    `${icoda.replace("usr.icoda.co.kr", "user@usr.icoda.co.kr")}123`,
    `${icoda.replace("usr.icoda.co.kr", "usr.icoda.co.kr.evil.test")}123`,
  ]) assert.throws(() => validatePriceSource("ICODA", url), undefined, url);
  assert.throws(() => validatePriceSource("COMPUZONE", `${icoda}123`));
  assert.throws(() => validatePriceSource("UNAPPROVED", `${compu}?ProductNo=123`));
});

test("provider numeric limits match the Java source policy", () => {
  for (const [name, prefix] of [["DANAWA", "https://prod.danawa.com/info/?pcode="],
    ["SAMSUNG_CNH", "https://www.samsungzip.shop/goods/goods_view.php?goodsNo="]]) {
    const maximum = "1".repeat(128);
    assert.equal(validatePriceSource(name, `${prefix}${maximum}`, maximum), maximum);
    assert.throws(() => validatePriceSource(name, `${prefix}${maximum}1`));
  }
  const url = "https://www.compuzone.co.kr/product/product_detail.htm?ProductNo=1234567890123456"
    + "&DivNo=0000000000000000&MediumDivNo=1234567890123456";
  assert.equal(validatePriceSource("COMPUZONE", url), "1234567890123456");
});

test("explicit preview admits PN-null physical purchase boards while keeping all base identities", () => {
  const candidate = preview();
  assert.throws(() => validateFullCatalogSnapshot(candidate, approved), /explicit preview/);
  const accepted = validateFullCatalogSnapshot(candidate, approved, { allowReviewedBoardAdditions: true });
  assert.equal(accepted.products.length, 309);
  assert.deepEqual(accepted.products.slice(0, 307), approved.products);
  assert.equal(accepted.products[307].identity.partNumber, null);
  assert.equal(accepted.products[308].identity.role, "BOTH");
});

test("preview refuses removal, replacement, duplicate or any change to a base identity", () => {
  const opts = { allowReviewedBoardAdditions: true };
  const missing = preview(); missing.products.splice(0, 1);
  assert.throws(() => validateFullCatalogSnapshot(missing, approved, opts), /missing/);
  const duplicated = preview(); duplicated.products.push(structuredClone(duplicated.products[307]));
  assert.throws(() => validateFullCatalogSnapshot(duplicated, approved, opts), /duplicate/);
  for (const [key, value] of [["manufacturer", "Different manufacturer"], ["modelName", "Different model"],
    ["partNumber", "DIFFERENT-PN"], ["identityKind", "PHYSICAL_VARIANT"], ["role", "BOTH"],
    ["verificationStatus", "DIFFERENT"], ["active", true]]) {
    const changed = preview(); changed.products[0].identity[key] = value;
    assert.throws(() => validateFullCatalogSnapshot(changed, approved, opts), /identity changed/);
  }
  const replacement = preview(); replacement.products[0].identity.canonicalId = "00000000-0000-0000-0000-00000000aa03";
  assert.throws(() => validateFullCatalogSnapshot(replacement, approved, opts));
});

test("preview never accepts appended references, nonboards or incorrect sale units", () => {
  for (const patch of [{ type: "GPU" }, { type: "RAM", saleUnit: "RAM_KIT", moduleCount: 2 },
    { identityKind: "MODEL_REFERENCE" }, { identityKind: "LEGACY_UNCLASSIFIED" },
    { role: "INSTALLED_PC_REFERENCE" }, { role: "UNASSIGNED" }, { moduleCount: 2 }, { saleUnit: "RAM_KIT" },
    { partNumber: "UNREVIEWED-PN" }, { active: true }, { verificationStatus: "VERIFIED" }]) {
    const candidate = preview(); Object.assign(candidate.products[307].identity, patch);
    assert.throws(() => validateFullCatalogSnapshot(candidate, approved, { allowReviewedBoardAdditions: true }));
  }
  const modelReference = structuredClone(approved);
  modelReference.products.find((entry) => entry.identity.identityKind === "MODEL_REFERENCE").price
    = structuredClone(preview().products[307].price);
  assert.throws(() => validateFullCatalogSnapshot(modelReference, approved), /model reference/);
});

test("preview preserves every existing price and null, including ordinary physical products", () => {
  const opts = { allowReviewedBoardAdditions: true };
  for (let index = 0; index < approved.products.length; index++) {
    const candidate = preview();
    const previous = approved.products[index].price;
    candidate.products[index].price = previous === null
      ? structuredClone(candidate.products[307].price) : null;
    assert.throws(() => validateFullCatalogSnapshot(candidate, approved, opts), /existing price changed/);
  }
  const index = approved.products.findIndex((entry) => entry.price !== null);
  for (const [key, value] of [["amountKrw", approved.products[index].price.amountKrw + 1],
    ["sourceName", "ICODA"], ["sourceUrl", "https://prod.danawa.com/info/?pcode=123"],
    ["observedAt", "2026-10-10T12:00:00.123456789Z"]]) {
    const candidate = preview(); candidate.products[index].price[key] = value;
    assert.throws(() => validateFullCatalogSnapshot(candidate, approved, opts));
  }
});

test("preview rejects more than 1000 products and prices above the approved maximum", () => {
  const opts = { allowReviewedBoardAdditions: true };
  const oversized = preview();
  while (oversized.products.length < 1001) oversized.products.push(structuredClone(oversized.products[307]));
  assert.throws(() => validateFullCatalogSnapshot(oversized, approved, opts), /product count/);
  const candidate = preview(); candidate.products[307].price.amountKrw = 1_000_000_000_000;
  assert.throws(() => validateFullCatalogSnapshot(candidate, approved, opts), /price amount/);
  candidate.products[307].price.amountKrw = 999_999_999_999;
  assert.equal(validateFullCatalogSnapshot(candidate, approved, opts), candidate);
});

test("snapshot preserves strict public fields, positive amounts and actual UTC instants", () => {
  for (const badPrice of [{ amountKrw: 0 }, { amountKrw: -1 }, { amountKrw: Number.MAX_SAFE_INTEGER + 1 },
    { observedAt: "2026-02-30T12:00:00Z" }, { observedAt: "2026-10-10T12:00:00.1234567890Z" },
    { observedAt: "2026-10-10T21:00:00+09:00" }, { sourceName: "ICODA", sourceUrl: "https://usr.icoda.co.kr/item/view/0" },
    { shippingFeeKrw: 1000 }]) {
    const candidate = preview(); Object.assign(candidate.products[307].price, badPrice);
    assert.throws(() => validateFullCatalogSnapshot(candidate, approved, { allowReviewedBoardAdditions: true }));
  }
  const extraField = preview(); extraField.products[307].identity.reviewedSaleConfiguration = {};
  assert.throws(() => validateFullCatalogSnapshot(extraField, approved, { allowReviewedBoardAdditions: true }), /identity fields/);
});

let runtime;
before(async () => {
  const input = JSON.stringify(preview());
  const result = await build({ absWorkingDir: root, stdin: { resolveDir: root, loader: "ts", contents: `
    import { createPreviewPriceRequestHandler, handlePriceRequest } from "./src/provider";
    const input = ${input};
    const preview = createPreviewPriceRequestHandler(input);
    input.products[307].identity.modelName = "MUTATED CALLER INPUT";
    input.products[307].price.amountKrw = 1;
    export default { fetch(request, env) {
      const handler = request.headers.get("X-Test-Use-Default") === "yes" ? handlePriceRequest : preview;
      return handler(request, env, Date.parse(${JSON.stringify(now)}));
    } };` }, bundle: true, platform: "browser", format: "esm", target: "es2022", write: false });
  runtime = new Miniflare(localWorkerOptions(result.outputFiles[0].text, { SHARED_PRICES_API_TOKEN: token }));
  await runtime.ready;
});
after(async () => { await runtime?.dispose(); });

function request(ids, extraHeaders = {}, path = "/api/v2/prices") {
  return runtime.dispatchFetch(`http://worker.test${path}?canonicalIds=${ids.join(",")}`, {
    headers: { Authorization: `Bearer ${token}`, ...extraHeaders },
  });
}

test("preview HTTP serves appended boards without caller mutations or public contract additions", async () => {
  const response = await request(boardIds);
  assert.equal(response.status, 200);
  const body = await response.json();
  for (let i = 0; i < boardIds.length; i++) {
    assert.deepEqual(body.items[i].product, preview().products[307 + i].identity);
    assert.deepEqual(body.items[i].price, preview().products[307 + i].price);
    assert.equal(body.items[i].status, "OK");
    assert.equal(body.items[i].freshness, "FRESH");
  }
  assert.equal(response.headers.get("Cache-Control"), "no-store");
});

test("default v2 still returns unknown for preview additions and keeps all 307 identities/80 prices", async () => {
  const unknown = await request(boardIds, { "X-Test-Use-Default": "yes" });
  assert.deepEqual((await unknown.json()).items.map((item) => item.status), ["UNKNOWN_PRODUCT", "UNKNOWN_PRODUCT"]);
  const items = [];
  for (let start = 0; start < approved.products.length; start += 100) {
    const expected = approved.products.slice(start, start + 100);
    const response = await request(expected.map((entry) => entry.identity.canonicalId), { "X-Test-Use-Default": "yes" });
    assert.equal(response.status, 200);
    const body = await response.json();
    body.items.forEach((item, i) => { assert.deepEqual(item.product, expected[i].identity); assert.deepEqual(item.price, expected[i].price); });
    items.push(...body.items);
  }
  assert.equal(items.length, 307);
  assert.equal(items.filter((item) => item.price !== null).length, 80);
});

test("preview keeps the existing v1 14-product/8-price endpoint unchanged", async () => {
  const response = await request(pilot.products.map((entry) => entry.identity.canonicalId), {}, "/api/v1/prices");
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.schemaVersion, 1);
  assert.equal(body.catalogVersion, pilot.catalogVersion);
  assert.equal(body.items.length, 14);
  assert.equal(body.items.filter((item) => item.price !== null).length, 8);
  body.items.forEach((item, i) => { assert.deepEqual(item.product, pilot.products[i].identity); assert.deepEqual(item.price, pilot.products[i].price); });
});
