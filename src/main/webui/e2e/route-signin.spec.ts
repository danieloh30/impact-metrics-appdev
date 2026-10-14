import {test,expect} from '@playwright/test';
for(const width of [1280,390])test(`Route sign-in shows local helper commands and detects completion at ${width}px`,async({page})=>{
 await page.setViewportSize({width,height:1000});let connected=false,signIns=0;
 await page.route('**/api/**',route=>{
  const path=new URL(route.request().url()).pathname;
  if(path==='/api/runs')return route.fulfill({json:[]});
  if(path==='/api/sharepoint/session'){if(route.request().method()==='POST')signIns++;return route.fulfill({json:{busy:false,status:connected?'CONNECTED':'NOT_CONNECTED',message:connected?'SharePoint session verified.':'Use the local sign-in helper.',loginMode:'LOCAL_HELPER'}});}
  if(path==='/api/linkedin/session')return route.fulfill({json:{connecting:false,message:connected?'Connected. Session verified.':'Use the local sign-in helper.',loginMode:'LOCAL_HELPER'}});
  return route.fulfill({json:{}});
 });
 await page.goto('/');
 const sharepoint=page.getByRole('complementary',{name:'Local SharePoint sign-in'}),linkedin=page.getByRole('complementary',{name:'Local LinkedIn sign-in'});
 await expect(sharepoint).toBeVisible();await expect(linkedin).toBeVisible();
 await expect(sharepoint.locator('code').first()).toContainText('--provider sharepoint');await expect(sharepoint.locator('code').first()).toContainText('--url');
 await expect(linkedin.locator('code').first()).toContainText('--provider linkedin');await expect(linkedin.locator('code').first()).not.toContainText('--url');
 await expect(sharepoint).toContainText('no tunnel or remote desktop');
 await expect(page.locator('#connect-sharepoint')).toBeEnabled();await page.locator('#connect-sharepoint').click();expect(signIns).toBe(1);
 await expect(page.locator('.linkedin-progress')).toHaveCount(0);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 connected=true;await expect(sharepoint).toHaveCount(0,{timeout:7000});await expect(linkedin).toHaveCount(0,{timeout:7000});await expect(page.locator('.linkedin-success')).toBeVisible();
});
for(const width of [1280,390])test(`Restored SharePoint session still offers the helper guide and reconnect instructions at ${width}px`,async({page})=>{
 await page.setViewportSize({width,height:1000});
 await page.route('**/api/**',route=>{
  const path=new URL(route.request().url()).pathname;
  if(path==='/api/runs')return route.fulfill({json:[]});
  if(path==='/api/sharepoint/session')return route.fulfill({json:{busy:false,status:'CONNECTED',message:route.request().method()==='POST'?'Use the local sign-in helper on your computer, then check the connection here.':'Saved SharePoint session restored.',loginMode:'LOCAL_HELPER'}});
  if(path==='/api/linkedin/session')return route.fulfill({json:{connecting:false,message:'Connected. Session verified.',loginMode:'LOCAL_HELPER'}});
  return route.fulfill({json:{}});
 });
 await page.goto('/');
 const guide=page.getByRole('link',{name:'Local sign-in helper guide'}),help=page.getByRole('complementary',{name:'Local SharePoint sign-in'});
 await expect(guide).toBeVisible();await expect(guide).toHaveAttribute('href',/deploy\/openshift\/#3-connect-ibm-and-linkedin-from-your-computer$/);await expect(help).toHaveCount(0);
 await page.locator('#connect-sharepoint').click();await expect(help).toBeVisible();await expect(help).toContainText('press Enter');await expect(help.locator('code').first()).toContainText('--provider sharepoint');
 await page.getByRole('button',{name:'Check SharePoint connection',exact:true}).click();await expect(help).toBeVisible();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
