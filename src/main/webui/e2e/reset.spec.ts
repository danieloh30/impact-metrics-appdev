import {test,expect} from '@playwright/test';
test('Reset confirmation is available without any CSV import dependency and cancel preserves history',async({page})=>{
 const run={id:'saved-run',createdAt:'2026-10-09T12:00:00Z',status:'READY',phase:0,currentRows:1};let deletes=0;
 await page.route('**/api/runs',route=>route.fulfill({json:[run]}));await page.route('**/api/runs/reset',route=>{deletes++;return route.fulfill({json:{deletedRuns:1}});});
 await page.route('**/api/sharepoint/session',route=>route.fulfill({json:{busy:false,status:'CONNECTED',message:'Signed in.'}}));
 await page.goto('/');await expect(page.locator('.history')).toHaveCount(1);await page.getByRole('button',{name:'Reset run history',exact:true}).click();
 const dialog=page.getByRole('dialog',{name:'Reset run history?'});await expect(dialog).toBeVisible();await expect(dialog.getByRole('button',{name:'Keep history'})).toBeFocused();await expect(dialog).toContainText('SharePoint/LinkedIn sessions');await page.keyboard.press('Escape');await expect(dialog).toHaveCount(0);expect(deletes).toBe(0);await expect(page.locator('.history')).toHaveCount(1);
 await page.getByRole('button',{name:'Reset run history',exact:true}).click();await dialog.getByRole('button',{name:'Delete all run history'}).click();await expect(page.locator('.notice')).toContainText('Run history has been reset');expect(deletes).toBe(1);await expect(page.locator('.history')).toHaveCount(0);expect(await page.evaluate(()=>localStorage.getItem('impact-run'))).toBeNull();
});
