const { test, expect } = require('@playwright/test');

test('moderation controls use the isolated Preview and suppress repeated submissions',async({page})=>{
  const submissions=[];
  page.on('request',request=>{if(request.method()==='POST'&&new URL(request.url()).pathname.endsWith('/moderation'))submissions.push(request.url());});
  await page.goto('/preview/');
  await expect(page.locator('#app')).toContainText('PreviewOwner');
  await page.evaluate(()=>{
    const model=LovePreview.model,request=model.request.bind(model);window.moderationSubmissions=0;
    model.request=(method,path,...args)=>{if(method==='POST'&&path.includes('/moderation'))window.moderationSubmissions++;return request(method,path,...args);};
    return window.renderPunishmentsView('restrictions');
  });
  await page.getByRole('button',{name:'Мут',exact:true}).click();
  await page.locator('#restriction-form [name=player]').fill('Ada123');
  await page.locator('#restriction-form [name=duration]').fill('1h');
  await page.locator('#restriction-form [name=reason]').fill('Проверка интерфейса');
  await page.locator('#restriction-form').evaluate(form=>{form.requestSubmit();form.requestSubmit();});
  await expect(page.locator('#active-restrictions')).toContainText('Ada123');
  await expect(page.locator('#active-restrictions')).toContainText('Проверка интерфейса');
  expect(await page.evaluate(()=>window.moderationSubmissions)).toBe(1);
  expect(submissions).toHaveLength(0);
  await page.getByRole('button',{name:'Предварительный бан',exact:true}).click();
  await page.locator('#restriction-form [name=player]').fill('Ada123');
  await page.locator('#restriction-form [type=submit]').click();
  await expect(page.locator('#pending-bans')).toContainText('Ada123');
  await page.locator('#pending-bans [data-finalize]').click();
  await expect(page.locator('#pending-ban-form')).toBeVisible();
});
