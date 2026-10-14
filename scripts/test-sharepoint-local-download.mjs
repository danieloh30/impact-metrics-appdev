import assert from 'node:assert/strict';
import {sharePointDownloadUrls,verifyLocalSharePointDownload} from './sharepoint-local-download.mjs';
const link='https://ibm.sharepoint.com/:x:/r/sites/Test/Shared%20Documents/Avocado%20-%C2%A0Bob%27s.xlsx?d=synthetic&web=1';
const urls=sharePointDownloadUrls(link),zip=Buffer.from([80,75,3,4,0]);
assert(urls.rest.includes("Avocado%20-%C2%A0Bob''s.xlsx"));assert(!urls.direct.includes('synthetic'));assert.equal(new URL(urls.layout).searchParams.get('SourceUrl'),urls.direct.replace('?download=1',''));
for(const bad of ['https://evil.com/sites/Test/book.xlsx','https://ibm.sharepoint.com.evil.com/sites/Test/book.xlsx','http://ibm.sharepoint.com/sites/Test/book.xlsx','https://user:pass@ibm.sharepoint.com/sites/Test/book.xlsx','https://ibm.sharepoint.com:444/sites/Test/book.xlsx','https://ibm.sharepoint.com/invalid/book.xlsx','https://ibm.sharepoint.com/sites/Test/book.txt'])assert.throws(()=>sharePointDownloadUrls(bad));
function mock(handler){
 const calls=[],pages=[];let disposed=0,warmed=false;
 return {calls,pages,get disposed(){return disposed;},context:{request:{get:async(url,options)=>{
  calls.push(url);assert.equal(options.failOnStatusCode,false);const result=handler(url,warmed);
  return {status:()=>result.code,ok:()=>result.code===200,body:async()=>result.body??zip,url:()=>result.url??url,dispose:async()=>{disposed++;}};
 }},newPage:async()=>{pages.push({closed:false});const page=pages.at(-1);return {goto:async value=>{assert.equal(value,link);warmed=true;throw new Error('Synthetic download navigation abort');},close:async()=>{page.closed=true;}};}}};
}
const direct=mock(()=>({code:200}));await verifyLocalSharePointDownload(direct.context,link);assert.deepEqual(direct.calls,[urls.rest]);assert.equal(direct.disposed,1);assert.equal(direct.pages.length,0);
const fallback=mock(url=>({code:url===urls.rest?401:200}));await verifyLocalSharePointDownload(fallback.context,link);assert.deepEqual(fallback.calls,[urls.rest,urls.layout]);assert.equal(fallback.disposed,2);
const refresh=mock((_url,warmed)=>({code:warmed?200:403}));await verifyLocalSharePointDownload(refresh.context,link);assert.deepEqual(refresh.calls,[urls.rest,urls.layout,urls.rest]);assert(refresh.pages[0].closed);assert.equal(refresh.disposed,3);
const final=mock(url=>({code:200,body:url===urls.direct?zip:Buffer.from('<html>Viewer</html>')}));await verifyLocalSharePointDownload(final.context,link);assert.equal(final.calls.at(-1),urls.direct);assert(final.pages[0].closed);assert.equal(final.disposed,5);
const denied=mock(()=>({code:401}));await assert.rejects(verifyLocalSharePointDownload(denied.context,link),/local workbook download failed \(HTTP 401\)/);assert.equal(denied.calls.length,5);assert(denied.pages[0].closed);assert.equal(denied.disposed,5);
const unavailable=mock(()=>({code:503}));await assert.rejects(verifyLocalSharePointDownload(unavailable.context,link),/HTTP 503/);assert.equal(unavailable.calls.length,1);assert.equal(unavailable.pages.length,0);
const redirected=mock(()=>({code:200,body:Buffer.from('<html>Sign in</html>'),url:'https://login.microsoftonline.com/'}));await assert.rejects(verifyLocalSharePointDownload(redirected.context,link),/redirected.*sign-in/);
console.log('SharePoint local download: URL restrictions, binary validation, download fallback, access refresh and safe failure reasons passed');
