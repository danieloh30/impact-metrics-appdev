import {test,expect,type Page} from '@playwright/test';

const fixture=(id:string,count:number,status='FAILED_RETRYABLE')=>({id,createdAt:'2026-10-09T12:00:00Z',status,phase:1,version:1,currentRows:count,trailingRows:0,trailing:false,review:'',error:status==='FAILED_RETRYABLE'?'Synthetic interruption':'',instructionHash:'fixture',issues:[],artifacts:{'digital.csv':{name:'digital.csv',version:1,phase:1,rows:1,sha256:'fixture'}},events:[],approvals:[]});
async function setup(page:Page,selected?:string,statuses?:string[]){
 let runs=(statuses||['FAILED_RETRYABLE','FAILED_RETRYABLE']).map((status,i)=>fixture(`saved-${i+1}`,i+1,status));
 const deletes:string[]=[],unexpectedWrites:string[]=[];let conflict=false,gone=false,readyReads=0;
 await page.addInitScript(({selected})=>{
  if(selected)localStorage.setItem('impact-run',selected);
  (window as any).streams=[];
  (window as any).EventSource=class{url:string;onmessage:any;onerror:any;constructor(url:string){this.url=url;(window as any).streams.push(this);}close(){}};
 },{selected});
 await page.route('**/api/**',async route=>{
  const request=route.request(),path=new URL(request.url()).pathname,method=request.method();
  if(method==='DELETE'){const id=path.split('/').pop()!;deletes.push(id);expect(new URL(request.url()).searchParams.get('confirm')).toBe('true');if(conflict)return route.fulfill({status:409,json:{error:'Run is currently active; cancel it first'}});runs=runs.filter(run=>run.id!==id);return route.fulfill({status:gone?404:200,json:gone?{error:'Run not found'}:{deletedRun:id}});}
  if(method!=='GET'){unexpectedWrites.push(path);if(path.endsWith('/start')){runs=runs.map(run=>run.id===path.split('/')[3]?{...run,status:'RUNNING_PHASE'}:run);return route.fulfill({json:{}});}return route.fulfill({status:500,json:{error:'Unexpected synthetic write'}});}
  if(path==='/api/runs')return route.fulfill({json:runs});
  if(path==='/api/sharepoint/session')return route.fulfill({json:{status:'CONNECTED',busy:false,message:'Synthetic signed-in session'}});
  if(path==='/api/linkedin/session')return route.fulfill({json:{connecting:false,message:'Not connected'}});
  if(path==='/api/chart-history')return route.fulfill({json:{source:{}}});
  if(path.includes('/preview'))return route.fulfill({json:{headers:['Name of activity'],rows:[{id:'1',cells:{'Name of activity':'Preserved synthetic result'}}],total:1,unfilteredTotal:1}});
  const match=path.match(/^\/api\/runs\/([^/]+)$/);if(match){const run=runs.find(item=>item.id===match[1]);if(run?.status==='READY')readyReads++;return route.fulfill({status:run?200:404,json:run?{run,approvalDigest:'digest'}:{error:'Run not found'}});}
  return route.fulfill({json:{}});
 });
 await page.goto('/');await expect(page.locator('.history')).toHaveCount(runs.length);
 if(selected)await expect(page.locator('.history.active')).toHaveCount(1);
 return {deletes,unexpectedWrites,readyReads:()=>readyReads,ready:(id:string)=>{runs=runs.map(run=>run.id===id?{...run,status:'READY',error:''}:run);},conflict:()=>{conflict=true;},gone:()=>{gone=true;},remove:(id:string)=>{runs=runs.filter(run=>run.id!==id);}};
}
const trash=(page:Page,id:string)=>page.getByRole('button',{name:new RegExp(`^Delete run .*${id}`)});
const dialog=(page:Page)=>page.getByRole('dialog',{name:'Delete this run?'});

