import assert from 'node:assert/strict';
import {chromium} from '../src/main/webui/node_modules/@playwright/test/index.mjs';
import {articleUrl,articleCounter,pendingArticles,readArticle,batchFor} from './dzone-local-collection.mjs';
const url='https://dzone.com/articles/example';
assert.equal(articleUrl('http://dzone.com/articles/example?utm_source=synthetic#part'),url);
for(const value of ['https://dzone.com.evil.com/articles/example','https://other.dzone.com/articles/example','https://u:p@dzone.com/articles/example','https://dzone.com:443/articles/example','https://dzone.com/users/example','https://dzone.com/articles/../users/foo'])assert.throws(()=>articleUrl(value));
assert.equal(articleCounter('4.9K Views').value,'4900');assert(articleCounter('4.9K Views').approximate);assert.equal(articleCounter('4,963 Views').value,'4963');assert.equal(articleCounter('1.2m Views').value,'1200000');
for(const value of ['4.9 Views','-1 Views','Views','1e3 Views','0.0001K Views','1,23 Views'])assert.throws(()=>articleCounter(value));
const run={phase:4,status:'AWAITING_APPROVAL',version:3,issues:[{rowId:'t1',activity:'Article',url,blocking:false,metricType:'VIEWS'},{rowId:'t2',url,blocking:false,metricType:'VIEWS'},{rowId:'t3',url,blocking:true,metricType:'BASELINE'},{rowId:'t4',url:'https://example.com/foo',blocking:false,metricType:'VIEWS'}]};
assert.equal(pendingArticles(run).length,1);assert.equal(pendingArticles({...run,status:'COMPLETED'}).length,0);
function page(code=200,label='4.9K Views',title='4,963 Views',count=1){return {goto:async()=>({status:()=>code}),url:()=>url,locator:selector=>selector==='h1'?{count:async()=>1}:{first:()=>({waitFor:async()=>{}}),count:async()=>count,innerText:async()=>label,getAttribute:async()=>title}};}
const exact=await readArticle(page(),url);assert.equal(exact.value,'4963');assert.equal(exact.approximate,false);assert.match(exact.collectedAt,/T/);
const redirected=page();redirected.url=()=> 'https://dzone.com/articles/other';await assert.rejects(readArticle(redirected,url),/one valid public Views counter/);
await assert.rejects(readArticle(page(403),url),/HTTP 403.*No totals were saved/);await assert.rejects(readArticle(page(200,'bad',null),url),/one valid public Views counter/);await assert.rejects(readArticle(page(200,'4 Views',null,2),url),/one valid public Views counter/);
const batch=batchFor(run,new Map([[url,exact]]));assert.equal(batch.version,3);assert.equal(batch.values.length,1);assert.equal(batch.values[0].value,'4963');assert.equal(batch.confirmed,true);assert(!('attributed' in batch.values[0]));
const approximate=await readArticle(page(200,'4.9K Views',null),url);assert.match(batchFor(run,new Map([[url,approximate]])).values[0].evidence,/approximate.*4900/);
assert.throws(()=>batchFor(run,new Map()),/review changed/);
// Exercise actual DOM selection: the old mock cannot detect a publisher class change.
const chrome=await chromium.launch({channel:'chrome',headless:true});
try{
 const context=await chrome.newContext();const article=await context.newPage();
 const current='<h1>Article</h1><aside>999K Views</aside><div id="activity-view-container" class="activity-status activity-views"><svg aria-hidden="true"></svg><span>1.4K Views</span></div>';
 let html=current;
 await context.route('**/*',route=>route.fulfill({status:200,contentType:'text/html',body:html}));
 assert.equal((await readArticle(article,url)).value,'1400');
 html='<h1>Article</h1><div id="activity-view-container"><span class="view-count" title="1,432 Views">1.4K Views</span></div>';
 assert.equal((await readArticle(article,url)).value,'1432');
 html=current.replace('<span>1.4K Views</span>','<span>1.4K Views</span><span>2K Views</span>');
 await assert.rejects(readArticle(article,url),error=>error.message.includes(url)&&/No totals were saved.*closes Chrome.*sign-in is not required/.test(error.message));
 html=current.replace('1.4K Views','Likes (4)');
 await assert.rejects(readArticle(article,url),/one valid public Views counter/);
}finally{await chrome.close();}
console.log('DZone local collection: scoped URLs/counters, blocked pages, URL deduplication, raw totals, approximation and stale-review checks passed');
