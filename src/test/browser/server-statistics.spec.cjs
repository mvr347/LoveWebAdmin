const { test, expect } = require("@playwright/test");
async function ready(page) {
  await page.goto("/preview/");
  await expect(page.locator("#app")).toContainText("PreviewOwner");
}
async function database(page) {
  const desktop = page.locator('[data-nav="database"]');
  await (
    (await desktop.isVisible())
      ? desktop
      : page.locator('[data-compact-nav="database"]')
  ).click();
  await expect(page.locator("#db-metrics-grid")).toContainText(
    "УНИКАЛЬНЫХ ИГРОКОВ",
  );
}
test("the whole database switches views and preserves unique-player period and inspector", async ({
  page,
}) => {
  await ready(page);
  await database(page);
  await page.locator('[data-period="7d"]').click();
  await expect(page.locator("#db-unique-view")).toBeVisible();
  await page.locator('[data-db-view="general"]').click();
  await expect(page.locator("#db-unique-view")).toBeHidden();
  await expect(page.locator("#db-general-content")).toContainText(
    "Аккаунты и активность",
  );
  for (const id of [
    "activity",
    "clans",
    "duels",
    "hunt",
    "adaptation",
    "behavior",
    "contracts",
    "security",
    "economy",
    "moderation",
    "reports",
    "appeals",
  ])
    await expect(
      page.locator('[data-stat-section="' + id + '"]').first(),
    ).toBeVisible();
  await expect(page.locator("#db-general-content")).toContainText(
    "Охват: игроков онлайн",
  );
  await page.locator('[data-db-view="unique"]').click();
  await expect(page.locator("#db-unique-view")).toBeVisible();
  await expect(page.locator('[data-period="7d"]')).toHaveClass(/active/);
  await page.locator("#inspect-player-name").fill("Ada123");
  await page.locator("#btn-inspect-player").click();
  await expect(page.locator("#inspector-card-result")).toContainText("Ada123");
});
test("general statistics stay usable on mobile and escape provider labels and values", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await ready(page);
  await database(page);
  await page.evaluate(() => {
    const original = window.fetch;
    window.fetch = (path, options) =>
      String(path) === "/api/analytics/general"
        ? Promise.resolve(
            new Response(
              JSON.stringify({
                success: true,
                data: {
                  scope: "server",
                  sections: [
                    {
                      id: "test",
                      title: "<img src=x onerror=alert(1)>",
                      source: "Тест",
                      available: true,
                      stale: true,
                      observedAt: 100,
                      fields: { "Длинный показатель": "A".repeat(150) },
                    },
                  ],
                },
              }),
              { headers: { "Content-Type": "application/json" } },
            ),
          )
        : original(path, options);
  });
  await page.locator('[data-db-view="general"]').click();
  await expect(page.locator("#db-general-content")).toContainText("<img src=x");
  await expect(page.locator("#db-general-content img")).toHaveCount(0);
  await expect(page.locator("#db-general-content")).toContainText(
    "Сохранённый снимок",
  );
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(390);
});
test("a late statistics response cannot replace a newer visit to the database", async ({
  page,
}) => {
  await ready(page);
  await database(page);
  await page.evaluate(() => {
    const original = window.fetch;
    let first = true;
    window.fetch = (path, options) =>
      String(path) === "/api/analytics/general" && first
        ? ((first = false),
          new Promise(
            (resolve) =>
              (window.finishOldStats = () =>
                resolve(
                  new Response(
                    '{"success":true,"data":{"sections":[{"id":"old","title":"СТАРЫЙ ОТВЕТ","fields":{}}]}}',
                    { headers: { "Content-Type": "application/json" } },
                  ),
                )),
          ))
        : original(path, options);
  });
  await page.locator('[data-db-view="general"]').click();
  await expect
    .poll(() => page.evaluate(() => typeof window.finishOldStats))
    .toBe("function");
  await page.locator('[data-nav="dashboard"]').click();
  await database(page);
  await page.locator('[data-db-view="general"]').click();
  await expect(page.locator("#db-general-content")).toContainText(
    "Аккаунты и активность",
  );
  await page.evaluate(() => window.finishOldStats());
  await expect(page.locator("#db-general-content")).not.toContainText(
    "СТАРЫЙ ОТВЕТ",
  );
});
test("dashboard summary opens a filtered queue and case returns to that queue", async ({
  page,
}) => {
  await ready(page);
  const id = await page.evaluate(() => {
    LovePreview.model.scenario("report");
    return LovePreview.model.snapshot().reports.at(-1).id;
  });
  await page.evaluate(() => window.navigateTo("dashboard"));
  await expect(page.locator("#work-summary-cards")).toContainText(
    "Взять обращения",
  );
  await page.locator('[data-work-bucket="unassigned"]').click();
  const link = page
    .locator('#modal-overlay a[href*="kind=report"]')
    .filter({ hasText: "№" + id });
  await expect(link).toBeVisible();
  await link.click();
  await expect(
    page.locator("#modal-overlay .case-dialog-content"),
  ).toContainText("Ada123");
  await page.getByRole("button", { name: "Закрыть дело" }).click();
  await expect(page.locator("#modal-overlay .work-queue")).toBeVisible();
  await expect(
    page.locator('#modal-overlay a[href*="kind=report"]'),
  ).toHaveCount(1);
});
test("dashboard summary exposes provisional bans and leaves no polling in another section", async ({
  page,
}) => {
  await ready(page);
  await page.evaluate(() => {
    LovePreview.model.request("POST", "/api/moderation", {
      action: "ban",
      player: "Ada123",
    });
    return window.navigateTo("dashboard");
  });
  await expect(page.locator('[data-work-bucket="pending"]')).toBeVisible();
  await page.locator('[data-work-bucket="pending"]').click();
  await expect(page.locator("#modal-overlay .work-queue")).toContainText(
    "Ada123",
  );
  await page.getByRole("button", { name: "Закрыть очередь" }).click();
  await page.locator('[data-nav="journal"]').click();
  expect(
    await page.evaluate(async () => {
      const { state } = await import("/preview/js/state.js");
      return state.pollers.work_summary;
    }),
  ).toBeUndefined();
});
