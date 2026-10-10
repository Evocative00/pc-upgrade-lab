import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const PREVIEW = 'data/catalog-shared/reference-prices-preview-2026-10-10.json';
const AUDIT = 'data/catalog-review/reference-price-preview-2026-10-10.json';
const RUNTIME = 'workers/catalog-prices/generated/catalog-retail-approved.json';
const LAUNCH = 'data/catalog-review/historical-domestic-source-probe-2026-10-10.json';
const FAMILY = 'data/catalog-review/held146-ram-four-family-quotes-2026-10-10.json';
const read = p => JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,''));
const sha = data => crypto.createHash('sha256').update(data).digest('hex');
export const normalizedInputSha256 = data => sha((Buffer.isBuffer(data)?data.toString('utf8'):String(data)).replaceAll('\r\n','\n'));
const round = n => Math.max(1000,Math.round(n/(n>=1000000?10000:1000))*(n>=1000000?10000:1000));
const clamp = (n,low,high)=>Math.min(high,Math.max(low,n));
const logDistance = (a,b)=>a>0&&b>0?Math.abs(Math.log2(a/b)):1;
const median = a=>{const b=[...a].sort((x,y)=>x-y);return b.length%2?b[Math.floor(b.length/2)]:(b[b.length/2-1]+b[b.length/2])/2;};
const date = s=>/^\d{4}-\d{2}-\d{2}/.test(s||'')?s.slice(0,10):null;

export function gpuClass(name){
 const nvidia=/(?:RTX|GTX)\s*(\d{4})/i.exec(name);const amd=/RX\s*(\d{3,4})/i.exec(name);const intel=/Arc\s*[AB](\d{3})/i.exec(name);
 const m=nvidia||amd||intel;if(!m)return {generation:1,tier:50,variant:0};const number=Number(m[1]);
 if(nvidia)return {generation:Math.floor(number/1000),tier:number%100,variant:/Ti/i.test(name)?7:/SUPER/i.test(name)?4:0};
 if(amd)return {generation:number<1000?0.5:Math.floor(number/1000),tier:number>=9000?number%100:Math.floor(number%1000/10),variant:/XT/i.test(name)?5:0};
 return {generation:/Arc\s*B/i.test(name)?2:1,tier:Math.floor(number/10),variant:0};
}
const chipsetTier = spec=>{const letter=/(?:^|\s)([ABHXZ])\d{3}/i.exec(spec.chipset||'')?.[1]?.toUpperCase();return letter==='X'||letter==='Z'?1.75:letter==='B'?1.35:1;};
const cpuGeneration = name=>{const intel=/Core i\d-(\d{4,5})/.exec(name);const ryzen=/Ryzen \d (\d{4})/.exec(name);return intel?Math.floor(Number(intel[1])/1000):ryzen?Math.floor(Number(ryzen[1])/1000):null;};

