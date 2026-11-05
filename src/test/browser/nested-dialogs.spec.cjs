const {test,expect}=require('@playwright/test');
async function ready(page) {
  await page.goto('/preview/');
  await expect(page.locator('#app')).toContainText('PreviewOwner');
  await page.evaluate(()=>window.openQuickBanModal('Ada123','Читы','Описание нарушения'));
  await expect(page.locator('#ban-target-name')).toHaveValue('Ada123');
  await page.locator('#ban-duration').selectOption('86400');
  await page.locator('#ban-comment-text').fill('Подробности, которые нельзя потерять');
}
for(const mode of ['button','escape','cross','backdrop']) {
  test(`closing empty report picker by ${mode} keeps the ban draft`,async({page})=>{
    if(mode==='button')await page.setViewportSize({width:390,height:844});
    await ready(page);
    await page.locator('#btn-browse-reports-picker').click();
    await expect(page.locator('#report-picker-list')).toContainText('Подходящих открытых жалоб нет');
    await expect(page.locator('.modal-overlay[hidden]')).toHaveCount(1);
    if(mode==='button') await page.locator('#modal-overlay .modal-footer button').click();
    else if(mode==='escape')await page.keyboard.press('Escape');
    else if(mode==='cross')await page.getByRole('button',{name:'Вернуться к бану',exact:true}).first().click();
    else await page.locator('#modal-overlay').click({position:{x:2,y:2}});
    await expect(page.locator('#ban-target-name')).toBeVisible();
    await expect(page.locator('#ban-target-name')).toHaveValue('Ada123');
    await expect(page.locator('#ban-comment-text')).toHaveValue('Подробности, которые нельзя потерять');
    await expect(page.locator('#ban-duration')).toHaveValue('86400');
    await expect(page.locator('#ban-reason-select')).toHaveValue('Читы');
    await expect(page.locator('#btn-browse-reports-picker')).toBeFocused();
    await expect(page.locator('.modal-overlay')).toHaveCount(1);
  });
}
test('cancelling confirmation and a failed ban keep all fields; repeated clicks send one ban',async({page})=>{
  await ready(page);
  await page.locator('#ban-submit-btn').click();
  await page.locator('dialog[open]').getByRole('button',{name:'Отмена',exact:true}).click();
  await expect(page.locator('#ban-submit-btn')).toBeEnabled();
  await page.evaluate(()=>{
    const original=window.fetch;
    window.fetch=(path,options)=>String(path)==='/api/bans'&&options?.method==='POST'
      ? new Promise(resolve=>window.finishFailedBan=()=>resolve(new Response('{"success":false,"error":"Запись отклонена"}',{status:400,headers:{'Content-Type':'application/json'}})))
      : original(path,options);
  });
  await page.locator('#ban-submit-btn').click();
  await page.locator('dialog[open]').getByRole('button',{name:'Выдать бан',exact:true}).click();
  await expect.poll(()=>page.evaluate(()=>typeof window.finishFailedBan)).toBe('function');
  await expect(page.locator('#ban-submit-btn')).toBeDisabled();
  await page.evaluate(()=>window.finishFailedBan());
  await expect(page.locator('dialog[open]')).toContainText('Введённые данные сохранены');
  await page.locator('dialog[open] button').click();
  await expect(page.locator('#ban-comment-text')).toHaveValue('Подробности, которые нельзя потерять');
  await expect(page.locator('#ban-duration')).toHaveValue('86400');
  await expect(page.locator('#ban-submit-btn')).toBeEnabled();
  await page.evaluate(()=>{
    const original=window.fetch;
    window.banCalls=0;
    window.fetch=(path,options)=>{
      if(String(path)==='/api/bans'&&options?.method==='POST'){
        window.banCalls++;
        return Promise.resolve(new Response('{"success":true,"data":{}}',{headers:{'Content-Type':'application/json'}}));
      }
      return original(path,options);
    };
    const button=document.getElementById('ban-submit-btn');button.click();button.click();button.click();
  });
  await expect(page.locator('dialog[open]')).toHaveCount(1);
  await page.locator('dialog[open]').getByRole('button',{name:'Выдать бан',exact:true}).click();
  await expect(page.locator('.modal-overlay')).toHaveCount(0);
  expect(await page.evaluate(()=>window.banCalls)).toBe(1);
});
test('closing a confirmation in other editors preserves their inputs and select Escape closes only the list',async({page})=>{
  await ready(page);
  await page.evaluate(async()=>{
    const {confirmAction}=await import('/preview/js/features/ui.js');
    confirmAction('Проверка','Подтвердите действие',()=>{});
  });
  await page.keyboard.press('Escape');
  await expect(page.locator('#ban-comment-text')).toHaveValue('Подробности, которые нельзя потерять');
  await page.locator('#modal-overlay .ui-select').filter({has:page.locator('#ban-duration')}).locator('button').click();
  await page.keyboard.press('Escape');
  await expect(page.locator('.ui-select-popup')).toHaveCount(0);
  await expect(page.locator('#ban-target-name')).toBeVisible();
});
test('late picker and autocomplete results never affect a replacement form; logout dismisses confirmations',async({page})=>{
  await ready(page);
  await page.evaluate(()=>{
    const original=window.fetch;
    window.fetch=(path,options)=>String(path).includes('/api/reports?')&&String(path).includes('limit=51')
      ?new Promise(resolve=>window.finishReports=()=>resolve(new Response('{"success":true,"data":{"reports":[]}}',{headers:{'Content-Type':'application/json'}})))
      :original(path,options);
  });
  await page.locator('#btn-browse-reports-picker').click();
  await expect.poll(()=>page.evaluate(()=>typeof window.finishReports)).toBe('function');
  await page.keyboard.press('Escape');
  await page.locator('#ban-comment-text').fill('Новые данные');
  await page.evaluate(()=>window.finishReports());
  await expect(page.locator('#report-picker-list')).toHaveCount(0);
  await expect(page.locator('#ban-comment-text')).toHaveValue('Новые данные');
  await page.locator('#ban-submit-btn').click();
  await expect(page.locator('dialog[open]')).toHaveCount(1);
  await page.evaluate(async()=>{const {setToken}=await import('/preview/js/features/ui.js');setToken(null);});
  await expect(page.locator('dialog[open],.modal-overlay')).toHaveCount(0);
});
test('attached report reasons and attributes render as text; changing target detaches the report',async({page})=>{
  await ready(page);
  await page.evaluate(()=>{
    const original=window.fetch;
    window.fetch=(path,options)=>String(path)==='/api/reports/900'
      ?Promise.resolve(new Response(JSON.stringify({success:true,data:{report:{id:900,targetName:'Ada123',reporterName:'Reporter',status:'PENDING',reasons:['<img src=x onerror="window.injected=1">'],description:'Описание'}}}),{headers:{'Content-Type':'application/json'}}))
      :original(path,options);
  });
  await page.locator('#btn-browse-reports-picker').click();
  await page.locator('details summary').filter({hasText:'Найти по номеру'}).click();
  await page.locator('#report-picker-number input').fill('900');
  await page.locator('#report-picker-number button').click();
  await expect(page.locator('#ban-attached-report-box')).toContainText('<img src=x');
  await expect(page.locator('#ban-attached-report-box img')).toHaveCount(0);
  await page.locator('#ban-target-name').fill('OtherPlayer');
  await expect(page.locator('#ban-attached-report-box')).toBeHidden();
  const escaped=await page.evaluate(async()=>{
    const {esc}=await import('/preview/js/features/ui.js');
    const host=document.createElement('div');host.innerHTML='<input value="'+esc('\" autofocus onfocus=alert(1) x=\"')+'">';
    return {value:host.firstChild.value,onfocus:host.firstChild.getAttribute('onfocus')};
  });
  expect(escaped.onfocus).toBeNull();expect(escaped.value).toContain('onfocus=');
});
test('one context request per player replaces downloading all bans and shares the report lookup',async({page})=>{
  await page.goto('/preview/');await expect(page.locator('#app')).toContainText('PreviewOwner');
  await page.evaluate(()=>{
    const original=window.fetch;window.contextRequests=[];
    window.fetch=(path,options)=>{window.contextRequests.push(String(path));return original(path,options);};
    return window.openQuickBanModal('Ada123','Читы','Описание');
  });
  await expect(page.locator('#ban-player-context')).toContainText('Активных блокировок:');
  await page.locator('#ban-target-name').focus();await page.locator('#ban-comment-text').focus();
  const paths=await page.evaluate(()=>window.contextRequests);
  expect(paths.filter(p=>p.includes('/api/bans/all'))).toHaveLength(0);
  expect(paths.filter(p=>p.includes('/api/bans/context?'))).toHaveLength(1);
  expect(paths.filter(p=>p.includes('/api/reports?'))).toHaveLength(1);
});

