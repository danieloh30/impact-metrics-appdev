import {test,expect} from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';
test('workbook generates both CSV inputs and runs through the existing agents',async({page})=>{
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));await page.goto('/');
 await page.getByLabel('Original Excel workbook').setInputFiles(path.resolve('../../../target/workbook-e2e.xlsx'));
 await page.getByRole('button',{name:'Read workbook',exact:true}).click();
 await expect(page.getByLabel('Reporting month')).toBeVisible();await page.getByLabel('Reporting month').fill('2026-09');await expect(page.getByText('Trailing window: 2026-07-01 – 2026-09-30')).toBeVisible();await page.getByLabel('Reporting month').fill('2026-10');await expect(page.getByLabel('Current-month sheet')).toHaveValue('October inputs');
 await expect(page.getByText('Trailing window: 2026-08-01 – 2026-10-31')).toBeVisible();
 await expect(page.getByRole('checkbox',{name:'Previous months',exact:false})).toBeChecked();
 await page.screenshot({path:'../../../target/e2e-workbook-options.png',fullPage:true});
 await page.getByRole('button',{name:'Prepare workbook inputs'}).click();await expect(page.getByRole('heading',{name:'Prepared workbook inputs'})).toBeVisible();
 await expect(page.locator('.metrics')).toContainText('8');
 for(const name of ['AvocadoBot 2.csv','trailing_90-day.csv']){
  const download=page.waitForEvent('download');await page.getByRole('link',{name:`Download ${name} ↓`,exact:true}).click();const file=await download;
  expect(file.suggestedFilename()).toBe(name);const text=await fs.readFile((await file.path())!,'utf8');
  expect(text).toContain(name.startsWith('Avocado')?'October meeting':'café 日本語');
 }
 await page.getByLabel('I accept these calculation rules for this run.').check();await page.getByRole('button',{name:'Start processing'}).click();
 for(let phase=1;phase<=3;phase++){await expect(page.getByRole('button',{name:`Approve phase ${phase}`})).toBeEnabled();await page.getByRole('button',{name:`Approve phase ${phase}`} ).click();}
 await expect(page.getByRole('button',{name:'Approve phase 4'})).toBeVisible();
 await page.getByLabel('I reviewed the unresolved collection issues and will retain them for follow-up.').check();await page.getByRole('button',{name:'Approve phase 4'}).click();
 await expect(page.locator('.status')).toHaveText('Complete');
 const updated=page.locator('.export-row').filter({has:page.getByText('updated_90-day.csv',{exact:true})});await expect(updated.getByRole('link',{name:'Download updated_90-day.csv'})).toBeVisible();
 await page.reload();await expect(page.getByRole('heading',{name:'Prepared workbook inputs'})).toBeVisible();
 await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.screenshot({path:'../../../target/e2e-workbook-mobile.png',fullPage:true});expect(errors).toEqual([]);
});

test('Raw workbook defaults to current and previous inputs without selecting its Bob export for history',async({page})=>{
 await page.route('**/api/workbooks/inspect',route=>route.fulfill({json:{
  sheets:[{name:'Bob csv',eligible:true,rows:29,months:['2026-07','2026-08'],reason:''},{name:'Current month inputs',eligible:true,rows:11,months:['2026-10'],reason:''},{name:'Previous months inputs',eligible:true,rows:402,months:['2026-09'],reason:''}],
  suggestedMonth:'2026-10',suggestedCurrentSheet:'Current month inputs',suggestedHistorySheets:['Previous months inputs']
 }}));
 await page.goto('/');await page.getByLabel('Original Excel workbook').setInputFiles({name:'Raw inputs.xlsx',mimeType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',buffer:Buffer.from('synthetic UI fixture')});
 await page.getByRole('button',{name:'Read workbook',exact:true}).click();
 await expect(page.getByLabel('Current-month sheet')).toHaveValue('Current month inputs');
 await expect(page.getByRole('option',{name:'Current month inputs · 11 activities in sheet'})).toHaveCount(1);
 await expect(page.getByRole('checkbox',{name:/Previous months inputs/})).toBeChecked();
 await expect(page.getByRole('checkbox',{name:/Bob csv/})).not.toBeChecked();
 await expect(page.getByRole('checkbox',{name:/Current month inputs/})).not.toBeChecked();
});

test('Blog supplements show reporting-month counts and undated exclusions without joining history',async({page})=>{
 const errors:string[]=[];page.on('pageerror',error=>errors.push(error.message));
 await page.route('**/api/workbooks/inspect',route=>route.fulfill({json:{
  sheets:[{name:'Current month inputs',eligible:true,rows:11,months:['2026-10'],reason:''},{name:'Previous months inputs',eligible:true,rows:402,months:['2026-09'],reason:''},
   {name:'Markus blog data',eligible:true,blog:true,rows:288,months:['2026-09'],monthCounts:{'2026-09':11},undatedRows:0,reason:''},
   {name:'Elder blog data',eligible:true,blog:true,rows:132,months:['2026-09'],monthCounts:{'2026-09':23},undatedRows:14,reason:''}],
  suggestedMonth:'2026-10',suggestedCurrentSheet:'Current month inputs',suggestedHistorySheets:['Previous months inputs']
 }}));
 await page.goto('/');await page.getByLabel('Original Excel workbook').setInputFiles({name:'Blogs.xlsx',mimeType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',buffer:Buffer.from('synthetic UI fixture')});await page.getByRole('button',{name:'Read workbook',exact:true}).click();
 await page.getByLabel('Reporting month').fill('2026-09');const blogs=page.locator('.blog-sheets');
 await expect(blogs).toContainText('11 activities for 2026-09');await expect(blogs).toContainText('23 activities for 2026-09');await expect(blogs).toContainText('matched using Month');await expect(blogs).toContainText('14 rows have no Date or Month and will be excluded');
 await expect(page.getByRole('option',{name:/Markus blog/})).toHaveCount(0);await expect(page.getByRole('checkbox',{name:/Elder blog/})).toHaveCount(0);
 await expect(page.getByRole('checkbox',{name:/Previous months inputs/})).toBeChecked();
 await page.getByLabel('Reporting month').fill('2026-10');await expect(blogs.getByText('0 activities for 2026-10',{exact:false})).toHaveCount(2);
 await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);expect(errors).toEqual([]);
});
