import {test,expect,Page} from '@playwright/test';
function fixture(phase=2,rows=30){
 const version=phase===1?1:3;
 const artifact=(name:string,count:number)=>({name,version,phase,rows:count,sha256:name+'-hash'});
 return {id:'attendance-run',createdAt:'2026-10-09T12:00:00Z',status:'AWAITING_APPROVAL',phase,version,currentRows:rows+1,trailingRows:0,trailing:false,review:`In-person attendance: 3 artifacts; 1 review item. Review the phase results before continuing.`,error:'',instructionHash:'fixture',issues:[{rowId:'c1',activity:'Meeting follow-up',url:'',reason:'Confirm supplied attendance',blocking:false,metricType:'ATTENDANCE'}] as any[],artifacts:{'digital.csv':artifact('digital.csv',1),...(phase===2?{'in-person.csv':artifact('in-person.csv',rows),'follow-up.csv':artifact('follow-up.csv',0)}:{})} as Record<string,any>,events:[],approvals:[] as {phase:number;version:number}[]};
}
async function mock(page:Page,initial:ReturnType<typeof fixture>){
 let run=initial;const approvals:any[]=[];const previews:string[]=[];
 await page.route('**/api/runs',route=>route.fulfill({json:[run]}));
 await page.route('**/api/runs/attendance-run',route=>route.fulfill({json:{run,approvalDigest:'phase-digest'}}));
 await page.route('**/api/runs/attendance-run/events*',route=>route.fulfill({contentType:'text/event-stream',body:''}));
 await page.route('**/api/runs/attendance-run/artifacts/*/preview?**',route=>{
  const url=new URL(route.request().url()),name=decodeURIComponent(url.pathname.split('/').at(-2)!);previews.push(name);
  const historical=Number(url.searchParams.get('version'))<run.version;
  const count=historical?({'digital.csv':2,'in-person.csv':1,'follow-up.csv':1} as Record<string,number>)[name]??0:run.artifacts[name]?.rows??0,offset=Number(url.searchParams.get('offset')),limit=Number(url.searchParams.get('limit'));
  const rows=Array.from({length:count},(_,i)=>({id:'Current month inputs:'+(i+2),cells:{'Name of activity':name==='in-person.csv'?`Meeting ${i+1}`:'Digital activity','Views / attendance':'25','Duration (in hours)':'2','Attendance hours':name==='in-person.csv'?'50':''}}));
  return route.fulfill({json:{headers:['Name of activity','Views / attendance','Duration (in hours)','Attendance hours'],rows:rows.slice(offset,offset+limit),total:count}});
 });
 await page.route('**/api/runs/attendance-run/phases/*/approve',route=>{
  approvals.push(route.request().postDataJSON());run={...run,phase:3,status:'RUNNING_PHASE'};return route.fulfill({status:202,json:run});
 });
 await page.addInitScript(()=>localStorage.setItem('impact-run','attendance-run'));
 return {approvals,previews,setRun:(value:ReturnType<typeof fixture>)=>{run=value;}};
}
test('Phase 2 with manual review opens attendance preview and preserves explicit approval',async({page})=>{
 const state=await mock(page,fixture());await page.goto('/');
 await expect(page.getByRole('heading',{name:'in-person.csv preview',exact:true})).toBeVisible();
 await expect(page.locator('.export-row.selected')).toContainText('in-person.csv');await expect(page.locator('table')).toContainText('Meeting 1');
 const checklist=page.getByLabel('In-person attendance review checklist');
 await expect(checklist).toContainText('Standard participation uses one participation unit per activity, shared in Phase 3; attendance and duration are not multiplied.');
 await expect(checklist).toContainText('Booth duty requires positive attendance and positive hours, and uses actual hours without that minimum.');
 await expect(checklist).toContainText('Booth duty calculates automatically when attendance and hours are both greater than zero');
 await expect(page.getByLabel('Changes from the previous phase')).toContainText('What changed from Phase 1');await expect(page.getByLabel('Changes from the previous phase')).toContainText('Review issues below');
 await expect(checklist).not.toContainText('No Phase 2 validation issues');await expect(checklist).toContainText('25 attendees × 2 hours = 50');await expect(checklist).toContainText('one-hour minimum');await expect(checklist).toContainText('Booth duty');await expect(checklist).toContainText('Phase 3');await expect(checklist).toContainText('missing or duplicate');
 const approve=page.getByRole('button',{name:'Approve phase 2',exact:false});await expect(approve).toBeDisabled();expect(state.approvals).toHaveLength(0);
 await page.reload();await expect(page.getByRole('heading',{name:'in-person.csv preview',exact:true})).toBeVisible();expect(state.approvals).toHaveLength(0);
 await page.setViewportSize({width:390,height:844});await expect(checklist).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
 await page.getByLabel('I reviewed the unresolved collection issues and will retain them for follow-up.').check();await expect(approve).toBeEnabled();
 await approve.click();await expect.poll(()=>state.approvals.length).toBe(1);expect(state.approvals[0]).toEqual({version:3,digest:'phase-digest',acknowledgeIssues:true,automaticProcessing:true});
});
test('Published Phase 2 result replaces Phase 1 preview and pagination but preserves explicit choices on refresh',async({page})=>{
 const initial=fixture(1);initial.artifacts['digital.csv'].rows=30;
 const state=await mock(page,initial);await page.goto('/');await expect(page.getByRole('heading',{name:'digital.csv preview',exact:true})).toBeVisible();
 await page.getByRole('button',{name:'Next →',exact:true}).click();await expect(page.getByText('26–30 of 30 rows',{exact:true})).toBeVisible();
 // Phase 2 begins before its in-person result exists. Selection must wait for publication.
 state.setRun({...initial,phase:2,status:'RUNNING_PHASE'});await expect(page.getByRole('heading',{name:'Review digital collection',exact:true})).toHaveCount(0,{timeout:7000});
 state.setRun(fixture());await expect(page.getByRole('heading',{name:'in-person.csv preview',exact:true})).toBeVisible({timeout:7000});await expect(page.getByText('1–25 of 30 rows',{exact:true})).toBeVisible();
 await page.locator('.export-row').filter({has:page.getByText('digital.csv',{exact:true})}).getByRole('button',{name:'Preview',exact:true}).click();await expect(page.getByRole('heading',{name:'digital.csv preview',exact:true})).toBeVisible();
 const newer=fixture();newer.version=4;for(const artifact of Object.values(newer.artifacts))artifact.version=4;state.setRun(newer);
 await expect(page.getByText('PHASE 2 · VERSION 4',{exact:true})).toBeVisible({timeout:7000});await expect(page.getByRole('heading',{name:'digital.csv preview',exact:true})).toBeVisible();expect(state.approvals).toHaveLength(0);
});
test('Empty attendance preview explains confirmation and blocking issues still prevent approval',async({page})=>{
 const run=fixture(2,0);run.issues=[{rowId:'c1',activity:'Invalid attendance',url:'',reason:'Invalid attendance number',blocking:true,metricType:'ATTENDANCE'}];
 const state=await mock(page,run);await page.goto('/');await expect(page.getByRole('heading',{name:'in-person.csv preview',exact:true})).toBeVisible();
 await expect(page.getByLabel('In-person attendance review checklist')).toContainText('No in-person activities were found');await expect(page.getByLabel('In-person attendance review checklist')).not.toContainText('No Phase 2 validation issues');
 await expect(page.locator('.phase-changes-status.is-clear')).toHaveCount(0);await expect(page.locator('.phase-changes-status.is-attention')).toHaveText('Review issues below');await expect(page.locator('.phase-changes-status svg')).toHaveCount(0);
 const approve=page.getByRole('button',{name:'Approve phase 2',exact:false});await expect(approve).toBeDisabled();await page.getByLabel('I reviewed the unresolved collection issues and will retain them for follow-up.').check();await expect(approve).toBeDisabled();expect(state.approvals).toHaveLength(0);
});