test('autocomplete ignores stale names and keyboard selection uses the selected player',async({page})=>{
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await ready(page);
  await page.evaluate(()=>{
    const original=window.fetch;
    window.searchFinishes={};
    window.fetch=(path,options)=>String(path).startsWith('/api/players?q=')
      ?new Promise(resolve=>{const name=new URL(path,location.origin).searchParams.get('q');window.searchFinishes[name]=()=>resolve(new Response(JSON.stringify({success:true,data:[{name}]}),{headers:{'Content-Type':'application/json'}}));})
      :original(path,options);
  });
  await page.locator('#ban-target-name').fill('Alpha');
  await expect.poll(()=>page.evaluate(()=>typeof window.searchFinishes.Alpha)).toBe('function');
  await page.locator('#ban-target-name').fill('Beta');
  await expect.poll(()=>page.evaluate(()=>typeof window.searchFinishes.Beta)).toBe('function');
  await page.evaluate(()=>{window.searchFinishes.Beta();window.searchFinishes.Alpha();});
  await expect(page.locator('.autocomplete-dropdown')).toContainText('Beta');
  await expect(page.locator('.autocomplete-dropdown')).not.toContainText('Alpha');
  await page.locator('#ban-target-name').press('ArrowDown');
  await page.keyboard.press('Enter');
  await expect(page.locator('#ban-target-name')).toHaveValue('Beta');
  await expect(page.locator('.autocomplete-dropdown')).not.toHaveClass(/open/);
  expect(errors).toEqual([]);
});

