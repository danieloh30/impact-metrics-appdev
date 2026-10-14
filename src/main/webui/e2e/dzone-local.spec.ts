import {test,expect} from '@playwright/test';
const id='00000000-0000-0000-0000-000000000001';
const run={id,createdAt:'2026-10-14T12:00:00Z',status:'AWAITING_APPROVAL',phase:4,version:5,currentRows:0,trailingRows:2,trailing:true,review:'Review collection',error:'',instructionHash:'fixture',issues:[{rowId:'t1',activity:'Public article',url:'https://dzone.com/articles/example',reason:'DZone background collection blocked or unavailable (HTTP 403)',blocking:false,metricType:'VIEWS'},{rowId:'t2',activity:'Public article',url:'https://dzone.com/articles/example',reason:'DZone background collection blocked or unavailable (HTTP 403)',blocking:false,metricType:'VIEWS'}],artifacts:{},events:[],approvals:[]};
for(const width of [1280,390])test(`OpenShift DZone helper is highlighted and has a scoped command at ${width}px`,async({page})=>{
 await page.setViewportSize({width,height:1000});let writes=0;
 await page.route('**/api/**',route=>{
  const path=new URL(route.request().url()).pathname;
  if(route.request().method()==='POST')writes++;
  if(path==='/api/config')return route.fulfill({json:{dzoneLocalCollection:true}});
  if(path==='/api/runs')return route.fulfill({json:[run]});
  if(path===`/api/runs/${id}`)return route.fulfill({json:{run,approvalDigest:'digest'}});
  if(path.endsWith('/events'))return route.fulfill({contentType:'text/event-stream',body:''});
  if(path==='/api/linkedin/session')return route.fulfill({json:{message:'Not connected',loginMode:'LOCAL_HELPER'}});
  if(path==='/api/sharepoint/session')return route.fulfill({json:{status:'CONNECTED',message:'Connected',busy:false,loginMode:'LOCAL_HELPER'}});
  return route.fulfill({json:{}});
 });
 await page.addInitScript(value=>localStorage.setItem('impact-run',value),id);await page.goto('/');
 const guide=page.getByRole('navigation',{name:'Next required action'});await expect(guide).toContainText('Collect DZone views on your computer');
 const button=page.getByRole('button',{name:'Collect DZone views locally (1)',exact:true});await guide.getByRole('button',{name:'Go to section'}).click();await expect(button).toBeFocused();await button.click();
 const help=page.getByRole('complementary',{name:'Local DZone helper instructions'});await expect(help).toBeVisible();await expect(help.locator('pre')).toContainText('node scripts/collect-dzone-local.mjs');await expect(help.locator('pre')).toContainText(`--run '${id}'`);await expect(help).toContainText('type yes');
 expect(writes).toBe(0);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
test('Local dev config hides the DZone helper',async({page})=>{
 await page.route('**/api/**',route=>route.fulfill({json:new URL(route.request().url()).pathname==='/api/runs'?[]:{dzoneLocalCollection:false}}));await page.goto('/');await expect(page.getByRole('region',{name:'DZone local collection'})).toHaveCount(0);
});
