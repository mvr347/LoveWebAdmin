const {test,expect}=require('@playwright/test');
test('global anticheat stream requests all players without an out-of-scope name',async({page})=>{
 await page.goto('/preview/');
 await page.evaluate(()=>{
  const original=window.fetch;window.streamRequests=[];
  window.fetch=(path,options)=>{
   if(String(path).includes('/api/vesuvio/violations')){
    window.streamRequests.push(String(path));
    return Promise.resolve(new Response(JSON.stringify({success:true,data:[{playerName:'Ada123',checkName:'Movement',vl:3,timestamp:1700000000}]}),{status:200,headers:{'Content-Type':'application/json'}}));
   }return original(path,options);
  };
  return window.navigateTo('anticheat');
 });
 await expect(page.locator('.violation-card').first()).toContainText('Ada123');
 expect(await page.evaluate(()=>window.streamRequests)).toEqual(['/api/vesuvio/violations?limit=50']);
 await expect(page.locator('#content-area')).not.toContainText('playerName is not defined');
});
test('login background uses the red theme rather than the old purple gradient',async({page})=>{
 await page.goto('/preview/');await page.evaluate(()=>LovePreview.model.scenario('login'));await page.reload();
 await expect(page.locator('#login-user')).toBeVisible();
 const background=await page.locator('.auth-screen').evaluate(e=>getComputedStyle(e).backgroundImage);
 expect(background).not.toContain('25, 16, 54');expect(background).not.toContain('25, 16, 54');
});
