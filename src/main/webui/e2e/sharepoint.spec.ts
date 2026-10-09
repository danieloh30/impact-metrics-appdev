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

test('SharePoint read waits for complete sign-in and disables itself when access expires',async({page})=>{
 const url='https://ibm-my.sharepoint.com/:x:/r/personal/test_ibm_com/Documents/Inputs/Activity%20inputs.xlsx';
 let status='NOT_CONNECTED',busy=false,reads=0;
 await page.route('**/api/sharepoint/session',async route=>{
  if(route.request().method()==='POST'){status='CONNECTING';busy=true;}
  await route.fulfill({status:route.request().method()==='POST'?202:200,json:{busy,enabled:true,status,message:status==='CONNECTED'?'Signed in.':status==='CONNECTING'?'Sign in in the separate Chrome window.':'Reconnect SharePoint.'}});
 });
 await page.route('**/api/workbooks/from-sharepoint',async route=>{
  reads++;status='NOT_CONNECTED';
  await route.fulfill({status:401,json:{error:'SharePoint access expired. Reconnect.'}});
 });
 await page.goto('/');await page.getByRole('button',{name:'Import from SharePoint',exact:true}).click();
 await page.getByLabel('SharePoint workbook link').fill(url);
 const read=page.getByRole('button',{name:'Read SharePoint workbook',exact:true});
 await expect(read).toBeDisabled();
 await page.getByRole('button',{name:'Connect SharePoint',exact:true}).click();await expect(page.locator('.sharepoint-status')).toContainText('Sign in');await expect(read).toBeDisabled();
 status='CONNECTED';// Authentication is detected before the sign-in browser finishes closing.
 await expect(page.locator('.sharepoint-status')).toContainText('Signed in.');await expect(read).toBeDisabled();
 busy=false;await expect(read).toBeEnabled({timeout:7000});await read.click();
 await expect(page.getByRole('alert')).toContainText('SharePoint access expired');await expect(read).toBeDisabled();
 expect(reads).toBe(1);
});

test('SharePoint download shows actual stages and elapsed time, then clears on success or error',async({page})=>{
 let stage='OPENING',attempt=0,release:((value:unknown)=>void)|undefined;
 await page.route('**/api/sharepoint/session',route=>route.fulfill({json:{busy:false,enabled:true,status:'CONNECTED',downloadStage:stage,message:'Signed in.'}}));
 await page.route('**/api/workbooks/from-sharepoint',async route=>{
  attempt++;await new Promise(resolve=>{release=resolve;});
  if(attempt===1)await route.fulfill({json:{sheets:[{name:'Current inputs',eligible:true,rows:2,months:['2026-10'],reason:''}],suggestedCurrentSheet:'Current inputs',suggestedMonth:'2026-10',sourceToken:'synthetic-progress',filename:'Activity inputs.xlsx'}});
  else await route.fulfill({status:403,json:{error:'SharePoint denied workbook download (HTTP 403).'}});
 });
 await page.goto('/');await page.getByRole('button',{name:'Import from SharePoint',exact:true}).click();
 await page.getByLabel('SharePoint workbook link').fill('https://ibm-my.sharepoint.com/:x:/r/personal/test_ibm_com/Documents/Activity.xlsx');
 const read=page.getByRole('button',{name:'Read SharePoint workbook',exact:true});await expect(read).toBeEnabled();await read.click();
 const panel=page.locator('.workbook-progress');const bar=page.getByRole('progressbar',{name:'SharePoint workbook download progress'});
 await expect(panel).toBeVisible();await expect(bar).not.toHaveAttribute('aria-valuenow');await expect(panel).toContainText('Preparing your SharePoint connection');await expect(page.getByRole('button',{name:'Downloading workbook…',exact:true})).toBeDisabled();
 stage='DOWNLOADING';await expect(panel).toContainText('Downloading your workbook from SharePoint');await expect(panel.locator('.workbook-progress-time')).not.toHaveText('0s elapsed');
 await page.screenshot({path:'../../../target/e2e-sharepoint-progress.png',fullPage:true});
 stage='VALIDATING';await expect(panel).toContainText('Checking the downloaded Excel file');stage='INSPECTING';await expect(panel).toContainText('Reading activity sheets');release?.(undefined);
 await expect(panel).toBeHidden();await expect(page.getByLabel('Current-month sheet')).toBeVisible();
 stage='OPENING';await read.click();await expect(panel).toBeVisible();await expect.poll(()=>attempt).toBe(2);release?.(undefined);
 await expect(panel).toBeHidden();await expect(page.getByRole('alert')).toContainText('HTTP 403');await expect(read).toBeEnabled();
});