test('closed ban forms release document listeners after repeated opening',async({page})=>{
  await ready(page);
  await page.evaluate(()=>window.closeCurrentModal());
  const session=await page.context().newCDPSession(page);
  await session.send('HeapProfiler.collectGarbage');
  const before=(await session.send('Memory.getDOMCounters')).jsEventListeners;
  await page.evaluate(async()=>{
    for(let i=0;i<30;i++) {await window.openQuickBanModal('Ada123','Читы','Описание');window.closeCurrentModal();}
  });
  await page.waitForTimeout(100);
  await session.send('HeapProfiler.collectGarbage');
  const after=(await session.send('Memory.getDOMCounters')).jsEventListeners;
  expect(after).toBeLessThanOrEqual(before+2);
  await expect(page.locator('.modal-overlay,.ui-select-popup')).toHaveCount(0);
});

test('evidence links containing quotes are data, not inline code, and image returns to details',async({page})=>{
  await ready(page);
  await page.evaluate(()=>{
    const original=window.fetch;
    window.fetch=(path,options)=>String(path)==='/api/bans/901'
      ?Promise.resolve(new Response(JSON.stringify({success:true,data:{id:901,targetName:'Ada123',status:'ACTIVE',ruleReason:'Читы',proofUrls:["https://example.com/');window.injected=1;//"],createdAt:100,expiresAt:200}}),{headers:{'Content-Type':'application/json'}}))
      :original(path,options);
    window.viewBanDetails(901);
  });
  const image=page.locator('#ban-details-body img[data-evidence-src]');
  await expect(image).toBeVisible();
  expect(await image.getAttribute('onclick')).toBeNull();
  await image.click();
  await expect(page.locator('#modal-overlay .evidence-lightbox')).toBeVisible();
  expect(await page.evaluate(()=>window.injected)).toBeUndefined();
  await page.keyboard.press('Escape');
  await expect(page.locator('#ban-details-body')).toBeVisible();
  await expect(page.locator('#ban-details-body')).toContainText('До');
});

test('ban confirmation waits for validating the attached report',async({page})=>{
  await ready(page);
  await page.evaluate(()=>{
    const original=window.fetch;
    window.fetch=(path,options)=>String(path)==='/api/reports/902'
      ?new Promise(resolve=>window.finishAttachedReport=()=>resolve(new Response(JSON.stringify({success:true,data:{report:{id:902,targetName:'Ada123',reporterName:'Reporter',status:'PENDING',reasons:['Читы']}}}),{headers:{'Content-Type':'application/json'}})))
      :original(path,options);
    return window.openQuickBanModal('Ada123','Читы','Описание',902);
  });
  await expect(page.locator('#ban-validation-help')).toContainText('Проверяем выбранную жалобу');
  await expect(page.locator('#ban-submit-btn')).toBeDisabled();
  await page.evaluate(()=>window.finishAttachedReport());
  await expect(page.locator('#ban-attached-report-box')).toContainText('902');
  await expect(page.locator('#ban-submit-btn')).toBeEnabled();
});
