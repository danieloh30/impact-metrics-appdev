import {useEffect,useRef,useState} from 'react';
import {WorkbookProgress} from './WorkbookProgress';
import {importStart,type NextStep} from './NextAction';

type Role='current'|'history';
type Sheet={name:string;eligible:boolean;rows:number;months:string[];reason:string;blog?:boolean;monthCounts?:Record<string,number>;undatedRows?:number;inputMonth?:number;inputYear?:number};
type Inspection={sheets:Sheet[];suggestedCurrentSheet:string;sourceToken:string;filename:string};
const defaults={
 current:'https://ibm-my.sharepoint.com/:x:/r/personal/alexandra_freeman_ibm_com/Documents/Developer%20Relations/Really%20useful%20numbers%20(RUN)/Inputs/AvocadoBot%202.0%20-%20Runtimes%20DA%20impact%20inputs.xlsx?d=wc4b8fb9134a44856ac3980cc9b39c53f&csf=1&web=1&e=7Ja7w9',
 history:'https://ibm.sharepoint.com/:x:/r/sites/DEPT-DeveloperRelations/Shared%20Documents/Really%20Useful%20Numbers%20(RUN)/Advocacy/2026/Working%20data/AppDev%20Advocacy%20impact%20metrics%202026.xlsx?d=w17d51aa602214ef29e2d761379e0b65f&csf=1&web=1&e=BdC23f'
};
const titles={current:'AvocadoBot 2.0 – Runtimes DA impact inputs',history:'AppDev Advocacy impact metrics 2026'};
function reportingWindow(month:string){
 const [year,m]=month.split('-').map(Number);if(!year||!m)return [];
 return [2,1,0].map(offset=>{const date=new Date(Date.UTC(year,m-1-offset,1));return {year:date.getUTCFullYear(),month:date.getUTCMonth()+1,label:date.toLocaleDateString('en-US',{month:'long',year:'numeric',timeZone:'UTC'})};});
}
export function WorkbookImport({busy,onImport,onStep,onActivate}:{busy:boolean;onImport:(form:FormData)=>Promise<boolean>;onStep:(step:NextStep)=>void;onActivate:()=>void}){
 const today=new Date();const [month,setMonth]=useState(`${today.getFullYear()}-${String(today.getMonth()+1).padStart(2,'0')}`);
 const [urls,setUrls]=useState(defaults),[inspections,setInspections]=useState<Partial<Record<Role,Inspection>>>({}),[errors,setErrors]=useState<Partial<Record<Role,string>>>({});
 const [currentSheet,setCurrentSheet]=useState(''),[loading,setLoading]=useState<Role|null>(null),[connectTarget,setConnectTarget]=useState<Role>('current');
 const [session,setSession]=useState<{busy:boolean;status:string;message:string;downloadStage?:string}|null>(null);
 const generation=useRef({current:0,history:0});
 async function sessionAction(connect=false,quiet=false){try{const response=await fetch('/api/sharepoint/session',connect?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({url:urls[connectTarget]})}:undefined);const body=await response.json();if(!response.ok)throw new Error(body.error||'SharePoint connection failed');setSession(body);}catch(e){if(!quiet)setErrors(old=>({...old,[connectTarget]:(e as Error).message}));}}
 useEffect(()=>{void sessionAction(false,true);},[]);
 useEffect(()=>{if(session?.status!=='CONNECTING'&&!session?.busy&&!loading)return;const timer=globalThis.setInterval(()=>{void sessionAction(false,true);},1000);return()=>clearInterval(timer);},[session?.status,session?.busy,loading]);
 async function inspect(role:Role){
  const version=++generation.current[role];setLoading(role);setErrors(old=>({...old,[role]:''}));setInspections(old=>({...old,[role]:undefined}));setSession(old=>old?{...old,downloadStage:'OPENING'}:old);
  try{const response=await fetch('/api/workbooks/from-sharepoint',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({url:urls[role]})});const body=await response.json();if(!response.ok){if(response.status===401)setConnectTarget(role);throw new Error(body.error||'Cannot read workbook');}if(version!==generation.current[role])return;
   const result=body as Inspection;if(role===connectTarget)setConnectTarget('current');setInspections(old=>({...old,[role]:result}));if(role==='current')setCurrentSheet(result.suggestedCurrentSheet);
  }catch(e){if(version===generation.current[role])setErrors(old=>({...old,[role]:(e as Error).message}));void sessionAction(false,true);}finally{if(version===generation.current[role])setLoading(null);}
 }
 const current=inspections.current,history=inspections.history,window=reportingWindow(month);
 const eligible=current?.sheets.filter(sheet=>sheet.eligible&&!sheet.blog)||[],blogs=current?.sheets.filter(sheet=>sheet.eligible&&sheet.blog)||[];
 const matching=[...window].reverse().map(target=>(history?.sheets||[]).filter(sheet=>sheet.inputMonth===target.month&&(!sheet.inputYear||sheet.inputYear===target.year))).find(sheets=>sheets.length>0)||[];
 const missing=window.filter(target=>!matching.some(sheet=>sheet.months.includes(`${target.year}-${String(target.month).padStart(2,'0')}`)));
 const blocked=matching.length>1||matching.some(sheet=>!sheet.eligible);
 const bounds=window.length?`${window[0].year}-${String(window[0].month).padStart(2,'0')}-01 – ${new Date(Date.UTC(window[2].year,window[2].month,0)).toISOString().slice(0,10)}`:'';
 const stateKey=loading?'reading:'+loading:!session||session.busy||session.status==='CONNECTING'?'connecting':session.status!=='CONNECTED'?'connect':!current?'current':!history?'history':!month||!currentSheet?'month':!matching.length||blocked?'invalid-history':'prepare';
 useEffect(()=>{
  const steps:Record<string,NextStep>={
   connecting:{...importStart,key:'import:connecting',title:session?.status==='CONNECTING'?'Finish IBM sign-in':'Checking SharePoint connection',description:'Wait for connection readiness; complete sign-in in the Chrome window when requested.',auto:false},
   connect:importStart,
   current:{key:'import:current',title:'Read the AvocadoBot workbook',description:'Download current-month activities and blog data.',sectionId:'workbook-current',actionId:'read-current'},
   history:{key:'import:history',title:'Read the metrics workbook',description:'Download the separate historical metrics snapshot.',sectionId:'workbook-history',actionId:'read-history'},
   month:{key:'import:month',title:'Choose the reporting month and current sheet',description:'These choices determine current activities and the trailing date window.',sectionId:'reporting-month',actionId:'reporting-month-input'},
   'invalid-history':{key:'import:invalid-history',title:'Check the historical sheet selection',description:'Choose a month with a valid monthly snapshot, or correct the workbook and read it again.',sectionId:'history-selection',actionId:'reporting-month-input'},
   prepare:{key:'import:prepare',title:'Confirm the month and prepare inputs',description:`Reporting month ${month}. Check the source selections, then prepare both workbook snapshots.`,sectionId:'prepare-inputs',actionId:'prepare-workbooks'},
  };
  onStep(loading?{key:'import:reading:'+loading,title:'Reading '+(loading==='current'?'AvocadoBot':'historical metrics'),description:'Wait for download and sheet inspection to finish.',sectionId:'workbook-'+loading,auto:false}:steps[stateKey]);
 },[stateKey,month,onStep,session?.status]);
 return <section className="workbook-panel" aria-labelledby="workbook-title">
  <div className="eyebrow">01 / CONNECT YOUR SOURCE WORKBOOKS</div>
  <div className="section-title"><div><h2 id="workbook-title">Two workbooks. One reporting month.</h2><p>Read current activities and historical metrics separately from SharePoint. Agents process Excel cells directly; CSV files are created only when downloaded.</p></div><span className="workbook-badge">XLSX</span></div>
  <div className="sharepoint-connection-bar" id="sharepoint-connection"><div><strong>SharePoint connection</strong><p>One saved IBM sign-in is reused for both workbooks when your account has access.</p></div><div className="sharepoint-connection-controls"><button type="button" disabled={!urls[connectTarget]||busy||!!loading||session?.busy} id="connect-sharepoint" onClick={()=>{onActivate();void sessionAction(true);}}>Connect SharePoint</button><button type="button" className="text-button" disabled={busy||!!loading} onClick={()=>void sessionAction()}>Check SharePoint connection</button></div></div>
  {session&&<p role="status" className="sharepoint-status">{session.message}</p>}
  {connectTarget==='history'&&<p className="baseline-notice">The metrics workbook needs sign-in. Connect SharePoint will open that workbook to refresh access.</p>}
  <p className="sharepoint-signin-note">Connect opens a separate Chrome window with an app-specific profile. Complete IBM sign-in there. Access to each workbook is checked when you read it.</p>
  <label className="reporting-month-picker" id="reporting-month">Reporting month<input id="reporting-month-input" aria-label="Reporting month" type="month" required value={month} disabled={busy} onChange={e=>{onActivate();setMonth(e.target.value);}}/><small>Trailing window: {bounds||'Choose a month'}</small></label>
  <div className="sharepoint-workbooks">{(['current','history'] as Role[]).map(role=><section key={role} id={`workbook-${role}`} className="sharepoint-workbook" aria-label={`${role==='current'?'Current activities':'Historical metrics'} workbook`}>
   <span className="workbook-source-label">{role==='current'?'CURRENT-MONTH ACTIVITIES':'TRAILING HISTORY'}</span><h3>{titles[role]}</h3>
   <p>{role==='current'?'Current month inputs plus matching Markus and Elder blog data.':'Latest monthly input sheet selected automatically; its rolling history is filtered to the reporting window.'}</p>
   <label>{role==='current'?'AvocadoBot SharePoint link':'Metrics SharePoint link'}<input aria-label={role==='current'?'AvocadoBot SharePoint link':'Metrics SharePoint link'} type="url" value={urls[role]} disabled={busy||!!loading||session?.busy} onChange={e=>{onActivate();generation.current[role]++;setUrls(old=>({...old,[role]:e.target.value}));setInspections(old=>({...old,[role]:undefined}));setErrors(old=>({...old,[role]:''}));}}/></label>
   <div className="workbook-read-row"><button type="button" className="primary" disabled={!urls[role]||busy||!!loading||session?.busy||session?.status!=='CONNECTED'} id={`read-${role}`} onClick={()=>{onActivate();void inspect(role);}}>{loading===role?'Downloading workbook…':role==='current'?'Read AvocadoBot workbook':'Read metrics workbook'}</button>{inspections[role]&&<span className="workbook-ready">✓ Snapshot ready</span>}</div>
   {loading===role&&<WorkbookProgress sharepoint stage={session?.downloadStage}/>}
   {errors[role]&&<p role="alert" className="error">{errors[role]}</p>}
   {inspections[role]&&<><p className="workbook-snapshot">Snapshot: <strong>{inspections[role]!.filename}</strong></p>{inspections[role]!.sheets.some(sheet=>!sheet.eligible)&&<details className="excluded-sheets"><summary>Excluded sheets ({inspections[role]!.sheets.filter(sheet=>!sheet.eligible).length})</summary>{inspections[role]!.sheets.filter(sheet=>!sheet.eligible).map(sheet=><p key={sheet.name}><strong>{sheet.name}</strong>: {sheet.reason}</p>)}</details>}</>}
  </section>)}</div>
  {current&&<div className="workbook-options"><label>Current-month sheet<select aria-label="Current-month sheet" value={currentSheet} disabled={busy} onChange={e=>{onActivate();setCurrentSheet(e.target.value);}}>{eligible.map(sheet=><option key={sheet.name} value={sheet.name}>{sheet.name} · {sheet.rows} activities in sheet</option>)}</select></label>
   {blogs.length>0&&<fieldset className="history-sheets blog-sheets"><legend>Blog activities appended automatically</legend>{blogs.map(sheet=><div key={sheet.name}><strong>{sheet.name}</strong><p>{sheet.monthCounts?.[month]??0} activities for {month}</p>{!!sheet.undatedRows&&<p className="baseline-notice">{sheet.undatedRows} rows have no Date or Month and will be excluded. Update Excel and read AvocadoBot again to include them.</p>}</div>)}</fieldset>}
  </div>}
  {history&&<div id="history-selection" className="workbook-rules historical-selection" aria-label="Selected historical input sheets"><strong>Metrics workbook · {bounds}</strong><p>Historical baseline sheet: {matching.map(sheet=>sheet.name).join(', ')||'None found'}</p><p>Use one monthly snapshot to avoid duplicates from overlapping historical sheets. Only rows dated within this inclusive window are included. AvocadoBot’s previous-month sheets are not used for history.</p>{matching.length>1&&<p role="alert" className="error">Multiple sheets match the latest month. Keep one authoritative monthly input sheet before importing.</p>}{matching.length===1&&matching[0].inputMonth!==window[2]?.month&&<p className="baseline-notice">No input sheet found for {window[2]?.label}. Using the latest available snapshot: {matching[0].name}.</p>}{matching.filter(sheet=>!sheet.eligible).map(sheet=><p role="alert" className="error" key={sheet.name}>{sheet.name}: {sheet.reason}. Correct the sheet and read the metrics workbook again.</p>)}{missing.length>0&&<p className="baseline-notice">No dated activities found for {missing.map(target=>target.label).join(', ')} in this snapshot. Only available rows within the window will be processed.</p>}<p>Recorded Current total values remain the previous baselines; missing or invalid values require Phase 4 review.</p></div>}
  <form id="prepare-inputs" className="workbook-prepare" onSubmit={async e=>{e.preventDefault();if(!current||!history)return;const form=new FormData();form.append('sourceToken',current.sourceToken);form.append('historySourceToken',history.sourceToken);form.append('month',month);form.append('currentSheet',currentSheet);if(await onImport(form))setInspections({});}}><button id="prepare-workbooks" className="primary" disabled={busy||!!loading||!current||!history||!month||!currentSheet||!matching.length||blocked} type="submit">Prepare workbook inputs</button><span className="workbook-next">Read both workbooks, then review the calculation contract and start the agents.</span></form>
 </section>;
}
