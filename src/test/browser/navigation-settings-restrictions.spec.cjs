const { test, expect } = require("@playwright/test");

async function moderation(page, body) {
  return page.evaluate(
    (body) => LovePreview.model.request("POST", "/api/moderation", body),
    body,
  );
}
async function openRestrictions(page) {
  await page.locator('[data-nav="restrictions"]').click();
  await expect(page.locator(".restrictions-workspace")).toBeVisible();
}

test("navigation separates work, moderation and settings; personal security excludes global rules", async ({
  page,
}) => {
  await page.goto("/preview/");
  await expect(page.locator("#sidebar-nav .nav-section-title")).toHaveText([
    "Работа",
    "Модерация",
    "Сервер и игроки",
    "Управление",
  ]);
  await page.locator('[data-nav="settings"]').click();
  await expect(page.locator(".settings-home-group h3")).toHaveText([
    "Веб-панель",
    "Игровой сервер",
    "Discord и проверка",
  ]);
  await page.locator('[data-settings="panel-security"]').click();
  await expect(page.locator("#setting-minPasswordLength")).toBeVisible();
  await page.locator('.settings-tab-btn[data-tab="security"]').click();
  await expect(page.locator("#sec-old-pass")).toBeVisible();
  await expect(page.locator("#setting-minPasswordLength")).toHaveCount(0);
  await expect(page.locator("#settings-tab-content")).toContainText(
    "только вашей",
  );
  await page.locator('.settings-tab-btn[data-tab="maintenance"]').click();
  await expect(page.locator("#settings-tab-content")).toContainText(
    "Minecraft-сервер продолжает работать",
  );
  await page.screenshot({
    path: "test-results/settings-navigation.png",
    fullPage: true,
  });
});

test("dossier has no inventory link or inventory clearing action", async ({
  page,
}) => {
  await page.goto("/preview/");
  await page.evaluate(() => window.viewPlayerProfile("Ada123"));
  await expect(page.locator(".modal")).toContainText("Ada123");
  await expect(page.locator('.modal a[href*="inventory"]')).toHaveCount(0);
  await page.locator('#dossier-tabs [data-tab="actions"]').click();
  await expect(page.locator("#act-clear-inv")).toHaveCount(0);
  await expect(page.locator(".modal")).not.toContainText("ОЧИСТИТЬ ИНВЕНТАРЬ");
});

test("restrictions paginate, filter, validate and retain a replaced mute when an old form is submitted", async ({
  page,
}) => {
  await page.goto("/preview/");
  await moderation(page, {
    action: "mute",
    player: "Ada123",
    duration: "1h",
    reason: "Нарушение правил чата",
  });
  await openRestrictions(page);
  await page.getByRole("tab", { name: /Муты/ }).click();
  await expect(page.locator("#active-restrictions")).toContainText("Ada123");
  await page.getByRole("button", { name: "Снять мут", exact: true }).click();
  const form = page.locator("#restriction-form");
  await expect(form.locator("[type=submit]")).toBeDisabled();
  await form.locator("[name=reason]").fill("Проверка завершена");
  await moderation(page, {
    action: "mute",
    player: "Ada123",
    duration: "2h",
    reason: "Новая версия ограничения",
  });
  await form.locator("[type=submit]").click();
  await page
    .locator("dialog")
    .getByRole("button", { name: "Снять ограничение", exact: true })
    .click();
  await expect(form.locator("[role=alert]")).toContainText("изменилось");
  await expect(form).toBeVisible();
  await form.getByRole("button", { name: "Отмена", exact: true }).click();
  await page.locator("[data-refresh]").click();
  await expect(page.locator("#active-restrictions")).toContainText(
    "Новая версия",
  );
  await page.getByRole("tab", { name: "История", exact: true }).click();
  await expect(page.locator("#restriction-history")).toContainText(
    "Нарушение правил",
  );
  await page.locator("[data-search]").fill("Несуществующий");
  await expect(page.locator("#restriction-history")).toContainText(
    "Ничего не найдено",
  );
  await page.locator("[data-search]").fill("");
  await expect(page.locator("#restriction-history")).toContainText(
    "Новая версия",
  );
  await page.screenshot({
    path: "test-results/restrictions-history.png",
    fullPage: true,
  });
});

test("cancelling pending ban is distinct from unban and requires a reason", async ({
  page,
}) => {
  await page.goto("/preview/");
  const created = await moderation(page, { action: "ban", player: "Ada123" });
  await openRestrictions(page);
  await page
    .getByRole("button", { name: "Отменить предварительный бан", exact: true })
    .click();
  await expect(page.locator("#restriction-form [type=submit]")).toBeDisabled();
  await page
    .locator("#restriction-form [name=reason]")
    .fill("Создан по ошибке");
  await page.locator("#restriction-form [type=submit]").click();
  await page
    .locator("dialog")
    .getByRole("button", { name: "Снять ограничение", exact: true })
    .click();
  await expect(page.locator("#pending-bans")).toContainText("Список пуст");
  expect(
    await page.evaluate(
      (id) => LovePreview.model.snapshot().bans.find((b) => b.id === id).status,
      created.body.data.id,
    ),
  ).toBe("CANCELLED");
});

