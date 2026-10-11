import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { generate, estimate, neighbourCalculation, normalizedInputSha256 } from './generate-reference-prices.mjs';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const read=p=>JSON.parse(fs.readFileSync(path.join(root,p),'utf8').replace(/^\uFEFF/,''));
const stamp='2026-10-10T14:21:39.957Z';
const digest=p=>crypto.createHash('sha256').update(fs.readFileSync(path.join(root,p))).digest('hex');
const ramTarget={identity:{type:'RAM',manufacturer:'Corsair',modelName:'16 GB kit',saleUnit:'RAM_KIT',moduleCount:2},specification:{memoryType:'DDR4',dataRateMts:2666,moduleCapacityBytes:8*2**30,moduleCount:2}};
const familyAnchor={identity:{type:'RAM',manufacturer:'Kingston',modelName:'Different Kingston 8 GB',saleUnit:'PRODUCT',moduleCount:1},specification:{memoryType:'DDR4',dataRateMts:2666,moduleCapacityBytes:8*2**30,moduleCount:1},quote:{canonicalId:null,modelName:'Different Kingston 8 GB',amountKrw:85900,sourceName:'DANAWA',sourceUrl:'https://prod.danawa.com/info/?pcode=7013641',sourceDate:'2026-10-10',saleUnit:'PRODUCT',moduleCount:1},anchorBasis:'DIFFERENT_RAM_FAMILY_ESTIMATION_ANCHOR_ONLY'};

test('all 227 exact identities are covered, with no actual quote replaced or public input mutation',()=>{
 const protectedPaths=['workers/catalog-prices/generated/catalog-retail-approved.json','data/catalog-shared/active-approved-prices.json','data/catalog-shared/approved-retail-extension-2026-10-10.json'];
 const before=protectedPaths.map(digest);const {preview}=generate(root,stamp);const runtime=read(protectedPaths[0]);const expected=runtime.products.filter(x=>x.price===null).map(x=>x.identity);
 assert.deepEqual(preview.products.map(x=>x.identity),expected);assert.equal(preview.products.length,227);assert.equal(new Set(preview.products.map(x=>x.identity.canonicalId)).size,227);assert.deepEqual(protectedPaths.map(digest),before);assert.equal(preview.publicationApproved,false);
 const actualIds=new Set(runtime.products.filter(x=>x.price!==null).map(x=>x.identity.canonicalId));assert.ok(preview.products.every(x=>!actualIds.has(x.identity.canonicalId)));
});

test('fixed original sources and review clock reproduce the entire preview and version',()=>{
 const a=generate(root,stamp),b=generate(root,stamp);assert.deepEqual(a,b);assert.equal(a.preview.referenceVersion,'references-v1-'+crypto.createHash('sha256').update(JSON.stringify(a.preview.products)).digest('hex'));
});

test('RAM kit conversion remains an estimate per kit with the original different-model anchor amount',()=>{
 const r=estimate(ramTarget,[familyAnchor],stamp);assert.equal(r.referenceEstimate.amountKrw,172000);assert.equal(r.referenceEstimate.saleUnit,'RAM_KIT');assert.equal(r.referenceEstimate.moduleCount,2);assert.equal(r.referenceEstimate.confidence,'ESTIMATED');assert.equal(r.referenceEstimate.identityScope,'SIMILAR_SPEC');assert.equal(r.referenceEstimate.sourceDate,null);assert.equal(r.referenceEstimate.sourceQuotes[0].amountKrw,85900);assert.equal(r.referenceEstimate.sourceQuotes[0].canonicalId,null);assert.equal(r.calculation.selected[0].multiplier,2);assert.ok(r.referenceEstimate.rangeLowKrw<r.referenceEstimate.amountKrw);assert.ok(r.referenceEstimate.rangeHighKrw>r.referenceEstimate.amountKrw);
});

test('different DDR or different product categories cannot silently provide a RAM estimate',()=>{
 const wrongDdr={...familyAnchor,specification:{...familyAnchor.specification,memoryType:'DDR5'}};
 const wrongCategory={...familyAnchor,identity:{...familyAnchor.identity,type:'GPU'}};
 assert.throws(()=>estimate(ramTarget,[wrongDdr,wrongCategory],stamp),/No same-category/);assert.throws(()=>neighbourCalculation(ramTarget,wrongCategory),/cannot cross product categories/);assert.throws(()=>neighbourCalculation(ramTarget,wrongDdr),/same DDR/);
});

