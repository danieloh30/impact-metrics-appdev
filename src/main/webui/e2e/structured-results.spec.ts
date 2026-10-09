import {test,expect} from '@playwright/test';
test('structured phase results preview and download CSV only on request',async({page})=>{
 const artifact={name:'digital.csv',version:1,phase:1,rows:1,sha256:'structured-result-hash'};
 const run={id:'structured-run',createdAt:'2026-10-09T12:00:00Z',status:'AWAITING_APPROVAL',phase:1,version:1,currentRows:1,trailingRows:0,trailing:false,review:'Review phase results',error:'',instructionHash:'fixture',issues:[],artifacts:{'digital.csv':artifact},events:[],approvals:[]};
 let downloads=0;
 await page.route('**/api/runs',route=>route.fulfill({json:[run]}));
 await page.route('**/api/runs/structured-run',route=>route.fulfill({json:{run,approvalDigest:'digest'}}));
 await page.route('**/api/runs/structured-run/events*',route=>route.fulfill({contentType:'text/event-stream',body:''}));
 await page.route('**/api/runs/structured-run/artifacts/digital.csv/preview?**',route=>route.fulfill({json:{headers:['Name of activity','Views / attendance'],rows:[{id:'Current month inputs:2',cells:{'Name of activity':'Cells, not CSV; intact','Views / attendance':'1000'}}],total:1}}));
 await page.route('**/api/runs/structured-run/artifacts/digital.csv/download?**',route=>{downloads++;return route.fulfill({contentType:'text/csv; charset=utf-8',headers:{'Content-Disposition':'attachment; filename="digital.csv"'},body:'Name of activity,Views / attendance\r\n"Cells, not CSV; intact",1000\r\n'});});
 await page.addInitScript(()=>localStorage.setItem('impact-run','structured-run'));await page.goto('/');
 await expect(page.getByRole('heading',{name:'Review results & export'})).toBeVisible();await expect(page.getByText('Result datasets available',{exact:true})).toBeVisible();
 await expect(page.getByText('Preview phase results. CSV files are created only when you download them.')).toBeVisible();
 await expect(page.locator('table')).toContainText('Cells, not CSV; intact');expect(downloads).toBe(0);
 const downloaded=page.waitForEvent('download');await page.getByRole('link',{name:'Download digital.csv',exact:true}).click();expect((await downloaded).suggestedFilename()).toBe('digital.csv');expect(downloads).toBe(1);
 await page.setViewportSize({width:390,height:844});await expect(page.getByRole('heading',{name:'Review results & export'})).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
});
