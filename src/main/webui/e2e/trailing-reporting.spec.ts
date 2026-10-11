import {test,expect} from '@playwright/test';

test('trailing preview separates growth, retained inputs and unresolved rows with snapshot provenance',async({page})=>{
 const name='updated_90-day.csv';
 const run={id:'reporting-run',createdAt:'2026-10-10T01:00:00Z',status:'AWAITING_APPROVAL',phase:4,version:4,currentRows:1,trailingRows:3,trailing:true,review:'Historical inputs; 0 issues',error:'',instructionHash:'fixture',issues:[],artifacts:{[name]:{name,version:4,phase:4,rows:3,sha256:'fixture'}},events:[],approvals:[]};
 const rows=[
  {id:'growth',cells:{'Name of activity':'Measured article','Views / attendance':'434','Growth classification':'growth','Impact hours':'216.75'}},
  {id:'retained',cells:{'Name of activity':'Retained blog','Views / attendance':'9533','Growth classification':'retained','Impact hours':''}},
  {id:'unresolved',cells:{'Name of activity':'Unavailable article','Views / attendance':'','Growth classification':'unresolved','Impact hours':''}}
 ];
 const reporting={growthRows:1,retainedRows:1,unresolvedRows:1,overlapRows:1,currentAvailable:true,missingWeightRows:0,exactGrowth:'433.5',impactHours:'216.75',firstMeasuredAt:'2026-10-11T00:00:00Z',lastMeasuredAt:'2026-10-11T00:01:00Z',snapshotImportedAt:run.createdAt,baseline:{historyFilename:'AppDev Advocacy impact metrics 2026.xlsx',historySheets:['September inputs'],historySha256:'frozen-baseline-sha',windowStart:'2026-07-01',windowEnd:'2026-09-30'}};
 const requests:string[]=[];
 await page.route('**/api/runs',route=>route.fulfill({json:[run]}));
 await page.route('**/api/runs/reporting-run',route=>route.fulfill({json:{run,approvalDigest:'digest'}}));
 await page.route('**/api/runs/reporting-run/events*',route=>route.fulfill({contentType:'text/event-stream',body:''}));
 await page.route('**/api/runs/reporting-run/artifacts/*/preview?**',route=>{
  const params=new URL(route.request().url()).searchParams,basis=params.get('basis')||'all',q=(params.get('q')||'').toLowerCase();requests.push(basis);
  const matches=rows.filter(row=>(basis==='all'||row.cells['Growth classification']===basis)&&Object.values(row.cells).some(cell=>cell.toLowerCase().includes(q)));
  return route.fulfill({json:{headers:['Name of activity','Views / attendance','Growth classification','Impact hours'],rows:matches,total:matches.length,unfilteredTotal:rows.length,reporting}});
 });
 await page.addInitScript(()=>localStorage.setItem('impact-run','reporting-run'));await page.goto('/');
 const preview=page.getByRole('region',{name:`${name} preview`}),summary=preview.getByLabel('Trailing metrics interpretation');
 await expect(preview.getByRole('status')).toHaveText('1–3 of 3 rows');
 await expect(summary).toContainText('1 measured delta rows');await expect(summary).toContainText('1 retained input rows');await expect(summary).toContainText('1 unresolved rows');
 await expect(summary).toContainText('Exact measured growth: 433.5');await expect(summary).toContainText('Recalculated impact hours: 216.75');
 await expect(summary).toContainText('Retained inputs are excluded');await expect(summary).toContainText('1 trailing rows also match current.csv');await expect(summary).toContainText('Do not sum both files');
 await summary.getByText('Baseline snapshot and measurement times',{exact:true}).click();
 await expect(summary).toContainText('September inputs');await expect(summary).toContainText('frozen-baseline-sha');await expect(summary).toContainText('2026-07-01');await expect(summary).toContainText('2026-09-30');
 await expect(summary).toContainText(run.createdAt);await expect(summary).toContainText(reporting.firstMeasuredAt);await expect(summary).toContainText('may be after the reporting month');
 const filter=preview.getByLabel('Show rows');
 await filter.selectOption('growth');await expect(preview.getByRole('status')).toHaveText('1–1 of 1 matches (3 total rows)');await expect(preview.locator('tbody')).toContainText('Measured article');await expect(preview.locator('tbody')).not.toContainText('Retained blog');
 await filter.selectOption('retained');await expect(preview.locator('tbody')).toContainText('Retained blog');await expect(preview.locator('tbody')).not.toContainText('Measured article');
 const search=preview.getByRole('searchbox',{name:'Search preview'});await search.fill('unavailable');await expect(preview.getByRole('status')).toHaveText('0–0 of 0 matches (3 total rows)');await expect(summary).toContainText('Exact measured growth: 433.5');
 await filter.selectOption('unresolved');await expect(preview.locator('tbody')).toContainText('Unavailable article');await expect(preview.locator('tbody tr')).toHaveCount(1);
 await preview.getByRole('button',{name:'Clear search'}).click();await filter.selectOption('all');await expect(preview.locator('tbody tr')).toHaveCount(3);
 expect(requests).toEqual(expect.arrayContaining(['all','growth','retained','unresolved']));
 await expect(page.getByRole('link',{name:`Download ${name}`,exact:true})).toHaveAttribute('href','/api/runs/reporting-run/artifacts/updated_90-day.csv/download?version=4');await expect(preview).toContainText('Downloads include the complete result');
 await page.setViewportSize({width:390,height:844});await expect(filter).toBeVisible();await expect(search).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
 reporting.currentAvailable=false;reporting.overlapRows=0;await page.reload();
 await expect(summary).toContainText('Overlap comparison becomes available when current.csv is generated');await expect(summary).not.toContainText('0 trailing rows also match current.csv');
});
