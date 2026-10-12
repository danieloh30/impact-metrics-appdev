import {CollectionProgressPanel,type CollectionProgress} from './CollectionProgressPanel';
import {MonthlyReport} from './MonthlyReport';
import {PreviewTable} from './PreviewTable';
import {PhaseChanges} from './PhaseChanges';
import {AttendanceReview} from './AttendanceReview';
import React, {useEffect,useRef,useState} from 'react';
import {createRoot} from 'react-dom/client';
import '@fontsource/ibm-plex-sans/400.css';
import '@fontsource/ibm-plex-sans/500.css';
import '@fontsource/ibm-plex-sans/600.css';
import './style.css';
import {WorkbookImport} from './WorkbookImport';
import {NextAction,importStart,type NextStep} from './NextAction';

type Artifact={name:string;version:number;phase:number;rows:number;sha256:string};
type TrailingInput={originalViews:string;originalDuration:string;baseline:string;views:string;duration:string;value?:string;canRetain:boolean;hasDuration?:boolean;attributed?:boolean;source?:{workbook:string;sheet:string;rowId:string;author:string;tool:string}};
type Issue={rowId:string;activity:string;url:string;reason:string;blocking:boolean;metricType:string};
type Run={reportingRevision?:number;collectionProgress?:CollectionProgress|null;id:string;createdAt:string;status:string;phase:number;version:number;currentRows:number;trailingRows:number;trailing:boolean;review:string;error:string;instructionHash:string;issues:Issue[];artifacts:Record<string,Artifact>;events:{sequence:number;at:string;agent:string;message:string}[];approvals:{phase:number;version:number}[];workbook?:{reportingPolicy?:string;month:string;currentSheet:string;historySheets:string[];windowStart:string;windowEnd:string;missingBaselines:number;filename?:string;historyFilename?:string;blogRows?:Record<string,number>;blogSkippedRows?:Record<string,number>}};
const phases=['Digital collection','In-person attendance','Author / tool attribution','Trailing 90 days','Other activities','Combine exports','Current totals'];
const names=['digital.csv','in-person.csv','follow-up.csv','updated_90-day.csv','current.csv'];
class ApiError extends Error{constructor(message:string,readonly status:number){super(message);}}
async function api<T>(path:string,options?:RequestInit):Promise<T>{const response=await fetch('/api'+path,options);if(!response.ok){let message;try{const body=await response.json();message=body.error||body.title||JSON.stringify(body);}catch{message=await response.text();}throw new ApiError(message||`Request failed (${response.status})`,response.status);}return response.json();}
const post=(body:unknown={}):RequestInit=>({method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
const statusLabel=(status:string)=>({READY:'Ready to start',RUNNING_PHASE:'Processing',AWAITING_APPROVAL:'Review required',COMPLETED:'Complete',FAILED_RETRYABLE:'Retry available',CANCELLED:'Cancelled',CANCELLING:'Cancelling'}[status]||status);

type IconName='chart'|'upload'|'file'|'layers'|'check'|'arrow'|'shield';
function Icon({name,className=''}:{name:IconName;className?:string}){
 const paths:Record<IconName,React.ReactNode>={
  chart:<><path d="M4 19h16M7 15v-4m5 4V5m5 10V8"/></>,
  upload:<><path d="M12 16V4m-4 4 4-4 4 4M4 16v4h16v-4"/></>,
  file:<><path d="M14 3H5v18h14V8l-5-5Z M14 3v5h5M8 12h8m-8 4h6"/></>,
  layers:<><path d="m12 3 10 5-10 5L2 8l10-5Zm-9 9 9 5 9-5m-18 5 9 5 9-5"/></>,
  check:<><path d="m5 12 4 4L19 6"/><circle cx="12" cy="12" r="10"/></>,
  arrow:<><path d="M4 12h16m-6-6 6 6-6 6"/></>,
  shield:<><path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3Z M8 12l3 3 5-6"/></>
 };
 return <svg className={`icon ${className}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name]}</svg>;
}

function ResetHistoryDialog({busy,error,onClose,onReset}:{busy:boolean;error:string;onClose:()=>void;onReset:()=>void}){
 const dialog=useRef<HTMLDialogElement>(null);
 useEffect(()=>{const element=dialog.current!;element.showModal();return()=>element.close();},[]);
 return <dialog ref={dialog} className="modal reset-dialog" aria-labelledby="reset-title" onCancel={e=>{e.preventDefault();if(!busy)onClose();}}>
  <div className="eyebrow">WORKSPACE HISTORY</div><h2 id="reset-title">Reset run history?</h2>
  <p>This permanently deletes every saved run, including its uploaded copies, measurements, approvals, and saved phase results.</p>
  <p>It also removes the obsolete input files <code>AvocadoBot 2.csv</code> and <code>trailing_90-day.csv</code> from the workspace.</p>
  <p className="reset-preserved"><Icon name="shield"/>Your workflow instructions and saved SharePoint/LinkedIn sessions will be kept.</p>
  <p>Download any results you want to keep before continuing.</p>
  {error&&<p role="alert" className="error">{error}</p>}
  <div className="modal-actions"><button autoFocus disabled={busy} onClick={onClose}>Keep history</button><button className="danger" disabled={busy} onClick={onReset}>{busy?'Resetting…':'Delete all run history'}</button></div>
 </dialog>;
}

function App(){
 const [run,setRun]=useState<Run|null>(null),[digest,setDigest]=useState(''),[history,setHistory]=useState<Run[]>([]);
 const [busy,setBusy]=useState(false),[error,setError]=useState('');
 const retryAttempt=useRef<{id:string;phase:number;version:number}|null>(null);
 const [accepted,setAccepted]=useState(false),[acknowledge,setAcknowledge]=useState(false),[selected,setSelected]=useState('');
 const [sourceInput,setSourceInput]=useState<TrailingInput|null>(null),[inputMode,setInputMode]=useState(false),[duration,setDuration]=useState('');
 const [manual,setManual]=useState<Issue|null>(null),[value,setValue]=useState(''),[evidence,setEvidence]=useState('');
 const [connection,setConnection]=useState('');
 const [resetOpen,setResetOpen]=useState(false),[notice,setNotice]=useState('');
 const [importing,setImporting]=useState(false),[importStep,setImportStep]=useState<NextStep>(importStart),[reportStep,setReportStep]=useState<NextStep|null>(null);
 const selectedRun=useRef<string|null>(null),historyGeneration=useRef(0),phasePreviewContext=useRef<string|null>(null);
async function openManual(issue:Issue){setManual(issue);setSourceInput(null);setInputMode(false);setValue('');setEvidence('');if(!run)return;await perform(async()=>{const retention=run.phase===4&&!issue.url&&!issue.blocking&&issue.metricType!=='BASELINE';const path=retention?`/runs/${run.id}/trailing-input?rowId=${encodeURIComponent(issue.rowId)}`:`/runs/${run.id}/measurement-input?rowId=${encodeURIComponent(issue.rowId)}&type=${encodeURIComponent(issue.metricType)}`;const input=await api<TrailingInput>(path);setSourceInput(input);setInputMode(retention);setValue(retention?input.views:input.value||'');setDuration(input.duration);});}
function resourceHref(value:string){try{const url=new URL(value.trim());return ['http:','https:'].includes(url.protocol)&&!url.username&&!url.password?url.href:undefined;}catch{return undefined;}}
async function toggleHistoricalMode(){if(!run||!manual)return;await perform(async()=>{const next=!inputMode;const path=next?`/runs/${run.id}/trailing-input?rowId=${encodeURIComponent(manual.rowId)}`:`/runs/${run.id}/measurement-input?rowId=${encodeURIComponent(manual.rowId)}&type=${encodeURIComponent(manual.metricType)}`;const input=await api<TrailingInput>(path);setSourceInput(input);setInputMode(next);setValue(next?input.views:input.value||'');setDuration(input.duration);setEvidence('');});}
async function saveInput(keepOriginal=false){if(!run||!manual)return;await perform(async()=>{await api(`/runs/${run.id}/trailing-inputs`,post({version:run.version,rowId:manual.rowId,views:value,duration,keepOriginal,note:evidence}));setManual(null);await refresh(run.id);});}
function clearRunHistory(message:string){setReportStep(null);retryAttempt.current=null;phasePreviewContext.current=null;historyGeneration.current++;selectedRun.current=null;localStorage.removeItem('impact-run');setRun(null);setHistory([]);setDigest('');setSelected('');setManual(null);setAccepted(false);setAcknowledge(false);setResetOpen(false);setError('');setNotice(message);}
async function refresh(id:string){try{const data=await api<{run:Run;approvalDigest:string}>(`/runs/${id}`);if(selectedRun.current!==id)return data.run;setRun(data.run);setDigest(data.approvalDigest);setHistory(h=>h.map(r=>r.id===id?data.run:r));return data.run;}catch(e){if(e instanceof ApiError&&e.status===404&&selectedRun.current===id){clearRunHistory('Saved run history was reset. Read both SharePoint workbooks to start fresh.');return null;}throw e;}}
 async function perform(action:()=>Promise<unknown>){setBusy(true);setError('');try{await action();}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
 async function choose(created:Run){setReportStep(null);retryAttempt.current=null;setImporting(false);phasePreviewContext.current=null;selectedRun.current=created.id;setNotice('');localStorage.setItem('impact-run',created.id);setSelected('');setAcknowledge(false);setAccepted(false);await refresh(created.id);setHistory(await api<Run[]>('/runs'));}
 useEffect(()=>{const generation=historyGeneration.current;api<Run[]>('/runs').then(h=>{if(generation===historyGeneration.current)setHistory(h);}).catch(e=>setError(e.message));const id=localStorage.getItem('impact-run');if(id){selectedRun.current=id;refresh(id).catch(()=>{localStorage.removeItem('impact-run');selectedRun.current=null;});}},[]);
useEffect(()=>{if(!run)return;const id=run.id;const events=new EventSource(`/api/runs/${id}/events`);const timer=window.setInterval(()=>{refresh(id).catch(()=>{});},2500);events.onmessage=(event)=>{let update:{status?:string};try{update=JSON.parse(event.data);}catch{return;}if(update.status==='RESET'){events.close();clearInterval(timer);if(selectedRun.current===id)clearRunHistory('Saved run history was reset. Import a workbook to start fresh.');return;}refresh(id).catch(()=>{});};events.onerror=()=>{refresh(id).catch(()=>{});};return()=>{events.close();clearInterval(timer);};},[run?.id]);
 useEffect(()=>{setAcknowledge(false);if(run&&!selected&&Object.keys(run.artifacts).length)setSelected(Object.keys(run.artifacts)[0]);},[run?.id,run?.version]);
 // Open the relevant result only when its phase has published, including recovery.
 // Polls and later versions preserve the user's explicit preview choice.
 useEffect(()=>{
  if(!run)return;
  const preferred=({1:'digital.csv',2:'in-person.csv',3:'digital.csv',4:'updated_90-day.csv',5:'follow-up.csv',6:'current.csv',7:'current.csv'} as Record<number,string>)[run.phase];
  const context=`${run.id}:${run.phase}`;
  if(!preferred||run.artifacts[preferred]?.phase!==run.phase||phasePreviewContext.current===context)return;
  phasePreviewContext.current=context;setSelected(preferred);
 },[run?.id,run?.phase,run?.version]);
async function retryCollection(){if(!run)return;await perform(async()=>{
 const attempt={id:run.id,phase:run.phase,version:run.version};
 const queued=await api<Run>(`/runs/${run.id}/retry`,post());
 retryAttempt.current=attempt;setRun(queued);setAcknowledge(false);
 setNotice('Retry started. Failed resource requests will be attempted again; successful and manually entered measurements are kept.');
 await refresh(run.id);
});}
useEffect(()=>{
 const attempt=retryAttempt.current;if(!run||!attempt||run.id!==attempt.id)return;
 if(run.status==='RUNNING_PHASE'||run.status==='CANCELLING')return;
 if(run.status==='AWAITING_APPROVAL'&&run.phase===attempt.phase&&run.version>attempt.version){
  const failed=run.collectionProgress?.failed??0;
  setNotice(failed?`Retry finished. ${failed} resource ${failed===1?'request is':'requests are'} still unavailable. See the latest collection error below; retry does not bypass publisher blocks.`:run.issues.length?'Retry finished. Review the remaining input or manual collection items below.':'Retry finished. No collection issues remain; review the results and approve this phase.');
  retryAttempt.current=null;
 }else if(run.status==='FAILED_RETRYABLE'||run.status==='CANCELLED'){
  setNotice(run.status==='CANCELLED'?'Collection retry was cancelled.':'Collection retry stopped. Review the error below before trying again.');retryAttempt.current=null;
 }
},[run?.id,run?.status,run?.version]);
 async function resetHistory(){await perform(async()=>{await api('/runs/reset',post({confirm:true}));clearRunHistory('Run history has been reset. Import a workbook to start fresh.');});}
 const running=run?.status==='RUNNING_PHASE';const waiting=run?.status==='AWAITING_APPROVAL';
 const download=(name:string)=>`/api/runs/${run!.id}/artifacts/${name}/download?version=${run!.artifacts[name].version}`;
 let nextStep=importStep;
 if(run&&!importing){
  const key=`${run.id}:${run.status}:${run.phase}`;
  if(run.status==='READY')nextStep=accepted?{key:key+':start',title:'Start the agents',description:'The calculation contract is accepted. Start processing these prepared inputs.',sectionId:'calculation-contract',actionId:'start-processing'}:{key:key+':contract',title:'Review and accept the calculation contract',description:'Confirm the calculation rules before starting the agents.',sectionId:'calculation-contract',actionId:'accept-contract'};
  else if(waiting){
   const index=run.issues.findIndex(issue=>issue.blocking);
   const issue=index>=0?run.issues[index]:!acknowledge?run.issues[0]:undefined;
   nextStep=issue?{key:key+':issue:'+issue.rowId+':'+issue.metricType,title:issue.blocking?'Correct required data':'Review collection items',description:issue.blocking?`Fix ${issue.activity||'this activity'} before approval.`:'Enter verified measurements, or review the option to retain unresolved items for follow-up.',sectionId:`issue-card-${index>=0?index:0}`,actionId:[1,2,4].includes(run.phase)?`issue-action-${index>=0?index:0}`:undefined,alternate:!issue.blocking?{label:'Review follow-up option',sectionId:'phase-review',actionId:'acknowledge-issues'}:undefined}:{key:key+':approve',title:`Review changes and approve Phase ${run.phase}`,description:'Confirm the highlighted changes and result preview, then approve this phase.',sectionId:'phase-review',actionId:'approve-phase'};
  }else if(running||run.status==='CANCELLING')nextStep={key,title:run.status==='CANCELLING'?'Waiting for cancellation':`Agents are processing Phase ${run.phase}`,description:running&&[1,4].includes(run.phase)?'Follow resource checks, saved measurements, and collection issues below. The next review checkpoint will appear when processing finishes.':'No action is required yet. The next review checkpoint will appear automatically.',sectionId:running?'phase-processing':'active-run',auto:!!running};
  else if(run.status==='COMPLETED')nextStep=reportStep|| (run.workbook?.month?{key,title:'Review month-end impact and exclusions',description:'Review the reporting cutoff, charts, and excluded rows, then download the pivot workbook.',sectionId:'monthly-report',actionId:'download-report'}:{key,title:'Download your results',description:'Processing is complete. Preview the results or download the CSV bundle.',sectionId:'results-export',actionId:'download-all'});
  else if(run.status==='FAILED_RETRYABLE')nextStep={key,title:'Review the error and retry',description:run.error||'This phase can be retried from its saved inputs.',sectionId:'run-error',actionId:'retry-phase'};
  else nextStep={key,title:'Start a new reporting run',description:'Open the source workbooks to prepare a new reporting month.',sectionId:'source-switch',actionId:'show-sources'};
 }
 if(error)nextStep={key:'error:'+error,title:'Check the request error',description:'Review the error message, then retry the relevant action.',sectionId:'request-error'};
 return <div className="shell"><a className="skip-link" href="#main-content">Skip to main content</a>
<aside className="sidebar">
 <a href="/" className="brand"><span className="brandmark"><Icon name="chart"/></span><span>Impact Metrics<small>ADVOCACY INTELLIGENCE</small></span></a>
 <div className="sidebar-label">YOUR WORKSPACE</div>
 <div className="workspace"><span className="workspace-symbol"><Icon name="layers"/></span><span>AppDev Developer Advocacy</span></div>
 <p className="sidebar-note">From activity to attributable impact.</p>
 <div className="sidebar-label history-title">RECENT RUNS <span>{history.length}</span></div>
 <nav aria-label="Recent runs">{history.slice(0,8).map(h=><button key={h.id} className={`history ${run?.id===h.id?'active':''}`} disabled={busy} aria-current={run?.id===h.id?'page':undefined} onClick={()=>perform(()=>choose(h))}><span>{new Date(h.createdAt).toLocaleDateString(undefined,{month:'short',day:'numeric'})} · {h.currentRows} activities</span><small><span className={`history-dot ${h.status.toLowerCase()}`}/>{statusLabel(h.status)}</small></button>)}</nav>
 <button className="reset-history" disabled={busy||running||run?.status==='CANCELLING'} onClick={()=>{setError('');setResetOpen(true);}}>Reset run history</button>
 {!history.length&&<div className="history-empty"><Icon name="file"/><p>Your runs will appear here.<br/>Connect your SharePoint workbooks.</p></div>}
 <div className="sidebar-footer"><span className="footer-mark"><Icon name="shield"/></span><div>Every activity has a story.<small>Make its impact visible.</small></div></div>
</aside>
  <main id="main-content"><div className="page-topline"><span>Workspace <span className="breadcrumb-divider">/</span> Impact reporting</span><span className="scope"><span className="dot"/>AppDev Developer Advocacy</span></div>
 <header className="page-head"><div><div className="eyebrow">ACTIVITY INTELLIGENCE</div><h1>Make your impact count.</h1><p>A clearer view of the work you do and the reach it creates.</p></div><div className="header-art" aria-hidden="true"><div className="art-grid"/><span className="art-bar bar-one"/><span className="art-bar bar-two"/><span className="art-bar bar-three"/><span className="art-bar bar-four"/><span className="art-orbit"><Icon name="chart"/></span></div></header>
   {notice&&<div role="status" className="notice"><Icon name="check"/>{notice}</div>}
   {error&&<div id="request-error" role="alert" className="error">{error}<button aria-label="Dismiss error" onClick={()=>setError('')}>×</button></div>}
   <NextAction step={nextStep} paused={!!manual||resetOpen}/>
   {run&&<div id="source-switch" className="source-switch"><span>Source workbooks · {run.workbook?.month||'Saved run'}</span><button id="show-sources" className="text-button" onClick={()=>setImporting(value=>!value)}>{importing?'Return to active run':'Show source workbooks / start a new run'}</button></div>}
   <div hidden={!!run&&!importing}><WorkbookImport busy={busy} onStep={setImportStep} onActivate={()=>setImporting(true)} onImport={async form=>{let imported=false;await perform(async()=>{await choose(await api<Run>('/runs/from-sharepoint',{method:'POST',body:form}));imported=true;});return imported;}} /></div>

   <div className="linkedin-controls"><button type="button" className="text-button" disabled={busy} onClick={()=>perform(async()=>{const session=await api<{message:string}>('/linkedin/session',post());setConnection(session.message);})}>Connect LinkedIn for metric collection</button>{connection&&<p role="status">{connection} <button type="button" className="text-button" onClick={()=>perform(async()=>{const session=await api<{message:string}>('/linkedin/session');setConnection(session.message);})}>Check LinkedIn connection</button></p>}</div>
{!run&&<section className="getting-started"><div className="section-title"><div><h2>From activity to impact</h2><p>One guided workflow. A complete set of metrics.</p></div><span>YOUR NEXT STEPS</span></div><div className="guide-grid"><article><span className="guide-number">01</span><h3>Bring your data</h3><p>Connect SharePoint once. Read both workbooks and select the reporting month.</p></article><article><span className="guide-number">02</span><h3>Review with confidence</h3><p>Confirm calculations and add verified measurements where collection needs your input.</p></article><article><span className="guide-number">03</span><h3>Share your impact</h3><p>Review month-end pivots and charts, then download CSV results or an Excel report.</p></article></div></section>}
{run&&<>
    {run.workbook?.month&&<section className="section workbook-summary"><div className="section-title"><div><h2>Prepared workbook inputs</h2><p>Reporting month {run.workbook.month} · trailing window {run.workbook.windowStart} through {run.workbook.windowEnd} (three calendar months).</p></div></div><p>Current workbook: <strong>{run.workbook.filename}</strong> · Historical workbook: <strong>{run.workbook.historyFilename||run.workbook.filename}</strong></p><p>Current sheet: <strong>{run.workbook.currentSheet}</strong> · History: {run.workbook.historySheets.join(', ')||'None selected'}</p>{run.workbook.blogRows&&<p>Blog activities appended: {Object.entries(run.workbook.blogRows).map(([sheet,count])=>`${sheet}: ${count}`).join(', ')}</p>}{Object.entries(run.workbook.blogSkippedRows||{}).filter(([,count])=>count>0).map(([sheet,count])=><p className="baseline-notice" key={sheet}>{sheet}: {count} undated rows excluded. Fill their Date or Month in Excel to include them.</p>)}{run.workbook.missingBaselines>0&&<p className="baseline-notice">{run.workbook.missingBaselines} historical activities need a verified previous total during phase 4.</p>}<p>Optional input CSV snapshots are created only when downloaded.</p><div className="import-actions"><a className="button secondary" href={`/api/runs/${run.id}/inputs/AvocadoBot%202.csv/download`}>Download AvocadoBot 2.csv ↓</a>{run.trailing&&<a className="button secondary" href={`/api/runs/${run.id}/inputs/trailing_90-day.csv/download`}>Download trailing_90-day.csv ↓</a>}</div></section>}
    <section id="active-run" className="run-head"><div><div className="eyebrow">ACTIVE RUN · {run.id.slice(0,8)}</div><h2>{new Date(run.createdAt).toLocaleDateString(undefined,{month:'long',day:'numeric',year:'numeric'})}</h2></div><span className={`status ${run.status.toLowerCase()}`}><span className="dot"/>{statusLabel(run.status)}</span></section>
    <section className="metrics" aria-label="Run summary"><div><span className="metric-icon"><Icon name="chart"/></span><strong>{run.currentRows}</strong><span>Current-month activities</span></div><div><span className="metric-icon"><Icon name="layers"/></span><strong>{run.trailingRows}</strong><span>Trailing-90 activities</span></div><div><span className="metric-icon"><Icon name="file"/></span><strong>{Object.keys(run.artifacts).length}<small> / 5</small></strong><span>Result datasets available</span></div><div><span className="metric-icon"><Icon name="check"/></span><strong>{run.approvals.length}</strong><span>Phases approved</span></div></section>
    <ol className="timeline" aria-label="Processing phases">{phases.map((name,i)=>{const done=run.approvals.some(a=>a.phase===i+1)||(run.phase>i+1)||run.status==='COMPLETED';return <li key={name} className={`${done?'done':''} ${run.phase===i+1?'current':''}`}><span>{done?'✓':i+1}</span><div>{name}</div></li>;})}</ol>
    {run.status==='READY'&&<section id="calculation-contract" className="review-panel"><div><h2>Review the calculation contract</h2><p>Workbook cells feed the agents directly. Current-month rows without a URL use supplied numeric views/attendance and duration; incomplete measurements go to follow-up. LinkedIn posts use reactions only; Live events use Video Views. Attendance uses a minimum duration of one hour. Author × tool splits conserve totals at six-decimal precision. Trailing totals are allocated once per shared URL, then compared with each previous baseline.</p>{run.workbook?.reportingPolicy==='MONTH_END'&&<p className="baseline-notice">Final reports use DA metric model 2026 and the selected month’s closing totals. Approved agent counts, including counts collected later, are assigned to the selected reporting month. The monthly metrics sheet supplies baselines and fallback totals; actual collection dates are preserved. Markus/Elder blog supplements are one-time contributions. Unresolved month-end totals are excluded until confirmed, and combined pivots count overlapping activity attributions once.</p>}<p>Processing pauses for approval after phases 1, 2, 3, and 4. Missing measurements can be acknowledged; invalid numeric data must be corrected.</p><label className="checkbox"><input type="checkbox" id="accept-contract" checked={accepted} onChange={e=>setAccepted(e.target.checked)}/>I accept these calculation rules for this run.</label></div><button id="start-processing" className="primary" disabled={!accepted||busy} onClick={()=>perform(async()=>{await api(`/runs/${run.id}/start`,post({acceptContract:true}));await refresh(run.id);})}>Start processing →</button></section>}
    {waiting&&<section id="phase-review" className="review-panel"><div><div className="eyebrow">PHASE {run.phase} · VERSION {run.version}</div><h2>Review {phases[run.phase-1].toLowerCase()}</h2><p>{run.review}</p><PhaseChanges id={run.id} phase={run.phase} version={run.version} currentRows={run.currentRows} issues={run.issues.length} artifacts={run.artifacts} approvals={run.approvals}/>{run.phase===2&&<AttendanceReview rows={run.artifacts['in-person.csv']?.rows??0} issues={run.issues.length}/>}{run.issues.length>0&&<label className="checkbox"><input type="checkbox" id="acknowledge-issues" checked={acknowledge} onChange={e=>setAcknowledge(e.target.checked)}/>I reviewed the unresolved collection issues and will retain them for follow-up.</label>}</div><button id="approve-phase" className="primary" disabled={busy||run.issues.some(i=>i.blocking)||(run.issues.length>0&&!acknowledge)} onClick={()=>perform(async()=>{await api(`/runs/${run.id}/phases/${run.phase}/approve`,post({version:run.version,digest,acknowledgeIssues:acknowledge}));await refresh(run.id);})}>Approve phase {run.phase} →</button></section>}
    {run.collectionProgress&&run.collectionProgress.phase===run.phase&&run.collectionProgress.total>0&&<CollectionProgressPanel progress={run.collectionProgress} active={!!running}/>}
    {running&&!(run.collectionProgress?.phase===run.phase&&run.collectionProgress.total>0)&&<div id="phase-processing" className="processing" role="status"><span className="spinner"/>Agents are processing {phases[run.phase-1]?.toLowerCase()}. Successful measurements are saved as the run proceeds.</div>}
    {waiting&&[1,4].includes(run.phase)&&run.issues.length>0&&<button id="retry-collection" className="button secondary" disabled={busy} onClick={()=>void retryCollection()}>{run.issues.some(i=>!i.blocking)?'Retry unresolved collection':'Refresh data review'}</button>}
    {run.error&&<div id="run-error" className="error">{run.error}{run.status==='FAILED_RETRYABLE'&&<button id="retry-phase" onClick={()=>perform(async()=>{await api(`/runs/${run.id}/retry`,post());await refresh(run.id);})}>Retry phase</button>}</div>}
    {run.issues.length>0&&<section className="section"><div className="section-title"><h2>Collection & data review</h2><span>{run.issues.length} issues</span></div><div className="issue-list">{run.issues.map((issue,i)=><div id={`issue-card-${i}`} className="issue" key={issue.rowId+':'+i}><div><span className={`tag ${issue.blocking?'blocking':''}`}>{issue.blocking?'Correction required':'Manual collection'}</span><strong>{issue.activity||issue.rowId}</strong><p>{issue.reason==='Only public HTTPS resource URLs are supported'?'Automatic collection is unavailable; open the resource and confirm or update its total.':issue.reason}</p>{issue.url&&<a href={resourceHref(issue.url)} target="_blank" rel="noreferrer">Open resource ↗</a>}</div>{waiting&&[1,2,4].includes(run.phase)&&<button id={`issue-action-${i}`} onClick={()=>void openManual(issue)}>Enter {issue.metricType.toLowerCase().replace('_',' ')}</button>}</div>)}</div></section>}
    {run.status==='COMPLETED'&&run.workbook?.month&&<MonthlyReport onHistoryStep={setReportStep} highlight={nextStep.sectionId==='monthly-report'} id={run.id} version={run.version+(run.reportingRevision||0)} load={api} onError={setError}/>}
    <section id="results-export" className="section"><div className="section-title"><div><h2>Review results & export</h2><p>Preview phase results. CSV files are created only when you download them.</p></div>{Object.keys(run.artifacts).length>0&&<a id="download-all" className="button secondary" href={`/api/runs/${run.id}/download-all`}>Download all .zip ↓</a>}</div><div className="export-list">{names.map(name=>{const artifact=run.artifacts[name];return <div className={`export-row ${selected===name?'selected':''}`} key={name}><span className="file-icon"><Icon name="file"/><span>CSV</span></span><div className="file-details"><strong>{name}</strong><small>{artifact?`${artifact.rows} rows · phase ${artifact.phase} · version ${artifact.version}`:name==='updated_90-day.csv'&&!run.trailing?'No trailing input supplied':'Available after its phase runs'}</small></div>{artifact&&<><button className="text-button" onClick={()=>{setSelected(name);}}>Preview</button><a className="download" href={download(name)} aria-label={`Download ${name}`}>Download ↓</a></>}</div>;})}</div></section>
    {selected&&run.artifacts[selected]&&<PreviewTable key={`${run.id}:${selected}:${run.reportingRevision||0}`} name={selected} endpoint={`/runs/${run.id}/artifacts/${selected}/preview?version=${run.artifacts[selected].version}`} load={api} onError={setError}/> }
    <details className="activity-log"><summary>Agent activity · {run.events.length} events</summary>{run.events.slice(-30).map(event=><div className="log-entry" key={event.sequence}><time>{new Date(event.at).toLocaleTimeString()}</time><strong>{event.agent}</strong><span>{event.message}</span></div>)}<p>Frozen instruction hash: <code>{run.instructionHash}</code></p></details>
    {!['COMPLETED','CANCELLED'].includes(run.status)&&<button className="text-button cancel" disabled={busy} onClick={()=>perform(async()=>{await api(`/runs/${run.id}/cancel`,post());await refresh(run.id);})}>Cancel this run</button>}
   </>}
   <footer><span><Icon name="shield"/>Original data preserved · Every approval recorded</span><span>AppDev Developer Advocacy</span></footer>
  </main>
  {resetOpen&&<ResetHistoryDialog busy={busy} error={error} onClose={()=>setResetOpen(false)} onReset={()=>{void resetHistory();}}/>}
{manual&&run&&<div className="modal-backdrop"><section role="dialog" aria-modal="true" aria-labelledby="manual-title" className="modal"><button className="close" aria-label="Close measurement dialog" disabled={busy} onClick={()=>setManual(null)}>×</button><div className="eyebrow">{inputMode?'HISTORICAL INPUT REVIEW':'VERIFIED MEASUREMENT'}</div><h2 id="manual-title">{inputMode?'Review original input':`Enter ${manual.metricType.toLowerCase().replace('_',' ')}`}</h2><p>{manual.activity}</p>
 {sourceInput&&<div className="workbook-rules"><strong>{run.phase===4?'Original historical values':'Original input values'}</strong><p>Views / attendance: {sourceInput.originalViews||'Not recorded'}{sourceInput.hasDuration!==false&&<> · Duration (in hours): {sourceInput.originalDuration||'Not recorded'}</>}</p><p>Previous total: {sourceInput.baseline||'Not recorded'}</p>{sourceInput.source&&<><p>Source workbook: <strong>{sourceInput.source.workbook}</strong>{sourceInput.source.sheet&&<> · Sheet: {sourceInput.source.sheet}</>} · Row: {sourceInput.source.rowId}</p><p>Author: {sourceInput.source.author||'Not recorded'} · Tool: {sourceInput.source.tool||'Not recorded'}</p></>}{run.phase===4&&<p>These values come from the historical metrics source and may already be allocated across authors and tools. They can differ from raw AvocadoBot attendance; fractional shares are valid.</p>}{sourceInput.attributed&&<p>Enter this row’s share of the resource’s total. The same share is applied to other rows for this resource.</p>}<small>{inputMode?'Keeping or editing input values does not create a fresh lifetime total or trailing delta. Then approve phase 4 to complete the run.':run.phase===4?'The field starts with the previous total or your latest entered measurement. Confirm it or enter an updated total for this review.':'The field starts with the saved input or latest entered value. Confirm it or enter an updated measurement.'}</small></div>}
 {manual.metricType==='VIDEO_VIEWS'&&<p>Use post analytics → Video performance → Video Views. Lifetime unique viewers and peak live viewers are different metrics.</p>}
 <form onSubmit={e=>{e.preventDefault();if(inputMode){void saveInput();return;}perform(async()=>{await api(`/runs/${run.id}/measurements`,post({version:run.version,rowId:manual.rowId,value,type:manual.metricType,evidence,attributed:sourceInput?.attributed===true}));setManual(null);await refresh(run.id);});}}><label>{inputMode?'Views / attendance':manual.metricType==='BASELINE'?'Previous baseline':manual.metricType==='DURATION'?'Duration (in hours)':'Measured total'}<input required type="number" min="0" step="any" value={value} onChange={e=>setValue(e.target.value)}/></label>{inputMode&&sourceInput?.hasDuration!==false&&<label>Duration (in hours)<input required type="number" min="0" step="any" value={duration} onChange={e=>setDuration(e.target.value)}/></label>}<label>{inputMode?'Review note (optional)':'Source / collection note (optional)'}<textarea maxLength={1000} value={evidence} onChange={e=>setEvidence(e.target.value)} placeholder={inputMode?'Optional context for your review':'Where did you verify this value?'}/></label>{error&&<p role="alert" className="error">{error}</p>}<div className="modal-actions"><button className="primary" disabled={busy}>{inputMode?'Save edited inputs & refresh review':'Save & refresh review →'}</button>{inputMode&&<button type="button" disabled={busy||!sourceInput?.canRetain} onClick={()=>void saveInput(true)}>Keep original values</button>}</div></form>
 {sourceInput&&run.phase===4&&!manual.url&&!manual.blocking&&manual.metricType!=='BASELINE'&&<button className="text-button" disabled={busy} onClick={()=>void toggleHistoricalMode()}>{inputMode?'Enter a fresh total instead':'Review original inputs instead'}</button>}
</section></div>}

 </div>;
}
createRoot(document.getElementById('root')!).render(<App/>);
