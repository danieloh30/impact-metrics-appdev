#!/usr/bin/env node
import {createInterface} from 'node:readline/promises';
import {stdin,stdout} from 'node:process';
import {fileURLToPath} from 'node:url';
import {appOrigin,launchSignInBrowser} from './connect-browser-session.mjs';
import {pendingArticles,readArticle,batchFor} from './dzone-local-collection.mjs';

async function main(){
 const values={},args=process.argv.slice(2);
 for(let n=0;n<args.length;n+=2){if(!['--app','--run'].includes(args[n])||!args[n+1])throw new Error('Use: node scripts/collect-dzone-local.mjs --app HTTPS_ROUTE --run RUN_ID');values[args[n].slice(2)]=args[n+1];}
 const origin=appOrigin(values.app),id=values.run;
 if(!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(id??''))throw new Error('Use the saved run ID shown in the app helper command.');
 const terminal=createInterface({input:stdin,output:stdout});let browser;
 try{
  browser=await launchSignInBrowser();const appContext=await browser.newContext(),app=await appContext.newPage();
  await app.goto(origin,{waitUntil:'domcontentloaded'});await terminal.question('Sign in to the OpenShift app, then press Enter here: ');
  const config=await appContext.request.get(origin+'/api/config');
  if(!config.ok()||!(await config.json().catch(()=>({}))).dzoneLocalCollection)throw new Error('App needs the OpenShift local DZone feature enabled. Pull and redeploy the latest code before running this helper.');
  async function current(){const response=await appContext.request.get(origin+'/api/runs/'+id);if(!response.ok())throw new Error('App run could not be read. Check OpenShift access and the selected run.');return (await response.json()).run;}
  const initial=await current(),articles=pendingArticles(initial);
  if(!articles.length)throw new Error('App has no pending DZone collection in the current phase-1 or phase-4 review.');
  if(articles.length>100)throw new Error('DZone batch exceeds 100 unique articles.');
  const articleContext=await browser.newContext({acceptDownloads:false,serviceWorkers:'block'});
  // DZone gets its own context: neither provider cookies nor OpenShift cookies are exported.
  await articleContext.route('**/*',route=>{try{const url=new URL(route.request().url());const allowed=url.hostname==='dzone.com'||url.hostname.endsWith('.dzone.com')||url.hostname==='challenges.cloudflare.com';if(url.protocol!=='https:'||url.username||url.password||url.port||!allowed)return route.abort();return route.continue();}catch{return route.abort();}});
  const page=await articleContext.newPage(),measurements=new Map();
  for(const item of articles){const count=await readArticle(page,item.url);measurements.set(item.url,count);console.log(`${item.url}: ${count.value} views${count.approximate?' (approximate public display)':''}`);}
  console.log(`Collected ${measurements.size} whole-article totals for reporting month ${initial.workbook?.month??'this run'}, Phase ${initial.phase}. Existing attribution and baselines will be applied by the app.`);
  const answer=await terminal.question('Type yes to save these totals to the selected run, or anything else to cancel: ');
  if(answer.trim().toLowerCase()!=='yes'){console.log('Cancelled. No totals were saved.');return;}
  const latest=await current();if(latest.phase!==initial.phase)throw new Error('App phase changed during local collection. No totals were saved; return to the app.');
  const batch=batchFor(latest,measurements);
  const response=await appContext.request.post(origin+'/api/runs/'+id+'/dzone/measurements',{data:batch,headers:{Origin:origin},timeout:30000});
  if(response.status()!==202){if(response.status()===409)throw new Error('App review changed before saving. Refresh the run before collecting again.');throw new Error('App rejected the DZone totals. Check the run review and server logs.');}
  console.log('DZone totals saved. The app is recalculating the phase and will refresh automatically. Remaining data or historical reporting corrections still need review.');
 }finally{terminal.close();await browser?.close();}
}
if(process.argv[1]===fileURLToPath(import.meta.url))main().catch(error=>{console.error(error instanceof Error&&/^(DZone |App |Google Chrome|Use)/.test(error.message)?error.message:'DZone local collection could not finish. Check Chrome, OpenShift access and the active review.');process.exitCode=1;});