export function neighbourCalculation(target,anchor){
 const t=target.specification,a=anchor.specification,type=target.identity.type;
 if(type!==anchor.identity.type)throw new Error('A reference estimate cannot cross product categories');
 let distance=0,multiplier=1,formula='';
 if(type==='CPU'){
  distance=(target.identity.manufacturer===anchor.identity.manufacturer?0:8)+(t.socketCode===a.socketCode?0:4)+3*logDistance(t.coreCount,a.coreCount)+2*logDistance(t.threadCount,a.threadCount)+logDistance(t.boostClockMhz,a.boostClockMhz)+(t.hasIntegratedGraphics===a.hasIntegratedGraphics?0:0.3);
  const tg=cpuGeneration(target.identity.modelName),ag=cpuGeneration(anchor.identity.modelName);if(tg!==null&&ag!==null)distance+=Math.abs(tg-ag)*0.15;
  multiplier=clamp(Math.pow((t.coreCount*(t.boostClockMhz||t.baseClockMhz||4000))/(a.coreCount*(a.boostClockMhz||a.baseClockMhz||4000)),0.55),0.6,1.65);
  formula='anchor amount × clamp((target cores × boost / anchor cores × boost)^0.55, 0.6, 1.65)';
 }else if(type==='GPU'){
  const tc=gpuClass(t.chipset||target.identity.modelName),ac=gpuClass(a.chipset||anchor.identity.modelName);
  distance=(t.chipVendor===a.chipVendor?0:4)+2*logDistance(t.vramBytes,a.vramBytes)+Math.abs(tc.tier+tc.variant-ac.tier-ac.variant)/15+Math.abs(tc.generation-ac.generation)*0.5;
  multiplier=clamp(Math.pow((tc.tier+tc.variant)/(ac.tier+ac.variant),1.2)*Math.pow(1.1,tc.generation-ac.generation)*Math.pow(t.vramBytes/a.vramBytes,0.2),0.5,2);
  formula='anchor amount × clamp((tier proxy ratio)^1.2 × 1.1^(generation proxy delta) × VRAM ratio^0.2, 0.5, 2); naming proxies are not performance benchmarks';
 }else if(type==='MOTHERBOARD'){
  distance=(t.socketCode===a.socketCode?0:4)+(t.memoryType===a.memoryType?0:2)+(t.formFactor===a.formFactor?0:0.7)+(t.chipset===a.chipset?0:0.8)+Math.abs(chipsetTier(t)-chipsetTier(a))*2+(target.identity.manufacturer===anchor.identity.manufacturer?0:0.2);
  multiplier=clamp(chipsetTier(t)/chipsetTier(a)*(t.formFactor==='ATX'&&a.formFactor!=='ATX'?1.1:t.formFactor!=='ATX'&&a.formFactor==='ATX'?0.9:1),0.6,1.8);
  formula='anchor amount × chipset class proxy ratio × form factor proxy (ATX 1.1 / smaller 0.9), clamped 0.6..1.8; A/H=1, B=1.35, X/Z=1.75';
 }else if(type==='MONITOR'){
  const tp=t.nativeWidthPx*t.nativeHeightPx,ap=a.nativeWidthPx*a.nativeHeightPx;
  distance=3*logDistance(tp,ap)+1.5*logDistance(t.nativeStandardRefreshHz,a.nativeStandardRefreshHz)+logDistance(t.screenSizeInches,a.screenSizeInches)+(t.panelType===a.panelType?0:0.4);
  multiplier=clamp(Math.pow(tp/ap,0.5)*Math.pow(t.nativeStandardRefreshHz/a.nativeStandardRefreshHz,0.15)*Math.pow(t.screenSizeInches/a.screenSizeInches,0.6),0.5,2.4);
  formula='anchor amount × pixel count ratio^0.5 × refresh ratio^0.15 × diagonal ratio^0.6, clamped 0.5..2.4';
 }else if(type==='RAM'){
  if(t.memoryType!==a.memoryType)throw new Error('RAM estimate must use the same DDR generation');
  const tg=t.moduleCapacityBytes*t.moduleCount/2**30,ag=a.moduleCapacityBytes*a.moduleCount/2**30;
  distance=1.5*logDistance(t.dataRateMts,a.dataRateMts)+0.8*logDistance(tg,ag)+(t.moduleCount===a.moduleCount?0:0.2)+(target.identity.manufacturer===anchor.identity.manufacturer?0:0.1);
  multiplier=tg/ag*clamp(Math.pow(t.dataRateMts/a.dataRateMts,0.2),0.85,1.15);
  formula='anchor package amount × target total GiB / anchor total GiB × clamp(speed ratio^0.2, 0.85, 1.15); quantity conversion is an estimate, not a target sale quote';
 }else throw new Error(`Unsupported estimate category ${type}`);
 const adjusted=anchor.quote.amountKrw*multiplier;
 if(!Number.isFinite(distance)||!Number.isFinite(adjusted)||adjusted<=0)throw new Error(`Missing required estimate specification ${target.identity.modelName}`);
 return {anchor,distance,multiplier,adjusted,formula};
}

