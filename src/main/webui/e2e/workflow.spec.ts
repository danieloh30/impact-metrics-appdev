import {test,expect} from '@playwright/test';
test('CSV and runtime seed import controls are absent; CSV and Excel reporting remain advertised',async({page})=>{
 await page.route('**/api/runs',route=>route.fulfill({json:[]}));await page.route('**/api/sharepoint/session',route=>route.fulfill({json:{busy:false,status:'NOT_CONNECTED',message:'Connect SharePoint.'}}));await page.goto('/');
 await expect(page.getByLabel('AvocadoBot SharePoint link')).toHaveValue('https://ibm.sharepoint.com/:x:/r/sites/DEPT-DeveloperRelations/Shared%20Documents/Really%20Useful%20Numbers%20(RUN)/Advocacy/2026/Input%20files/AvocadoBot%202.0%20-%C2%A0AppDev%20DA%20impact%20inputs.xlsx?d=w25cb891e867b4fb39d1b486b41c9ce5b&csf=1&web=1&e=pge2wn');await expect(page.getByRole('region',{name:'Current activities workbook'})).toContainText('AppDev DA impact inputs');await expect(page.getByRole('region',{name:'Current activities workbook'})).not.toContainText('Runtimes DA impact');
 await expect(page.getByText('Import existing CSV files instead')).toHaveCount(0);await expect(page.getByRole('button',{name:'Load runtimes inputs'})).toHaveCount(0);await expect(page.locator('input[type=file]')).toHaveCount(0);
 const exportStep=page.getByRole('article').filter({has:page.getByRole('heading',{name:'Share your impact'})});
 await expect(exportStep).toBeVisible();await expect(exportStep).toContainText('CSV');await expect(exportStep).toContainText('Excel report');
});
