const { test, expect } = require("@playwright/test");
test("local support has a transcript, static overview and persistent actions without Discord", async ({
  page,
}) => {
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/preview/?test=support#section=support");
  await page.locator(".reference-preview-actions a").click();
  await expect(page.locator(".case-overview #summary")).toContainText(
    "Тестовое обращение",
  );
  await expect(page.locator(".case-actions-sticky #claim")).toBeVisible();
  await expect(page.locator(".case-dialog #support-messages")).toBeAttached();
  await page.locator("#support-reply").fill("Тестовый ответ без Discord");
  await page.locator("#support-reply-form button").click();
  await expect(page.locator("#support-messages")).toContainText(
    "Тестовый ответ без Discord",
  );
  await page.locator("#support-close-reason").fill("Тест успешно завершён");
  await page.locator("#support-close-form button").click();
  await page.locator("dialog[open] button.primary").click();
  await expect(page.locator("#support-reply")).toBeDisabled();
  expect(errors).toEqual([]);
});
test("quick search includes reports appeals and support and opens the correct case", async ({
  page,
}) => {
  await page.goto("/preview/?test=cases#section=reports");
  await page.locator("#topbar-search-trigger").click();
  await page.locator("#global-search-input").fill("Ada");
  await expect(
    page.locator(".search-result-card").filter({ hasText: "Поддержка" }),
  ).toBeVisible();
  await expect(
    page.locator(".search-result-card").filter({ hasText: "Апелляция" }),
  ).toBeVisible();
  await expect(
    page.locator(".search-result-card").filter({ hasText: "Жалоба" }),
  ).toBeVisible();
  await page
    .locator(".search-result-card")
    .filter({ hasText: "Поддержка" })
    .locator("a")
    .click();
  await expect(page.locator(".case-overview #summary")).toContainText(
    "Тестовое обращение",
  );
});
test("punishment switches retain the workspace and only show real pagination", async ({
  page,
}) => {
  await page.clock.install();
  await page.goto("/preview/");
  await page.evaluate(() =>
    LovePreview.model.request("POST", "/api/moderation", {
      action: "mute",
      player: "Ada123",
      duration: "1h",
      reason: "Проверка нового интерфейса",
    }),
  );
  await page.locator("[data-nav=restrictions]").click();
  await page
    .locator(".punishments-workspace")
    .evaluate((e) => (e.dataset.marker = "same"));
  await page.locator("[data-punishment-kind=mute]").click();
  await expect(page.locator("[data-punishment-results]")).toContainText(
    "Ada123",
  );
  await expect(page.locator("[data-pager]")).toBeHidden();
  const row = page.locator("[data-punishment-results] tbody tr");
  await row.evaluate((el) => (el.dataset.marker = "preserved"));
  await page.locator(".view-header h2").click();
  await page.clock.fastForward(8100);
  await expect(row).toHaveAttribute("data-marker", "preserved");
  await page.screenshot({
    path: "test-results/punishments-workspace.png",
    fullPage: true,
  });

  await expect(page.locator(".punishments-workspace")).toHaveAttribute(
    "data-marker",
    "same",
  );
  await page.locator("[data-stage=history]").click();
  await expect(page.locator("[data-actor]")).toBeVisible();
  await expect(page.locator("[data-from]")).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Обновить", exact: true }),
  ).toHaveCount(0);
});
test("empty wave is styled and cannot be executed", async ({ page }) => {
  await page.goto("/preview/");
  await page.locator("[data-nav=anticheat]").click();
  await page.locator("[data-tab=wave]").click();
  await expect(page.locator(".wave-hero")).toContainText("Волна наказаний");
  await expect(page.locator("#wave-execute")).toBeDisabled();
  await expect(page.locator("#wave-entries")).toContainText(
    "Наказаний в очереди нет",
  );
});
test("console formatting is stripped and zero trust is not replaced with 85", async ({
  page,
}) => {
  await page.goto("/preview/");
  const text = await page.evaluate(async () => {
    const m = await import("/preview/js/core/log-text.js");
    return m.plainLogText(
      "\u001b[38;2;185;103;255mItemsAdder\u001b[0m » [Pack] Uploading resourcepack...",
    );
  });
  expect(text).toBe("ItemsAdder » [Pack] Uploading resourcepack...");
  const markup = await page.evaluate(async () => {
    const m = await import("/preview/js/core/trust-score.js");
    return m.trustScoreCard({
      trustScore: 0,
      trustFactors: ["Базовая оценка: +70", "Блокировки: −120"],
    });
  });
  expect(markup).toContain("Низкое · 0/100");
  expect(markup).not.toContain("85/100");
});
