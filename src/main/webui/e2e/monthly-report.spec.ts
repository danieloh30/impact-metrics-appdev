import {test,expect} from '@playwright/test';

test('month-end pivots support scope, exact totals, detail search and dated confirmation',async({page})=>{
 const run={id:'month-end',createdAt:'2026-10-11T12:00:00Z',status:'COMPLETED',phase:7,version:7,currentRows:2,trailingRows:1,trailing:true,review:'Complete',error:'',instructionHash:'fixture',issues:[],artifacts:{'current.csv':{name:'current.csv',version:7,phase:7,rows:2,sha256:'fixture'}},events:[],approvals:[],workbook:{month:'2026-09',currentSheet:'Current month inputs',historySheets:['September inputs'],windowStart:'2026-07-01',windowEnd:'2026-09-30',missingBaselines:0}};
 const aggregate={name:'Recorded video',currentImpact:'83.25',trailingImpact:'8.25',impact:'91.5',metrics:'183',unit:'Views',rows:2};
 let revision=0;const scopes:string[]=[];let measurement:Record<string,unknown>|undefined;
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url());const path=url.pathname;let body:unknown={};
  if(path==='/api/runs')body=[run];else if(path==='/api/config')body={phases:['Digital collection','Attendance','Attribution','Trailing','Other','Combine','Totals']};
  else if(path==='/api/runs/month-end')body={run,approvalDigest:'fixture'};
  else if(path.endsWith('/reporting/measurements')){measurement=route.request().postDataJSON();revision++;body=run;}
  else if(path.endsWith('/reporting')){const scope=url.searchParams.get('scope')||'combined';scopes.push(scope);body={month:'2026-09',cutoff:'2026-09-30',scope,revision,complete:true,included:2,excluded:1,impact:'91.5',activity:[aggregate],tool:[{...aggregate,name:'IBM Bob'}],bob:[aggregate],warnings:['1 rows are excluded.'],details:{rows:[{id:'missing',cells:{'Name of activity':'Missing dated total',Author:'Daniel Oh',Tool:'IBM Bob',Contribution:'Current','Reporting status':'Excluded','Reporting reason':'Month-end total unavailable','Current total':'166.5'}},{id:'included',cells:{'Name of activity':'Confirmed article',Author:'Daniel Oh',Tool:'IBM Bob',Contribution:'Current','Reporting status':'Included','Exact reporting metric':'166.5','Impact hours':'83.25'}}]}};}
  else if(path.endsWith('/preview'))body={headers:[],rows:[],total:0,unfilteredTotal:0};
  await route.fulfill({json:body});
 });
 await page.goto('/');await page.getByRole('button',{name:/Oct 11.*2 activities/}).click();
 const section=page.getByRole('region',{name:'Month-end pivots and charts'});await expect(section).toContainText('91.5');await expect(section).toContainText('2026-09-30');
 await expect(section.getByRole('link',{name:'Download pivots & charts .xlsx ↓'})).toHaveAttribute('href','/api/runs/month-end/reporting/download?scope=combined&format=xlsx');
 await section.getByRole('button',{name:'Show pivot table'}).first().click();await expect(section).toContainText('Historical growth impact hours');await expect(section).toContainText('83.25');
 await section.getByRole('combobox',{name:'Reporting scope'}).selectOption('trailing');await expect.poll(()=>scopes.includes('trailing')).toBe(true);
 await section.getByRole('searchbox',{name:'Search reporting detail'}).fill('Missing dated');await expect(section.locator('.report-detail tbody tr')).toHaveCount(1);
 await section.getByRole('button',{name:'Confirm month-end total'}).click();const dialog=page.getByRole('dialog',{name:'Confirm month-end total'});await expect(dialog).toContainText('2026-09-30');
 await dialog.getByRole('spinbutton',{name:'Month-end attributed total'}).fill('200');await expect(dialog.getByRole('button',{name:'Save & recalculate report'})).toBeDisabled();
 await dialog.getByRole('checkbox').check();await dialog.getByRole('button',{name:'Save & recalculate report'}).click();await expect(dialog).not.toBeVisible();expect(measurement).toMatchObject({revision:0,contribution:'current',rowId:'missing',value:'200',asOf:'2026-09-30',note:''});
 await page.setViewportSize({width:390,height:844});await expect(section.getByRole('combobox',{name:'Reporting scope'})).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
});
