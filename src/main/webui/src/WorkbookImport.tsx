import {useEffect,useRef,useState} from 'react';
import {WorkbookProgress} from './WorkbookProgress';
import {importStart,type NextStep} from './NextAction';

type Role='current'|'history';
type Flow='idle'|'current'|'history'|'validate'|'prepare'|'failed-current'|'failed-history'|'failed-prepare'|'choice'|'chart'|'failed-chart';
type Sheet={name:string;eligible:boolean;rows:number;months:string[];reason:string;blog?:boolean;monthCounts?:Record<string,number>;undatedRows?:number;inputMonth?:number;inputYear?:number};
type Inspection={sheets:Sheet[];suggestedCurrentSheet:string;sourceToken:string;filename:string};
const defaults={
 current:'https://ibm.sharepoint.com/:x:/r/sites/DEPT-DeveloperRelations/Shared%20Documents/Really%20Useful%20Numbers%20(RUN)/Advocacy/2026/Input%20files/AvocadoBot%202.0%20-%C2%A0AppDev%20DA%20impact%20inputs.xlsx?d=w25cb891e867b4fb39d1b486b41c9ce5b&csf=1&web=1&e=pge2wn',
 history:'https://ibm.sharepoint.com/:x:/r/sites/DEPT-DeveloperRelations/Shared%20Documents/Really%20Useful%20Numbers%20(RUN)/Advocacy/2026/Working%20data/AppDev%20Advocacy%20impact%20metrics%202026.xlsx?d=w59f2fcb193344cfcaf8ecb6be62c2a11&csf=1&web=1&e=KbPLK1'
};
const defaultChartUrl='https://ibm.sharepoint.com/:x:/r/sites/DEPT-DeveloperRelations/Shared%20Documents/Really%20Useful%20Numbers%20(RUN)/Advocacy/2026/Advocacy%20RUN%20metrics%202026.xlsx?d=w4b208e68dc63475e846b84af621e46c6&csf=1&web=1&e=EMMxU0';
type ChartSaved={source:{cacheId:string;filename:string;importedAt:string;endMonth:string};snapshot:{channel:{months:string[]};tool:{months:string[]}}};
const titles={current:'AvocadoBot 2.0 – AppDev DA impact inputs',history:'AppDev Advocacy impact metrics 2026'};
function reportingWindow(month:string){
 const [year,m]=month.split('-').map(Number);if(!year||!m)return [];
 return [2,1,0].map(offset=>{const date=new Date(Date.UTC(year,m-1-offset,1));return {year:date.getUTCFullYear(),month:date.getUTCMonth()+1,label:date.toLocaleDateString('en-US',{month:'long',year:'numeric',timeZone:'UTC'})};});
}
function historicalSheets(snapshot:Inspection|undefined,month:string){
 return [...reportingWindow(month)].reverse().map(target=>(snapshot?.sheets||[]).filter(sheet=>sheet.inputMonth===target.month&&(!sheet.inputYear||sheet.inputYear===target.year))).find(sheets=>sheets.length>0)||[];
}
function defaultCurrentSheet(snapshot:Inspection){
 const sheets=snapshot.sheets.filter(sheet=>sheet.eligible&&!sheet.blog);
 return sheets.find(sheet=>sheet.name.trim().toLowerCase()==='current month inputs')?.name||(sheets.length===1?sheets[0].name:'');
}
export type ImportDefaults={month?:string;urls?:Partial<typeof defaults>;chartUrl?:string};
export function WorkbookImport({busy,onImport,onStep,onActivate,initial}:{busy:boolean;onImport:(form:FormData)=>Promise<boolean>;onStep:(step:NextStep)=>void;onActivate:()=>void;initial?:ImportDefaults}){
 const today=new Date();const [month,setMonth]=useState(initial?.month||`${today.getFullYear()}-${String(today.getMonth()+1).padStart(2,'0')}`);
 const [urls,setUrls]=useState({...defaults,...initial?.urls}),[inspections,setInspections]=useState<Partial<Record<Role,Inspection>>>({}),[errors,setErrors]=useState<Partial<Record<Role|'prepare',string>>>({});
 const [currentSheet,setCurrentSheet]=useState(''),[loading,setLoading]=useState<Role|null>(null),[connectTarget,setConnectTarget]=useState<Role>('current');
 const [session,setSession]=useState<{busy:boolean;status:string;message:string;downloadStage?:string}|null>(null);
 const generation=useRef({current:0,history:0}),flowLock=useRef(false);
 const [flow,setFlow]=useState<Flow>('idle'),[flowBusy,setFlowBusy]=useState(false);
 const [chartUrl,setChartUrl]=useState(initial?.chartUrl||defaultChartUrl),[chart,setChart]=useState<ChartSaved|null>(null),[chartError,setChartError]=useState(''),[chartBusy,setChartBusy]=useState(false),[connectUrl,setConnectUrl]=useState('');const chartGeneration=useRef(0);
 const working=busy||flowBusy||!!loading||chartBusy;
 useEffect(()=>{const generation=++chartGeneration.current;setChart(null);void fetch(`/api/chart-history?url=${encodeURIComponent(chartUrl)}`).then(async response=>{if(!response.ok)return;const body=await response.json();if(generation===chartGeneration.current&&body.source?.cacheId)setChart(body);}).catch(()=>{});},[chartUrl]);
 async function readChart(){const generation=++chartGeneration.current;setChartBusy(true);setChartError('');setSession(old=>old?{...old,downloadStage:'OPENING'}:old);try{const response=await fetch('/api/chart-history',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({url:chartUrl})});const body=await response.json();if(!response.ok){if(response.status===401)setConnectUrl(chartUrl);throw new Error(body.error||'Cannot read chart history');}if(generation!==chartGeneration.current)return null;if(!body.source?.cacheId)throw new Error('Chart history did not return a saved snapshot');setChart(body);setConnectUrl('');return body as ChartSaved;}catch(error){setChartError(error instanceof Error?error.message:String(error));return null;}finally{setChartBusy(false);}}

 async function sessionAction(connect=false,quiet=false){try{const response=await fetch('/api/sharepoint/session',connect?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({url:connectUrl||urls[connectTarget]})}:undefined);const body=await response.json();if(!response.ok)throw new Error(body.error||'SharePoint connection failed');setSession(body);}catch(e){if(!quiet)setErrors(old=>({...old,[connectTarget]:(e as Error).message}));}}
 useEffect(()=>{void sessionAction(false,true);},[]);
 useEffect(()=>{if(session?.status!=='CONNECTING'&&!session?.busy&&!loading&&!chartBusy)return;const timer=globalThis.setInterval(()=>{void sessionAction(false,true);},1000);return()=>clearInterval(timer);},[session?.status,session?.busy,loading,chartBusy]);
 async function inspect(role:Role){
  const version=++generation.current[role];setLoading(role);setErrors(old=>({...old,[role]:''}));setInspections(old=>({...old,[role]:undefined}));setSession(old=>old?{...old,downloadStage:'OPENING'}:old);
  try{const response=await fetch('/api/workbooks/from-sharepoint',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({url:urls[role]})});const body=await response.json();if(!response.ok){if(response.status===401)setConnectTarget(role);throw new Error(body.error||'Cannot read workbook');}if(version!==generation.current[role])return null;
   const result=body as Inspection;if(role===connectTarget)setConnectTarget('current');setInspections(old=>({...old,[role]:result}));if(role==='current')setCurrentSheet(defaultCurrentSheet(result));return result;
  }catch(e){if(version===generation.current[role])setErrors(old=>({...old,[role]:(e as Error).message}));void sessionAction(false,true);}finally{if(version===generation.current[role])setLoading(null);}
  return null;
 }
 const current=inspections.current,history=inspections.history,window=reportingWindow(month);
 const eligible=current?.sheets.filter(sheet=>sheet.eligible&&!sheet.blog)||[],blogs=current?.sheets.filter(sheet=>sheet.eligible&&sheet.blog)||[];
 const matching=historicalSheets(history,month);
 const missing=window.filter(target=>!matching.some(sheet=>sheet.months.includes(`${target.year}-${String(target.month).padStart(2,'0')}`)));
 const blocked=matching.length>1||matching.some(sheet=>!sheet.eligible);
 const bounds=window.length?`${window[0].year}-${String(window[0].month).padStart(2,'0')}-01 – ${new Date(Date.UTC(window[2].year,window[2].month,0)).toISOString().slice(0,10)}`:'';
 async function readAndPrepare(){
  if(flowLock.current||working||session?.busy||session?.status!=='CONNECTED')return;
  flowLock.current=true;setFlowBusy(true);onActivate();setErrors(old=>({...old,prepare:''}));
  try{
   let currentSnapshot=current,historySnapshot=history,chosen=currentSheet;
   if(!currentSnapshot){setFlow('current');const result=await inspect('current');if(!result){setFlow('failed-current');return;}currentSnapshot=result;chosen=defaultCurrentSheet(result);}
   if(!historySnapshot){setFlow('history');const result=await inspect('history');if(!result){setFlow('failed-history');return;}historySnapshot=result;}
   setFlow('validate');const candidates=historicalSheets(historySnapshot,month);
   if(!month||!chosen||!currentSnapshot.sheets.some(sheet=>sheet.name===chosen&&sheet.eligible&&!sheet.blog)||candidates.length!==1||!candidates[0].eligible){setFlow('choice');return;}
   let chartSnapshot=chart;if(!chartSnapshot){setFlow('chart');chartSnapshot=await readChart();if(!chartSnapshot){setFlow('failed-chart');return;}}
   setFlow('prepare');const form=new FormData();form.append('chartHistoryId',chartSnapshot.source.cacheId);form.append('sourceToken',currentSnapshot.sourceToken);form.append('historySourceToken',historySnapshot.sourceToken);form.append('month',month);form.append('currentSheet',chosen);
   if(await onImport(form)){setInspections({});setFlow('idle');setErrors({});}
   else{setFlow('failed-prepare');setErrors(old=>({...old,prepare:'Preparation did not finish. Retry the failed step.'}));}
  }catch(e){setFlow('failed-prepare');setErrors(old=>({...old,prepare:e instanceof Error?e.message:String(e)}));}
  finally{flowLock.current=false;setFlowBusy(false);}
 }
 const stateKey=working?'working':!session||session.busy||session.status==='CONNECTING'?'connecting':session.status!=='CONNECTED'?'connect':flow==='choice'&&!currentSheet?'sheet':current&&history&&(!matching.length||blocked)?'invalid-history':!month?'month':'prepare';
 const failed=flow.startsWith('failed-');
 useEffect(()=>{
  const steps:Record<string,NextStep>={
   connecting:{...importStart,key:'import:connecting',title:session?.status==='CONNECTING'?'Finish IBM sign-in':'Checking SharePoint connection',description:'Wait for connection readiness; complete sign-in in the Chrome window when requested.',auto:false},
   connect:importStart,
   working:{key:'import:working',title:'Reading workbooks and preparing inputs',description:'Follow the four steps below. Successful snapshots are kept if a later step fails.',sectionId:'prepare-inputs',auto:false},
   month:{key:'import:month',title:'Choose the reporting month',description:'This determines current activities and the trailing date window.',sectionId:'reporting-month',actionId:'reporting-month-input'},
   sheet:{key:'import:sheet',title:'Choose the current-month sheet',description:'More than one activity sheet is available. Select the authoritative current sheet, then continue.',sectionId:'current-sheet-selection',actionId:'current-sheet-input'},
   'invalid-history':{key:'import:invalid-history',title:'Check the historical sheet selection',description:'Choose a month with a valid monthly snapshot, or correct the workbook and re-read it in Source details.',sectionId:'history-selection',actionId:'reporting-month-input'},
   prepare:{key:failed?'import:retry:'+flow:'import:prepare',title:failed?'Retry the failed step':'Read workbooks & prepare inputs',description:failed?'Review the step-specific error. Successful workbook snapshots will be reused.':`Reporting month ${month}. One action reads the activity sources, reuses saved chart history or reads it once, and prepares the selected inputs.`,sectionId:'prepare-inputs',actionId:'prepare-workbooks'},
  };
  onStep(steps[stateKey]);
 },[stateKey,month,onStep,session?.status,failed,flow]);
 const prepareStatus=flow==='prepare'?'Preparing…':flow==='failed-prepare'?'Failed — retry available':flow==='choice'?'Selection needs attention':'Waiting for workbook reads';
 return <section className="workbook-panel" aria-labelledby="workbook-title">
  <div className="eyebrow">01 / CONNECT YOUR SOURCE WORKBOOKS</div>
  <div className="section-title"><div><h2 id="workbook-title">Three workbooks. One reporting month.</h2><p>Choose your reporting month, then read the activity and chart-history workbooks and prepare the inputs in one action. Agents process Excel cells directly; CSV files are created only when downloaded.</p></div><span className="workbook-badge">XLSX</span></div>
  <div className="sharepoint-connection-bar" id="sharepoint-connection"><div><strong>SharePoint connection</strong><p>One saved IBM sign-in is reused for all three workbooks when your account has access.</p></div><div className="sharepoint-connection-controls"><button type="button" disabled={!urls[connectTarget]||working||session?.busy} id="connect-sharepoint" onClick={()=>{onActivate();void sessionAction(true);}}>Connect SharePoint</button><button type="button" className="text-button" disabled={working} onClick={()=>void sessionAction()}>Check SharePoint connection</button></div></div>
  {session&&<p role="status" className="sharepoint-status">{session.message}</p>}
  {connectTarget==='history'&&<p className="baseline-notice">The metrics workbook needs sign-in. Connect SharePoint will open that workbook to refresh access.</p>}
  <p className="sharepoint-signin-note">Connect opens a separate Chrome window with an app-specific profile. Complete IBM sign-in there. Source links remain editable during sign-in; changes apply to the next workbook read. Access to each workbook is checked when you read it.</p>
  <label className="reporting-month-picker" id="reporting-month">Reporting month<input id="reporting-month-input" aria-label="Reporting month" type="month" required value={month} disabled={working} onChange={e=>{onActivate();setMonth(e.target.value);}}/><small>Trailing window: {bounds||'Choose a month'}</small></label>
  <div className="sharepoint-workbooks">{(['current','history'] as Role[]).map(role=><section key={role} id={`workbook-${role}`} className="sharepoint-workbook" aria-label={`${role==='current'?'Current activities':'Historical metrics'} workbook`}>
   <span className="workbook-source-label">{role==='current'?'CURRENT-MONTH ACTIVITIES':'TRAILING HISTORY'}</span><h3>{titles[role]}</h3>
   <p>{role==='current'?'Current month inputs plus matching Markus and Elder blog data.':'Latest monthly input sheet selected automatically; its rolling history is filtered to the reporting window.'}</p>
   <label>{role==='current'?'AvocadoBot SharePoint link':'Metrics SharePoint link'}<input aria-label={role==='current'?'AvocadoBot SharePoint link':'Metrics SharePoint link'} type="url" value={urls[role]} disabled={working} onChange={e=>{onActivate();generation.current[role]++;setFlow('idle');setUrls(old=>({...old,[role]:e.target.value}));setInspections(old=>({...old,[role]:undefined}));setErrors(old=>({...old,[role]:''}));}}/></label>
   <div className="workbook-read-row"><details className="source-details"><summary>Source details</summary><p>Re-read only this workbook after updating its source. The other snapshot is retained.</p><button type="button" className="primary" disabled={!urls[role]||working||session?.busy||session?.status!=='CONNECTED'} id={`read-${role}`} onClick={()=>{onActivate();setFlow('idle');void inspect(role);}}>{loading===role?'Downloading workbook…':role==='current'?'Read AvocadoBot workbook':'Read metrics workbook'}</button></details>{inspections[role]&&<span className="workbook-ready">✓ Snapshot ready</span>}</div>
   {loading===role&&<WorkbookProgress sharepoint stage={session?.downloadStage}/>}
   {errors[role]&&<p role="alert" className="error">{errors[role]}</p>}
   {inspections[role]&&<><p className="workbook-snapshot">Snapshot: <strong>{inspections[role]!.filename}</strong></p>{inspections[role]!.sheets.some(sheet=>!sheet.eligible)&&<details className="excluded-sheets"><summary>Excluded sheets ({inspections[role]!.sheets.filter(sheet=>!sheet.eligible).length})</summary>{inspections[role]!.sheets.filter(sheet=>!sheet.eligible).map(sheet=><p key={sheet.name}><strong>{sheet.name}</strong>: {sheet.reason}</p>)}</details>}</>}
  </section>)}</div>
  <section id="initial-chart-history" className="sharepoint-workbook chart-source-input" aria-label="Published chart history workbook">
   <span className="workbook-source-label">PUBLISHED CHART HISTORY · SAVED LOCALLY</span><h3>Advocacy RUN metrics 2026</h3><p>Read the two published AppDev Advocacy tables once. History survives app and Dev Services restarts. Charts use September 2025 through the selected month; agents replace that month's published totals.</p>
   <label>Chart history SharePoint link<input aria-label="Initial chart history SharePoint link" type="url" value={chartUrl} disabled={working} onChange={event=>{onActivate();setFlow('idle');setChartError('');setConnectUrl('');setChartUrl(event.target.value);}}/></label>
   <div className="import-actions"><button id="read-chart-history" type="button" disabled={working||session?.busy||session?.status!=='CONNECTED'} onClick={()=>{onActivate();void readChart();}}>{chartBusy?'Reading chart history…':chart?'Refresh published chart history':'Read chart history workbook'}</button>{chart&&<span className="workbook-ready">✓ Saved history ready</span>}</div>
   {chart&&<p className="workbook-snapshot">{chart.source.filename} · {chart.snapshot.channel.months[0]} – {chart.source.endMonth} · saved {new Date(chart.source.importedAt).toLocaleString()}. Reused without another download.</p>}
   {chartBusy&&<WorkbookProgress sharepoint stage={session?.downloadStage}/>} {chartError&&<p className="error" role="alert">{chartError} {chart?'The previous saved history remains available.':'Retry this source; successful activity snapshots are retained.'}</p>}
  </section>
  {current&&<div className="workbook-options"><label id="current-sheet-selection">Current-month sheet<select id="current-sheet-input" aria-label="Current-month sheet" value={currentSheet} disabled={working} onChange={e=>{onActivate();setCurrentSheet(e.target.value);}}><option value="" disabled>Choose an activity sheet</option>{eligible.map(sheet=><option key={sheet.name} value={sheet.name}>{sheet.name} · {sheet.rows} activities in sheet</option>)}</select></label>
   {blogs.length>0&&<fieldset className="history-sheets blog-sheets"><legend>Blog activities appended automatically</legend>{blogs.map(sheet=><div key={sheet.name}><strong>{sheet.name}</strong><p>{sheet.monthCounts?.[month]??0} activities for {month}</p>{!!sheet.undatedRows&&<p className="baseline-notice">{sheet.undatedRows} rows have no Date or Month and will be excluded. Update Excel and read AvocadoBot again to include them.</p>}</div>)}</fieldset>}
  </div>}
  {history&&<div id="history-selection" className="workbook-rules historical-selection" aria-label="Selected historical input sheets"><strong>Metrics workbook · {bounds}</strong><p>Historical baseline sheet: {matching.map(sheet=>sheet.name).join(', ')||'None found'}</p><p>Use one monthly snapshot to avoid duplicates from overlapping historical sheets. Only rows dated within this inclusive window are included. AvocadoBot’s previous-month sheets are not used for history.</p>{matching.length>1&&<p role="alert" className="error">Multiple sheets match the latest month. Keep one authoritative monthly input sheet before importing.</p>}{matching.length===1&&matching[0].inputMonth!==window[2]?.month&&<p className="baseline-notice">No input sheet found for {window[2]?.label}. Using the latest available snapshot: {matching[0].name}.</p>}{matching.filter(sheet=>!sheet.eligible).map(sheet=><p role="alert" className="error" key={sheet.name}>{sheet.name}: {sheet.reason}. Correct the sheet and read the metrics workbook again.</p>)}{missing.length>0&&<p className="baseline-notice">No dated activities found for {missing.map(target=>target.label).join(', ')} in this snapshot. Only available rows within the window will be processed.</p>}<p>Recorded Current total values remain the previous baselines; missing or invalid values require Phase 4 review.</p></div>}
  <section id="prepare-inputs" className="combined-import" aria-label="Read workbooks and prepare inputs" aria-busy={working}>
   <div className="workbook-prepare"><button id="prepare-workbooks" className="primary" disabled={working||session?.busy||session?.status!=='CONNECTED'||!month||!urls.current||!urls.history||blocked||(!!history&&!matching.length)} type="button" onClick={()=>void readAndPrepare()}>{working?'Reading & preparing…':failed?'Retry failed step':flow==='choice'?'Continue & prepare inputs':'Read workbooks & prepare inputs'}</button><span className="workbook-next">Successful reads are reused on retry. Agents start automatically when preparation succeeds. Clean phases continue; review items pause processing.</span></div>
   <ol className="import-steps" aria-label="Workbook preparation progress" aria-live="polite">
    {(['current','history'] as Role[]).map((role,index)=><li key={role} className={errors[role]?'failed':inspections[role]?'complete':loading===role?'active':''}><span className="import-step-number">{inspections[role]?'✓':index+1}</span><div><strong>{role==='current'?'Read AvocadoBot workbook':'Read historical metrics workbook'}</strong><span>{loading===role?'Reading…':errors[role]?`Failed — ${errors[role]}`:inspections[role]?`Snapshot ready · ${inspections[role]!.filename}`:'Waiting'}</span></div></li>)}
    <li className={chartError?'failed':chart?'complete':chartBusy?'active':''}><span className="import-step-number">{chart?'✓':3}</span><div><strong>Load published chart history</strong><span>{chartBusy?'Reading…':chartError?'Failed — retry available':chart?'Saved history ready':'Waiting'}</span></div></li>
    <li className={flow==='failed-prepare'?'failed':flow==='prepare'?'active':''}><span className="import-step-number">4</span><div><strong>Validate sheets & prepare inputs</strong><span>{prepareStatus}</span></div></li>
   </ol>
   {flow==='choice'&&<p className="baseline-notice" role="status">Preparation paused. Choose a valid current-month sheet and reporting month above, then continue. Both downloads are retained.</p>}
   {errors.prepare&&<div className="error preparation-error" role="alert"><p><strong>Preparation could not finish</strong></p><p>{errors.prepare}</p><p>Both workbook selections are kept. Correct the source if needed, re-read it in <strong>Source details</strong>, then retry. If the snapshot expired, re-read both activity workbooks. Saved runs and chart history are kept.</p></div>}
  </section>

 </section>;
}