export function estimate(target,anchors,reviewedAt){
 const eligible=anchors.filter(a=>a.identity.type===target.identity.type&&(target.identity.type!=='RAM'||a.specification.memoryType===target.specification.memoryType));
 // A single source URL is counted once even where an installed-model reference and sale item share it.
 const unique=[...new Map(eligible.map(a=>[`${a.quote.sourceUrl}\0${a.quote.amountKrw}`,a])).values()];
 const chosen=unique.map(a=>neighbourCalculation(target,a)).sort((a,b)=>a.distance-b.distance||String(a.anchor.identity.canonicalId||a.anchor.quote.canonicalId||a.anchor.quote.sourceUrl).localeCompare(String(b.anchor.identity.canonicalId||b.anchor.quote.canonicalId||b.anchor.quote.sourceUrl))).slice(0,2);
 if(!chosen.length)throw new Error(`No same-category reference anchors for ${target.identity.modelName}`);
 const amounts=chosen.map(x=>x.adjusted);const amount=round(median(amounts));
 const low=round(Math.min(...amounts)*(target.identity.type==='RAM'?0.5:0.6));
 const high=round(Math.max(...amounts)*(target.identity.type==='RAM'?1.75:1.5));
 return {referenceEstimate:{amountKrw:amount,basis:'SIMILAR_PART_ESTIMATE',identityScope:'SIMILAR_SPEC',saleUnit:target.identity.saleUnit,moduleCount:target.identity.moduleCount,sourceDate:null,reviewedAt,confidence:'ESTIMATED',method:'SPEC_NEIGHBOUR_MEDIAN',rangeLowKrw:Math.min(low,amount),rangeHighKrw:Math.max(high,amount),sourceQuotes:chosen.map(x=>x.anchor.quote),notes:target.identity.type==='RAM'?'같은 DDR·총용량으로 환산한 추정':'유사 제원 2종을 기준으로 추정'},calculation:{targetSpecification:target.specification,eligibleAnchorCount:unique.length,selected:chosen.map(x=>({quote:x.anchor.quote,anchorSpecification:x.anchor.specification,anchorBasis:x.anchor.anchorBasis,distance:x.distance,multiplier:x.multiplier,unroundedAmountKrw:x.adjusted,formula:x.formula})),rawMedianKrw:median(amounts),rounding:'Nearest KRW 1,000 below one million / KRW 10,000 at or above one million',rangeMethod:target.identity.type==='RAM'?'0.50 × minimum adjusted anchor .. 1.75 × maximum adjusted anchor':'0.60 × minimum adjusted anchor .. 1.50 × maximum adjusted anchor',modelledMarketAccuracy:false}};
}

