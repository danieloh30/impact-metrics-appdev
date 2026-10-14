import {mkdir,chmod,readFile,writeFile,rename,unlink,lstat} from 'node:fs/promises';
import {homedir} from 'node:os';
import {join} from 'node:path';
import {createHash,randomUUID} from 'node:crypto';

export const appSessionDirectory=join(homedir(),'.impact-hours','helper-app-sessions');
export function appState(state,origin){
 const host=new URL(origin).hostname,now=Date.now()/1000;
 return {cookies:(state.cookies??[]).filter(cookie=>cookie.domain?.replace(/^\./,'')===host&&(cookie.expires===undefined||cookie.expires===-1||cookie.expires>now)),origins:(state.origins??[]).filter(entry=>entry.origin===origin)};
}
export function appSessionFile(origin,directory=appSessionDirectory){return join(directory,createHash('sha256').update(origin).digest('hex')+'.json');}
async function privateDirectory(directory){
 await mkdir(directory,{recursive:true,mode:0o700});
 const info=await lstat(directory);if(!info.isDirectory()||info.isSymbolicLink())throw new Error('App helper session directory must be a private local directory.');
 await chmod(directory,0o700);
}
export async function loadAppSession(origin,directory=appSessionDirectory){
 const file=appSessionFile(origin,directory);
 try{
  const info=await lstat(file);if(!info.isFile()||info.isSymbolicLink()||info.size>2_000_000)return undefined;
  await chmod(file,0o600);
  const saved=JSON.parse(await readFile(file,'utf8'));if(saved.origin!==origin)return undefined;
  return appState(saved.state,origin);
 }catch(error){if(error.code==='ENOENT'||error instanceof SyntaxError||error instanceof TypeError)return undefined;throw new Error('App helper session could not be read. Check access to your local helper session directory.');}
}
export async function saveAppSession(origin,state,directory=appSessionDirectory){
 await privateDirectory(directory);const file=appSessionFile(origin,directory),temporary=file+'.'+randomUUID()+'.tmp';
 try{await writeFile(temporary,JSON.stringify({origin,state:appState(state,origin)}),{mode:0o600,flag:'wx'});await rename(temporary,file);}
 finally{await unlink(temporary).catch(error=>{if(error.code!=='ENOENT')throw error;});}
}
export async function forgetAppSession(origin,directory=appSessionDirectory){await unlink(appSessionFile(origin,directory)).catch(error=>{if(error.code!=='ENOENT')throw error;});}
async function verified(context,origin){
 try{const response=await context.request.get(origin+'/api/config',{timeout:15000});return response.ok()&&Array.isArray((await response.json()).phases);}catch{return false;}
}
/** Keep Route authentication on this computer; provider contexts remain separate and temporary. */
export async function connectAppSession(browser,origin,terminal,{directory=appSessionDirectory,forget=false,log=console.log}={}){
 if(forget)await forgetAppSession(origin,directory);
 const saved=await loadAppSession(origin,directory),context=await browser.newContext(saved?{storageState:saved}:{});
 const app=await context.newPage();await app.goto(origin,{waitUntil:'domcontentloaded'});
 if(!await verified(context,origin)){
  log('Sign in to the Impact Hours app in the opened browser (OpenShift sign-in).');
  await terminal.question('When the app is visible, press Enter here: ');
  if(!await verified(context,origin))throw new Error('App sign-in is incomplete. Sign in to this Route and run the helper again.');
 }else log(saved?'Reusing your saved OpenShift app sign-in.':'OpenShift app access verified.');
 await saveAppSession(origin,await context.storageState({indexedDB:true}),directory);
 return {context,app};
}
