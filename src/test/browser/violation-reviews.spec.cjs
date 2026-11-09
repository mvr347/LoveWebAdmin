const {test,expect}=require('@playwright/test');
async function setup(page){await page.goto('/preview/');await page.evaluate(()=>{
 const fetch=window.fetch;window.reviewRequests=[];window.reviewRows=[{id:15,revision:0,name:'hulinetta',uuid:'00000000-0000-0000-0000-000000000015',checkName:'Baritone',vl:3.5399105522379104,timestamp:1794220000000,explanation:'Обычное движение вперёд'}];window.mlRows=[{id:'S123',name:'hulinetta',domain:'click',timestamp:1794220000000}];
 window.fetch=(path,opts)=>{
  path=String(path);let data;
  if(path.includes('/api/vesuvio/violations'))data=window.reviewRows;
  else if(path.includes('/api/vesuvio/violation/15/review')){const body=JSON.parse(opts.body);window.reviewRequests.push({path,...body});Object.assign(window.reviewRows[0],body,{reviewer:'PreviewOwner',reviewReason:body.reason,reviewedAt:Date.now(),revision:1});data={saved:true};}
  else if(path.includes('/api/vesuvio/reviews'))data=window.mlRows;
  else if(path.includes('/api/vesuvio/sample/S123/review')){window.reviewRequests.push({path,...JSON.parse(opts.body)});window.mlRows=[];data={saved:true};}
  else return fetch(path,opts);
  return Promise.resolve(new Response(JSON.stringify({success:true,data}),{status:200,headers:{'Content-Type':'application/json'}}));
 };
 return window.navigateTo('anticheat');
});}
test('staff can mark a real event as false positive; dates and VL are readable',async({page})=>{
 await setup(page);const card=page.locator('.violation-card');await expect(card).toContainText('2026');await expect(card).not.toContainText('58826');await expect(card).toContainText('3,54');await expect(card).not.toContainText('Уверенность');
 await card.getByRole('button',{name:'Чистый',exact:true}).click();await page.locator('dialog textarea').fill('Игрок двигается обычным удержанием W');await page.locator('dialog button.primary').click();await expect(card).toContainText('ложное срабатывание');await expect(card).toContainText('PreviewOwner');
 const calls=await page.evaluate(()=>window.reviewRequests);expect(calls).toHaveLength(1);expect(calls[0].verdict).toBe('legit');expect(calls[0].revision).toBe(0);
 await page.screenshot({path:'test-results/violation-review.png',fullPage:true});
});
test('ML review is available inside the stream without a model-management section',async({page})=>{
 await setup(page);await page.locator('#open-learning-reviews').click();const modal=page.locator('.learning-reviews');await expect(modal).toContainText('Клики');await modal.getByRole('button',{name:'Чистый',exact:true}).click();await page.locator('dialog button.primary').click();await expect(modal).toContainText('Образцов для обучения пока нет');expect((await page.evaluate(()=>window.reviewRequests))[0].path).toContain('/sample/S123/review');await expect(page.locator('[data-tab=models]')).toHaveCount(0);
});
test('legacy events cannot be assigned invented IDs',async({page})=>{
 await setup(page);await page.evaluate(()=>{delete window.reviewRows[0].id;});await page.locator('[data-tab=wave]').click();await page.locator('[data-tab=stream]').click();await expect(page.locator('.violation-card [data-review=legit]')).toBeDisabled();
});
