const { test, expect } = require("@playwright/test");
async function ready(page) {
  await page.goto("/preview/");
  await expect(page.locator("#app")).toContainText("PreviewOwner");
}
test("select uses safe labels, searches, handles disabled state and releases its popup", async ({page}) => {
  await ready(page);
  await page.evaluate(() => {
    const form = document.createElement("form"); form.id="select-test";
    const label = document.createElement("label"); label.textContent="Период";
    const select=document.createElement("select"); select.id="select-test-value";
    for(let i=1;i<=20;i++) {const option=document.createElement("option");option.value=String(i);option.textContent=i===20?"<img src=x onerror=alert(1)>":"Период "+i;select.append(option);}
    label.append(select); form.append(label);
    const after=document.createElement("input");after.id="after-select";after.placeholder="Следующее поле";form.append(after); document.querySelector("#content-area").prepend(form);
    window.selectChanges=0;select.onchange=()=>window.selectChanges++;
  });
  const trigger=page.locator("#select-test .ui-select-trigger");
  await expect(trigger).toHaveText("Период 1"); await trigger.click();
  const popup=page.locator(".ui-select-popup"); await expect(popup).toBeVisible();
  await popup.getByRole("searchbox").fill("<img");
  await expect(popup.getByRole("option")).toHaveCount(1);
  await expect(popup.locator("img")).toHaveCount(0);
  await page.screenshot({path:"test-results/nov05-select-search.png"});
  await popup.getByRole("option").click();
  await expect(trigger).toHaveText("<img src=x onerror=alert(1)>");
  expect(await page.evaluate(()=>window.selectChanges)).toBe(1);
  await expect(popup).toHaveCount(0);
  await page.locator("#select-test-value").evaluate(select=>select.disabled=true);
  await expect(trigger).toBeDisabled();
  await page.locator("#select-test-value").evaluate(select=>select.disabled=false);
  await expect(trigger).toBeEnabled();
  for(let i=0;i<15;i++) {await trigger.click();await page.keyboard.press("Escape");}
  await expect(popup).toHaveCount(0);
  await trigger.click();
  await page.keyboard.press("Tab");
  await expect(page.locator("#after-select")).toBeFocused();
  await trigger.click();
  await page.locator("#select-test").evaluate(form=>form.remove());
  await expect(popup).toHaveCount(0);
});
test("small select keeps keyboard selection and popup inside a native dialog", async ({page})=>{
  await ready(page);
  await page.evaluate(()=>{
    const dialog=document.createElement("dialog");dialog.className="workspace-dialog";
    dialog.innerHTML='<label>Режим<select id="modal-mode"><option value="a">Первый</option><option value="x" disabled>Недоступный</option><option value="b">Второй</option></select></label>';
    document.body.append(dialog);dialog.showModal();
  });
  const trigger=page.locator("dialog .ui-select-trigger");await trigger.focus();
  await page.keyboard.press("ArrowDown");await page.keyboard.press("ArrowDown");await page.keyboard.press("Enter");
  await expect(page.locator("#modal-mode")).toHaveValue("b");
  await trigger.click();
  await expect(page.locator("dialog .ui-select-popup")).toBeVisible();
  await page.keyboard.press("Escape");await expect(page.locator("dialog")).toBeVisible();
  await expect(page.locator(".ui-select-popup")).toHaveCount(0);
});
test("accepted mutations retain drafts and blocked storage retains retry keys",async({page})=>{
  await ready(page);
  const result=await page.evaluate(async()=>{
    const {requestFetch,clearOperationIntents}=await import("/preview/js/core/requests.js");
    const originalFetch=window.fetch,storage=Object.getOwnPropertyDescriptor(window,"sessionStorage");
    const keys=[];let completed=0,status=202;
    const listener=()=>completed++;
    document.addEventListener("lwa:mutation-complete",listener);
    try {
      Object.defineProperty(window,"sessionStorage",{configurable:true,get(){throw Error("blocked");}});
      window.fetch=async(path,options)=>{
        keys.push(new Headers(options.headers).get("Idempotency-Key"));
        return new Response('{"success":true}',{status,headers:{"Content-Type":"application/json"}});
      };
      const options={method:"POST",headers:{Authorization:"Bearer lwa_live_controls"},body:'{"action":"test"}'};
      await requestFetch("/api/test-mutation",options);const pendingCount=completed;
      status=200;await requestFetch("/api/test-mutation",options);
      await requestFetch("/api/test-mutation",options);
      return {keys,pendingCount,completed};
    } finally {
      clearOperationIntents();window.fetch=originalFetch;
      Object.defineProperty(window,"sessionStorage",storage);
      document.removeEventListener("lwa:mutation-complete",listener);
    }
  });
  expect(result.pendingCount).toBe(0);expect(result.completed).toBe(2);
  expect(result.keys[0]).toBe(result.keys[1]);expect(result.keys[2]).not.toBe(result.keys[1]);
});
test("a valid nickname waits for the remote availability result",async({page})=>{
  await ready(page);
  await page.evaluate(async()=>{
    const original=window.fetch;
    window.fetch=(path,options)=>String(path).includes("check-username")?new Promise(resolve=>window.finishNameCheck=()=>resolve(new Response('{"success":false,"error":"Имя уже занято"}',{status:409,headers:{"Content-Type":"application/json"}}))):original(path,options);
    const auth=await import("/preview/js/features/auth.js");auth.renderMasterOnboardingPassword("preview-token");
  });
  await page.locator("#ob-user").fill("OccupiedName");
  await page.locator("#ob-pass").fill("StrongPassword123!");
  await page.locator("#ob-pass2").fill("StrongPassword123!");
  const next=page.locator("#ob-step2-form button[type=submit]");
  await expect(next).toBeDisabled();
  await expect.poll(()=>page.evaluate(()=>typeof window.finishNameCheck)).toBe("function");
  await page.evaluate(()=>window.finishNameCheck());
  await expect(page.locator("#ob-user-validation")).toHaveText("Имя уже занято");
  await expect(next).toBeDisabled();
});
test("mobile navigation has named sections without hamburger controls",async({page})=>{
  await page.setViewportSize({width:390,height:844});await ready(page);
  await expect(page.locator("#mobile-menu-toggle")).toHaveCount(0);
  const sections=page.getByRole("navigation",{name:"Разделы панели"});
  await sections.getByRole("button",{name:"Игровой журнал",exact:true}).click();
  await expect(page.locator("#topbar-page-title")).toHaveText("Игровой журнал");
  const exit=page.locator("#logout-btn");
  const box=await page.locator(".topbar-right").boundingBox();
  expect(box.x+box.width).toBeLessThanOrEqual(390);
  await expect(page.locator("#topbar-settings-btn")).toBeVisible();
  await page.screenshot({path:"test-results/nov05-mobile-navigation.png"});
});