export function generate(root,reviewedAt){
 if(!Number.isFinite(Date.parse(reviewedAt)))throw new Error('generatedAt must be an ISO timestamp');
 const load=p=>read(path.join(root,p));const runtime=load(RUNTIME);const envelope=load('data/catalog-shared/approved-retail-extension-2026-10-10.json');
 const identities=new Map(envelope.identities.products.map(x=>[x.identity.canonicalId,x]));const specs=new Map();const sourceInputs=[RUNTIME,'data/catalog-shared/approved-retail-extension-2026-10-10.json',LAUNCH,FAMILY,'data/catalog-review/pilot-import-preview-2026-10-10.json','data/catalog-review/pilot-model-price-review-2026-10-10.json','data/catalog-review/retail-four-preview-2026-10-10.json'];
 const seeds=path.join(root,'backend/src/main/resources/catalog/seed');const bySource=new Map([...identities.values()].map(x=>[`${x.sourceIdentity.sourceName}\0${x.sourceIdentity.externalId}`,x.identity.canonicalId]));
 for(const d of fs.readdirSync(seeds)){const relative=`backend/src/main/resources/catalog/seed/${d}/manifest.json`;if(!fs.existsSync(path.join(root,relative)))continue;sourceInputs.push(relative);for(const item of load(relative).items){const id=bySource.get(`BUILDCORES\0${item.externalId}`);if(id)specs.set(id,item.specification);}}
 for(const p of load('data/catalog-review/pilot-import-preview-2026-10-10.json').products)specs.set(p.proposedCanonicalId,p.specification);
 for(const p of load('data/catalog-review/retail-four-preview-2026-10-10.json').candidates)if(p.proposedSaleProduct)specs.set(p.proposedSaleProduct.product.canonicalId||p.proposedSaleProduct.canonicalId,p.proposedSaleProduct.specification);
 const asusRetail=runtime.products.find(x=>x.identity.canonicalId==='f5d69333-0ab1-382d-bc72-9434811daccf');specs.set(asusRetail.identity.canonicalId,specs.get('688c711d-70d2-39f5-9642-d1c2a87d4bb7'));
 const quote=(identity,p,sourceName='DANAWA')=>({canonicalId:identity.canonicalId,modelName:identity.modelName,amountKrw:p.amountKrw,sourceName,sourceUrl:p.evidenceUrl||p.sourceUrl,sourceDate:date(p.observedAt),saleUnit:identity.saleUnit,moduleCount:identity.moduleCount});
 const anchors=runtime.products.filter(p=>p.price).map(p=>({identity:p.identity,specification:specs.get(p.identity.canonicalId),quote:quote(p.identity,p.price,p.price.sourceName||'DANAWA'),anchorBasis:'APPROVED_EXACT_RETAIL_OBSERVATION'}));
 if(anchors.length!==81||anchors.some(a=>!a.specification))throw new Error('Expected all 81 approved price anchors with typed specifications');
 const direct=new Map();const auditDirect=[];
 const addDirect=(id,price,sourceName,sourceFile,sourceField,extraNote)=>{const identity=identities.get(id)?.identity;if(!identity||!price?.amountKrw||!price.observedAt||!(price.evidenceUrl||price.sourceUrl)?.startsWith('https://'))throw new Error('Invalid direct model evidence');const q=quote(identity,price,sourceName);direct.set(id,{amountKrw:price.amountKrw,basis:'MODEL_RETAIL_REFERENCE',identityScope:'MODEL',saleUnit:identity.saleUnit,moduleCount:identity.moduleCount,sourceDate:q.sourceDate,reviewedAt,confidence:'VERIFIED_MODEL',method:'DIRECT_MODEL_QUOTE',rangeLowKrw:null,rangeHighKrw:null,sourceQuotes:[q],notes:'동일 모델 참고가·정확 판매품목 미확정'});auditDirect.push({canonicalId:id,sourceFile,sourceField,originalObservationAt:price.observedAt,matchingScope:'MODEL_ONLY',exactSaleSkuConfirmed:false,notes:extraNote});anchors.push({identity,specification:specs.get(id),quote:q,anchorBasis:'SAME_MODEL_OBSERVED_REFERENCE_NOT_EXACT_RETAIL_SKU'});};
 const four=load('data/catalog-review/retail-four-preview-2026-10-10.json');for(const c of four.candidates){const p=c.existingCanonicalId==='688c711d-70d2-39f5-9642-d1c2a87d4bb7'?asusRetail.price:c.observation?.price;if(p)addDirect(c.existingCanonicalId,p,c.observation?.sourceName||'DANAWA','data/catalog-review/retail-four-preview-2026-10-10.json',`candidates[${four.candidates.indexOf(c)}].observation.price`,c.reason||'Same model, separate retail source; installed model reference is not a sold exact SKU.');}
 const modelQuotes=load('data/catalog-review/pilot-model-price-review-2026-10-10.json');const b850=modelQuotes.items.find(x=>x.selectionNumber===6);addDirect('656c192f-dfee-3cb1-893f-f0000cc8f53b',b850.price,'DANAWA','data/catalog-review/pilot-model-price-review-2026-10-10.json','items[selectionNumber=6].price',b850.holdReason);
 const launch=load(LAUNCH);for(const item of launch.items){const identity=identities.get(item.canonicalId)?.identity,p=item.referencePrice;if(!identity||p.currency!=='KRW')throw new Error('Invalid domestic launch source');const q={canonicalId:null,modelName:p.sourceDeclaredModel,amountKrw:p.amountKrw,sourceName:'LG_OFFICIAL',sourceUrl:p.sourceUrl,sourceDate:p.sourceDate,saleUnit:identity.saleUnit,moduleCount:identity.moduleCount};direct.set(identity.canonicalId,{amountKrw:p.amountKrw,basis:'LAUNCH_PRICE',identityScope:'MODEL',saleUnit:identity.saleUnit,moduleCount:identity.moduleCount,sourceDate:p.sourceDate,reviewedAt,confidence:'VERIFIED_MODEL',method:'OFFICIAL_MODEL_LAUNCH',rangeLowKrw:null,rangeHighKrw:null,sourceQuotes:[q],notes:'LG 국내 모델 출하가·지역 SKU 미확정'});auditDirect.push({canonicalId:identity.canonicalId,sourceFile:LAUNCH,sourceField:`items[canonicalId=${identity.canonicalId}].referencePrice`,matchingScope:'MODEL_ONLY',exactSaleSkuConfirmed:false,notes:item.mappingLimits});anchors.push({identity,specification:specs.get(identity.canonicalId),quote:q,anchorBasis:'MANUFACTURER_MODEL_DOMESTIC_LAUNCH_REFERENCE'});}
 const family=load(FAMILY);for(const item of family.observations){const facts={16761047:{ddr:'DDR5',speed:5200,gb:16},7013641:{ddr:'DDR4',speed:2666,gb:8},96773492:{ddr:'DDR5',speed:5600,gb:16},96773477:{ddr:'DDR5',speed:5600,gb:32}}[item.code];if(!facts||!item.price||item.httpStatus!==200)throw new Error('RAM family anchor evidence mismatch');const q={canonicalId:null,modelName:item.title,amountKrw:item.price.amountKrw,sourceName:'DANAWA',sourceUrl:item.sourceUrl,sourceDate:date(item.price.observedAt),saleUnit:'PRODUCT',moduleCount:1};anchors.push({identity:{type:'RAM',manufacturer:'Kingston',modelName:item.title,saleUnit:'PRODUCT',moduleCount:1},specification:{memoryType:facts.ddr,dataRateMts:facts.speed,moduleCapacityBytes:facts.gb*2**30,moduleCount:1,moduleFormFactor:'DIMM'},quote:q,anchorBasis:'DIFFERENT_RAM_FAMILY_ESTIMATION_ANCHOR_ONLY'});}
 const products=[];const calculations=[];
 for(const p of runtime.products.filter(x=>x.price===null)){const target={identity:p.identity,specification:specs.get(p.identity.canonicalId)};if(!target.specification)throw new Error(`Missing target specification ${p.identity.modelName}`);if(direct.has(p.identity.canonicalId)){products.push({identity:p.identity,referenceEstimate:direct.get(p.identity.canonicalId)});}else{const result=estimate(target,anchors,reviewedAt);products.push({identity:p.identity,referenceEstimate:result.referenceEstimate});calculations.push({canonicalId:p.identity.canonicalId,identity:p.identity,referenceEstimate:result.referenceEstimate,...result.calculation});}}
 if(products.length!==227||new Set(products.map(x=>x.identity.canonicalId)).size!==227)throw new Error('Reference preview must contain exactly the unpriced 227 identities');
 const version='references-v1-'+sha(JSON.stringify(products));const preview={schemaVersion:1,catalogVersion:runtime.catalogVersion,referenceVersion:version,generatedAt:reviewedAt,publicationApproved:false,products};
 const largest50=[...products].sort((a,b)=>Buffer.byteLength(JSON.stringify(b))-Buffer.byteLength(JSON.stringify(a))).slice(0,50);const largestBatchBytes=Buffer.byteLength(JSON.stringify({catalogVersion:preview.catalogVersion,referenceVersion:version,products:largest50}));if(largestBatchBytes>65000)throw new Error(`Reference response exceeds 65 KB: ${largestBatchBytes}`);
 const bases=Object.fromEntries([...new Set(products.map(x=>x.referenceEstimate.basis))].sort().map(k=>[k,products.filter(x=>x.referenceEstimate.basis===k).length]));
 const audit={schemaVersion:1,stage:'ALL227_REFERENCE_PRICE_PREVIEW_NOT_PUBLISHED',generatedAt:reviewedAt,publicationApproved:false,runtimeSource:RUNTIME,runtimeSourceSha256:normalizedInputSha256(fs.readFileSync(path.join(root,RUNTIME))),hashBasis:'SHA256_UTF8_CRLF_TO_LF',catalogVersion:runtime.catalogVersion,referenceVersion:version,summary:{allUnpricedProducts:227,existingApprovedActualPricesPreserved:81,referenceByBasis:bases,sourceQuotesMaximumPerProduct:Math.max(...products.map(x=>x.referenceEstimate.sourceQuotes.length)),largest50ResponseBytes:largestBatchBytes},interpretation:{historicalOrModelSourceDatesAreNotReset:true,currentPurchasePricesCreated:0,exactSkuEvidenceNotInferredFromReference:true,estimatesAreNotBenchmarksOrStatisticalConfidenceIntervals:true,rangeIsAHeuristicSensitivityRange:true,currencyConversions:0,shippingAndPromotionalFieldsCopied:0,unreviewedResearchQuotesCopied:0,buildcoresSeedSourceDatesUsedAsMarketDates:0},sourceInputs:sourceInputs.map(p=>({path:p,sha256:normalizedInputSha256(fs.readFileSync(path.join(root,p)))})),directModelAndLaunchEvidence:auditDirect,estimationPolicy:{sameProductCategoryRequired:true,ramDdrGenerationMustMatch:true,maximumNearestAnchors:2,anchorQuantityConversionIsEstimate:true,specificationMissingStopsGenerator:true,tieBreak:'distance then canonical ID or URL lexicographic',estimateDates:null,rounding:'Nearest 1000 below 1M; nearest 10000 at/above 1M',range:'Non-RAM 0.60×min .. 1.50×max adjusted anchor; RAM 0.50×min .. 1.75×max',accuracyValidated:false,limitations:['Observed retail prices can include scarce or unusually high offers; these anchors do not establish typical market prices.','No measured performance, depreciation model, local popularity or market-clearing prices are asserted.','Naming tier/generation, chipset class and capacity multipliers are explicit heuristic assumptions.','The four different Kingston RAM pages are only estimate anchors. Their full part number and sale packaging are not established for any target SKU.','Source dates belong to each anchor. A newly calculated target estimate has no target market-observation date.']},calculations};
 return {preview,audit};
}

