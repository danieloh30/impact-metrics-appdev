import {useEffect,useState} from 'react';

const stages=['Prepare connection','Download workbook','Check file','Read sheets'];
const states:Record<string,{step:number;title:string}>={
 OPENING:{step:0,title:'Preparing your SharePoint connection'},
 REFRESHING:{step:0,title:'Refreshing access through the workbook link'},
 DOWNLOADING:{step:1,title:'Downloading your workbook from SharePoint'},
 VALIDATING:{step:2,title:'Checking the downloaded Excel file'},
 INSPECTING:{step:3,title:'Reading activity sheets'},
 DONE:{step:3,title:'Preparing your sheet choices'}
};

export function WorkbookProgress({stage,sharepoint}:{stage?:string;sharepoint:boolean}){
 const [elapsed,setElapsed]=useState(0);
 useEffect(()=>{const start=performance.now();const timer=window.setInterval(()=>setElapsed(Math.floor((performance.now()-start)/1000)),1000);return()=>clearInterval(timer);},[]);
 const current=sharepoint?(states[stage||'OPENING']||states.OPENING):{step:3,title:'Reading your Excel workbook'};
 return <div className="workbook-progress" aria-busy="true" data-stage={stage||'OPENING'}>
  <div className="workbook-progress-heading"><div role="status" aria-live="polite"><span className="workbook-progress-dot" aria-hidden="true"/><strong>{current.title}</strong></div><span className="workbook-progress-time" aria-hidden="true">{elapsed}s elapsed</span></div>
  <div className="workbook-progress-track" role="progressbar" aria-label={sharepoint?'SharePoint workbook download progress':'Excel workbook reading progress'} aria-valuetext={current.title}><span/></div>
  {sharepoint&&<ol className="workbook-progress-stages">{stages.map((title,i)=><li key={title} className={i<current.step?'complete':i===current.step?'active':''} aria-current={i===current.step?'step':undefined}><span aria-hidden="true">{i<current.step?'✓':i+1}</span>{title}</li>)}</ol>}
  <p>{sharepoint?(elapsed>=30?'SharePoint is still responding. Keep this page open while we finish.':'Keep this page open. Sheet choices will appear when the workbook is ready.'):'Checking sheet headers and activity dates. Sheet choices will appear shortly.'}</p>
 </div>;
}