test("section visibility follows permissions rather than the spelling of a role",async({page})=>{
  await ready(page);
  const result=await page.evaluate(async()=>{
    const {state}=await import("/preview/js/state.js");
    const {isSectionVisible}=await import("/preview/js/features/ui.js");
    const previous=state.me;
    try {
      state.me={isOwner:false,role:"Moderator",permissions:["VIEW_PLAYERS"]};
      const granted=isSectionVisible({perm:"VIEW_PLAYERS",modAllowed:false});
      state.me.permissions=[];
      return {granted,denied:!isSectionVisible({perm:"VIEW_PLAYERS",modAllowed:false})};
    } finally {state.me=previous;}
  });
  expect(result).toEqual({granted:true,denied:true});
});

test("an old mutation response cannot invalidate or populate the next staff session",async({page})=>{
  await ready(page);
  const result=await page.evaluate(async()=>{
    const {state}=await import("/preview/js/state.js");
    const {api}=await import("/preview/js/features/ui.js");
    const originalFetch=window.fetch,oldToken=state.token;
    const failures=[];
    try {
      window.fetch=async(path,options)=>String(path)==="/api/me"?new Response('{"success":true,"data":{"id":1}}',{headers:{"Content-Type":"application/json"}}):new Promise(resolve=>window.finishOldMutation=()=>resolve(new Response('{"success":false,"error":"old session"}',{status:401,headers:{"Content-Type":"application/json"}})));
      state.token="before-relogin";
      const pending=api("POST","/api/staff-test",{action:"test"}).catch(error=>failures.push(error.name));
      while(!window.finishOldMutation)await new Promise(requestAnimationFrame);
      state.token="after-relogin";
      window.finishOldMutation();await pending;
      return {failures,current:state.token};
    } finally {window.fetch=originalFetch;state.token=oldToken;}
  });
  expect(result).toEqual({failures:["AbortError"],current:"after-relogin"});
});