function main(){
 const args=process.argv.slice(2);const check=args.includes('--check');const stampIndex=args.indexOf('--generated-at');const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
 if(args.some((x,i)=>x!=='--check'&&x!=='--generated-at'&&!(stampIndex>=0&&i===stampIndex+1)))throw new Error('Usage: node backend/tools/generate-reference-prices.mjs [--check] [--generated-at ISO]');
 const previous=fs.existsSync(path.join(root,PREVIEW))?read(path.join(root,PREVIEW)):null;const stamp=stampIndex>=0?args[stampIndex+1]:check?previous?.generatedAt:new Date().toISOString();const {preview,audit}=generate(root,stamp);
 const serialize=x=>JSON.stringify(x,null,2)+'\n';
 if(check){for(const [p,v]of [[PREVIEW,preview],[AUDIT,audit]])if(fs.readFileSync(path.join(root,p),'utf8').replaceAll('\r\n','\n')!==serialize(v))throw new Error(`Generated reference artifact differs: ${p}`);}else{fs.writeFileSync(path.join(root,PREVIEW),serialize(preview));fs.writeFileSync(path.join(root,AUDIT),serialize(audit));}
 console.log(JSON.stringify({check,referenceVersion:preview.referenceVersion,...audit.summary},null,2));
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main();
