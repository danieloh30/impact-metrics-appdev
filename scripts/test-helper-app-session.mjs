import assert from 'node:assert/strict';
import {mkdtemp,readFile,stat,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {appState,appSessionFile,loadAppSession,saveAppSession,forgetAppSession,connectAppSession} from './helper-app-session.mjs';
const directory=await mkdtemp(join(tmpdir(),'impact-app-session-test-')),origin='https://app.example.com';
const appCookie={domain:'app.example.com',name:'_oauth_proxy',value:'synthetic-route-session',path:'/',expires:Date.now()/1000+3600};
const mixed={cookies:[appCookie,{...appCookie,name:'_oauth_proxy_csrf',value:'synthetic-pending-flow'},{...appCookie,domain:'.linkedin.com',name:'li_at'},{...appCookie,domain:'ibm.sharepoint.com',name:'FedAuth'},{...appCookie,domain:'example.com'},{...appCookie,domain:'app.example.com.evil.test'},{...appCookie,name:'expired',expires:1}],origins:[{origin,localStorage:[]},{origin:'https://www.linkedin.com',localStorage:[]},{origin:'https://ibm.sharepoint.com',localStorage:[]}]};
try{
 assert.deepEqual(appState(mixed,origin),{cookies:[appCookie],origins:[mixed.origins[0]]});
 await saveAppSession(origin,mixed,directory);assert.deepEqual(await loadAppSession(origin,directory),appState(mixed,origin));
 const stored=await readFile(appSessionFile(origin,directory),'utf8');assert(!stored.includes('li_at'));assert(!stored.includes('FedAuth'));
 if(process.platform!=='win32'){assert.equal((await stat(directory)).mode&0o777,0o700);assert.equal((await stat(appSessionFile(origin,directory))).mode&0o777,0o600);}
 assert.equal(await loadAppSession('https://other.example.com',directory),undefined);assert.notEqual(appSessionFile(origin,directory),appSessionFile(origin+':444',directory));
 await forgetAppSession(origin,directory);
 let prompts=0,contexts=[],requests=[],active;
 const browser={newContext:async(options={})=>{
  const ctx={signed:options.storageState?.cookies.some(cookie=>cookie.value===appCookie.value)??false,request:{get:async(url,request)=>{
   requests.push({url,signed:ctx.signed,redirects:request.maxRedirects});
   assert.equal(request.maxRedirects,0,'Authentication checks must never follow OAuth redirects');
   if(url.endsWith('/oauth/auth'))return {status:()=>ctx.signed?202:401};
   assert(ctx.signed,'Must not request protected config while an OAuth login is pending');
   return {ok:()=>ctx.signed,json:async()=>({phases:[1,2,3]})};
  }},newPage:async()=>({goto:async()=>{}}),storageState:async()=>mixed};
  active=ctx;contexts.push(options);return ctx;
 }};
 const terminal={question:async()=>{prompts++;active.signed=true;return '';}};
 for(let i=0;i<3;i++)await connectAppSession(browser,origin,terminal,{directory,log:()=>{}});
 assert.equal(prompts,1,'Three provider helpers require only one valid Route sign-in');assert.equal(contexts.length,3);assert.equal(contexts[0].storageState,undefined);assert.deepEqual(contexts[1].storageState,appState(mixed,origin));
 // A revoked Route cookie is rejected by the server and must prompt again.
 const stale={newContext:async()=>{const ctx=await browser.newContext({});return ctx;}};
 await connectAppSession(stale,origin,terminal,{directory,log:()=>{}});assert.equal(prompts,2);
 await connectAppSession(browser,origin,terminal,{directory,forget:true,log:()=>{}});assert.equal(prompts,3,'Explicit forget starts without stored authentication');
 assert(requests.every(request=>request.redirects===0));assert(requests.filter(request=>request.url.endsWith('/api/config')).every(request=>request.signed),'No protected config request may start another OAuth flow while sign-in is pending');
 await forgetAppSession(origin,directory);
 const denied={newContext:async()=>({newPage:async()=>({goto:async()=>{}}),request:{get:async()=>({ok:()=>false})},storageState:async()=>{throw new Error('Must not save failed login');}})};
 await assert.rejects(connectAppSession(denied,origin,{question:async()=>''},{directory,log:()=>{}}),/sign-in is incomplete/);assert.equal(await loadAppSession(origin,directory),undefined);
 await writeFile(appSessionFile(origin,directory),'broken JSON');assert.equal(await loadAppSession(origin,directory),undefined);
 await writeFile(appSessionFile(origin,directory),JSON.stringify({origin:'https://other.example.com',state:mixed}));assert.equal(await loadAppSession(origin,directory),undefined);
 console.log('Shared helper app session: one Route login, per-origin isolation, provider exclusion, private files, server validation, expired/revoked recovery and explicit forget passed');
}finally{await rm(directory,{recursive:true,force:true});}
