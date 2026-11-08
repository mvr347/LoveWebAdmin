const { test, expect } = require("@playwright/test");
for (const width of [1440, 390])
  test(`search fills the drawer and wave action belongs to its header at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 950 });
    await page.goto("/preview/?test=cases");
    await page.locator("#topbar-search-trigger").click();
    const drawer = page.locator(".global-search-drawer"),
      results = drawer.locator(".search-results-list");
    await expect(drawer).toBeVisible();
    const boxes = await Promise.all([
      drawer.boundingBox(),
      results.boundingBox(),
    ]);
    expect(boxes[1].height).toBeGreaterThan(700);
    expect(boxes[1].width).toBeGreaterThan(boxes[0].width - 5);
    await drawer.locator("input").fill("Ada");
    await expect(results.locator(".search-result-card").first()).toBeVisible();
    expect(
      await drawer.evaluate((el) => el.scrollWidth <= el.clientWidth),
    ).toBe(true);
    await page.screenshot({
      path: `test-results/search-followup-${width}.png`,
      fullPage: true,
    });
    await drawer.getByRole("button", { name: "Закрыть поиск" }).click();
    await page.evaluate(() => window.navigateTo("anticheat"));
    await expect(page.locator("[data-tab=models]")).toHaveCount(0);
    await page.locator("[data-tab=wave]").click();
    await expect(page.locator(".wave-hero #wave-execute")).toBeDisabled();
    expect(
      await page
        .locator(".wave-hero")
        .evaluate((el) => el.scrollWidth <= el.clientWidth),
    ).toBe(true);
    await page.screenshot({
      path: `test-results/wave-followup-${width}.png`,
      fullPage: true,
    });
  });
test("a late stream result cannot replace the selected wave and there are no model requests", async ({
  page,
}) => {
  await page.goto("/preview/");
  await page.evaluate(() => {
    const fetch = window.fetch;
    window.modelRequests = [];
    window.fetch = (path, opts) => {
      if (/\/api\/vesuvio\/(engine|models|reviews)/.test(String(path)))
        window.modelRequests.push(String(path));
      if (String(path).includes("/api/vesuvio/violations"))
        return new Promise(
          (resolve) =>
            (window.releaseStream = () =>
              resolve(
                new Response(
                  JSON.stringify({
                    success: true,
                    data: [{ name: "StalePlayer", check: "Movement", vl: 4 }],
                  }),
                  {
                    status: 200,
                    headers: { "Content-Type": "application/json" },
                  },
                ),
              )),
        );
      return fetch(path, opts);
    };
    void window.navigateTo("anticheat");
  });
  await page.locator("[data-tab=wave]").click();
  await expect(page.locator(".wave-hero")).toBeVisible();
  await page.evaluate(() => window.releaseStream());
  await page.waitForTimeout(100);
  await expect(page.locator(".wave-hero")).toBeVisible();
  await expect(page.locator("#anticheat-content-body")).not.toContainText(
    "StalePlayer",
  );
  expect(await page.evaluate(() => window.modelRequests)).toEqual([]);
});
test("automatic punishment history filters and hides unnecessary pagination", async ({
  page,
}) => {
  await page.goto("/preview/");
  await page.evaluate(() => {
    const fetch = window.fetch;
    window.fetch = (path, opts) =>
      String(path).includes("/api/vesuvio/punishments")
        ? Promise.resolve(
            new Response(
              JSON.stringify({
                success: true,
                data: Array.from({ length: 31 }, (_, i) => ({
                  name: i ? "Player" + i : "Ada123",
                  action: "BAN",
                  reason: "Проверка журнала",
                  timestamp: 1700000000 + i,
                })),
              }),
              { status: 200, headers: { "Content-Type": "application/json" } },
            ),
          )
        : fetch(path, opts);
  });
  await page.locator("[data-nav=anticheat]").click();
  await page.locator("[data-tab=punishments]").click();
  await expect(page.locator("[data-events] tbody tr")).toHaveCount(25);
  await expect(page.locator("[data-event-pager]")).toBeVisible();
  await page.locator("[data-event-search]").fill("Ada");
  await expect(page.locator("[data-events] tbody tr")).toHaveCount(1);
  await expect(page.locator("[data-event-pager]")).toBeHidden();
  await page.locator("[data-open-history]").click();
  await expect(page.locator("[data-stage=history]")).toHaveClass(/active/);
});
test("dashboard quick actions respect permissions", async ({ page }) => {
  await page.goto("/preview/");
  await page.evaluate(() =>
    LovePreview.model.request("PUT", "/api/roles/2", {
      permissions: ["VIEW_STATS", "MANAGE_MUTES"],
    }),
  );
  await page.locator("#preview-role").selectOption("2");
  await expect(
    page.locator("#tile-quick_actions .quick-action-btn"),
  ).toHaveCount(1);
  await expect(
    page.locator("#tile-quick_actions .quick-action-btn"),
  ).toContainText("ВЫДАТЬ МУТ");
});
test('leaving anticheat before its first response does not install a late poller',async({page})=>{
 await page.goto('/preview/');await page.evaluate(()=>{
  const fetch=window.fetch;window.fetch=(path,opts)=>String(path).includes('/api/vesuvio/violations')?new Promise(resolve=>window.releaseOldView=()=>resolve(new Response(JSON.stringify({success:true,data:[]}),{status:200,headers:{'Content-Type':'application/json'}}))):fetch(path,opts);
  void window.navigateTo('anticheat');
 });
 await expect(page.locator('#anticheat-tabs')).toBeVisible();await page.evaluate(()=>window.navigateTo('staff'));await page.evaluate(()=>window.releaseOldView());await page.waitForTimeout(150);
 const pollers=await page.evaluate(async()=>{const {state}=await import('/preview/js/state.js');return Object.keys(state.pollers);});expect(pollers).not.toContain('vesuvio_live_stream');await expect(page.locator('#topbar-page-title')).toContainText('Сотрудники');
});
