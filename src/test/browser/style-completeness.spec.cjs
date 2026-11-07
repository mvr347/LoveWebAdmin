const {test,expect}=require('@playwright/test');
for(const width of [1440,390])test(`shared form controls, tabs and empty lists fit ${width}px`,async({page})=>{
 await page.setViewportSize({width,height:950});await page.goto('/preview/');
 await page.evaluate(()=>window.navigateTo('restrictions'));
 await page.getByRole('button',{name:'Предварительный бан',exact:true}).click();
 const input=page.locator('#restriction-form input[name=player]');await expect(input).toBeVisible();
 expect(await input.getAttribute('type')).toBeNull();
 const style=await input.evaluate(e=>({bg:getComputedStyle(e).backgroundColor,border:getComputedStyle(e).borderTopWidth,color:getComputedStyle(e).color}));
 expect(style.bg).not.toBe('rgb(255, 255, 255)');expect(style.bg).not.toBe('rgba(0, 0, 0, 0)');expect(style.border).toBe('1px');
 await page.locator('#restriction-form [data-modal-close]').click();
 await page.evaluate(()=>window.navigateTo('auth'));
 await expect(page.locator('#auth-subtabs .active')).toHaveCSS('box-shadow','none');
 expect(await page.locator('#auth-subtabs').evaluate(e=>getComputedStyle(e).flexWrap)).toBe('wrap');
 await page.evaluate(()=>window.navigateTo('anticheat'));
 await expect(page.locator('#anticheat-tabs button')).toHaveCount(5);
 for(const button of await page.locator('#anticheat-tabs button').all()){
  const box=await button.boundingBox();expect(box.x).toBeGreaterThanOrEqual(0);expect(box.x+box.width).toBeLessThanOrEqual(width);
 }
 for(const section of ['appeals','support']){
  await page.evaluate(section=>window.navigateTo(section),section);
  await expect(page.locator('.reference-empty-state h3')).toHaveText('Обращений пока нет');
  await expect(page.locator('.reference-case-preview')).toBeHidden();
  await expect(page.locator('.reference-case-pager')).toBeHidden();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 }
 await page.locator('#topbar-theme-btn').click();
 await expect(page.locator('body')).toHaveClass(/theme-light/);
 await expect(page.locator('.reference-empty-state')).toBeVisible();
 await page.screenshot({path:`test-results/style-empty-${width}.png`,fullPage:true});
});