test('SharePoint workbook link starts prefilled and remains editable',async({page})=>{
 const defaultUrl="https://ibm-my.sharepoint.com/:x:/r/personal/alexandra_freeman_ibm_com/Documents/Developer%20Relations/Really%20useful%20numbers%20(RUN)/Inputs/AvocadoBot%202.0%20-%20Runtimes%20DA%20impact%20inputs.xlsx?d=wc4b8fb9134a44856ac3980cc9b39c53f&csf=1&web=1&e=7Ja7w9";
 const edited='https://ibm-my.sharepoint.com/:x:/r/personal/test_ibm_com/Documents/Inputs/Other.xlsx';
 let connectedUrl='';
 await page.route('**/api/sharepoint/session',route=>{if(route.request().method()==='POST')connectedUrl=route.request().postDataJSON().url;return route.fulfill({json:{busy:false,status:'NOT_CONNECTED',message:'Connect SharePoint first.'}});});
 await page.goto('/');
 await expect(page.getByRole('button',{name:'Import from SharePoint',exact:true})).toHaveAttribute('aria-pressed','true');
 await expect(page.getByRole('button',{name:'Upload Excel file',exact:true})).toHaveAttribute('aria-pressed','false');
 await expect(page.getByLabel('Original Excel workbook')).toHaveCount(0);
 const input=page.getByLabel('SharePoint workbook link');await expect(input).toHaveValue(defaultUrl);
 await input.fill(edited);await page.getByRole('button',{name:'Upload Excel file',exact:true}).click();await page.getByRole('button',{name:'Import from SharePoint',exact:true}).click();await expect(input).toHaveValue(edited);
 await page.getByRole('button',{name:'Connect SharePoint',exact:true}).click();await expect.poll(()=>connectedUrl).toBe(edited);
});

test('Default SharePoint tab restores saved connection readiness without starting sign-in',async({page})=>{
 let statusChecks=0,signIns=0;
 await page.route('**/api/sharepoint/session',route=>{
  if(route.request().method()==='POST')signIns++;else statusChecks++;
  return route.fulfill({json:{busy:false,status:'CONNECTED',message:'Signed in. Read SharePoint workbook.'}});
 });
 await page.goto('/');
 const sharepoint=page.getByRole('button',{name:'Import from SharePoint',exact:true});
 const upload=page.getByRole('button',{name:'Upload Excel file',exact:true});
 await expect(sharepoint).toHaveAttribute('aria-pressed','true');
 await expect(page.getByLabel('SharePoint workbook link')).toBeVisible();
 await expect(page.getByRole('button',{name:'Read SharePoint workbook',exact:true})).toBeEnabled();
 await expect(page.locator('.sharepoint-status')).toContainText('Signed in.');expect(statusChecks).toBeGreaterThan(0);expect(signIns).toBe(0);
 await upload.click();await expect(upload).toHaveAttribute('aria-pressed','true');await expect(sharepoint).toHaveAttribute('aria-pressed','false');
 await expect(page.getByLabel('Original Excel workbook')).toBeVisible();await expect(page.getByLabel('SharePoint workbook link')).toHaveCount(0);
 await sharepoint.click();await expect(sharepoint).toHaveAttribute('aria-pressed','true');await expect(page.getByRole('button',{name:'Read SharePoint workbook',exact:true})).toBeEnabled();expect(signIns).toBe(0);
 await page.reload();await expect(sharepoint).toHaveAttribute('aria-pressed','true');await expect(page.getByRole('button',{name:'Read SharePoint workbook',exact:true})).toBeEnabled();expect(signIns).toBe(0);
 await page.setViewportSize({width:390,height:844});await expect(page.getByLabel('SharePoint workbook link')).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
});
