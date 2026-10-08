import {test,expect} from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';

test('SharePoint sign-in and snapshot flow feeds the workbook preparation UI',async({page,request})=>{
 const workbook=await fs.readFile(path.resolve('../../../target/workbook-e2e.xlsx'));
 const url='https://ibm-my.sharepoint.com/:x:/r/personal/test_ibm_com/Documents/Inputs/Activity%20inputs.xlsx';
 let connected=false,reads=0,prepared=0;const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 // Identity and network download are synthetic. Workbook parsing and run creation use the real server.
 await page.route('**/api/sharepoint/session',async route=>{
  if(route.request().method()==='POST'){expect(route.request().postDataJSON().url).toBe(url);connected=true;}
  await route.fulfill({status:route.request().method()==='POST'?202:200,json:{busy:false,enabled:true,status:connected?'CONNECTED':'NOT_CONNECTED',message:connected?'Signed in. Read SharePoint workbook.':'Connect SharePoint first.'}});
 });
 await page.route('**/api/workbooks/from-sharepoint',async route=>{
  expect(connected).toBe(true);expect(route.request().postDataJSON().url).toBe(url);reads++;
  const response=await request.post('/api/workbooks/inspect',{multipart:{workbook:{name:'Activity inputs.xlsx',mimeType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',buffer:workbook}}});expect(response.status()).toBe(200);
  await route.fulfill({json:{...await response.json(),sourceToken:'synthetic-snapshot',filename:'Activity inputs.xlsx'}});
 });
 await page.route('**/api/runs/from-sharepoint',async route=>{
  expect(route.request().postData()).toContain('synthetic-snapshot');prepared++;
  const response=await request.post('/api/runs/from-workbook',{multipart:{workbook:{name:'Activity inputs.xlsx',mimeType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',buffer:workbook},month:'2026-10',currentSheet:'October inputs',historySheets:'["Previous months"]'}});expect(response.status()).toBe(200);
  await route.fulfill({json:await response.json()});
 });
 await page.goto('/');await page.getByRole('button',{name:'Import from SharePoint',exact:true}).click();
 await page.getByLabel('SharePoint workbook link').fill(url);await page.getByRole('button',{name:'Connect SharePoint',exact:true}).click();
 await expect(page.locator('.sharepoint-status')).toContainText('Signed in');await page.getByRole('button',{name:'Read SharePoint workbook',exact:true}).click();
 await expect(page.getByText('Downloaded snapshot:',{exact:false})).toBeVisible();await page.getByLabel('Reporting month').fill('2026-10');
 await page.getByRole('button',{name:'Prepare workbook inputs'}).click();await expect(page.getByRole('heading',{name:'Prepared workbook inputs'})).toBeVisible();
 expect(reads).toBe(1);expect(prepared).toBe(1);await expect(page.getByRole('link',{name:'Download trailing_90-day.csv ↓',exact:true})).toBeVisible();
 await page.screenshot({path:'../../../target/e2e-sharepoint-prepared.png',fullPage:true});expect(errors).toEqual([]);
});
