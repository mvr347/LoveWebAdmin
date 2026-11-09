const {test,expect}=require('@playwright/test');
test('cheat bans have a separate review queue and opening a punishment preserves it',async({page})=>{
 await page.goto('/preview/');await page.evaluate(()=>{
  const fetch=window.fetch;window.banReviewCalls=[];window.banReviewRow={id:15,name:'hulinetta',reason:'Читы',actor:'Employee',createdAt:1794220000,status:'ACTIVE',revision:0,verdict:''};
  window.fetch=(path,opts)=>{
   path=String(path);let data;
   if(path.includes('/api/vesuvio/ban-reviews'))data={items:[window.banReviewRow],total:1};
   else if(path.includes('/api/vesuvio/ban-review/15')){const body=JSON.parse(opts.body);window.banReviewCalls.push(body);Object.assign(window.banReviewRow,{verdict:body.verdict,revision:1,reviewReason:body.reason,reviewer:'PreviewOwner',reviewedAt:1794220010});data={saved:true};}
   else if(path.endsWith('/api/bans/15'))data={id:15,targetName:'hulinetta',ruleReason:'Читы',creatorName:'Employee',status:'ACTIVE',createdAt:1794220000,expiresAt:-1,proofUrls:[]};
   else return fetch(path,opts);
   return Promise.resolve(new Response(JSON.stringify({success:true,data}),{status:200,headers:{'Content-Type':'application/json'}}));
  };return window.navigateTo('anticheat');
 });
 await page.locator('#open-ban-reviews').click();const queue=page.locator('.ban-review-list');await expect(queue).toContainText('Бан №15');await expect(queue.locator('.pagination')).toBeHidden();
 await queue.getByRole('button',{name:'Чистый',exact:true}).click();await page.locator('dialog textarea').fill('Сотрудник проверил игровую сессию');await page.locator('dialog button.primary').click();await expect(queue).toContainText('Чистый · подтверждено');expect((await page.evaluate(()=>window.banReviewCalls))[0].verdict).toBe('legit');
 await page.screenshot({path:'test-results/ban-review-queue.png',fullPage:true});
 await queue.getByRole('button',{name:'Открыть наказание'}).click();await expect(page.locator('#ban-details-body')).toContainText('hulinetta');await page.locator('#modal-overlay .close-btn').click();await expect(queue).toBeVisible();await expect(queue).toContainText('Чистый · подтверждено');
});
