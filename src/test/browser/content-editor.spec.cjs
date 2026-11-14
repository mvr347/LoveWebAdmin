const {test,expect}=require('@playwright/test');

async function open(page){
  await page.goto('/preview/');
  await expect(page.locator('#app')).toContainText('PreviewOwner');
  await page.locator('[data-nav="content"]:visible, [data-compact-nav="content"]:visible').first().click();
  await expect(page.locator('#content-form')).toContainText('supply');
}
test('content chain can be authored, saved as draft, published and restored without NPC editing',async({page})=>{
  const errors=[];page.on('pageerror',e=>errors.push(e.message));await open(page);
  await page.locator('[data-kind="achievements"]').click();
  await page.locator('#content-id').fill('quest_master');await page.locator('#content-create button').click();
  await page.locator('[data-field="name"]').fill('Мастер поручений');
  await page.locator('[data-kind="marks"]').click();await page.locator('#content-id').fill('quest_master_mark');await page.locator('#content-create button').click();
  await page.locator('[data-field="display"]').fill('<gold>Мастер поручений');
  await page.locator('#content-from').selectOption('achievements:quest_master');await page.locator('#content-to').selectOption('marks:quest_master_mark');await page.locator('#content-connect').getByRole('button',{name:'Связать',exact:true}).click();
  await expect(page.locator('[data-field="achievement"]')).toHaveValue('quest_master');
  await page.locator('[data-kind="contracts"]').click();await page.locator('[data-field="completion-achievement"]').selectOption('quest_master');
  await expect(page.locator('#content-graph')).toContainText('Мастер поручений');
  await page.locator('#content-save').click();await expect(page.locator('#content-status')).toContainText('Черновик сохранён');
  await page.reload();await page.locator('[data-nav="content"]:visible, [data-compact-nav="content"]:visible').first().click();
  await expect(page.locator('[data-field="completion-achievement"]')).toHaveValue('quest_master');
  await page.locator('#content-publish').click();await page.locator('#modal-confirm-btn').click();
  await expect(page.locator('#content-status')).toContainText('Опубликовано');
  const published=await page.evaluate(()=>LovePreview.model.snapshot().content.catalogs);
  expect(published.contracts.supply['completion-achievement']).toBe('quest_master');
  expect(published.marks.quest_master_mark.achievement).toBe('quest_master');
  expect(await page.locator('[data-kind="npcs"]').count()).toBe(0);
  await page.locator('#content-history').click();await page.locator('[data-history]').first().click();await page.locator('#modal-confirm-btn').click();
  await expect(page.locator('[data-field="completion-achievement"]')).toHaveValue('');
  const live=await page.evaluate(()=>LovePreview.model.snapshot().content.catalogs);
  expect(live.contracts.supply['completion-achievement']).toBe('quest_master');
  expect(errors).toEqual([]);
});

test('chain stages reorder and invalid references block publication',async({page})=>{
  await open(page);await page.locator('[data-kind="stories"]').click();await page.locator('#content-id').fill('story');await page.locator('#content-create button').click();
  await page.locator('#content-add-step').click();
  await page.locator('[data-field="steps.1.description"]').fill('Второе действие');
  await page.locator('[data-step-up="1"]').click();await expect(page.locator('[data-field="steps.0.description"]')).toHaveValue('Второе действие');
  await page.locator('[data-kind="achievements"]').click();await page.locator('#content-delete').click();await page.locator('#modal-confirm-btn').click();
  await expect(page.locator('#content-errors')).toContainText('achievements:helper');
  await page.locator('#content-publish').click();await expect(page.locator('#content-status')).toContainText('Исправьте связи');
  await expect(page.locator('#modal-confirm-btn')).toHaveCount(0);
});

test('mobile editor keeps controls within viewport and text preview never executes markup',async({page})=>{
  await page.setViewportSize({width:390,height:844});await open(page);
  await page.locator('[data-kind="joinquit"]').click();
  await page.locator('[data-field="join"]').fill('<img src=x onerror="window.editorXss=1"> <player> пришёл');
  await expect(page.locator('#content-preview-text')).toContainText('Игрок пришёл');
  expect(await page.evaluate(()=>window.editorXss)).toBeUndefined();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1)).toBeTruthy();
});

test('unsaved content survives navigation and viewer permission provides no editing controls',async({page})=>{
  await open(page);
  await page.locator('[data-field="display-name"]').fill('Локальная правка');
  await page.locator('[data-nav="dashboard"]:visible').click();
  await page.locator('[data-nav="content"]:visible').click();
  await expect(page.locator('[data-field="display-name"]')).toHaveValue('Локальная правка');
  await page.screenshot({path:'/workspace/.setup/content-editor.png',fullPage:true});
  await page.evaluate(async()=>{
    const {state}=await import('/preview/js/state.js');
    state.me={...state.me,isOwner:false,permissions:['VIEW_CONTENT']};
    const {renderContentEditor}=await import('/preview/js/features/content-editor.js');
    await renderContentEditor();
  });
  await expect(page.locator('#content-publish')).toHaveCount(0);
  await expect(page.locator('#content-save')).toHaveCount(0);
  await expect(page.locator('#content-create')).toHaveCount(0);
  await expect(page.locator('[data-field="display-name"]')).toBeDisabled();
});
