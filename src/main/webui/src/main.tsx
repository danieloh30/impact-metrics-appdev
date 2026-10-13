import {CollectionProgressPanel,type CollectionProgress} from './CollectionProgressPanel';
import {MonthlyReport,type ReportCompletion} from './MonthlyReport';
import {WorkbookPublicationPanel,type Publication} from './WorkbookPublicationPanel';
import {PreviewTable} from './PreviewTable';
import {PhaseChanges} from './PhaseChanges';
import {AttendanceReview} from './AttendanceReview';
import React, {useEffect,useRef,useState} from 'react';
import {createRoot} from 'react-dom/client';
import '@fontsource/ibm-plex-sans/400.css';
import '@fontsource/ibm-plex-sans/500.css';
import '@fontsource/ibm-plex-sans/600.css';
import './style.css';
import {WorkbookImport,type ImportDefaults} from './WorkbookImport';
import {NextAction,importStart,type NextStep} from './NextAction';
import {ProcessStatus} from './ProcessStatus';

type Artifact={name:string;version:number;phase:number;rows:number;sha256:string};
type TrailingInput={originalViews:string;originalDuration:string;baseline:string;views:string;duration:string;value?:string;canRetain:boolean;hasDuration?:boolean;attributed?:boolean;source?:{workbook:string;sheet:string;rowId:string;author:string;tool:string}};
type Issue={rowId:string;activity:string;url:string;reason:string;blocking:boolean;metricType:string};
type Run={publication?:Publication|null;reportingRevision?:number;collectionProgress?:CollectionProgress|null;id:string;createdAt:string;automaticProcessing?:boolean;status:string;phase:number;version:number;currentRows:number;trailingRows:number;trailing:boolean;review:string;error:string;instructionHash:string;issues:Issue[];artifacts:Record<string,Artifact>;events:{sequence:number;at:string;agent:string;message:string}[];approvals:{phase:number;version:number}[];workbook?:{source?:string;sourceUrl?:string;historySourceUrl?:string;publicationCheckpoint?:boolean;linkedInCurrentRows?:number;linkedInTrailingRows?:number;chartHistory?:{sourceUrl?:string};reportingPolicy?:string;month:string;currentSheet:string;historySheets:string[];windowStart:string;windowEnd:string;missingBaselines:number;filename?:string;historyFilename?:string;blogRows?:Record<string,number>;blogSkippedRows?:Record<string,number>}};
const phases=['Digital collection','In-person attendance','Author / tool attribution','Trailing 90 days','Other activities','Combine exports','Current totals'];
const names=['digital.csv','in-person.csv','follow-up.csv','updated_90-day.csv','current.csv'];
class ApiError extends Error{constructor(message:string,readonly status:number){super(message);}}
async function api<T>(path:string,options?:RequestInit):Promise<T>{const response=await fetch('/api'+path,options);if(!response.ok){let message;try{const body=await response.json();message=body.error||body.title||JSON.stringify(body);}catch{message=await response.text();}throw new ApiError(message||`Request failed (${response.status})`,response.status);}return response.json();}
const post=(body:unknown={}):RequestInit=>({method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
const statusLabel=(status:string)=>({READY:'Ready to start',RUNNING_PHASE:'Processing',AWAITING_APPROVAL:'Review required',COMPLETED:'Complete',AWAITING_PUBLICATION:'Workbook update required',PUBLISHING_WORKBOOK:'Publishing workbook',FAILED_RETRYABLE:'Retry available',CANCELLED:'Cancelled',CANCELLING:'Cancelling'}[status]||status);

type IconName='chart'|'upload'|'file'|'layers'|'check'|'arrow'|'shield'|'trash';
function Icon({name,className=''}:{name:IconName;className?:string}){
 const paths:Record<IconName,React.ReactNode>={
  chart:<><path d="M4 19h16M7 15v-4m5 4V5m5 10V8"/></>,
  upload:<><path d="M12 16V4m-4 4 4-4 4 4M4 16v4h16v-4"/></>,
  file:<><path d="M14 3H5v18h14V8l-5-5Z M14 3v5h5M8 12h8m-8 4h6"/></>,
  layers:<><path d="m12 3 10 5-10 5L2 8l10-5Zm-9 9 9 5 9-5m-18 5 9 5 9-5"/></>,
  check:<><path d="m5 12 4 4L19 6"/><circle cx="12" cy="12" r="10"/></>,
  arrow:<><path d="M4 12h16m-6-6 6 6-6 6"/></>,
  shield:<><path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3Z M8 12l3 3 5-6"/></>,
  trash:<><path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7"/></>
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
  <p className="reset-preserved"><Icon name="shield"/>Your workflow instructions, published chart history, and saved SharePoint/LinkedIn sessions will be kept.</p>
  <p>Download any results you want to keep before continuing.</p>
  {error&&<p role="alert" className="error">{error}</p>}
  <div className="modal-actions"><button autoFocus disabled={busy} onClick={onClose}>Keep history</button><button className="danger" disabled={busy} onClick={onReset}>{busy?'Resetting…':'Delete all run history'}</button></div>
 </dialog>;
}

const runLabel=(run:Run)=>`${new Date(run.createdAt).toLocaleDateString(undefined,{month:'short',day:'numeric',year:'numeric'})} · ${run.workbook?.month?run.workbook.month+' · ':''}${run.id.slice(0,8)}`;
const deletionBlocked=(run:Run)=>['RUNNING_PHASE','PUBLISHING_WORKBOOK','CANCELLING'].includes(run.status);
function DeleteRunDialog({run,busy,error,onClose,onDelete}:{run:Run;busy:boolean;error:string;onClose:()=>void;onDelete:()=>void}){
 const dialog=useRef<HTMLDialogElement>(null);
 const keepButton=useRef<HTMLButtonElement>(null);
 useEffect(()=>{const element=dialog.current!;element.showModal();keepButton.current?.focus();return()=>element.close();},[]);
 return <dialog ref={dialog} className="modal reset-dialog" aria-labelledby="delete-run-title" onCancel={e=>{e.preventDefault();if(!busy)onClose();}}>
  <div className="eyebrow">ONE SAVED RUN</div><h2 id="delete-run-title">Delete this run?</h2>
  <p><strong>{runLabel(run)}</strong><br/>{run.currentRows} activities · {statusLabel(run.status)}<br/><small>Run ID: {run.id}</small></p>
  <p>This permanently deletes this run’s input copies, measurements, approvals, and saved results. Download any results you want to keep first.</p>
  <p className="reset-preserved"><Icon name="shield"/>Other runs, published chart history, and saved SharePoint/LinkedIn sessions are kept. Published SharePoint workbooks are not deleted.</p>
  {Object.keys(run.artifacts||{}).length>0&&<a href={`/api/runs/${run.id}/download-all`}>Download this run’s CSV bundle ↓</a>}
  {error&&<p role="alert" className="error">{error}</p>}
  <div className="modal-actions"><button ref={keepButton} autoFocus disabled={busy} onClick={onClose}>Keep this run</button><button className="danger" disabled={busy} onClick={onDelete}>{busy?'Deleting…':'Delete this run'}</button></div>
 </dialog>;
}

function App(){
 const [run,setRun]=useState<Run|null>(null),[digest,setDigest]=useState(''),[history,setHistory]=useState<Run[]>([]);
 const [busy,setBusy]=useState(false),[error,setError]=useState('');
 const automaticAction=useRef('');
 const retryAttempt=useRef<{id:string;phase:number;version:number}|null>(null);
 const [acknowledge,setAcknowledge]=useState(false),[selected,setSelected]=useState('');
 const [sourceInput,setSourceInput]=useState<TrailingInput|null>(null),[inputMode,setInputMode]=useState(false),[duration,setDuration]=useState('');
 const [manual,setManual]=useState<Issue|null>(null),[value,setValue]=useState(''),[evidence,setEvidence]=useState('');
 const [connection,setConnection]=useState(''),[linkedInConnecting,setLinkedInConnecting]=useState(false);
 const [linkedInStartedAt,setLinkedInStartedAt]=useState(0),[linkedInElapsed,setLinkedInElapsed]=useState(0),[linkedInCheckError,setLinkedInCheckError]=useState('');
 const linkedInRequests=useRef(0);
 const [resetOpen,setResetOpen]=useState(false),[deleteTarget,setDeleteTarget]=useState<Run|null>(null),[deleteError,setDeleteError]=useState(''),[notice,setNotice]=useState('');
 const [importKey,setImportKey]=useState(0),[importDefaults,setImportDefaults]=useState<ImportDefaults|undefined>(),[publicationStep,setPublicationStep]=useState<NextStep|null>(null),[reportCompletion,setReportCompletion]=useState<ReportCompletion|null>(null);
 const [importing,setImporting]=useState(false),[importStep,setImportStep]=useState<NextStep>(importStart),[reportStep,setReportStep]=useState<NextStep|null>(null);
 const selectedRun=useRef<string|null>(null),historyGeneration=useRef(0),phasePreviewContext=useRef<string|null>(null);
async function openManual(issue:Issue){setManual(issue);setSourceInput(null);setInputMode(false);setValue('');setEvidence('');if(!run)return;await perform(async()=>{const retention=run.phase===4&&!issue.url&&!issue.blocking&&issue.metricType!=='BASELINE';const path=retention?`/runs/${run.id}/trailing-input?rowId=${encodeURIComponent(issue.rowId)}`:`/runs/${run.id}/measurement-input?rowId=${encodeURIComponent(issue.rowId)}&type=${encodeURIComponent(issue.metricType)}`;const input=await api<TrailingInput>(path);setSourceInput(input);setInputMode(retention);setValue(retention?input.views:input.value||'');setDuration(input.duration);});}
function resourceHref(value:string){try{const url=new URL(value.trim());return ['http:','https:'].includes(url.protocol)&&!url.username&&!url.password?url.href:undefined;}catch{return undefined;}}
async function toggleHistoricalMode(){if(!run||!manual)return;await perform(async()=>{const next=!inputMode;const path=next?`/runs/${run.id}/trailing-input?rowId=${encodeURIComponent(manual.rowId)}`:`/runs/${run.id}/measurement-input?rowId=${encodeURIComponent(manual.rowId)}&type=${encodeURIComponent(manual.metricType)}`;const input=await api<TrailingInput>(path);setSourceInput(input);setInputMode(next);setValue(next?input.views:input.value||'');setDuration(input.duration);setEvidence('');});}
async function saveInput(keepOriginal=false){if(!run||!manual)return;await perform(async()=>{await api(`/runs/${run.id}/trailing-inputs`,post({version:run.version,rowId:manual.rowId,views:value,duration,keepOriginal,note:evidence}));setManual(null);await refresh(run.id);});}
function clearRunHistory(message:string,remaining:Run[]=[]){setImportKey(value=>value+1);setImporting(false);setImportStep(importStart);setPublicationStep(null);setReportCompletion(null);setReportStep(null);retryAttempt.current=null;phasePreviewContext.current=null;historyGeneration.current++;selectedRun.current=null;localStorage.removeItem('impact-run');setRun(null);setHistory(remaining);setDigest('');setSelected('');setManual(null);setAcknowledge(false);setResetOpen(false);setDeleteTarget(null);setError('');setNotice(message);}
async function reconcileDeletedRun(id:string){
 if(selectedRun.current!==id)return;
 clearRunHistory('This saved run was deleted. Choose another run or read the workbooks to start a new one.',history.filter(item=>item.id!==id));
 const generation=historyGeneration.current;
 try{const remaining=await api<Run[]>('/runs');if(generation===historyGeneration.current)setHistory(remaining);}catch{/* The next history refresh can recover without restoring the deleted selection. */}
}
function startAnotherRun(){if(!run||run.status!=='COMPLETED'||busy)return;const urls:Partial<{current:string;history:string}>={};if(run.workbook?.sourceUrl)urls.current=run.workbook.sourceUrl;if(run.workbook?.historySourceUrl)urls.history=run.workbook.historySourceUrl;setImportDefaults({month:run.workbook?.month,urls,chartUrl:run.workbook?.chartHistory?.sourceUrl});setImportKey(value=>value+1);setPublicationStep(null);setReportCompletion(null);setReportStep(null);setImportStep(importStart);setImporting(false);retryAttempt.current=null;phasePreviewContext.current=null;selectedRun.current=null;localStorage.removeItem('impact-run');setRun(null);setSelected('');setDigest('');setAcknowledge(false);setManual(null);setError('');setNotice('Start another run. Previous runs are kept; source workbooks will be read again.');}
async function refresh(id:string){try{const data=await api<{run:Run;approvalDigest:string}>(`/runs/${id}`);if(selectedRun.current!==id)return data.run;setRun(data.run);setDigest(data.approvalDigest);setHistory(h=>h.map(r=>r.id===id?data.run:r));return data.run;}catch(e){if(e instanceof ApiError&&e.status===404&&selectedRun.current===id){await reconcileDeletedRun(id);return null;}throw e;}}
 async function perform(action:()=>Promise<unknown>){setBusy(true);setError('');try{await action();}catch(e){automaticAction.current='';setError((e as Error).message);}finally{setBusy(false);}}
 async function choose(created:Run){setPublicationStep(null);setReportCompletion(null);setReportStep(null);retryAttempt.current=null;setImporting(false);phasePreviewContext.current=null;selectedRun.current=created.id;setNotice('');localStorage.setItem('impact-run',created.id);setSelected('');setAcknowledge(false);await refresh(created.id);setHistory(await api<Run[]>('/runs'));}
 useEffect(()=>{const generation=historyGeneration.current;api<Run[]>('/runs').then(h=>{if(generation===historyGeneration.current)setHistory(h);}).catch(e=>setError(e.message));const id=localStorage.getItem('impact-run');if(id){selectedRun.current=id;refresh(id).catch(()=>{localStorage.removeItem('impact-run');selectedRun.current=null;});}},[]);
useEffect(()=>{if(!run)return;const id=run.id;const events=new EventSource(`/api/runs/${id}/events`);const timer=window.setInterval(()=>{refresh(id).catch(()=>{});},2500);events.onmessage=(event)=>{let update:{status?:string};try{update=JSON.parse(event.data);}catch{return;}if(update.status==='RESET'){events.close();clearInterval(timer);if(selectedRun.current===id)void reconcileDeletedRun(id);return;}refresh(id).catch(()=>{});};events.onerror=()=>{refresh(id).catch(()=>{});};return()=>{events.close();clearInterval(timer);};},[run?.id]);
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
  setNotice(failed?`Retry finished. ${failed} resource ${failed===1?'request is':'requests are'} still unavailable. See the latest collection error below; retry does not bypass publisher blocks.`:run.issues.length?'Retry finished. Review the remaining input or manual collection items below.':'Retry finished. No collection issues remain; processing continues automatically.');
  retryAttempt.current=null;
 }else if(run.status==='FAILED_RETRYABLE'||run.status==='CANCELLED'){
  setNotice(run.status==='CANCELLED'?'Collection retry was cancelled.':'Collection retry stopped. Review the error below before trying again.');retryAttempt.current=null;
 }
},[run?.id,run?.status,run?.version]);
 async function resetHistory(){await perform(async()=>{await api('/runs/reset',post({confirm:true}));clearRunHistory('Run history has been reset. Import a workbook to start fresh.');});}
 async function deleteSavedRun(){if(!deleteTarget||busy)return;const id=deleteTarget.id;setBusy(true);setDeleteError('');try{
  try{await api(`/runs/${id}?confirm=true`,{method:'DELETE'});}catch(failure){if(!(failure instanceof ApiError&&failure.status===404))throw failure;}
  setDeleteTarget(null);
  if(selectedRun.current===id)clearRunHistory('Run deleted. Other runs are kept; choose one or start a new run.',history.filter(item=>item.id!==id));
  else{historyGeneration.current++;setHistory(items=>items.filter(item=>item.id!==id));setNotice('Run deleted. Your other runs are kept.');}
 }catch(failure){setDeleteError((failure as Error).message);}finally{setBusy(false);}}
 const running=run?.status==='RUNNING_PHASE';const waiting=run?.status==='AWAITING_APPROVAL';
 const linkedInIssues=waiting&&run&&[1,4].includes(run.phase)?run.issues.filter(issue=>{
  try{const url=new URL(issue.url);return /(^|\.)linkedin\.com$/i.test(url.hostname)&&/^\/(events|posts|feed\/update|pulse)\//.test(url.pathname)&&!issue.blocking&&['REACTIONS','VIDEO_VIEWS'].includes(issue.metricType);}catch{return false;}
 }):[];
 const [linkedInSkipped,setLinkedInSkipped]=useState('');
 const linkedInUpcoming=run?.status==='READY'?1:run?.status==='AWAITING_PUBLICATION'||run?.status==='AWAITING_APPROVAL'&&run.phase===3?4:0;
 const linkedInUpcomingCount=linkedInUpcoming===1?run?.workbook?.linkedInCurrentRows||0:linkedInUpcoming===4?run?.workbook?.linkedInTrailingRows||0:0;
 const linkedInPromptKey=`${run?.id}:${linkedInUpcoming}`;
 const linkedInPreparation=linkedInUpcomingCount>0&&!connection.startsWith('Connected.')&&linkedInSkipped!==linkedInPromptKey&&!importing;
 const linkedInAttention=linkedInIssues.length>0&&!acknowledge&&!run?.issues.some(issue=>issue.blocking)&&!importing;
 const linkedInConnected=connection.startsWith('Connected.');
 function receiveLinkedInSession(session:{message?:string;connecting?:boolean}){if(typeof session.message!=='string')return;setConnection(session.message);setLinkedInConnecting(session.connecting===true);setLinkedInStartedAt(start=>session.connecting?start||Date.now():0);setLinkedInCheckError('');}
 async function checkLinkedIn(){const request=++linkedInRequests.current;const session=await api<{message:string;connecting?:boolean}>('/linkedin/session');if(request===linkedInRequests.current)receiveLinkedInSession(session);}
 useEffect(()=>{let alive=true;const request=++linkedInRequests.current;api<{message:string;connecting?:boolean}>('/linkedin/session').then(session=>{if(alive&&request===linkedInRequests.current)receiveLinkedInSession(session);}).catch(()=>{});return()=>{alive=false;};},[]);
 useEffect(()=>{
  if(!linkedInStartedAt){setLinkedInElapsed(0);return;}
  const update=()=>setLinkedInElapsed(Math.floor((Date.now()-linkedInStartedAt)/1000));update();const timer=window.setInterval(update,1000);return()=>clearInterval(timer);
 },[linkedInStartedAt]);
 useEffect(()=>{
  if(!linkedInAttention&&!linkedInConnecting&&!linkedInPreparation)return;
  let alive=true;const check=async()=>{const request=++linkedInRequests.current;try{const session=await api<{message:string;connecting?:boolean}>('/linkedin/session');if(alive&&request===linkedInRequests.current)receiveLinkedInSession(session);}catch{if(alive&&request===linkedInRequests.current)setLinkedInCheckError('Connection status could not be refreshed. Automatic checks will retry; you can also check the connection below.');}};
  void check();const timer=window.setInterval(check,2500);return()=>{alive=false;clearInterval(timer);};
 },[run?.id,run?.phase,linkedInAttention,linkedInConnecting,linkedInPreparation]);
useEffect(()=>{
 if(!run||busy||error||manual||resetOpen||deleteTarget||importing)return;
 const ready=run.status==='READY',clean=run.status==='AWAITING_APPROVAL'&&run.issues.length===0;
 if(!ready&&!clean)return;
 if(linkedInPreparation||(ready||run.phase===3)&&linkedInUpcomingCount>0&&!connection)return;
 const key=`${run.id}:${run.status}:${run.phase}:${run.version}`;
 if(automaticAction.current===key)return;
 automaticAction.current=key;
 void perform(async()=>{
  try{
   if(ready)await api(`/runs/${run.id}/start`,post({acceptContract:true,automaticProcessing:true}));
   else await api(`/runs/${run.id}/phases/${run.phase}/approve`,post({version:run.version,digest,acknowledgeIssues:false,automaticProcessing:true}));
  }catch(failure){
   // Another tab may already have advanced the same version. Refresh instead of
   // showing a stale approval error; other failures remain actionable.
   if(!(failure instanceof ApiError&&failure.status===409))throw failure;
  }
  await refresh(run.id);
 });
},[run?.id,run?.status,run?.phase,run?.version,busy,error,manual,resetOpen,deleteTarget,importing,linkedInPreparation,connection,digest]);
 const publicationRequired=!!run?.workbook?.publicationCheckpoint;
 const publicationDone=run?.publication?.status==='PUBLISHED'&&run.publication.stage==='COMPLETED';
 const reportReady=!run?.workbook?.month||reportCompletion?.id===run.id&&reportCompletion.ready&&reportCompletion.attention===0&&reportCompletion.chartsReady;
 const chartsGenerated=run?.status==='COMPLETED'&&(!run.workbook?.month||reportCompletion?.id===run.id&&reportCompletion.ready&&reportCompletion.chartsReady);
 const allDone=run?.status==='COMPLETED'&&reportReady&&(!publicationRequired||publicationDone);
 const download=(name:string)=>`/api/runs/${run!.id}/artifacts/${name}/download?version=${run!.artifacts[name].version}`;
 const linkedInPrepareStep:NextStep={key:linkedInPromptKey+':linkedin:'+(linkedInConnecting?'sign-in':'connect'),title:linkedInConnecting?'Complete LinkedIn sign-in':`Connect LinkedIn before Phase ${linkedInUpcoming}`,description:`${linkedInUpcomingCount} ${linkedInUpcoming===1?'current':'trailing'} activities use LinkedIn metrics. Connect once before collection, or explicitly continue and review unavailable metrics later.`,sectionId:'linkedin-collection',actionId:linkedInConnecting?'check-linkedin':'connect-linkedin',alternate:{label:'Continue without LinkedIn',sectionId:'linkedin-collection',actionId:'skip-linkedin-preparation'}};
 let nextStep=importStep;
 if(run&&!importing){
  const key=`${run.id}:${run.status}:${run.phase}`;
  if(run.status==='READY')nextStep=linkedInPreparation?linkedInPrepareStep:{key:key+':automatic-start',title:'Starting processing automatically',description:'Prepared inputs use the implemented calculation rules. No start approval is needed.',sectionId:'calculation-contract',auto:false};
  else if(waiting){
   const index=run.issues.findIndex(issue=>issue.blocking);
   const issue=index>=0?run.issues[index]:!acknowledge?run.issues[0]:undefined;
   nextStep=linkedInPreparation&&run.phase===3&&!run.issues.some(issue=>issue.blocking)?linkedInPrepareStep:linkedInAttention?{key:key+':linkedin:'+(linkedInConnected?'retry':linkedInConnecting?'sign-in':'connect'),title:linkedInConnected?'Retry LinkedIn metric collection':linkedInConnecting?'Complete LinkedIn sign-in':'Connect LinkedIn for metric collection',description:linkedInConnected?'Your session is saved. Retry unresolved collection; existing successful measurements are kept.':`${linkedInIssues.length} LinkedIn ${linkedInIssues.length===1?'activity needs':'activities need'} a metric. Sign in to collect post reactions or event Video Views, then retry this phase.`,sectionId:'linkedin-collection',actionId:linkedInConnected?'retry-linkedin':linkedInConnecting?'check-linkedin':'connect-linkedin',alternate:{label:'Review follow-up option',sectionId:'phase-review',actionId:'acknowledge-issues'}}:issue?{key:key+':issue:'+issue.rowId+':'+issue.metricType,title:issue.blocking?'Correct required data':'Review collection items',description:issue.blocking?`Fix ${issue.activity||'this activity'} before approval.`:'Enter verified measurements, or review the option to retain unresolved items for follow-up.',sectionId:`issue-card-${index>=0?index:0}`,actionId:[1,2,4].includes(run.phase)?`issue-action-${index>=0?index:0}`:undefined,alternate:!issue.blocking?{label:'Review follow-up option',sectionId:'phase-review',actionId:'acknowledge-issues'}:undefined}:run.issues.length>0?{key:key+':approve',title:`Review changes and approve Phase ${run.phase}`,description:'Confirm your decision to retain unresolved items, then continue processing.',sectionId:'phase-review',actionId:'approve-phase'}:{key:key+':continue',title:`Continuing after Phase ${run.phase}`,description:'No review items remain. Processing advances automatically.',sectionId:'active-run',auto:false};
  }else if(running||run.status==='CANCELLING')nextStep={key,title:run.status==='CANCELLING'?'Waiting for cancellation':`Agents are processing Phase ${run.phase}`,description:running&&[1,4].includes(run.phase)?'Follow resource checks, saved measurements, and collection issues below. The next review checkpoint will appear when processing finishes.':'No action is required yet. The next review checkpoint will appear automatically.',sectionId:running?'phase-processing':'active-run',auto:!!running};
  else if(run.status==='AWAITING_PUBLICATION'||run.status==='PUBLISHING_WORKBOOK')nextStep=linkedInPreparation?linkedInPrepareStep:publicationStep||{key,title:run.status==='PUBLISHING_WORKBOOK'?'Publishing metrics workbook':'Publish current inputs before trailing collection',description:'Review the workbook update before the trailing agents start.',sectionId:'workbook-publication',actionId:run.status==='PUBLISHING_WORKBOOK'?undefined:'preview-publication'};
  else if(run.status==='COMPLETED')nextStep=reportCompletion?.id===run.id&&reportCompletion.attention>0?{key:key+':report-attention',title:'Resolve month-end reporting items',description:'Resolve actionable reporting values before publishing the completed snapshot. Reference-only rows need no confirmation.',sectionId:'monthly-report',actionId:'review-report-attention'}:publicationRequired&&!publicationDone?(publicationStep||{key:key+':publish-final',title:'Publish completed monthly snapshot',description:'Save the reviewed current and trailing results in the metrics workbook.',sectionId:'workbook-publication',actionId:'preview-publication'}):reportStep|| (run.workbook?.month?{key,title:'Review month-end impact and exclusions',description:'Review the reporting cutoff, charts, and excluded rows, then download the pivot workbook.',sectionId:'monthly-report',actionId:'download-report'}:{key,title:'Download your results',description:'Processing is complete. Preview the results or download the CSV bundle.',sectionId:'results-export',actionId:'download-all'});
  else if(run.status==='FAILED_RETRYABLE')nextStep={key,title:'Review the error and retry',description:run.error||'This phase can be retried from its saved inputs.',sectionId:'run-error',actionId:'retry-phase'};
  else nextStep={key,title:'Start a new reporting run',description:'Open the source workbooks to prepare a new reporting month.',sectionId:'source-switch',actionId:'show-sources'};
 }
 if(run?.status==='COMPLETED'&&!importing){
  if(allDone)nextStep={key:run.id+':finished',title:'Results and charts are ready',description:'Download the combined pivot workbook with editable charts, or start another reporting run. Previous runs stay saved.',sectionId:'results-export',auto:false};
  else nextStep={...nextStep,auto:false,actionLabel:nextStep.sectionId==='workbook-publication'?'Review publication':reportCompletion?.attention?'Review reporting items':'View report & charts'};
 }
 if(error)nextStep={key:'error:'+error,title:'Check the request error',description:'Review the error message, then retry the relevant action.',sectionId:'request-error'};
 return <div className="shell"><a className="skip-link" href="#main-content">Skip to main content</a>
<aside className="sidebar">
 <a href="/" className="brand"><span className="brandmark"><Icon name="chart"/></span><span>Impact Hours<small>ADVOCACY INTELLIGENCE</small></span></a>
 <div className="sidebar-label">YOUR WORKSPACE</div>
 <div className="workspace"><span className="workspace-symbol"><Icon name="layers"/></span><span>AppDev Developer Advocacy</span></div>
 <p className="sidebar-note">From activity to attributable impact.</p>
 <div className="sidebar-label history-title">RECENT RUNS <span>{history.length}</span></div>
 <nav aria-label="Recent runs">{history.slice(0,8).map(h=><div className="history-row" key={h.id}><button className={`history ${run?.id===h.id?'active':''}`} disabled={busy||!!deleteTarget} aria-current={run?.id===h.id?'page':undefined} onClick={()=>perform(()=>choose(h))}><span>{new Date(h.createdAt).toLocaleDateString(undefined,{month:'short',day:'numeric'})} · {h.currentRows} activities</span><small><span className={`history-dot ${h.status.toLowerCase()}`}/>{statusLabel(h.status)} · {h.id.slice(0,8)}</small></button><button className="delete-run" aria-label={`Delete run ${runLabel(h)}`} title={deletionBlocked(h)?'Wait for this run to finish or cancel it before deleting it':`Delete run ${runLabel(h)}`} disabled={busy||!!deleteTarget||deletionBlocked(h)} onClick={()=>{setDeleteError('');setDeleteTarget(h);}}><Icon name="trash"/></button></div>)}</nav>
 <button className="reset-history" disabled={busy||running||run?.status==='PUBLISHING_WORKBOOK'||run?.status==='CANCELLING'} onClick={()=>{setError('');setResetOpen(true);}}>Reset run history</button>
 {!history.length&&<div className="history-empty"><Icon name="file"/><p>Your runs will appear here.<br/>Connect your SharePoint workbooks.</p></div>}
 <div className="sidebar-footer"><span className="footer-mark"><Icon name="shield"/></span><div>Every activity has a story.<small>Make its impact visible.</small></div></div>
</aside>
  <main id="main-content"><div className="page-topline"><span>Workspace <span className="breadcrumb-divider">/</span> Impact reporting</span><span className="scope"><span className="dot"/>AppDev Developer Advocacy</span></div>
 <header className="page-head"><div><div className="eyebrow">ACTIVITY INTELLIGENCE</div><h1>Make your impact count.</h1><p>A clearer view of the work you do and the reach it creates.</p></div><div className="header-art" aria-hidden="true"><div className="art-grid"/><span className="art-bar bar-one"/><span className="art-bar bar-two"/><span className="art-bar bar-three"/><span className="art-bar bar-four"/><span className="art-orbit"><Icon name="chart"/></span></div></header>
   {notice&&<div role="status" className="notice"><Icon name="check"/>{notice}</div>}
   {error&&<div id="request-error" role="alert" className="error">{error}<button aria-label="Dismiss error" onClick={()=>setError('')}>×</button></div>}
   <div className="workflow-monitor"><ProcessStatus run={importing?null:run} importing={importing&&(busy||importStep.key==='import:working')} step={nextStep} chartsReady={!!chartsGenerated} allDone={!!allDone} attention={reportCompletion?.id===run?.id?reportCompletion?.attention||0:0}/><NextAction step={nextStep} paused={!!manual||resetOpen||!!deleteTarget||busy} finished={allDone&&!importing&&!error&&run?{downloadHref:run.workbook?.month?`/api/runs/${run.id}/reporting/download?scope=combined&format=xlsx`:`/api/runs/${run.id}/download-all`,downloadLabel:run.workbook?.month?'Download pivots & charts':'Download CSV bundle',onNewRun:startAnotherRun}:undefined}/></div>
   {run?.status==='COMPLETED'&&<section className={`completion-banner ${allDone?'is-complete':''}`} aria-label="Run completion"><div role="status" aria-live="polite"><strong>{chartsGenerated?'Process done — charts generated':'Agent processing finished'}</strong><p>{allDone?'All processes are complete. Results and charts are ready. You can download them or start another run, including the same month.':reportCompletion?.attention?`Report is provisional: ${reportCompletion.attention} reporting items need attention. Saved corrections update the charts automatically.`:publicationRequired&&!publicationDone?'Chart generation is done. Publish the completed snapshot to finish workbook publication.':'Preparing the final report and charts; check chart history if it is unavailable.'}</p></div><button id="start-another-run" className="primary" disabled={busy} onClick={startAnotherRun}>Start another run</button></section>}
   {run&&<div id="source-switch" className="source-switch"><span>Source workbooks · {run.workbook?.month||'Saved run'}</span><button id="show-sources" className="text-button" onClick={()=>setImporting(value=>!value)}>{importing?'Return to active run':'Show source workbooks / start a new run'}</button></div>}
   <div hidden={!!run&&!importing}><WorkbookImport key={importKey} initial={importDefaults} busy={busy} onStep={setImportStep} onActivate={()=>setImporting(true)} onImport={async form=>{let imported=false;await perform(async()=>{await choose(await api<Run>('/runs/from-sharepoint',{method:'POST',body:form}));imported=true;});return imported;}} /></div>

   <section id="linkedin-collection" aria-label="LinkedIn metric collection" className={`linkedin-controls${linkedInAttention||linkedInConnecting||linkedInPreparation?' linkedin-attention':''}`}>
    {linkedInUpcoming>0&&<><div className="eyebrow">BEFORE PHASE {linkedInUpcoming} · METRIC COLLECTION</div><h2>{linkedInUpcomingCount>0?"LinkedIn setup before collection":"LinkedIn source check"}</h2>{linkedInUpcomingCount===0?<p className="linkedin-no-sources" role="status"><strong>No LinkedIn URLs found in the selected {linkedInUpcoming===1?"current":"trailing"} input rows.</strong> Processing starts automatically; sign-in is optional for these inputs. To collect LinkedIn metrics, add the post or event URL to the workbook’s resource column and prepare a new run.</p>:<p>{linkedInUpcomingCount} {linkedInUpcoming===1?'current':'trailing'} activities need LinkedIn metrics. Sign in once before Phase 1 or Phase 4; the saved session is reused. Live events need account access to Video Views.</p>}</>}
    {!linkedInAttention&&!linkedInPreparation&&!linkedInConnecting&&linkedInUpcoming===0&&<p className="linkedin-context">LinkedIn is used in Phase 1 and Phase 4 for post reactions and Live event Video Views. Connect when the workflow highlights this section; other activity sources do not need it.</p>}
    {linkedInAttention&&<><div className="eyebrow">PHASE {run?.phase} · LINKEDIN COLLECTION</div><h2>{linkedInConnected?'LinkedIn is connected — retry collection':'LinkedIn metrics need your attention'}</h2><p>{linkedInIssues.length} unresolved {linkedInIssues.length===1?'activity':'activities'}. Posts use reactions; Live events use Video Views. Event analytics require access through your LinkedIn account.</p></>}
    {linkedInConnecting&&<div className="linkedin-progress"><div role="status"><span className="spinner"/><strong>Waiting for LinkedIn sign-in</strong><span className="linkedin-elapsed">{linkedInElapsed}s elapsed</span></div><p>Complete sign-in in the LinkedIn browser window. This page checks automatically every few seconds and updates when the session is saved.</p><small>The sign-in window allows up to five minutes. If it closes or sign-in times out, connect again. Reloading this page resumes connection checks.</small>{linkedInElapsed>=60&&<p className="linkedin-help">Still waiting? Bring the LinkedIn window forward and finish any verification challenge. If you are already signed in, use Check LinkedIn connection.</p>}</div>}
    <div className="import-actions"><button id="connect-linkedin" type="button" className={(linkedInAttention||linkedInPreparation)&&!linkedInConnected?'primary':'secondary'} disabled={busy||linkedInConnecting} onClick={()=>perform(async()=>{linkedInRequests.current++;const session=await api<{message:string;connecting?:boolean}>('/linkedin/session',post());linkedInRequests.current++;receiveLinkedInSession(session);})}>{linkedInConnecting?'LinkedIn sign-in in progress…':'Connect LinkedIn for metric collection'}</button>
    {linkedInAttention&&linkedInConnected&&<button id="retry-linkedin" type="button" className="primary" disabled={busy} onClick={()=>void retryCollection()}>Retry LinkedIn metric collection</button>}
    {connection&&<button id="check-linkedin" type="button" className="text-button" disabled={busy} onClick={()=>perform(checkLinkedIn)}>Check LinkedIn connection</button>}</div>
    {linkedInPreparation&&<button id="skip-linkedin-preparation" className="text-button" type="button" disabled={busy||linkedInConnecting} onClick={()=>setLinkedInSkipped(linkedInPromptKey)}>Continue without LinkedIn — review missing metrics later</button>}
    {connection&&!linkedInConnecting&&<p role="status" className={linkedInConnected?'linkedin-success':''}>{linkedInConnected?'LinkedIn session saved. Prepared inputs start automatically; retry unresolved collection in an active review phase.':connection}</p>}
    {linkedInCheckError&&<p className="linkedin-help" role="status">{linkedInCheckError}</p>}
    {linkedInAttention&&!linkedInConnecting&&<p>Sign-in does not guarantee analytics access; you can still enter a verified measurement below or retain unresolved items for follow-up.</p>}
   </section>
{!run&&<section className="getting-started"><div className="section-title"><div><h2>From activity to impact</h2><p>One guided workflow. A complete set of metrics.</p></div><span>YOUR NEXT STEPS</span></div><div className="guide-grid"><article><span className="guide-number">01</span><h3>Bring your data</h3><p>Connect SharePoint once. Read the activity workbooks, load saved chart history, and select the reporting month.</p></article><article><span className="guide-number">02</span><h3>Review with confidence</h3><p>Confirm calculations and add verified measurements where collection needs your input.</p></article><article><span className="guide-number">03</span><h3>Share your impact</h3><p>Review month-end pivots and charts, then download CSV results or an Excel report.</p></article></div></section>}
{run&&<>
    {run.workbook?.month&&<section className="section workbook-summary"><div className="section-title"><div><h2>Prepared workbook inputs</h2><p>Reporting month {run.workbook.month} · trailing window {run.workbook.windowStart} through {run.workbook.windowEnd} (three calendar months).</p></div></div><p>Current workbook: <strong>{run.workbook.filename}</strong> · Historical workbook: <strong>{run.workbook.historyFilename||run.workbook.filename}</strong></p><p>Current sheet: <strong>{run.workbook.currentSheet}</strong> · History: {run.workbook.historySheets.join(', ')||'None selected'}</p>{run.workbook.blogRows&&<p>Blog activities appended: {Object.entries(run.workbook.blogRows).map(([sheet,count])=>`${sheet}: ${count}`).join(', ')}</p>}{Object.entries(run.workbook.blogSkippedRows||{}).filter(([,count])=>count>0).map(([sheet,count])=><p className="baseline-notice" key={sheet}>{sheet}: {count} undated rows excluded. Fill their Date or Month in Excel to include them.</p>)}{run.workbook.missingBaselines>0&&<p className="baseline-notice">{run.workbook.missingBaselines} historical activities need a verified previous total during phase 4.</p>}<p>Optional input CSV snapshots are created only when downloaded.</p><div className="import-actions"><a className="button secondary" href={`/api/runs/${run.id}/inputs/AvocadoBot%202.csv/download`}>Download AvocadoBot 2.csv ↓</a>{run.trailing&&<a className="button secondary" href={`/api/runs/${run.id}/inputs/trailing_90-day.csv/download`}>Download trailing_90-day.csv ↓</a>}</div></section>}
    <section id="active-run" className="run-head"><div><div className="eyebrow">ACTIVE RUN · {run.id.slice(0,8)}</div><h2>{new Date(run.createdAt).toLocaleDateString(undefined,{month:'long',day:'numeric',year:'numeric'})}</h2></div><span className={`status ${run.status.toLowerCase()}`}><span className="dot"/>{statusLabel(run.status)}</span></section>
    <section className="metrics" aria-label="Run summary"><div><span className="metric-icon"><Icon name="chart"/></span><strong>{run.currentRows}</strong><span>Current-month activities</span></div><div><span className="metric-icon"><Icon name="layers"/></span><strong>{run.trailingRows}</strong><span>Trailing-90 activities</span></div><div><span className="metric-icon"><Icon name="file"/></span><strong>{Object.keys(run.artifacts).length}<small> / 5</small></strong><span>Result datasets available</span></div><div><span className="metric-icon"><Icon name="check"/></span><strong>{run.status==='COMPLETED'?7:['AWAITING_APPROVAL','AWAITING_PUBLICATION','PUBLISHING_WORKBOOK'].includes(run.status)?run.phase:Math.max(0,run.phase-1)}</strong><span>Phases completed</span></div></section>
    <ol className="timeline" aria-label="Processing phases">{phases.flatMap((name,i)=>{const done=run.approvals.some(a=>a.phase===i+1)||(run.phase>i+1)||run.status==='COMPLETED';const atPublication=run.phase===3&&['AWAITING_PUBLICATION','PUBLISHING_WORKBOOK'].includes(run.status);const regular=<li key={name} className={`${done?'done':''} ${run.phase===i+1&&!atPublication?'current':''}`}><span>{done?'✓':i+1}</span><div>{name}</div></li>;return i===3?[<li key="linkedin-check" className={`${run.phase>3?"done":""} ${linkedInUpcoming===4?"current":""}`}><span>{run.phase>3?"✓":"in"}</span><div><a href="#linkedin-collection">LinkedIn source check</a></div></li>,...(publicationRequired?[<li key="publication" className={`${run.phase>3?'done':''} ${atPublication?'current':''}`}><span>{run.phase>3?'✓':'↗'}</span><div>Publish current inputs</div></li>,regular]:[regular])]:[regular];})}</ol>
    {run.workbook?.source==='SharePoint'&&['AWAITING_PUBLICATION','PUBLISHING_WORKBOOK','COMPLETED'].includes(run.status)&&<WorkbookPublicationPanel id={run.id} version={run.version} revision={run.reportingRevision||0} phase={run.phase} status={run.status} month={run.workbook.month} url={run.workbook.historySourceUrl||''} publication={run.publication} collectionReady={!linkedInPreparation} load={api} onRefresh={()=>refresh(run.id)} onStep={setPublicationStep}/>}
{run.status==='READY'&&<section id="calculation-contract" className="review-panel"><div><h2>Automatic processing</h2><p>Prepared workbook inputs start automatically. Clean phases continue without approval; missing measurements, invalid data and workbook publication pause for your review.</p><details><summary>Calculation rules</summary><p>Standard participation counts one unit per activity, split across authors and tools. Attendance uses supplied counts and duration, except monthly aggregate workshops. Historical digital contributions use allocated current totals minus their previous baselines. Final impact uses DA metric model 2026 and the selected reporting month.</p></details><p role="status">{linkedInPreparation?'Connect LinkedIn or choose to continue without it before collection starts.':'Starting the agents…'}</p></div></section>}
    {waiting&&run.issues.length>0&&<section id="phase-review" className="review-panel"><div><div className="eyebrow">PHASE {run.phase} · VERSION {run.version}</div><h2>Review {phases[run.phase-1].toLowerCase()}</h2><p>{run.review}</p><PhaseChanges id={run.id} phase={run.phase} version={run.version} currentRows={run.currentRows} issues={run.issues.length} artifacts={run.artifacts} approvals={run.approvals}/>{run.phase===2&&<AttendanceReview rows={run.artifacts['in-person.csv']?.rows??0} issues={run.issues.length}/>}{run.issues.length>0&&<label className="checkbox"><input type="checkbox" id="acknowledge-issues" checked={acknowledge} onChange={e=>setAcknowledge(e.target.checked)}/>I reviewed the unresolved collection issues and will retain them for follow-up.</label>}</div><button id="approve-phase" className="primary" disabled={busy||run.issues.some(i=>i.blocking)||(run.issues.length>0&&!acknowledge)||run.phase===3&&linkedInPreparation} onClick={()=>perform(async()=>{await api(`/runs/${run.id}/phases/${run.phase}/approve`,post({version:run.version,digest,acknowledgeIssues:acknowledge,automaticProcessing:true}));await refresh(run.id);})}>Approve phase {run.phase} →</button></section>}
    {run.collectionProgress&&run.collectionProgress.phase===run.phase&&run.collectionProgress.total>0&&<CollectionProgressPanel progress={run.collectionProgress} active={!!running}/>}
    {running&&!(run.collectionProgress?.phase===run.phase&&run.collectionProgress.total>0)&&<div id="phase-processing" className="processing" role="status"><span className="spinner"/>Agents are processing {phases[run.phase-1]?.toLowerCase()}. Successful measurements are saved as the run proceeds.</div>}
    {waiting&&[1,4].includes(run.phase)&&run.issues.length>0&&<button id="retry-collection" className="button secondary" disabled={busy} onClick={()=>void retryCollection()}>{run.issues.some(i=>!i.blocking)?'Retry unresolved collection':'Refresh data review'}</button>}
    {run.error&&<div id="run-error" className="error">{run.error}{run.status==='FAILED_RETRYABLE'&&<button id="retry-phase" onClick={()=>perform(async()=>{await api(`/runs/${run.id}/retry`,post());await refresh(run.id);})}>Retry phase</button>}</div>}
    {run.issues.length>0&&<section className="section"><div className="section-title"><h2>Collection & data review</h2><span>{run.issues.length} issues</span></div><div className="issue-list">{run.issues.map((issue,i)=><div id={`issue-card-${i}`} className="issue" key={issue.rowId+':'+i}><div><span className={`tag ${issue.blocking?'blocking':''}`}>{issue.blocking?'Correction required':'Manual collection'}</span><strong>{issue.activity||issue.rowId}</strong><p>{issue.reason==='Only public HTTPS resource URLs are supported'?'Automatic collection is unavailable; open the resource and confirm or update its total.':issue.reason}</p>{issue.url&&<a href={resourceHref(issue.url)} target="_blank" rel="noreferrer">Open resource ↗</a>}</div>{waiting&&[1,2,4].includes(run.phase)&&<button id={`issue-action-${i}`} onClick={()=>void openManual(issue)}>Enter {issue.metricType.toLowerCase().replace('_',' ')}</button>}</div>)}</div></section>}
    {run.status==='COMPLETED'&&run.workbook?.month&&<MonthlyReport onCompletion={setReportCompletion} onHistoryStep={setReportStep} highlight={nextStep.sectionId==='monthly-report'} id={run.id} version={run.version+(run.reportingRevision||0)} load={api} onError={setError}/>}
    <section id="results-export" className="section"><div className="section-title"><div><h2>Review results & export</h2><p>Preview phase results. CSV files are created only when you download them.</p></div>{Object.keys(run.artifacts).length>0&&<a id="download-all" className="button secondary" href={`/api/runs/${run.id}/download-all`}>Download all .zip ↓</a>}</div><div className="export-list">{names.map(name=>{const artifact=run.artifacts[name];return <div className={`export-row ${selected===name?'selected':''}`} key={name}><span className="file-icon"><Icon name="file"/><span>CSV</span></span><div className="file-details"><strong>{name}</strong><small>{artifact?`${artifact.rows} rows · phase ${artifact.phase} · version ${artifact.version}`:name==='updated_90-day.csv'&&!run.trailing?'No trailing input supplied':'Available after its phase runs'}</small></div>{artifact&&<><button className="text-button" onClick={()=>{setSelected(name);}}>Preview</button><a className="download" href={download(name)} aria-label={`Download ${name}`}>Download ↓</a></>}</div>;})}</div></section>
    {selected&&run.artifacts[selected]&&<PreviewTable key={`${run.id}:${selected}:${run.reportingRevision||0}`} name={selected} endpoint={`/runs/${run.id}/artifacts/${selected}/preview?version=${run.artifacts[selected].version}`} load={api} onError={setError}/> }
    <details className="activity-log"><summary>Agent activity · {run.events.length} events</summary>{run.events.slice(-30).map(event=><div className="log-entry" key={event.sequence}><time>{new Date(event.at).toLocaleTimeString()}</time><strong>{event.agent}</strong><span>{event.message}</span></div>)}<p>Frozen instruction hash: <code>{run.instructionHash}</code></p></details>
    {!['COMPLETED','CANCELLED'].includes(run.status)&&<button className="text-button cancel" disabled={busy} onClick={()=>perform(async()=>{await api(`/runs/${run.id}/cancel`,post());await refresh(run.id);})}>Cancel this run</button>}
   </>}
   <footer><span><Icon name="shield"/>Original data preserved · Automatic transitions and reviews recorded</span><span>AppDev Developer Advocacy</span></footer>
  </main>
  {resetOpen&&<ResetHistoryDialog busy={busy} error={error} onClose={()=>setResetOpen(false)} onReset={()=>{void resetHistory();}}/>}
  {deleteTarget&&<DeleteRunDialog run={deleteTarget} busy={busy} error={deleteError} onClose={()=>{setDeleteTarget(null);setDeleteError('');}} onDelete={()=>{void deleteSavedRun();}}/>}
{manual&&run&&<div className="modal-backdrop"><section role="dialog" aria-modal="true" aria-labelledby="manual-title" className="modal"><button className="close" aria-label="Close measurement dialog" disabled={busy} onClick={()=>setManual(null)}>×</button><div className="eyebrow">{inputMode?'HISTORICAL INPUT REVIEW':'VERIFIED MEASUREMENT'}</div><h2 id="manual-title">{inputMode?'Review original input':`Enter ${manual.metricType.toLowerCase().replace('_',' ')}`}</h2><p>{manual.activity}</p>
 {sourceInput&&<div className="workbook-rules"><strong>{run.phase===4?'Original historical values':'Original input values'}</strong><p>Views / attendance: {sourceInput.originalViews||'Not recorded'}{sourceInput.hasDuration!==false&&<> · Duration (in hours): {sourceInput.originalDuration||'Not recorded'}</>}</p><p>Previous total: {sourceInput.baseline||'Not recorded'}</p>{sourceInput.source&&<><p>Source workbook: <strong>{sourceInput.source.workbook}</strong>{sourceInput.source.sheet&&<> · Sheet: {sourceInput.source.sheet}</>} · Row: {sourceInput.source.rowId}</p><p>Author: {sourceInput.source.author||'Not recorded'} · Tool: {sourceInput.source.tool||'Not recorded'}</p></>}{run.phase===4&&<p>These values come from the historical metrics source and may already be allocated across authors and tools. They can differ from raw AvocadoBot attendance; fractional shares are valid.</p>}{sourceInput.attributed&&<p>Enter this row’s share of the resource’s total. The same share is applied to other rows for this resource.</p>}<small>{inputMode?'Keeping or editing input values does not create a fresh lifetime total or trailing delta. Then approve phase 4 to complete the run.':run.phase===4?'The field starts with the previous total or your latest entered measurement. Confirm it or enter an updated total for this review.':'The field starts with the saved input or latest entered value. Confirm it or enter an updated measurement.'}</small></div>}
 {manual.metricType==='VIDEO_VIEWS'&&<p>Use post analytics → Video performance → Video Views. Lifetime unique viewers and peak live viewers are different metrics.</p>}
 <form onSubmit={e=>{e.preventDefault();if(inputMode){void saveInput();return;}perform(async()=>{await api(`/runs/${run.id}/measurements`,post({version:run.version,rowId:manual.rowId,value,type:manual.metricType,evidence,attributed:sourceInput?.attributed===true}));setManual(null);await refresh(run.id);});}}><label>{inputMode?'Views / attendance':manual.metricType==='BASELINE'?'Previous baseline':manual.metricType==='DURATION'?'Duration (in hours)':'Measured total'}<input required type="number" min="0" step="any" value={value} onChange={e=>setValue(e.target.value)}/></label>{inputMode&&sourceInput?.hasDuration!==false&&<label>Duration (in hours)<input required type="number" min="0" step="any" value={duration} onChange={e=>setDuration(e.target.value)}/></label>}<label>{inputMode?'Review note (optional)':'Source / collection note (optional)'}<textarea maxLength={1000} value={evidence} onChange={e=>setEvidence(e.target.value)} placeholder={inputMode?'Optional context for your review':'Where did you verify this value?'}/></label>{error&&<p role="alert" className="error">{error}</p>}<div className="modal-actions"><button className="primary" disabled={busy}>{inputMode?'Save edited inputs & refresh review':'Save & refresh review →'}</button>{inputMode&&<button type="button" disabled={busy||!sourceInput?.canRetain} onClick={()=>void saveInput(true)}>Keep original values</button>}</div></form>
 {sourceInput&&run.phase===4&&!manual.url&&!manual.blocking&&manual.metricType!=='BASELINE'&&<button className="text-button" disabled={busy} onClick={()=>void toggleHistoricalMode()}>{inputMode?'Enter a fresh total instead':'Review original inputs instead'}</button>}
</section></div>}

 </div>;
}
createRoot(document.getElementById('root')!).render(<App/>);
