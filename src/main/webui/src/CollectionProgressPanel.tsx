import {useEffect,useState} from 'react';

export type CollectionProgress={phase:number;total:number;completed:number;collected:number;cached:number;failed:number;state:string;resource:string;activity:string;provider:string;detail:string;lastError:string;startedAt:string;finishedAt:string;dzoneIncluded:boolean;backgroundDzone:boolean};
export function CollectionProgressPanel({progress,active}:{progress:CollectionProgress;active:boolean}){
 const [now,setNow]=useState(Date.now());
 useEffect(()=>{setNow(Date.now());if(!active||progress.state!=='CHECKING')return;const timer=window.setInterval(()=>setNow(Date.now()),1000);return()=>window.clearInterval(timer);},[active,progress.state,progress.startedAt]);
 const end=progress.finishedAt?Date.parse(progress.finishedAt):now;
 const elapsed=Math.max(0,Math.floor((end-Date.parse(progress.startedAt))/1000))||0;
 const checking=active&&progress.state==='CHECKING';
 return <section id="phase-processing" className="collection-progress" aria-label="Resource collection progress">
  <div className="collection-progress-heading"><div><div className="eyebrow">LIVE COLLECTION · PHASE {progress.phase}</div><h2>{checking?'Collecting resource metrics':progress.state==='COMPLETE'?'Collection checks finished':'Collection interrupted'}</h2></div><span className="collection-elapsed">{elapsed}s elapsed</span></div>
  <div className="collection-count" role="status" aria-live="polite">{checking&&<span className="spinner"/>}{progress.completed} of {progress.total} resource checks completed</div>
  <progress aria-label="Resource checks completed" value={progress.completed} max={progress.total}/>
  <div className="collection-stats"><div><strong>{progress.collected}</strong><span>Fresh measurements</span></div><div><strong>{progress.cached}</strong><span>Saved measurements reused</span></div><div className={progress.failed?'collection-needs-review':''}><strong>{progress.failed}</strong><span>Need review</span></div></div>
  {progress.activity&&<p className="collection-current"><strong>{progress.provider}: {progress.activity}</strong><span>{progress.resource}</span></p>}
  <p className="collection-detail">{progress.detail}</p>
  {progress.dzoneIncluded&&<p className="collection-mode">{progress.backgroundDzone?'DZone checks run in the background. Regular Chrome stays minimized; headless mode is optional.':'Visible DZone collection is configured. One browser window is reused for this phase.'}</p>}
  {progress.lastError&&<p className="collection-warning"><strong>Collection needs attention.</strong> {progress.lastError}</p>}
  {progress.state==='COMPLETE'&&progress.failed>0&&<p className="collection-mode">Checks completed includes failed checks. Review the collection items below; unavailable values were not invented.</p>}
 </section>;
}
