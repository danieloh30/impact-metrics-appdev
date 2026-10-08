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