test('Phase 3 highlight compares rows with the approved Phase 2 version and preserves the gate',async({page})=>{
 const run=fixture();run.phase=3;run.version=4;run.approvals=[{phase:2,version:2}];
 for(const artifact of Object.values(run.artifacts)){artifact.phase=3;artifact.version=4;artifact.rows=4;}
 const state=await mock(page,run);await page.goto('/');
 const changes=page.getByLabel('Changes from the previous phase');
 await expect(changes).toContainText('What changed from Phase 2');await expect(changes).toContainText('Compared with approved Phase 2 · version 2');
 await expect(changes).toContainText('author × tool');await expect(changes).toContainText('2 → 4 rows');await expect(changes).toContainText('1 → 4 rows');await expect(changes).toContainText('conserving activity totals');
 await expect(page.getByRole('heading',{name:'digital.csv preview',exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'Approve phase 3',exact:false})).toBeDisabled();expect(state.approvals).toHaveLength(0);
 await page.locator('.review-panel').screenshot({path:'../../../target/e2e-phase-changes.png'});
});
test('Phase 4 highlight explains historical updates and selects their preview',async({page})=>{
 const run=fixture();run.phase=4;run.version=5;run.trailing=true;run.trailingRows=2;run.approvals=[{phase:3,version:4}];run.artifacts['updated_90-day.csv']={name:'updated_90-day.csv',version:5,phase:4,rows:2,sha256:'history-hash'};
 const state=await mock(page,run);await page.goto('/');const changes=page.getByLabel('Changes from the previous phase');
 await expect(changes).toContainText('What changed from Phase 3');await expect(changes).toContainText('Current-month results are unchanged');await expect(changes).toContainText('2 rows');await expect(changes).toContainText('retained or edited inputs');
 await expect(page.getByRole('heading',{name:'updated_90-day.csv preview',exact:true})).toBeVisible();expect(state.approvals).toHaveLength(0);
});
