import {test,expect} from '@playwright/test';
test('CSV and runtime seed import controls are absent; CSV export format remains advertised',async({page})=>{
 await page.route('**/api/runs',route=>route.fulfill({json:[]}));await page.route('**/api/sharepoint/session',route=>route.fulfill({json:{busy:false,status:'NOT_CONNECTED',message:'Connect SharePoint.'}}));await page.goto('/');
 await expect(page.getByText('Import existing CSV files instead')).toHaveCount(0);await expect(page.getByRole('button',{name:'Load runtimes inputs'})).toHaveCount(0);await expect(page.locator('input[type=file]')).toHaveCount(0);await expect(page.getByText('Preview your results and download individual CSV files or the complete ZIP.')).toBeVisible();
});
