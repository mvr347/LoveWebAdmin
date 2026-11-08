const {test,expect}=require('@playwright/test');
async function intercept(page){await page.evaluate(()=>{
 const fetch=window.fetch;window.factorCalls=[];
 window.fetch=(path,options)=>{
  if(String(path).includes('/api/me/backup-codes/regenerate')){
   const code=new Headers(options?.headers).get('X-LWA-2FA');window.factorCalls.push(code);
   return Promise.resolve(new Response(JSON.stringify(code?{success:true,data:{backupCodes:['TEST-ONLY']}}:{success:false,code:'SECOND_FACTOR_REQUIRED',error:'Подтвердите действие кодом 2FA'}),{status:code?200:403,headers:{'Content-Type':'application/json'}}));
  }return fetch(path,options);
 };
});}
async function start(page){await page.evaluate(async()=>{const {api}=await import('/preview/js/features/ui.js');window.factorTask=api('POST','/api/me/backup-codes/regenerate').then(value=>window.factorResult=value).catch(error=>window.factorError=error.message);});}
test('sensitive action waits for 2FA and retries only after six digits',async({page})=>{
 await page.goto('/preview/');await intercept(page);await start(page);
 const dialog=page.locator('.second-factor-dialog');await expect(dialog).toBeVisible();await expect(dialog.locator('[type=submit]')).toBeDisabled();expect(await page.evaluate(()=>window.factorCalls)).toEqual([null]);
 await dialog.locator('input').fill('123');await expect(dialog.locator('[type=submit]')).toBeDisabled();await dialog.locator('input').fill('123456');await dialog.locator('[type=submit]').click();
 await expect.poll(()=>page.evaluate(()=>window.factorResult?.backupCodes)).toEqual(['TEST-ONLY']);expect(await page.evaluate(()=>window.factorCalls)).toEqual([null,'123456']);
});
test('cancelling the factor or changing accounts never sends the action',async({page})=>{
 await page.goto('/preview/');await intercept(page);await start(page);await page.locator('.second-factor-dialog [data-cancel]').click();await expect.poll(()=>page.evaluate(()=>window.factorError)).toContain('отменено');expect(await page.evaluate(()=>window.factorCalls)).toEqual([null]);
 await start(page);await expect(page.locator('.second-factor-dialog')).toBeVisible();await page.evaluate(async()=>{const {setToken}=await import('/preview/js/features/ui.js');setToken(null);});await expect(page.locator('.second-factor-dialog')).toHaveCount(0);await expect.poll(()=>page.evaluate(()=>window.factorError)).toContain('Учётная запись');expect(await page.evaluate(()=>window.factorCalls)).toEqual([null,null]);
});
