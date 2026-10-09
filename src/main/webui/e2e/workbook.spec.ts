import {test,expect} from '@playwright/test';
test('SharePoint workbook fields remain editable and saved connection checks never start sign-in',async({page})=>{
 let signIns=0;await page.route('**/api/runs',route=>route.fulfill({json:[]}));await page.route('**/api/sharepoint/session',route=>{if(route.request().method()==='POST')signIns++;return route.fulfill({json:{busy:false,status:'CONNECTED',message:'Signed in.'}});});
 await page.goto('/');await page.locator('.source-details summary').nth(0).click();await page.locator('.source-details summary').nth(1).click();await expect(page.getByRole('button',{name:'Read AvocadoBot workbook',exact:true})).toBeEnabled();await expect(page.getByRole('button',{name:'Read metrics workbook',exact:true})).toBeEnabled();expect(signIns).toBe(0);
 const field=page.getByLabel('Metrics SharePoint link');const edited='https://ibm.sharepoint.com/sites/DEPT-DeveloperRelations/Shared%20Documents/Other.xlsx';await field.fill(edited);await expect(field).toHaveValue(edited);await page.getByRole('button',{name:'Check SharePoint connection',exact:true}).click();expect(signIns).toBe(0);
});
