const { test, expect } = require("@playwright/test");
const { create } = require("../../main/resources/web/preview/preview-model.js");
async function open(page, tab) {
  await page.evaluate(async (tab) => {
    const m = await import("/preview/js/features/settings.js");
    await m.openAdminSettingsModal(tab);
  }, tab);
}
async function capture(page) {
  await page.evaluate(() => {
    window.settingsPosts = [];
    const original = window.fetch;
    window.fetch = (input, options = {}) => {
      if (input === "/api/server/config" && options.method === "POST")
        window.settingsPosts.push(JSON.parse(options.body));
      return original(input, options);
    };
  });
}

test("Discord settings explain fields and a double click saves only the changed value", async ({
  page,
}) => {
  await page.goto("/preview/");
  await capture(page);
  await open(page, "discord");
  await expect(page.locator("#settings-tab-content")).toContainText(
    "Вебхук безопасности",
  );
  await page
    .locator("#setting-discordPanelChannelId")
    .fill("123456789012345678");
  await page.evaluate(() => {
    const form = document.querySelector(".settings-section form");
    form.requestSubmit();
    form.requestSubmit();
  });
  await expect(page.locator(".settings-save-status")).toContainText(
    "перезапуск",
  );
  expect(await page.evaluate(() => settingsPosts)).toEqual([
    { discordPanelChannelId: "123456789012345678" },
  ]);
  expect(
    await page.evaluate(
      () => LovePreview.model.snapshot().config.discordPanelChannelId,
    ),
  ).toBe("123456789012345678");
  await page.locator(".modal").evaluate(async (element) => {
    await Promise.all(
      element.getAnimations().map((animation) => animation.finished),
    );
  });
  await page.screenshot({
    path: "test-results/settings-discord.png",
    fullPage: true,
  });
});

test("Preview settings reject a category ID reused as a channel without saving partial data", async ({
  page,
}) => {
  await page.goto("/preview/");
  await open(page, "preview");
  await page.locator("#setting-previewCategoryId").fill("123456789012345678");
  await page.locator("#setting-previewChannelId").fill("123456789012345678");
  await page
    .getByRole("button", { name: "Сохранить настройки", exact: true })
    .click();
  await expect(page.locator(".settings-save-status")).toContainText(
    "разные ID",
  );
  expect(
    await page.evaluate(
      () => LovePreview.model.snapshot().config.previewCategoryId || "",
    ),
  ).toBe("");
  await page.locator("#setting-previewChannelId").fill("234567890123456789");
  await page
    .locator("#setting-previewAllowedUserIds")
    .fill("345678901234567890");
  await page
    .getByRole("button", { name: "Сохранить настройки", exact: true })
    .click();
  await expect(page.locator(".settings-save-status")).toContainText(
    "Сохранено",
  );
  expect(
    await page.evaluate(
      () => LovePreview.model.snapshot().config.previewAllowedUserIds,
    ),
  ).toEqual(["345678901234567890"]);
});

test("webhook field is hidden, blank preserves the secret and explicit removal clears it", async ({
  page,
}) => {
  await page.goto("/preview/");
  await open(page, "webhooks");
  await expect(page.locator("#settings-tab-content")).not.toContainText(
    /telegram|телеграм/i,
  );
  await page
    .locator("#setting-discordWebhookUrl")
    .fill("https://discord.com/api/webhooks/123456789/test-secret");
  await page.locator("#setting-webhooksEnabled").check();
  await page
    .getByRole("button", { name: "Сохранить настройки", exact: true })
    .click();
  await expect(page.locator(".settings-save-status")).toContainText(
    "сохранены",
  );
  await expect(page.locator("#setting-discordWebhookUrl")).toHaveValue("");
  await page.locator("#setting-webhookNotifyCommands").uncheck();
  await page
    .getByRole("button", { name: "Сохранить настройки", exact: true })
    .click();
  await expect(page.locator(".settings-save-status")).toContainText(
    "сохранены",
  );
  expect(
    await page.evaluate(
      () => LovePreview.model.snapshot().config.discordWebhookConfigured,
    ),
  ).toBe(true);
  await page.locator("#setting-clearDiscordWebhookUrl").check();
  await page
    .getByRole("button", { name: "Сохранить настройки", exact: true })
    .click();
  await expect(
    page.locator("#setting-clearDiscordWebhookUrl"),
  ).not.toBeChecked();
  expect(
    await page.evaluate(
      () => LovePreview.model.snapshot().config.discordWebhookConfigured,
    ),
  ).toBe(false);
  await expect(page.locator("#setting-webhooksEnabled")).not.toBeChecked();
  await expect(page.locator("#setting-discordWebhookUrl")).toBeEnabled();
});

test("personal notifications do not show or submit global configuration", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = create().snapshot();
  state.roles.find((r) => r.id === 2).permissions = ["VIEW_STATS"];
  state.viewer = 2;
  await page.addInitScript(
    (state) =>
      sessionStorage.setItem("lwa-preview:v1:state", JSON.stringify(state)),
    state,
  );
  await page.goto("/preview/");
  await capture(page);
  await open(page, "notifications");
  await expect(page.locator('[data-tab="discord"]')).toHaveCount(0);
  await expect(page.locator('[data-tab="server"]')).toHaveCount(0);
  await page.locator('input[name="discordNotifyReports"]').check();
  await page
    .getByRole("button", { name: "Сохранить мои уведомления", exact: true })
    .click();
  await expect(page.locator("#settings-tab-content")).toContainText(
    "Личные уведомления сохранены",
  );
  expect(await page.evaluate(() => settingsPosts)).toEqual([]);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth + 1,
    ),
  ).toBe(true);
});
