import {test,expect} from '@playwright/test';
const progress=()=>({phase:1,total:3,completed:0,collected:0,cached:0,failed:0,state:'CHECKING',resource:'https://dzone.com/articles/first',activity:'Building agent workflows with Quarkus',provider:'DZone',detail:'Checking DZone for a fresh measurement',lastError:'',startedAt:new Date().toISOString(),finishedAt:'',dzoneIncluded:true,backgroundDzone:true});
const fixture=()=>({id:'progress-run',createdAt:'2026-10-09T12:00:00Z',status:'RUNNING_PHASE',phase:1,version:0,currentRows:6,trailingRows:0,trailing:false,review:'',error:'',instructionHash:'fixture',issues:[] as any[],artifacts:{},events:[],approvals:[],collectionProgress:progress()});
async function mock(page:any,initial:any){
 let run=initial;
 await page.route('**/api/**',(route:any)=>route.fulfill({status:404,json:{error:'Synthetic route only'}}));
 await page.route('**/api/runs',(route:any)=>route.fulfill({json:[run]}));
 await page.route('**/api/runs/progress-run',(route:any)=>route.fulfill({json:{run,approvalDigest:'fixture'}}));
 await page.route('**/api/runs/progress-run/events*',(route:any)=>route.fulfill({contentType:'text/event-stream',body:''}));
 await page.addInitScript(()=>localStorage.setItem('impact-run','progress-run'));
 return {setRun:(next:any)=>{run=next;}};
}
test('main-page collection progress updates while checking and shows blocked outcomes explicitly',async({page})=>{
 const run=fixture(),state=await mock(page,run);let popups=0;page.on('popup',()=>popups++);await page.goto('/');
 const panel=page.getByRole('region',{name:'Resource collection progress'});
 await expect(panel.getByRole('heading',{name:'Collecting resource metrics'})).toBeVisible();await expect(panel.getByRole('status')).toHaveText('0 of 3 resource checks completed');await expect(panel.getByRole('progressbar')).toHaveAttribute('max','3');await expect(panel).toContainText('DZone: Building agent workflows with Quarkus');await expect(panel).toContainText('Regular Chrome stays minimized');
 state.setRun({...run,collectionProgress:{...run.collectionProgress,completed:1,collected:1,activity:'Another DZone article',resource:'https://dzone.com/articles/second'}});
 await expect(panel.getByRole('status')).toHaveText('1 of 3 resource checks completed');await expect(panel.getByRole('progressbar')).toHaveAttribute('value','1');await expect(panel).toContainText('Another DZone article');
 const error='DZone background collection blocked (HTTP 403); retry later or enter verified views manually.';
 state.setRun({...run,status:'AWAITING_APPROVAL',version:1,issues:[{rowId:'blocked',activity:'Blocked article',url:'https://dzone.com/articles/blocked',reason:error,blocking:false,metricType:'VIEWS'}],collectionProgress:{...run.collectionProgress,state:'COMPLETE',completed:3,collected:1,cached:1,failed:1,resource:'',activity:'',provider:'',detail:'Resource checks finished; 1 need review',lastError:error,finishedAt:new Date().toISOString()}});
 await expect(panel.getByRole('heading',{name:'Collection checks finished'})).toBeVisible();await expect(panel.getByRole('status')).toHaveText('3 of 3 resource checks completed');await expect(panel).toContainText('HTTP 403');await expect(panel).toContainText('Checks completed includes failed checks');expect(popups).toBe(0);
});
test('reload restores persisted progress and mobile panel fits the page',async({page})=>{
 const run=fixture();run.collectionProgress={...run.collectionProgress,completed:2,collected:1,cached:1};await mock(page,run);await page.setViewportSize({width:390,height:844});await page.goto('/');
 let panel=page.getByRole('region',{name:'Resource collection progress'});await expect(panel.getByRole('status')).toHaveText('2 of 3 resource checks completed');await panel.scrollIntoViewIfNeeded();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.reload();panel=page.getByRole('region',{name:'Resource collection progress'});await expect(panel.getByRole('progressbar')).toHaveAttribute('value','2');await expect(panel).toContainText('DZone: Building agent workflows with Quarkus');
});
test('interrupted checks and explicit visible-browser configuration are labeled honestly',async({page})=>{
 const run=fixture();run.status='FAILED_RETRYABLE';run.collectionProgress={...run.collectionProgress,state:'STOPPED',completed:1,collected:1,backgroundDzone:false,detail:'Collection stopped before all resources were checked',finishedAt:new Date().toISOString()};await mock(page,run);await page.goto('/');
 const panel=page.getByRole('region',{name:'Resource collection progress'});await expect(panel.getByRole('heading',{name:'Collection interrupted'})).toBeVisible();await expect(panel.getByRole('status')).toHaveText('1 of 3 resource checks completed');await expect(panel).toContainText('One browser window is reused for this phase');await expect(panel).not.toContainText('Regular Chrome stays minimized');
});
test('retry button publishes processing and success feedback, then removes the resolved review item',async({page})=>{
 const run:any=fixture();run.status='AWAITING_APPROVAL';run.version=1;
 run.issues=[{rowId:'blocked',activity:'Blocked DZone article',url:'https://dzone.com/articles/blocked',reason:'DZone background collection blocked (HTTP 403)',blocking:false,metricType:'VIEWS'}];
 run.collectionProgress={...progress(),state:'COMPLETE',completed:3,collected:2,failed:1,finishedAt:new Date().toISOString()};
 const state=await mock(page,run);let retries=0;
 await page.route('**/api/runs/progress-run/retry',route=>{
  retries++;const queued={...run,status:'RUNNING_PHASE',collectionProgress:progress()};state.setRun(queued);return route.fulfill({status:202,json:queued});
 });
 await page.goto('/');await page.getByRole('button',{name:'Retry unresolved collection'}).click();
 await expect(page.getByRole('status').filter({hasText:'Retry started.'})).toBeVisible();
 const panel=page.getByRole('region',{name:'Resource collection progress'});await expect(panel.getByRole('heading',{name:'Collecting resource metrics'})).toBeVisible();await expect(page.getByRole('button',{name:'Retry unresolved collection'})).toHaveCount(0);
 state.setRun({...run,status:'AWAITING_APPROVAL',version:2,issues:[],collectionProgress:{...progress(),state:'COMPLETE',completed:3,collected:1,cached:2,finishedAt:new Date().toISOString()}});
 await expect(page.getByRole('status').filter({hasText:'Retry finished. No collection issues remain'})).toBeVisible();await expect(page.getByText('Blocked DZone article',{exact:true})).toHaveCount(0);await expect(page.getByRole('button',{name:'Approve phase 1'})).toBeEnabled();expect(retries).toBe(1);
});
test('still-blocked retry reports the actual failure and leaves another retry available',async({page})=>{
 const run:any=fixture();run.status='AWAITING_APPROVAL';run.version=1;
 run.issues=[{rowId:'blocked',activity:'Blocked article',url:'https://dzone.com/articles/blocked',reason:'DZone blocked (HTTP 403)',blocking:false,metricType:'VIEWS'}];
 run.collectionProgress={...progress(),state:'COMPLETE',completed:3,failed:1,finishedAt:new Date().toISOString()};
 const state=await mock(page,run);
 await page.route('**/api/runs/progress-run/retry',route=>{
  const finished={...run,version:2};state.setRun(finished);return route.fulfill({status:202,json:{...run,status:'RUNNING_PHASE',collectionProgress:null}});
 });
 await page.goto('/');await page.getByRole('button',{name:'Retry unresolved collection'}).click();
 await expect(page.getByRole('status').filter({hasText:'Retry finished. 1 resource request is still unavailable'})).toBeVisible();await expect(page.getByRole('button',{name:'Retry unresolved collection'})).toBeEnabled();await expect(page.getByText('DZone blocked (HTTP 403)',{exact:true})).toBeVisible();
});
