const {test,expect}=require('@playwright/test');
test('real navigation opens separated cases and keeps claim and empty search consistent',async({page})=>{
 await page.goto('/preview/');
 await page.evaluate(()=>{LovePreview.model.scenario('report');return window.navigateTo('reports')});
 await expect(page.locator('.reference-list-item').first()).toBeVisible();
 await page.locator('[data-claim]').click();
 await expect(page.locator('[data-case-filter=working]')).toHaveClass(/active/);
 await expect(page.locator('.reference-case-preview')).toContainText('PreviewOwner');
 await page.locator('.reference-search input').fill('NoSuchPlayer999');
 await expect(page.locator('.reference-preview-actions')).toHaveCount(0);
 await page.locator('.reference-search input').fill('');
 await page.locator('.reference-preview-actions a').click();
 await expect(page.locator('.case-dialog #summary')).toContainText('Ada123');
 await expect(page.locator('.case-dialog')).toHaveCSS('border-radius','0px');
 await page.locator('.case-dialog .close-btn').click();
 await expect(page.locator('[data-case-filter=working]')).toHaveClass(/active/);
});
