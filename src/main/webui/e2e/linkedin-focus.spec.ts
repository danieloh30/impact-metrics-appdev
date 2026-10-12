import {test,expect,Page} from '@playwright/test';
const linkedIssue={rowId:'c1',activity:'Terraform Live',url:'https://www.linkedin.com/events/123/',reason:'LinkedIn login required',blocking:false,metricType:'VIDEO_VIEWS'};
async function mock(page:Page,phase:number,issues:any[]){
 let session={message:'Not connected',connecting:false},connects=0,retries=0;
 let run={id:'linkedin-run',createdAt:'2026-10-11T12:00:00Z',status:'AWAITING_APPROVAL',phase,version:1,currentRows:1,trailingRows:1,trailing:true,review:'Review collection',error:'',issues,artifacts:{},events:[],approvals:[]};
 await page.addInitScript(()=>localStorage.setItem('impact-run','linkedin-run'));
 await page.route('**/api/**',route=>{const path=new URL(route.request().url()).pathname;let body:unknown={};
  if(path==='/api/runs')body=[run];else if(path==='/api/runs/linkedin-run')body={run,approvalDigest:'fixture'};
  else if(path==='/api/linkedin/session'){if(route.request().method()==='POST'){connects++;session={message:'Sign in using the LinkedIn browser window',connecting:true};}body=session;}
  else if(path==='/api/runs/linkedin-run/retry'){retries++;run={...run,version:2,issues:[]};body=run;}
  else if(path.endsWith('/events'))return route.fulfill({contentType:'text/event-stream',body:''});
  return route.fulfill({json:body});
 });
 return {connected:()=>{session={message:'Connected. Retry the pending phase to collect fresh LinkedIn metrics.',connecting:false};},counts:()=>({connects,retries})};
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
