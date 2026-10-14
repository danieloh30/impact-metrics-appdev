export function articleUrl(value){
 const url=new URL(value);
 if(/:\d+$/.test(String(value).match(/^https?:\/\/([^/?#]+)/i)?.[1]??''))throw new Error('DZone local collection supports default article hosts without explicit ports.');
 if(!['http:','https:'].includes(url.protocol)||url.username||url.password||url.port||!['dzone.com','www.dzone.com'].includes(url.hostname)||!/\/articles\/[A-Za-z0-9_-]+\/?$/.test(url.pathname)||!url.pathname.startsWith('/articles/'))throw new Error('DZone local collection supports public article URLs only.');
 url.protocol='https:';url.search='';url.hash='';return url.href.replace(/\/$/,'');
}
export function articleCounter(label){
 const match=label.replaceAll('\u00a0',' ').match(/^\s*((?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?)\s*([KMB]?)\s+Views\s*$/i);
 if(!match)throw new Error('DZone public Views counter is missing or invalid.');
 const raw=match[1].replaceAll(',',''),[whole,fraction='']=raw.split('.'),suffix=match[2].toUpperCase();
 const scale=10n**BigInt(fraction.length),base=BigInt(whole+fraction),factor={K:1000n,M:1000000n,B:1000000000n}[suffix]??1n;
 const scaled=base*factor;
 if(scaled%scale!==0n||scaled/scale>1000000000000000n)throw new Error('DZone Views counter is outside the supported whole-number range.');
 return {value:String(scaled/scale),label:label.trim(),approximate:!!suffix};
}
export function pendingArticles(run){
 if(run?.status!=='AWAITING_APPROVAL'||![1,4].includes(run.phase))return [];
 const unique=new Map();
 for(const issue of run.issues??[]){if(issue.blocking||issue.metricType!=='VIEWS')continue;try{const url=articleUrl(issue.url);if(!unique.has(url))unique.set(url,{rowId:issue.rowId,activity:issue.activity,url});}catch{}}
 return [...unique.values()];
}
export async function readArticle(page,value){
 const url=articleUrl(value);
 let response;
 try{response=await page.goto(url,{waitUntil:'domcontentloaded',timeout:30000});}catch{throw new Error('DZone article could not open locally. No totals were saved.');}
 if(!response||response.status()!==200)throw new Error(`DZone local browser was blocked or unavailable (HTTP ${response?.status()??'unknown'}). No totals were saved; use verified manual measurements in the app.`);
 try{
  const finalUrl=articleUrl(page.url());
  if(new URL(finalUrl).pathname!==new URL(url).pathname)throw new Error('Article redirected to a different resource');
  const counter=page.locator('#activity-view-container .view-count');
  await counter.first().waitFor({timeout:10000});
  if(await counter.count()!==1||await page.locator('h1').count()===0)throw new Error('Counter is not unique');
  let measured=articleCounter(await counter.innerText());const title=await counter.getAttribute('title');
  if(title)try{const exact=articleCounter(title);if(!exact.approximate)measured=exact;}catch{}
  return {...measured,collectedAt:new Date().toISOString()};
 }catch{throw new Error('DZone article did not expose one valid public Views counter. No totals were saved; use verified manual measurements in the app.');}
}
export function batchFor(run,measurements){
 const pending=pendingArticles(run);
 if(!pending.length)throw new Error('App has no pending DZone collection in the current phase-1 or phase-4 review.');
 if(pending.length>100)throw new Error('DZone local collection supports at most 100 unique articles per batch.');
 const values=pending.map(item=>{
  const count=measurements.get(item.url);if(!count)throw new Error('App review changed during local collection. No totals were saved; refresh and rerun the helper.');
  const evidence=`DZone article Views counter: ${count.label}${count.approximate?`; approximate abbreviated public display expanded to ${count.value}`:'; exact public count'}; collected locally in Chrome at ${count.collectedAt}; user confirmed whole-article total`;
  return {rowId:item.rowId,value:count.value,evidence};
 });
 return {version:run.version,confirmed:true,values};
}
