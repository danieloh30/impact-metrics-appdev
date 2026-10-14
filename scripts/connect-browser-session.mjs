#!/usr/bin/env node
import {chromium} from '../src/main/webui/node_modules/@playwright/test/index.mjs';
import {createInterface} from 'node:readline/promises';
import {stdin,stdout} from 'node:process';
import {fileURLToPath} from 'node:url';
import {verifyLocalSharePointDownload} from './sharepoint-local-download.mjs';

export function appOrigin(value) {
 const url=new URL(value);
 if(url.username||url.password||url.search||url.hash||!['','/'].includes(url.pathname)||!(url.protocol==='https:'||(url.protocol==='http:'&&['localhost','127.0.0.1','[::1]'].includes(url.hostname))))throw new Error('Use the HTTPS app Route URL (or a local loopback app URL).');
 return url.origin;
}
export function providerState(state,provider) {
 const hosts=provider==='sharepoint'?['ibm.sharepoint.com','ibm-my.sharepoint.com']:provider==='linkedin'?['linkedin.com','www.linkedin.com']:[];
 if(!hosts.length)throw new Error('Provider must be sharepoint or linkedin.');
 const allowed=domain=>hosts.includes(domain.replace(/^\./,''));
 return {cookies:state.cookies.filter(cookie=>allowed(cookie.domain)||(provider==='sharepoint'&&cookie.domain.replace(/^\./,'')==='sharepoint.com'&&cookie.name==='rtFa'&&cookie.path==='/')),origins:state.origins.filter(origin=>{try{return hosts.includes(new URL(origin.origin).hostname);}catch{return false;}})};
}
export function signInUrl(provider,value) {
 if(provider==='linkedin')return 'https://www.linkedin.com/login';
 const url=new URL(value);
 if(provider!=='sharepoint'||url.protocol!=='https:'||!['ibm.sharepoint.com','ibm-my.sharepoint.com'].includes(url.hostname)||url.username||url.password||(url.port&&url.port!=='443')||!url.pathname.toLowerCase().endsWith('.xlsx'))throw new Error('Use your IBM SharePoint .xlsx workbook link.');
 return url.href;
}

export async function launchSignInBrowser(browserType=chromium) {
 try{return await browserType.launch({channel:'chrome',headless:false});}
 catch{throw new Error('Google Chrome could not start. Install the Google Chrome desktop app from https://www.google.com/chrome/. On macOS, move it to Applications; on Windows, run the downloaded installer. If already installed, open Chrome once and retry this helper. Installation guide: https://support.google.com/chrome/answer/95346');}
}
export function helperErrorMessage(error) {
 return error instanceof Error&&/^(Use |Provider |Usage:|App |The app |Provider sign-in|Server verification|The server|Google Chrome|SharePoint )/.test(error.message)?error.message:'Sign-in helper could not finish. Check Chrome, app access and provider sign-in, then retry.';
}
export function requireSharePointTransferSupport(session) {
 if(session.loginMode!=='LOCAL_HELPER')throw new Error('App uses local browser sign-in. Use Connect SharePoint in the app instead of this helper.');
 if(!(Number(session.transferVersion)>=2))throw new Error('App needs the updated SharePoint session support. Pull and redeploy the latest code before using this helper; no IBM session has been sent.');
}

async function main() {
 const args=process.argv.slice(2),values={};
 for(let index=0;index<args.length;index+=2){if(!['--app','--provider','--url'].includes(args[index])||!args[index+1])throw new Error('Usage: node scripts/connect-browser-session.mjs --app HTTPS_ROUTE --provider sharepoint|linkedin [--url WORKBOOK_URL]');values[args[index].slice(2)]=args[index+1];}
 const origin=appOrigin(values.app),provider=values.provider,target=signInUrl(provider,values.url);
 const terminal=createInterface({input:stdin,output:stdout});let browser;
 try {
  browser=await launchSignInBrowser();
  const context=await browser.newContext();const app=await context.newPage();
  await app.goto(origin,{waitUntil:'domcontentloaded'});
  console.log('Sign in to the Impact Hours app in the opened browser (OpenShift sign-in).');
  await terminal.question('When the app is visible, press Enter here: ');
  const config=await context.request.get(origin+'/api/config');
  if(!config.ok()||!(await config.json().catch(()=>({}))).phases)throw new Error('App sign-in is incomplete. Sign in to this Route and run the helper again.');
  if(provider==='sharepoint'){
   const support=await context.request.get(origin+'/api/sharepoint/session');
   if(!support.ok())throw new Error('App SharePoint connection status could not be read. Check app access before retrying.');
   requireSharePointTransferSupport(await support.json());
  }
  const providerPage=await context.newPage();
  // Direct Excel links can start downloads after IBM sign-in; cookies still persist.
  await providerPage.goto(target,{waitUntil:'domcontentloaded',timeout:60_000}).catch(()=>{});
  console.log(provider==='sharepoint'?'Complete IBM SSO/MFA in the new tab and wait until the workbook opens or its Excel download starts.':'Complete LinkedIn sign-in in the new tab.');
  await terminal.question(provider==='sharepoint'?'When the workbook is available, press Enter to verify and connect this session: ':'When signed in, press Enter to connect this session to your app: ');
  let state=providerState(await context.storageState({indexedDB:true}),provider);
  const required=provider==='sharepoint'?'FedAuth':'li_at';
  if(!state.cookies.some(cookie=>cookie.name===required&&cookie.value))throw new Error('Provider sign-in is incomplete. Run the helper again after completing sign-in.');
  if(provider==='sharepoint'){
   console.log('Checking the transfer-ready SharePoint session on your computer…');
   const local=await browser.newContext({storageState:state});
   try{await verifyLocalSharePointDownload(local,values.url);state=providerState(await local.storageState({indexedDB:true}),provider);}
   finally{await local.close();}
   console.log('Workbook download verified locally. Sending that session to the pod…');
  }
  const response=await context.request.post(origin+'/api/browser-sessions/'+provider,{data:{url:values.url,state},headers:{Origin:origin},timeout:30_000});
  if(response.status()!==202)throw new Error('The app rejected the session transfer. Check app sign-in and retry the helper.');
  console.log('Session sent over HTTPS. Waiting for the pod to verify access…');
  const deadline=Date.now()+150_000;
  while(Date.now()<deadline) {
   await new Promise(resolve=>setTimeout(resolve,1000));
   const check=await context.request.get(origin+`/api/${provider}/session`);
   if(!check.ok())throw new Error('App access expired during verification. Run the helper again.');
   const session=await check.json();
   if(session.busy||session.connecting||session.status==='CONNECTING')continue;
   if(provider==='sharepoint'?session.status!=='CONNECTED':!session.message.startsWith('Connected.'))throw new Error((session.message||'The server could not verify sign-in.')+(provider==='sharepoint'?' The same transfer-ready session downloaded the workbook on your computer; the pod check failed. Keep this distinction when investigating cluster access.':''));
   console.log('Connected and verified. Return to the app and check the connection.');return;
  }
  throw new Error('Server verification timed out. Check the app connection status before retrying.');
 } finally {terminal.close();await browser?.close();}
}
if(process.argv[1]===fileURLToPath(import.meta.url))main().catch(error=>{console.error(helperErrorMessage(error));process.exitCode=1;});
