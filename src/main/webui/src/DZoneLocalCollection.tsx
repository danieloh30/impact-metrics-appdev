import {useEffect,useState} from 'react';
type Issue={rowId:string;activity:string;url:string;metricType:string;blocking:boolean};
type Run={id:string;status:string;phase:number;issues:Issue[]};
export function pendingDZoneUrls(run:Run|null){
 const urls=new Set<string>();
 if(run?.status==='AWAITING_APPROVAL'&&[1,4].includes(run.phase))for(const issue of run.issues){if(issue.blocking||issue.metricType!=='VIEWS')continue;try{const url=new URL(issue.url);if(['http:','https:'].includes(url.protocol)&&!url.username&&!url.password&&!url.port&&['dzone.com','www.dzone.com'].includes(url.hostname)&&/^\/articles\/[A-Za-z0-9_-]+\/?$/.test(url.pathname))urls.add(url.hostname+url.pathname.replace(/\/$/,''));}catch{}}
 return [...urls];
}
export function DZoneLocalCollection({run,busy,onEnabled}:{run:Run|null;busy:boolean;onEnabled:(enabled:boolean)=>void}){
 const [enabled,setEnabled]=useState(false),[show,setShow]=useState(false);
 useEffect(()=>{let alive=true;void fetch('/api/config').then(async response=>{if(response.ok&&alive){const enabled=(await response.json()).dzoneLocalCollection===true;setEnabled(enabled);onEnabled(enabled);}}).catch(()=>{});return()=>{alive=false;};},[onEnabled]);
 useEffect(()=>setShow(false),[run?.id,run?.phase]);
 if(!enabled)return null;
 const urls=pendingDZoneUrls(run);
 const quote=(value:string)=>"'"+value.replaceAll("'","'\\''")+"'";
 const command=run?`node scripts/collect-dzone-local.mjs --app ${quote(window.location.origin)} --run ${quote(run.id)}`:'';
 return <section id="dzone-local-collection" className="linkedin-section" aria-label="DZone local collection">
  <h3>DZone views from your computer</h3><p>OpenShift’s headless browser may receive HTTP 403. Read pending public article counters in local Chrome, then confirm the totals for this run. DZone sign-in and cookie transfer are not required.</p>
  <button id="collect-dzone-local" className={urls.length?'primary':'secondary'} disabled={busy||urls.length===0} onClick={()=>setShow(value=>!value)}>Collect DZone views locally{urls.length?` (${urls.length})`:''}</button>
  {!urls.length&&<p>No pending DZone view collection in the current phase review.</p>}
  {show&&run&&<aside className="local-signin-help" aria-label="Local DZone helper instructions"><h4>Run from your project checkout</h4><p>Install <a href="https://www.google.com/chrome/" target="_blank" rel="noopener noreferrer">Google Chrome desktop</a> and run <code>npm ci --prefix src/main/webui</code> once.</p><pre><code>{command}</code></pre><ol><li>Sign in to the OpenShift app in the helper’s Chrome window, then press Enter in its terminal.</li><li>The helper reads each pending article’s public Views counter. Review the displayed totals, then type <strong>yes</strong> to save them.</li><li>The app recalculates this phase and refreshes automatically. Whole-article totals use the existing author/tool splits and previous baselines.</li></ol><p>If DZone blocks the local browser or the counter is unavailable, no totals are saved. Use the existing measurement action in the app. Historical dates, negative deltas, and other data corrections still require their normal review.</p></aside>}
 </section>;
}
