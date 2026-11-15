const {test,expect}=require('@playwright/test');
async function open(page){
  await page.goto('/preview/');await expect(page.locator('#app')).toContainText('PreviewOwner');
  await page.evaluate(async()=>{
    const original=window.fetch;
    window.cfWords={bad:[{word:'запрещённое',source:'config'}],safe:Array.from({length:125},(_,i)=>({word:'слово'+i,source:'bundled'})),ad:[{word:'ads.example',source:'game',addedBy:'Admin',addedAt:1700000000}]};
    window.fetch=async(input,options={})=>{
      const url=new URL(String(input),location.href);
      if(!url.pathname.includes('/api/chatfilter/'))return original(input,options);
      let data;
      if(url.pathname.endsWith('/status'))data={available:true};
      else if(url.pathname.endsWith('/words')&&(!options.method||options.method==='GET')){
        const kind=url.searchParams.get('kind');
        if(kind==='safe'&&window.cfFail)throw Error('Ошибка чтения словаря');
        if(kind==='safe'&&window.cfDelaySafe)await new Promise(resolve=>window.cfRelease=resolve);
        data=window.cfWords[kind];
      }else if(url.pathname.endsWith('/words')){
        const {kind,word}=JSON.parse(options.body);window.cfWords[kind].push({word,source:'game',addedBy:'PreviewOwner',addedAt:1700000000});data={word};
      }else if(url.pathname.endsWith('/words/remove')){
        const {kind,word}=JSON.parse(options.body);window.cfWords[kind]=window.cfWords[kind].filter(r=>r.word!==word);data={removed:true};
      }
      return new Response(JSON.stringify({success:true,data}),{status:200,headers:{'Content-Type':'application/json'}});
    };
    const {renderChatFilterView}=await import('/preview/js/features/chatfilter.js');await renderChatFilterView();
  });
  await expect(page.locator('#cf-rows')).toContainText('запрещённое');
}
test('safe dictionary is visible, searchable, paginated and additions select the saved word',async({page})=>{
  const errors=[];page.on('pageerror',e=>errors.push(e.message));await open(page);
  await page.locator('#cf-kinds [data-kind="safe"]').click();
  await expect(page.locator('#cf-list-status')).toContainText('Всего: 125');
  await expect(page.locator('#cf-rows tr')).toHaveCount(50);
  await expect(page.locator('#cf-rows')).toContainText('Встроенный словарь');
  await page.screenshot({path:test.info().outputPath('safe-words.png')});
  await page.locator('#cf-next').click();await expect(page.locator('#cf-page')).toHaveText('Страница 2 из 3');
  await page.locator('#cf-filter').fill('слово124');await expect(page.locator('#cf-rows tr')).toHaveCount(1);
  await page.locator('#cf-add input').fill('жестко');await page.locator('#cf-add button').click();
  await expect(page.locator('#cf-filter')).toHaveValue('жестко');
  await expect(page.locator('#cf-rows')).toContainText('Из игры и панели');
  await expect(page.locator('#cf-rows')).toContainText('жестко');
  await page.locator('#cf-refresh').click();await expect(page.locator('#cf-rows')).toContainText('жестко');
  await page.locator('#cf-rows [data-remove]').click();await expect(page.locator('#cf-rows')).toContainText('ничего не найдено');
  expect(errors).toEqual([]);
});
test('a failed word load keeps section controls and can be retried',async({page})=>{
  await page.setViewportSize({width:390,height:844});await open(page);
  await page.evaluate(()=>window.cfFail=true);await page.locator('#cf-kinds [data-kind="safe"]').click();
  await expect(page.locator('#cf-list-status')).toContainText('Ошибка чтения словаря');
  await expect(page.locator('#cf-kinds')).toBeVisible();
  await page.evaluate(()=>window.cfFail=false);await page.locator('#cf-refresh').click();
  await expect(page.locator('#cf-list-status')).toContainText('Всего: 125');
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBeTruthy();
});
test('a late safe-list response cannot replace the selected advertising list',async({page})=>{
  await open(page);await page.evaluate(()=>window.cfDelaySafe=true);
  await page.locator('#cf-kinds [data-kind="safe"]').click();
  await page.locator('#cf-kinds [data-kind="ad"]').click();await expect(page.locator('#cf-rows')).toContainText('ads.example');
  await page.evaluate(()=>window.cfRelease());
  await expect(page.locator('#cf-kinds [data-kind="ad"]')).toHaveClass(/active/);
  await expect(page.locator('#cf-rows')).toContainText('ads.example');
});