test('missing quantitative specification stops generation instead of manufacturing a zero or NaN price',()=>{
 const missing={...ramTarget,specification:{...ramTarget.specification,moduleCapacityBytes:null}};
 assert.throws(()=>estimate(missing,[familyAnchor],stamp),/Missing required estimate specification/);
});

test('source launch dates are preserved and no newly calculated estimate receives a fictional market date',()=>{
 const {preview}=generate(root,stamp);const launch=read('data/catalog-review/historical-domestic-source-probe-2026-10-10.json');
 for(const source of launch.items){const r=preview.products.find(x=>x.identity.canonicalId===source.canonicalId).referenceEstimate;assert.equal(r.sourceDate,source.referencePrice.sourceDate);assert.equal(r.sourceQuotes[0].sourceDate,source.referencePrice.sourceDate);assert.equal(r.amountKrw,source.referencePrice.amountKrw);assert.equal(r.identityScope,'MODEL');assert.equal(r.reviewedAt,stamp);assert.notEqual(r.sourceDate,stamp.slice(0,10));}
 for(const p of preview.products.filter(x=>x.referenceEstimate.basis==='SIMILAR_PART_ESTIMATE'))assert.equal(p.referenceEstimate.sourceDate,null);
});

test('all rounded reference amounts have ordered heuristic ranges and compact HTTPS evidence',()=>{
 const {preview,audit}=generate(root,stamp);assert.ok(audit.summary.largest50ResponseBytes<=65000);assert.equal(audit.summary.sourceQuotesMaximumPerProduct,2);
 for(const {referenceEstimate:r} of preview.products){assert.ok(Number.isSafeInteger(r.amountKrw)&&r.amountKrw>0);assert.ok(r.sourceQuotes.length>0&&r.sourceQuotes.length<=2);assert.ok(r.sourceQuotes.every(q=>new URL(q.sourceUrl).protocol==='https:'&&Number.isSafeInteger(q.amountKrw)&&q.amountKrw>0));if(r.basis==='SIMILAR_PART_ESTIMATE'){assert.ok(r.rangeLowKrw>0&&r.rangeLowKrw<=r.amountKrw);assert.ok(r.rangeHighKrw>=r.amountKrw);assert.equal(r.amountKrw%1000,0);}}
});

test('five model quotes preserve observed amounts while explicitly withholding exact sale equivalence',()=>{
 const {preview}=generate(root,stamp);const model=preview.products.filter(x=>x.referenceEstimate.basis==='MODEL_RETAIL_REFERENCE');assert.equal(model.length,5);
 const asus=model.find(x=>x.identity.modelName==='TUF GAMING B860-PLUS WIFI');assert.equal(asus.referenceEstimate.amountKrw,275350);assert.equal(asus.identity.identityKind,'MODEL_REFERENCE');assert.equal(asus.referenceEstimate.identityScope,'MODEL');assert.equal(asus.referenceEstimate.confidence,'VERIFIED_MODEL');assert.equal(asus.referenceEstimate.rangeLowKrw,null);assert.equal(asus.referenceEstimate.sourceQuotes[0].amountKrw,275350);
});