test('Keep and Escape preserve runs without sending DELETE',async({page})=>{
 const state=await setup(page);await trash(page,'saved-1').click();await expect(dialog(page)).toContainText('saved-1');await expect(dialog(page).getByRole('button',{name:'Keep this run'})).toBeFocused();await expect(dialog(page)).toContainText('Other runs, published chart history');await dialog(page).getByRole('button',{name:'Keep this run'}).click();await expect(dialog(page)).toHaveCount(0);await trash(page,'saved-1').click();await page.keyboard.press('Escape');await expect(dialog(page)).toHaveCount(0);await expect(page.locator('.history')).toHaveCount(2);expect(state.deletes).toEqual([]);expect(state.unexpectedWrites).toEqual([]);
});
test('Delete targets one saved run without selecting it',async({page})=>{
 const state=await setup(page);await trash(page,'saved-2').click();await expect(dialog(page)).toContainText('saved-2');await dialog(page).getByRole('button',{name:'Delete this run',exact:true}).click();await expect(page.locator('.history')).toHaveCount(1);await expect(trash(page,'saved-1')).toBeEnabled();expect(state.deletes).toEqual(['saved-2']);expect(await page.evaluate(()=>localStorage.getItem('impact-run'))).toBeNull();await expect(page.getByLabel('AvocadoBot SharePoint link')).toBeVisible();
});
test('Deleting selected run clears selection and preserves other saved history',async({page})=>{
 const state=await setup(page,'saved-1');await expect(page.getByRole('region',{name:'digital.csv preview'})).toContainText('Preserved synthetic result');await trash(page,'saved-1').click();await expect(dialog(page).getByRole('link',{name:'Download this run’s CSV bundle ↓'})).toHaveAttribute('href','/api/runs/saved-1/download-all');await dialog(page).getByRole('button',{name:'Delete this run',exact:true}).click();await expect(page.locator('.history')).toHaveCount(1);await expect(page.locator('.history.active')).toHaveCount(0);await expect(page.getByRole('region',{name:'digital.csv preview'})).toHaveCount(0);await expect(page.getByLabel('AvocadoBot SharePoint link')).toBeVisible();expect(await page.evaluate(()=>localStorage.getItem('impact-run'))).toBeNull();expect(state.deletes).toEqual(['saved-1']);
});
test('Deleting another run keeps selected results and local selection',async({page})=>{
 const state=await setup(page,'saved-1');await trash(page,'saved-2').click();await dialog(page).getByRole('button',{name:'Delete this run',exact:true}).click();await expect(page.locator('.history')).toHaveCount(1);await expect(page.locator('.history.active')).toContainText('saved-1');await expect(page.getByRole('region',{name:'digital.csv preview'})).toContainText('Preserved synthetic result');expect(await page.evaluate(()=>localStorage.getItem('impact-run'))).toBe('saved-1');expect(state.deletes).toEqual(['saved-2']);
});
test('Active processing, publication and cancelling runs cannot be deleted',async({page})=>{
 const state=await setup(page,undefined,['RUNNING_PHASE','PUBLISHING_WORKBOOK','CANCELLING']);for(const id of ['saved-1','saved-2','saved-3']){await expect(trash(page,id)).toBeDisabled();await expect(trash(page,id)).toHaveAttribute('title','Wait for this run to finish or cancel it before deleting it');}expect(state.deletes).toEqual([]);
});
test('Stale 409 retains confirmation, history and selected results without starting agents',async({page})=>{
 const state=await setup(page,'saved-1');state.conflict();await trash(page,'saved-1').click();await dialog(page).getByRole('button',{name:'Delete this run',exact:true}).click();await expect(dialog(page)).toBeVisible();await expect(dialog(page).getByRole('alert')).toContainText('Run is currently active');await expect(page.locator('.history')).toHaveCount(2);await expect(page.locator('.history.active')).toContainText('saved-1');expect(await page.evaluate(()=>localStorage.getItem('impact-run'))).toBe('saved-1');expect(state.unexpectedWrites).toEqual([]);await expect(dialog(page).getByRole('button',{name:'Keep this run'})).toBeEnabled();
});
test('Already deleted DELETE 404 is successful local cleanup',async({page})=>{
 const state=await setup(page,'saved-1');state.gone();await trash(page,'saved-1').click();await dialog(page).getByRole('button',{name:'Delete this run',exact:true}).click();await expect(dialog(page)).toHaveCount(0);await expect(page.locator('.history')).toHaveCount(1);await expect(page.locator('.history.active')).toHaveCount(0);expect(await page.evaluate(()=>localStorage.getItem('impact-run'))).toBeNull();await expect(page.locator('.notice')).toContainText('Run deleted');
});
test('Delete confirmation pauses automatic start until the safe Keep action',async({page})=>{
 const state=await setup(page,'saved-1');await trash(page,'saved-2').click();state.ready('saved-1');await page.evaluate(()=>{const stream=(window as any).streams.find((item:any)=>item.url.includes('saved-1'));stream.onmessage({data:JSON.stringify({status:'READY'})});});await expect.poll(state.readyReads).toBeGreaterThan(0);await expect(page.locator('#run-error')).toHaveCount(0);await expect(dialog(page)).toBeVisible();expect(state.unexpectedWrites).toEqual([]);await dialog(page).getByRole('button',{name:'Keep this run'}).click();await expect.poll(()=>state.unexpectedWrites).toEqual(['/api/runs/saved-1/start']);expect(state.deletes).toEqual([]);
});
for(const notification of ['404 refresh','SSE reset'])test(`Remote deletion via ${notification} preserves other saved runs`,async({page})=>{
 const state=await setup(page,'saved-1');state.remove('saved-1');if(notification==='SSE reset')await page.evaluate(()=>{const stream=(window as any).streams.find((item:any)=>item.url.includes('saved-1'));stream.onmessage({data:JSON.stringify({status:'RESET'})});});await expect(page.locator('.history')).toHaveCount(1);await expect(trash(page,'saved-2')).toBeEnabled();await expect(page.locator('.history.active')).toHaveCount(0);await expect(page.locator('.notice')).toContainText('This saved run was deleted');expect(await page.evaluate(()=>localStorage.getItem('impact-run'))).toBeNull();expect(state.deletes).toEqual([]);
});
for(const width of [1280,390])test(`Delete controls and confirmation fit at ${width}px`,async({page})=>{
 await page.setViewportSize({width,height:844});await setup(page);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);for(const row of await page.locator('.history-row').all()){expect(await row.evaluate(el=>{const selection=el.querySelector('.history')!.getBoundingClientRect(),remove=el.querySelector('.delete-run')!.getBoundingClientRect();return selection.right<=remove.left&&remove.width>=32&&remove.height>=32;})).toBe(true);}await trash(page,'saved-1').click();await expect(dialog(page)).toBeVisible();expect(await dialog(page).evaluate(el=>{const rect=el.getBoundingClientRect();return rect.left>=0&&rect.right<=innerWidth;})).toBe(true);await expect(dialog(page).getByRole('button',{name:'Keep this run'})).toBeFocused();
});
