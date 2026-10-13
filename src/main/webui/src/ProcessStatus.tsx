import type {NextStep} from './NextAction';

type ProcessRun={status:string;phase:number;currentRows:number;trailingRows:number;issues:{blocking:boolean}[];collectionProgress?:{phase:number;total:number;completed:number}|null;workbook?:{month?:string}};
const phases=['Digital collection','In-person attendance','Author / tool attribution','Trailing 90 days','Other activities','Combine exports','Current totals'];

export function ProcessStatus({run,importing,step,chartsReady,allDone,attention}:{run:ProcessRun|null;importing:boolean;step:NextStep;chartsReady:boolean;allDone:boolean;attention:number}){
 const waiting=!!run&&['READY','AWAITING_APPROVAL','AWAITING_PUBLICATION','FAILED_RETRYABLE'].includes(run.status);
 const running=run?.status==='RUNNING_PHASE';
 const collection=run?.collectionProgress;
 const checks=running&&collection?.phase===run.phase&&collection.total>0?collection:null;
 const completed=!run?0:run.status==='COMPLETED'?7:run.status==='AWAITING_APPROVAL'||run.status==='AWAITING_PUBLICATION'||run.status==='PUBLISHING_WORKBOOK'?run.phase:Math.max(0,run.phase-1);
 const progress=Math.min(7,completed+(checks?checks.completed/checks.total:0));
 const title=allDone?'Process done':run?.status==='CANCELLED'?'Process cancelled':run?.status==='CANCELLING'?'Cancelling process':attention>0?'Reporting needs attention':run?.status==='COMPLETED'?chartsReady?'Charts generated — publication pending':'Generating report and charts':run?.status==='PUBLISHING_WORKBOOK'?'Publishing workbook':waiting&&(run.status!=='READY'||step.sectionId==='linkedin-collection')?'Waiting for your review':running?`Phase ${run.phase} of 7 · ${phases[run.phase-1]}`:importing?'Reading and preparing workbooks':run?'Preparing automatic processing':'Prepare your reporting run';
 return <section className={`process-status${waiting||attention>0?' needs-action':''}${allDone?' is-complete':''}`} aria-label="Process status">
  <div className="process-status-heading"><strong role="status" aria-live="polite">{title}</strong><span>{run?.workbook?.month||'Source preparation'}</span></div>
  {run?<progress aria-label="Agent phase progress" value={progress} max={7} aria-valuetext={`${completed} of 7 phases finished${checks?`; ${checks.completed} of ${checks.total} resource checks complete`:''}`}/>:importing?<progress aria-label="Workbook preparation progress"/>:<progress aria-label="Agent phase progress" value={0} max={7}/>}
  <div className="process-status-detail"><span>{checks?`${checks.completed} / ${checks.total} resource checks`:run?`${completed} / 7 agent phases finished`:step.title}</span><span>{allDone?'Results and charts ready':attention>0?`${attention} reporting items need review`:run?.issues.length?`${run.issues.length} review items`:running?'Clean phases advance automatically':step.title}</span></div>
 </section>;
}