test('every quote preserves its original unit and RAM count instead of inheriting the target kit unit',()=>{
 const {preview}=generate(root,stamp);const runtime=read('workers/catalog-prices/generated/catalog-retail-approved.json');const identityMap=new Map(runtime.products.map(x=>[x.identity.canonicalId,x.identity]));
 let convertedKitFromSingleSource=0;
 for(const p of preview.products)for(const q of p.referenceEstimate.sourceQuotes){
  if(q.canonicalId){const source=identityMap.get(q.canonicalId);assert.ok(source);assert.equal(q.saleUnit,source.saleUnit);assert.equal(q.moduleCount,source.moduleCount);}
  else if(q.sourceName==='LG_OFFICIAL'){assert.equal(p.identity.type,'MONITOR');assert.equal(q.saleUnit,'PRODUCT');assert.equal(q.moduleCount,null);}
  else{assert.equal(p.identity.type,'RAM');assert.equal(q.saleUnit,'PRODUCT');assert.equal(q.moduleCount,1);}
  if(p.identity.type!=='RAM'){assert.equal(q.saleUnit,'PRODUCT');assert.equal(q.moduleCount,null);}
  if(p.referenceEstimate.saleUnit==='RAM_KIT'&&q.saleUnit==='PRODUCT'){convertedKitFromSingleSource++;assert.equal(p.referenceEstimate.basis,'SIMILAR_PART_ESTIMATE');assert.ok(p.referenceEstimate.moduleCount>=2);assert.equal(q.moduleCount,1);}
 }
 assert.ok(convertedKitFromSingleSource>0);
 const kit=estimate(ramTarget,[familyAnchor],stamp).referenceEstimate;assert.equal(kit.saleUnit,'RAM_KIT');assert.equal(kit.moduleCount,2);assert.equal(kit.sourceQuotes[0].saleUnit,'PRODUCT');assert.equal(kit.sourceQuotes[0].moduleCount,1);
});

test('LG original model-family launch sources never claim an exact -B regional catalog canonical ID',()=>{
 const {preview}=generate(root,stamp);let direct=0,estimateAnchors=0;
 for(const p of preview.products)for(const q of p.referenceEstimate.sourceQuotes)if(q.sourceName==='LG_OFFICIAL'){
  assert.equal(q.canonicalId,null);assert.ok(['27GL850','27GP950','32GQ950'].includes(q.modelName));assert.equal(q.saleUnit,'PRODUCT');assert.equal(q.moduleCount,null);
  if(p.referenceEstimate.basis==='LAUNCH_PRICE'){direct++;assert.equal(p.identity.modelName,q.modelName+'-B');assert.equal(p.referenceEstimate.identityScope,'MODEL');}
  else{estimateAnchors++;assert.equal(p.referenceEstimate.basis,'SIMILAR_PART_ESTIMATE');assert.equal(p.referenceEstimate.identityScope,'SIMILAR_SPEC');}
 }
 assert.equal(direct,3);assert.ok(estimateAnchors>0);
});

test('removing an exact canonical claim from a model source does not change tied anchor selection or amount',()=>{
 const specification={nativeWidthPx:2560,nativeHeightPx:1440,nativeStandardRefreshHz:144,screenSizeInches:27,panelType:'IPS'};
 const target={identity:{type:'MONITOR',manufacturer:'LG',modelName:'Target',saleUnit:'PRODUCT',moduleCount:null},specification};
 const anchors=[['0001',300000,'z'],['0002',500000,'y'],['0003',900000,'a']].map(([id,amount,url])=>({identity:{type:'MONITOR',canonicalId:id,modelName:id},specification,quote:{canonicalId:id,modelName:id,amountKrw:amount,sourceUrl:'https://example.com/'+url,saleUnit:'PRODUCT',moduleCount:null}}));
 const before=estimate(target,anchors,stamp);const modelSources=anchors.map(a=>({...a,quote:{...a.quote,canonicalId:null}}));const after=estimate(target,modelSources,stamp);
 assert.equal(after.referenceEstimate.amountKrw,before.referenceEstimate.amountKrw);assert.deepEqual(after.referenceEstimate.sourceQuotes.map(q=>q.sourceUrl),before.referenceEstimate.sourceQuotes.map(q=>q.sourceUrl));
});

test('audit input hashes tolerate Git LF/CRLF checkout differences and still detect changed JSON content',()=>{
 const lf='{\n  "modelName": "킹스톤 DDR4",\n  "amountKrw": 85900\n}\n';const crlf=lf.replaceAll('\n','\r\n');
 assert.equal(normalizedInputSha256(Buffer.from(lf,'utf8')),normalizedInputSha256(Buffer.from(crlf,'utf8')));
 assert.equal(normalizedInputSha256(lf),normalizedInputSha256(crlf));assert.notEqual(normalizedInputSha256(lf),normalizedInputSha256(lf.replace('85900','86000')));
 const {preview,audit}=generate(root,stamp);assert.equal(audit.hashBasis,'SHA256_UTF8_CRLF_TO_LF');assert.equal(preview.referenceVersion,read('data/catalog-shared/reference-prices-preview-2026-10-10.json').referenceVersion);
});
