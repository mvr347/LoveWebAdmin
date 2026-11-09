const { test, expect } = require('@playwright/test');
async function setup(page) {
 await page.goto('/preview/');
 await page.evaluate(() => {
  const original=window.fetch;window.trainingPosts=[];
  window.trainingRows=[1,2].map(id=>({id,uuid:'same-player',name:'Ada123',session:'same-session',client:'V_26_2',domain:'click',label:0,captured:Date.now()-id*15000,source:'TRUSTED',state:'pending',reviewer:'Auto',revision:0,reason:'',reference:''}));
  window.fetch=(path,options)=>{
   const url=String(path);let data;
   if(url.endsWith('/api/vesuvio/training/windows'))data=window.trainingRows;
   else if(url.includes('/api/vesuvio/training/')){
    const body=JSON.parse(options.body);window.trainingPosts.push({url,...body});
    if(body.ids)window.trainingRows.forEach(row=>{if(body.ids.includes(row.id))row.state='approved';});
    else Object.assign(window.trainingRows.find(row=>url.includes(`/${row.id}/`)),{state:body.verdict==='pending'?'pending':'approved',revision:1,reason:body.reason});
    data={saved:true};
   }else return original(path,options);
   return Promise.resolve(new Response(JSON.stringify({success:true,data}),{status:200,headers:{'Content-Type':'application/json'}}));
  };
  return window.navigateTo('anticheat');
 });
 await page.locator('#open-training-windows').click();
 await expect(page.locator('.training-windows [data-list]')).toContainText('Ada123');
}
test('verified gameplay fragment submits selected windows with an explanation',async({page})=>{
 await setup(page);const host=page.locator('.training-windows');
 await host.locator('[data-select="1"]').check();await host.locator('[data-select="2"]').check();
 await host.locator('[data-verify]').click();await page.locator('dialog textarea').fill('Проверена запись игры за выбранный фрагмент');await page.locator('dialog button.primary').click();
 await expect(host.locator('[data-list]')).toContainText('Фрагментов пока нет');
 const posts=await page.evaluate(()=>window.trainingPosts);expect(posts).toHaveLength(1);expect(posts[0].ids).toEqual([1,2]);expect(posts[0].reason).toContain('Проверена запись');
 await host.locator('[data-state]').selectOption('approved');await expect(host.locator('[data-list]')).toContainText('Подтверждено');
 await expect(host.locator('[data-pages]')).toBeHidden();
 await page.screenshot({path:'test-results/training-windows.png',fullPage:true});
});
test('mixed client sessions cannot be verified as one clean fragment',async({page})=>{
 await setup(page);await page.evaluate(()=>window.trainingRows[1].session='different-session');
 await page.locator('.modal-header [data-modal-close]').last().click();await page.locator('#open-training-windows').click();
 const host=page.locator('.training-windows');await host.locator('[data-state]').selectOption('');
 await host.locator('[data-select="1"]').check();await host.locator('[data-select="2"]').check();await host.locator('[data-verify]').click();
 expect(await page.evaluate(()=>window.trainingPosts)).toHaveLength(0);await expect(page.locator('dialog')).toHaveCount(0);
});
test('individual review carries a ban reference and stale-write revision',async({page})=>{
 await setup(page);const host=page.locator('.training-windows');await host.locator('[data-ban]').fill('15');
 await host.locator('[data-id="1"][data-verdict="cheat"]').click();await page.locator('dialog textarea').fill('Сотрудник проверил доказательства');await page.locator('dialog button.primary').click();
 await expect.poll(()=>page.evaluate(()=>window.trainingPosts.length)).toBe(1);
 const post=(await page.evaluate(()=>window.trainingPosts))[0];expect(post.banId).toBe(15);expect(post.revision).toBe(0);expect(post.verdict).toBe('cheat');
});
