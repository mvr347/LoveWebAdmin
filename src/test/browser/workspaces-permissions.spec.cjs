const {test,expect}=require('@playwright/test');
async function ready(page){await page.goto('/preview/');await expect(page.locator('#sidebar-nav')).toBeAttached();}
test('Webadmin has separate settings, web journal and panel sessions',async({page})=>{
 await ready(page);await expect(page.locator('[data-nav=auth] .nav-label')).toHaveText('Авторизация панели');
 await expect(page.locator('[data-nav=game-auth] .nav-label')).toHaveText('Безопасность аккаунтов');
 await page.locator('[data-nav=settings]').click();await expect(page.locator('.settings-workspace')).toBeVisible();await expect(page.locator('.modal-overlay')).toHaveCount(0);
 await expect(page.locator('.settings-workspace [data-tab=general]')).toHaveCount(0);
 await page.locator('[data-tab=panel-security]').click();await expect(page.locator('#setting-minPasswordLength')).toBeVisible();
});
test('theme survives reload and login screen',async({page})=>{
 await ready(page);await page.locator('#topbar-theme-btn').click();const light=await page.locator('body').evaluate(e=>e.classList.contains('theme-light'));
 await page.reload();await expect(page.locator('#sidebar-nav')).toBeAttached();expect(await page.locator('body').evaluate(e=>e.classList.contains('theme-light'))).toBe(light);
 await page.evaluate(()=>LovePreview.model.scenario('login'));await page.reload();await expect(page.locator('#login-user')).toBeVisible();expect(await page.locator('body').evaluate(e=>e.classList.contains('theme-light'))).toBe(light);
});
test('permissions have groups, search and automatic read dependencies',async({page})=>{
 await ready(page);await page.locator('[data-nav=staff]').click();await page.locator('#staff-sub-tabs [data-sub=roles]').click();await page.locator('#btn-create-role').click();
 await expect(page.locator('.permission-group')).toHaveCount(9);await expect(page.locator('.permission-group summary')).toContainText(['Игровое содержимое']);await expect(page.locator('.permission-card[data-permission=VIEW_PLAYER_INVENTORY]')).toHaveCount(0);
 await page.locator('.role-perm-cb[value=MANAGE_REPORTS]').check();await expect(page.locator('.role-perm-cb[value=VIEW_REPORTS]')).toBeChecked();
 await page.locator('.role-perm-cb[value=PUBLISH_CONTENT]').check();await expect(page.locator('.role-perm-cb[value=MANAGE_CONTENT]')).toBeChecked();await expect(page.locator('.role-perm-cb[value=VIEW_CONTENT]')).toBeChecked();
 await page.locator('.permission-tools input[type=search]').fill('жалоб');await expect(page.locator('.permission-card[data-permission=MANAGE_REPORTS]')).toBeVisible();await expect(page.locator('.permission-card[data-permission=EXECUTE_COMMANDS]')).toBeHidden();
});
test('account search suggests a player without submitting the whole nickname',async({page})=>{
 await ready(page);await page.locator('[data-nav=game-auth]').click();await page.locator('#loveauth-search-input').fill('Ada');await expect(page.locator('#account-suggestions button').first()).toHaveText('Ada123');
 await page.locator('#loveauth-search-input').press('ArrowDown');await page.locator('#loveauth-search-input').press('Enter');await expect(page.locator('#loveauth-search-input')).toHaveValue('Ada123');
});
test('notification grouping and unread filter preserve every record',async({page})=>{
 await ready(page);await page.evaluate(()=>{for(let i=0;i<2;i++)LovePreview.model.request('POST','/api/notifications',{title:'Проверка',message:'Одинаковое событие',type:'INFO'});});
 await page.locator('#topbar-notif-btn').click();await expect(page.locator('.notif-item')).toHaveCount(1);await expect(page.locator('.notif-item')).toContainText('Проверка · 2');await page.locator('.notif-item button').click();await expect(page.locator('.notif-item')).toHaveCount(0);await page.locator('[data-notification-filter=all]').click();await expect(page.locator('.notif-item.read')).toHaveCount(1);
});
test('journal queues new records without replacing the rows being read',async({page})=>{
 await page.clock.install();await ready(page);
 await page.evaluate(()=>{const original=window.fetch;window.moreLogs=false;window.fetch=(path,options)=>String(path).startsWith('/api/logs/web')?Promise.resolve(new Response(JSON.stringify({success:true,data:[{id:1,timestamp:1700000000,adminUsername:'Staff',action:'Первое событие'},...(window.moreLogs?[{id:2,timestamp:1700000010,adminUsername:'Staff',action:'Второе событие'}]:[])]}),{status:200,headers:{'Content-Type':'application/json'}})):original(path,options);return window.navigateTo('web-journal');});
 await expect(page.locator('#journal-table-body')).toContainText('Первое событие');await page.evaluate(()=>window.moreLogs=true);await page.clock.fastForward(10001);
 await expect(page.locator('.journal-new-events')).toBeVisible();await expect(page.locator('#journal-table-body')).not.toContainText('Второе событие');await page.locator('.journal-new-events').click();await expect(page.locator('#journal-table-body')).toContainText('Второе событие');
});
test('server player refresh retains the selected filter and search',async({page})=>{
 await page.clock.install();await ready(page);await page.locator('[data-nav=server]').click();
 await page.locator('#srv-player-status-filter').evaluate(e=>{e.value='all';e.dispatchEvent(new Event('change',{bubbles:true}));});
 await page.locator('#srv-player-search').fill('Ada');await page.clock.fastForward(8001);
 await expect(page.locator('#srv-player-status-filter')).toHaveValue('all');await expect(page.locator('#srv-player-search')).toHaveValue('Ada');
});
