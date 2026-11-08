const {test,expect}=require('@playwright/test');
test('stream is first and cases default to All before New and Working',async({page})=>{
 await page.goto('/preview/?test=cases');await page.evaluate(()=>window.navigateTo('anticheat'));
 await expect(page.locator('#anticheat-tabs button').first()).toHaveAttribute('data-tab','stream');
 for(const section of ['reports','appeals','support']){
  await page.evaluate(section=>window.navigateTo(section),section);
  await expect(page.locator('[data-case-filter]')).toHaveText(['Все','Новые','В работе']);
  await expect(page.locator('[data-case-filter=all]')).toHaveClass(/active/);
 }
});
for(const width of [1440,390])test(`punishments use a selectable list and sanction preview at ${width}px`,async({page})=>{
 await page.setViewportSize({width,height:1000});await page.clock.install();await page.goto('/preview/');
 await page.evaluate(async()=>{for(const player of ['Ada123','Lovelace69'])await LovePreview.model.request('POST','/api/moderation',{action:'mute',player,duration:'1h',reason:'Проверка карточки '+player});await window.navigateTo('restrictions');});
 await page.locator('[data-punishment-kind=mute]').click();await expect(page.locator('[data-punishment-row]')).toHaveCount(2);
 const second=page.locator('[data-punishment-row]').filter({hasText:'Lovelace69'});await second.click();
 await second.evaluate(el=>el.dataset.retained='yes');await page.clock.fastForward(8100);await expect(second).toHaveAttribute('data-retained','yes');await expect(second).toBeFocused();
 await expect(page.locator('[data-punishment-preview]')).toContainText('Lovelace69');await expect(page.locator('[data-punishment-preview]')).toContainText('Сотрудник');await expect(page.locator('[data-punishment-preview]')).toContainText('Срок');
 await expect(page.locator('[data-punishment-preview] [data-remove]')).toBeVisible();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.screenshot({path:`test-results/punishment-cards-${width}.png`,fullPage:true});
 await page.locator('[data-query]').fill('MissingPlayer');await expect(page.locator('[data-punishment-preview]')).toBeHidden();await expect(page.locator('[data-pager]')).toBeHidden();
});
