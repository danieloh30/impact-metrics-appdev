import {test,expect,Page} from '@playwright/test';
const linkedIssue={rowId:'c1',activity:'Terraform Live',url:'https://www.linkedin.com/events/123/',reason:'LinkedIn login required',blocking:false,metricType:'VIDEO_VIEWS'};
async function mock(page:Page,phase:number,issues:any[],options:{ready?:boolean;connecting?:boolean;publication?:boolean;currentLinks?:number;trailingLinks?:number}={}){
 let session={message:options.connecting?'Sign in using the LinkedIn browser window':'Not connected',connecting:options.connecting===true},connects=0,retries=0;
 let run={id:'linkedin-run',createdAt:'2026-10-11T12:00:00Z',status:options.publication?'AWAITING_PUBLICATION':options.ready?'READY':'AWAITING_APPROVAL',phase:options.ready?0:phase,version:1,currentRows:1,trailingRows:1,trailing:true,review:'Review collection',error:'',issues,artifacts:{},events:[],approvals:[],workbook:{linkedInCurrentRows:options.currentLinks||0,linkedInTrailingRows:options.trailingLinks||0}};
 await page.addInitScript(()=>localStorage.setItem('impact-run','linkedin-run'));
 await page.route('**/api/**',route=>{const path=new URL(route.request().url()).pathname;let body:unknown={};
  if(path==='/api/runs')body=[run];else if(path==='/api/runs/linkedin-run')body={run,approvalDigest:'fixture'};
  else if(path==='/api/linkedin/session'){if(route.request().method()==='POST'){connects++;session={message:'Sign in using the LinkedIn browser window',connecting:true};}body=session;}
  else if(path==='/api/runs/linkedin-run/retry'){retries++;run={...run,version:2,issues:[]};body=run;}
  else if(path.endsWith('/events'))return route.fulfill({contentType:'text/event-stream',body:''});
  return route.fulfill({json:body});
 });
 return {connected:()=>{session={message:'Connected. Retry the pending phase to collect fresh LinkedIn metrics.',connecting:false};},timeout:()=>{session={message:'Sign-in timed out. Connect again or enter measurements manually.',connecting:false};},counts:()=>({connects,retries})};
}
for(const phase of [1,4])test(`Phase ${phase} focuses LinkedIn sign-in then retry without automatic actions`,async({page})=>{
 const state=await mock(page,phase,[linkedIssue]);await page.goto('/');const guide=page.getByRole('navigation',{name:'Next required action'});
 await expect(guide).toContainText('Connect LinkedIn for metric collection');await expect(page.locator('#linkedin-collection')).toHaveClass(/next-action-section/);await expect(page.locator('#connect-linkedin')).toHaveClass(/next-action-control/);
 await guide.getByRole('button',{name:'Go to section'}).click();await expect(page.locator('#connect-linkedin')).toBeFocused();expect(state.counts()).toEqual({connects:0,retries:0});
 await page.locator('#connect-linkedin').click();await expect(guide).toContainText('Complete LinkedIn sign-in');await expect(page.locator('#check-linkedin')).toBeFocused();await expect(page.locator('#connect-linkedin')).toBeDisabled();expect(state.counts().connects).toBe(1);
 state.connected();await expect(guide).toContainText('Retry LinkedIn metric collection',{timeout:7000});await expect(page.locator('#retry-linkedin')).toBeFocused();expect(state.counts().retries).toBe(0);
 await page.locator('#retry-linkedin').click();await expect(guide).toContainText(`approve Phase ${phase}`);expect(state.counts().retries).toBe(1);await expect(page.locator('#linkedin-collection')).not.toHaveClass(/linkedin-attention/);
});
test('Blocking corrections take precedence over optional LinkedIn sign-in',async({page})=>{
 await mock(page,4,[linkedIssue,{rowId:'c2',activity:'Invalid baseline',url:'',reason:'Invalid previous total',blocking:true,metricType:'BASELINE'}]);await page.goto('/');const guide=page.getByRole('navigation',{name:'Next required action'});await expect(guide).toContainText('Correct required data');await expect(page.locator('#issue-action-1')).toHaveClass(/next-action-control/);await expect(page.locator('#linkedin-collection')).not.toHaveClass(/linkedin-attention/);
});
test('LinkedIn guidance permits explicit follow-up without silently acknowledging issues',async({page})=>{
 await mock(page,1,[linkedIssue]);await page.goto('/');const guide=page.getByRole('navigation',{name:'Next required action'});await expect(guide).toContainText('Connect LinkedIn');await guide.getByRole('button',{name:'Review follow-up option'}).click();await expect(page.locator('#acknowledge-issues')).toBeFocused();await expect(page.locator('#acknowledge-issues')).not.toBeChecked();await page.locator('#acknowledge-issues').check();await expect(guide).toContainText('approve Phase 1');await expect(page.locator('#linkedin-collection')).not.toHaveClass(/linkedin-attention/);
});
test('Sign-in before processing automatically finishes without clicking Check connection',async({page})=>{
 const state=await mock(page,0,[],{ready:true});await page.goto('/');await page.locator('#connect-linkedin').click();const section=page.getByRole('region',{name:'LinkedIn metric collection'});await expect(section).toContainText('Waiting for LinkedIn sign-in');await expect(section).toContainText('five minutes');await expect(section.locator('.linkedin-elapsed')).not.toHaveText('0s elapsed',{timeout:4000});state.connected();await expect(section).toContainText('LinkedIn session saved',{timeout:7000});await expect(section.locator('.linkedin-progress')).toHaveCount(0);await expect(page.locator('#connect-linkedin')).toBeEnabled();expect(state.counts()).toEqual({connects:1,retries:0});
});
test('Reload recovers an ongoing LinkedIn sign-in outside collection phases',async({page})=>{
 const state=await mock(page,2,[],{connecting:true});await page.goto('/');await expect(page.locator('.linkedin-progress')).toBeVisible();await page.reload();await expect(page.locator('.linkedin-progress')).toBeVisible();state.connected();await expect(page.locator('.linkedin-success')).toContainText('session saved',{timeout:7000});await expect(page.locator('#connect-linkedin')).toBeEnabled();expect(state.counts()).toEqual({connects:0,retries:0});
});
test('Server sign-in timeout removes waiting indicator and enables reconnect in any phase',async({page})=>{
 const state=await mock(page,2,[],{connecting:true});await page.goto('/');await expect(page.locator('.linkedin-progress')).toBeVisible();state.timeout();await expect(page.getByRole('region',{name:'LinkedIn metric collection'})).toContainText('Sign-in timed out',{timeout:7000});await expect(page.locator('.linkedin-progress')).toHaveCount(0);await expect(page.locator('#connect-linkedin')).toBeEnabled();expect(state.counts().connects).toBe(0);
});
test('A temporary session-check failure is visible and automatic polling recovers',async({page})=>{
 const state=await mock(page,0,[],{ready:true});let failNext=false;await page.route('**/api/linkedin/session',route=>{if(route.request().method()==='POST'){failNext=true;return route.fallback();}if(failNext){failNext=false;return route.fulfill({status:503,json:{error:'Temporary outage'}});}return route.fallback();});await page.goto('/');await page.locator('#connect-linkedin').click();await expect(page.getByRole('region',{name:'LinkedIn metric collection'})).toContainText('Connection status could not be refreshed');state.connected();await expect(page.locator('.linkedin-success')).toContainText('session saved',{timeout:7000});await expect(page.getByText('Connection status could not be refreshed',{exact:false})).toHaveCount(0);expect(state.counts()).toEqual({connects:1,retries:0});
});
test('Current LinkedIn sources are highlighted before Phase 1, after accepting the contract',async({page})=>{
 const state=await mock(page,0,[],{ready:true,currentLinks:2});await page.goto('/');await page.locator('#accept-contract').check();const guide=page.getByRole('navigation',{name:'Next required action'});await expect(guide).toContainText('Connect LinkedIn before Phase 1');await expect(page.locator('#connect-linkedin')).toHaveClass(/next-action-control/);await expect(page.locator('#linkedin-collection')).toContainText('2 current activities');expect(state.counts()).toEqual({connects:0,retries:0});await page.locator('#connect-linkedin').click();state.connected();await expect(guide).toContainText('Start the agents',{timeout:7000});expect(state.counts()).toEqual({connects:1,retries:0});
});
test('Trailing LinkedIn sources get advance guidance before publication starts Phase 4',async({page})=>{
 const state=await mock(page,3,[],{publication:true,trailingLinks:3});await page.goto('/');const guide=page.getByRole('navigation',{name:'Next required action'});await expect(guide).toContainText('Connect LinkedIn before Phase 4');await expect(page.locator('#linkedin-collection')).toContainText('3 trailing activities');await expect(page.locator('#connect-linkedin')).toHaveClass(/next-action-control/);await page.locator('#skip-linkedin-preparation').click();await expect(guide).toContainText('Publish current inputs');await expect(page.locator('#linkedin-collection')).not.toHaveClass(/linkedin-attention/);expect(state.counts()).toEqual({connects:0,retries:0});
});
test('Runs without LinkedIn source activities do not request advance sign-in',async({page})=>{
 const state=await mock(page,0,[],{ready:true});await page.goto('/');await page.locator('#accept-contract').check();await expect(page.getByRole('navigation',{name:'Next required action'})).toContainText('Start the agents');await expect(page.locator('#skip-linkedin-preparation')).toHaveCount(0);expect(state.counts().connects).toBe(0);
});
test('Phase 3 requires LinkedIn setup before approval can advance to trailing collection',async({page})=>{
 const state=await mock(page,3,[],{trailingLinks:2});await page.goto('/');await expect(page.getByRole('navigation',{name:'Next required action'})).toContainText('Connect LinkedIn before Phase 4');await expect(page.locator('#approve-phase')).toBeDisabled();await expect(page.getByRole('list',{name:'Processing phases'})).toContainText('LinkedIn source check');await page.locator('#skip-linkedin-preparation').click();await expect(page.locator('#approve-phase')).toBeEnabled();await expect(page.getByRole('navigation',{name:'Next required action'})).toContainText('approve Phase 3');expect(state.counts()).toEqual({connects:0,retries:0});
});
test('The pre-trailing source check explains zero LinkedIn links without an unnecessary sign-in gate',async({page})=>{
 const state=await mock(page,3,[],{publication:true});await page.goto('/');await expect(page.getByRole('region',{name:'LinkedIn metric collection'})).toContainText('No LinkedIn URLs found in the selected trailing input rows');await expect(page.getByRole('navigation',{name:'Next required action'})).toContainText('Publish current inputs');await expect(page.locator('#skip-linkedin-preparation')).toHaveCount(0);expect(state.counts()).toEqual({connects:0,retries:0});
});
