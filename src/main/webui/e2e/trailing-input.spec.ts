import {test,expect} from '@playwright/test';
test('Trailing review prefills source metrics and supports keep or edit without a fresh total',async({page})=>{
 const issue={rowId:'t1',activity:'AAIF & IBM Open-Source AI Meetup',url:'',reason:'No URL — manual collection required',blocking:false,metricType:'VIEWS'};
 const run={id:'review-run',createdAt:'2026-10-08T12:00:00Z',status:'AWAITING_APPROVAL',phase:4,version:4,currentRows:1,trailingRows:1,trailing:true,review:'Review trailing inputs',error:'',instructionHash:'fixture',issues:[issue],artifacts:{},events:[],approvals:[{phase:1,version:1},{phase:2,version:2},{phase:3,version:3}]};
 const saves:any[]=[];
 await page.route('**/api/runs',route=>route.fulfill({json:[run]}));
 await page.route('**/api/runs/review-run',route=>route.fulfill({json:{run,approvalDigest:'digest'}}));
 await page.route('**/api/runs/review-run/events?**',route=>route.fulfill({status:200,contentType:'text/event-stream',body:''}));
 await page.route('**/api/runs/review-run/trailing-input?**',route=>route.fulfill({json:{originalViews:'30',originalDuration:'2',baseline:'90',views:'30',duration:'2',canRetain:true}}));
 await page.route('**/api/runs/review-run/measurement-input?**',route=>route.fulfill({json:{originalViews:'30',originalDuration:'2',baseline:'90',views:'30',duration:'2',value:'90',canRetain:false,hasDuration:true,attributed:true}}));
 await page.route('**/api/runs/review-run/trailing-inputs',route=>{saves.push(route.request().postDataJSON());return route.fulfill({status:202,json:run});});
 await page.addInitScript(()=>localStorage.setItem('impact-run','review-run'));await page.goto('/');
 await page.getByRole('button',{name:'Enter views',exact:true}).click();let dialog=page.getByRole('dialog',{name:'Review original input'});
 await expect(dialog.getByLabel('Views / attendance',{exact:true})).toHaveValue('30');await expect(dialog.getByLabel('Duration (in hours)',{exact:true})).toHaveValue('2');await expect(dialog).toContainText('Previous total: 90');
 await dialog.getByRole('button',{name:'Keep original values',exact:true}).click();await expect(dialog).toHaveCount(0);expect(saves[0].keepOriginal).toBe(true);
 await page.getByRole('button',{name:'Enter views',exact:true}).click();dialog=page.getByRole('dialog',{name:'Review original input'});await dialog.getByLabel('Views / attendance',{exact:true}).fill('35');await dialog.getByLabel('Duration (in hours)',{exact:true}).fill('1.5');await dialog.getByRole('button',{name:'Save edited inputs & refresh review'}).click();await expect(dialog).toHaveCount(0);expect(saves[1]).toMatchObject({views:'35',duration:'1.5',keepOriginal:false});
 await page.getByRole('button',{name:'Enter views',exact:true}).click();dialog=page.getByRole('dialog',{name:'Review original input'});await dialog.getByRole('button',{name:'Enter a fresh total instead'}).click();await expect(page.getByLabel('Measured total')).toHaveValue('90');await expect(page.getByRole('dialog')).toContainText('Original historical values');
 await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