test("server access confirms the actual name and blocks duplicate operator grants", async ({
  page,
}) => {
  await page.goto("/preview/");
  await page.locator('[data-nav="settings"]').click();
  await page.locator('[data-settings="game-access"]').click();
  const section = page.locator('[data-access="ops"]');
  await section.locator("[name=player]").fill("Ada123");
  await section.locator("[type=submit]").click();
  await expect(page.locator("dialog")).toContainText("Ada123");
  await expect(page.locator("dialog")).not.toContainText("игроку LoveWebAdmin");
  await page
    .locator("dialog")
    .getByRole("button", { name: "Применить", exact: true })
    .click();
  await expect(section.locator("[data-list]")).toContainText("Ada123");
});

for (const viewport of [
  { width: 1440, height: 1000 },
  { width: 390, height: 844 },
]) {
  test(`restrictions and settings fit ${viewport.width}px`, async ({
    page,
  }) => {
    await page.setViewportSize(viewport);
    await page.goto("/preview/");
    await page.evaluate(() => window.navigateTo("restrictions"));
    await expect(page.locator(".restrictions-workspace")).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await page.evaluate(() => window.navigateTo("settings"));
    await page.locator('[data-settings="discord"]').click();
    await expect(page.locator("#setting-discordPanelChannelId")).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await page.locator(".modal").evaluate(async (el) => {
      await Promise.all(el.getAnimations().map((a) => a.finished));
    });
    await page.screenshot({
      path: `test-results/settings-${viewport.width}.png`,
      fullPage: true,
    });
  });
}

test("section shortcuts retain their numbers after navigation grouping", async ({
  page,
}) => {
  await page.goto("/preview/");
  for (const [key, section] of [
    ["4", "anticheat"],
    ["5", "server"],
    ["8", "database"],
    ["9", "restrictions"],
    ["0", "settings"],
  ]) {
    await page.locator("#topbar-page-title").click();
    await page.keyboard.press(key);
    await expect(page.locator(`[data-nav="${section}"]`)).toHaveAttribute(
      "aria-current",
      "page",
    );
  }
});

test("mute-only staff can open restrictions without seeing bans or settings", async ({
  page,
}) => {
  await page.goto("/preview/");
  await page.evaluate(() =>
    LovePreview.model.request("PUT", "/api/roles/2", {
      permissions: ["MANAGE_MUTES"],
    }),
  );
  await page.locator("#preview-role").selectOption("2");
  await expect(page.locator('[data-nav="restrictions"]')).toBeVisible();
  await expect(page.locator('[data-nav="punishments"]')).toHaveCount(0);
  await expect(page.locator('[data-nav="settings"]')).toHaveCount(0);
  await openRestrictions(page);
  await expect(page.getByRole("tab", { name: /Муты/ })).toBeVisible();
  await expect(page.getByRole("tab", { name: /Оформление банов/ })).toHaveCount(
    0,
  );
  await expect(
    page.getByRole("button", { name: "Предварительный бан", exact: true }),
  ).toHaveCount(0);
});

test("restriction pages and search keep a bounded working list", async ({
  page,
}) => {
  await page.goto("/preview/");
  await page.evaluate(() => {
    const model = LovePreview.model,
      request = model.request.bind(model);
    model.request = (method, path, ...args) => {
      if (
        method === "GET" &&
        new URL(path, location.origin).pathname === "/api/moderation"
      )
        return {
          status: 200,
          body: {
            success: true,
            data: {
              pending: [],
              serverTime: Date.now() / 1000,
              restrictions: Array.from({ length: 31 }, (_, i) => ({
                kind: "MUTE",
                name: `Player${String(i).padStart(2, "0")}`,
                uuid: String(i),
                actor: "Staff",
                reason: "Проверка списка",
                created: 1000 + i,
                expires: -1,
                revision: String(i),
              })),
            },
          },
        };
      return request(method, path, ...args);
    };
  });
  await openRestrictions(page);
  await page.getByRole("tab", { name: /Муты/ }).click();
  await expect(page.locator(".restriction-card")).toHaveCount(25);
  await page.locator("[data-next]").click();
  await expect(page.locator(".restriction-card")).toHaveCount(6);
  await page.locator("[data-search]").fill("Player00");
  await expect(page.locator(".restriction-card")).toHaveCount(1);
  await expect(page.locator("[data-page]")).toHaveText("1–1 из 1");
});

test("console restrictions use the shared validated form and preserve a tab draft", async ({
  page,
}) => {
  await page.goto("/preview/");
  await page.locator('[data-nav="settings"]').click();
  await page.locator('[data-settings="blacklist"]').click();
  const input = page.locator("#setting-commandBlacklist");
  await input.fill("stop\nrestart\nserver:op");
  await page.locator('.settings-tab-btn[data-tab="panel-security"]').click();
  await page.locator('.settings-tab-btn[data-tab="blacklist"]').click();
  await expect(input).toHaveValue("stop\nrestart\nserver:op");
  await page.locator(".settings-section [type=submit]").click();
  await expect(page.locator(".settings-save-status")).toContainText(
    /сохранены|Сохранено/,
  );
  expect(
    await page.evaluate(
      () => LovePreview.model.snapshot().config.commandBlacklist,
    ),
  ).toEqual(["stop", "restart", "server:op"]);
});
