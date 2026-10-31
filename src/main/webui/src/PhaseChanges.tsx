import {useEffect,useState} from 'react';

type Result={rows:number;phase:number};
type Approval={phase:number;version:number};
type Props={id:string;phase:number;version:number;currentRows:number;issues:number;artifacts:Record<string,Result>;approvals:Approval[]};
const currentNames=['digital.csv','in-person.csv','follow-up.csv'];
const labels:Record<string,string>={'digital.csv':'Digital rows','in-person.csv':'In-person rows','follow-up.csv':'Follow-up rows','updated_90-day.csv':'Historical rows'};

/** Compare with the previous approved phase, not an arbitrary previous run version. */
export function PhaseChanges({id,phase,version,currentRows,issues,artifacts,approvals}:Props){
 const priorVersion=approvals.find(a=>a.phase===phase-1)?.version;
 const key=`${id}:${phase}:${version}:${priorVersion}`;
 const [prior,setPrior]=useState<{key:string;counts:Record<string,number>;unavailable:boolean}|null>(null);
 useEffect(()=>{
  if(![2,3].includes(phase)||priorVersion===undefined)return;
  const controller=new AbortController();
  const names=phase===2?['digital.csv','follow-up.csv']:currentNames;
  Promise.all(names.map(async name=>{
   const response=await fetch(`/api/runs/${id}/artifacts/${encodeURIComponent(name)}/preview?version=${priorVersion}&offset=0&limit=1`,{signal:controller.signal});
   if(!response.ok)throw new Error('Earlier results unavailable');
   const body=await response.json();if(!Number.isInteger(body.total)||body.total<0)throw new Error('Earlier row count unavailable');
   return [name,body.total] as const;
  })).then(entries=>{if(!controller.signal.aborted)setPrior({key,counts:Object.fromEntries(entries),unavailable:false});})
   .catch(()=>{if(!controller.signal.aborted)setPrior({key,counts:{},unavailable:true});});
  return()=>controller.abort();
 },[id,phase,version,priorVersion]);
 const baseline=prior?.key===key&&!prior.unavailable?prior.counts:null;
 const unavailable=prior?.key===key&&prior.unavailable;
 const names=phase===1?['digital.csv','follow-up.csv']:phase===4?['updated_90-day.csv']:currentNames;
 const description=phase===1
  ?`Digital activities were classified and their available measurements prepared from ${currentRows} imported activities. Resolved digital rows and collection follow-up are now separated; in-person and other activities are handled in later phases.`
  :phase===2
   ?'In-person activities were added and attendance hours calculated using the one-hour minimum. Digital results were carried forward unchanged. Booth duty uses attendance × actual hours automatically when both are positive; zero, missing, or invalid inputs remain in follow-up.'
   :phase===3
    ?'Authors and tools were expanded into every author × tool combination. Digital views and in-person attendance hours were allocated across those rows while conserving activity totals. Follow-up rows were split without recalculating their metrics.'
    :'Historical measurement review was added separately. Recorded baselines are preserved; available fresh totals produce trailing deltas, while explicitly retained or edited inputs are labeled separately. Current-month results are unchanged.';
 return <aside className="phase-changes" aria-label="Changes from the previous phase">
  <div className="phase-changes-heading"><h3>{phase===1?'What changed since import':`What changed from Phase ${phase-1}`}</h3><span className={`phase-changes-status ${issues===0?'is-clear':'is-attention'}`} role="status">
   {issues===0&&<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><circle cx="12" cy="12" r="9"/><path d="m8 12 3 3 5-6"/></svg>}
   <span>{issues===0?'No manual updates flagged':'Review issues below'}</span>
  </span></div>
  {priorVersion!==undefined&&<p className="phase-change-baseline">Compared with approved Phase {phase-1} · version {priorVersion}</p>}
  <p>{description}</p>
  <dl className="phase-change-counts">{names.filter(name=>artifacts[name]).map(name=>{
   const before=phase===2&&name==='in-person.csv'?0:baseline?.[name];
   return <div key={name}><dt>{labels[name]}</dt><dd>{before===undefined?`${artifacts[name].rows} rows`:`${before} → ${artifacts[name].rows} rows`}</dd></div>;
  })}</dl>
  {unavailable&&<p>Earlier row counts are unavailable; review the current results below.</p>}
  <p className="phase-changes-confirm">{issues===0?'No manual corrections are currently flagged. Confirm these changes before approving; zero issues does not verify the accuracy of source data.':'Confirm these changes and resolve blocking issues or acknowledge collection follow-up before approving.'}</p>
 </aside>;
}
