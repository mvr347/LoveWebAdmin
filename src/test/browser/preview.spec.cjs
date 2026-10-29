const { test, expect } = require("@playwright/test");
test("real screens use synthetic state and leave production storage and network alone", async ({
  page,
}) => {
  const liveRequests = [],
    errors = [];
  page.on("request", (r) => {
    if (!new URL(r.url()).pathname.startsWith("/preview/"))
      liveRequests.push(r.url());
  });
  page.on("pageerror", (e) => errors.push(e.message));
  await page.addInitScript(() =>
    localStorage.setItem("wa_token", "REAL-SESSION-SENTINEL"),
  );
  await page.goto("/preview/");
  await expect(page.locator("#love-preview-banner")).toBeVisible();
  await expect(page.locator("#app")).toContainText("PreviewOwner");
  expect(await page.evaluate(() => localStorage.getItem("wa_token"))).toBe(
    "REAL-SESSION-SENTINEL",
  );
  await page.evaluate(() => {
    const m = LovePreview.model;
    m.scenario("ban");
    m.scenario("report");
    m.scenario("appeal");
  });
  const state = await page.evaluate(() => LovePreview.model.snapshot());
  const appeal = state.appeals[0],
    report = state.reports[0];
  await page.goto("/preview/case.html?kind=appeal&id=" + appeal.id);
  await expect(page.locator("#summary")).toContainText("Ada123");
  await page.locator("#note-text").fill("Служебная заметка в превью");
  await page.locator("#note button").click();
  await expect(page.locator("#notes")).toContainText("Служебная заметка");
  await page.locator("#reply").fill("Уточните подробности события");
  await page.locator("#reply-form button").click();
  await expect(page.locator("#messages")).toContainText("Уточните подробности");
  await page.goto("/preview/case.html?kind=report&id=" + report.id);
  await page.locator("#claim").click();
  await expect(page.locator("#owner")).toContainText("PreviewOwner");
  await page.goto("/preview/inventory.html?player=Ada123");
  await expect(page.locator("#items")).toContainText("Алмаз");
  page.once("dialog", (d) => d.accept("Изъятие для тестовой проверки"));
  await page
    .getByRole("button", { name: "Изъять", exact: true })
    .first()
    .click();
  await expect(page.locator("#evidence")).toContainText(
    "Изъятие для тестовой проверки",
  );
  await page.goto("/preview/");
  await page.locator("#preview-role").selectOption("3");
  await expect(page.locator("#app")).toContainText("PreviewReader");
  expect(
    await page.evaluate(async () => {
      const r = await fetch("/api/bans", {
        method: "POST",
        body: JSON.stringify({ targetName: "Ada123" }),
      });
      return r.status;
    }),
  ).toBe(403);
  expect(await page.evaluate(() => localStorage.getItem("wa_token"))).toBe(
    "REAL-SESSION-SENTINEL",
  );
  await page.evaluate(() =>
    LovePreview.storage.setItem("wa_quick_actions", "test-before-reset"),
  );
  page.once("dialog", (d) => d.accept());
  await page.locator("#preview-reset").click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Подтвердить", exact: true })
    .click();
  await expect(page.locator("#app")).toContainText("PreviewOwner");
  expect(
    await page.evaluate(() => LovePreview.model.snapshot().bans.length),
  ).toBe(0);
  expect(
    await page.evaluate(() => LovePreview.storage.getItem("wa_quick_actions")),
  ).not.toBe("test-before-reset");
  expect(await page.evaluate(() => localStorage.getItem("wa_token"))).toBe(
    "REAL-SESSION-SENTINEL",
  );
  expect(liveRequests).toEqual([]);
  expect(errors).toEqual([]);
});
test("preview login uses the existing password and 2FA screens", async ({
  page,
}) => {
  await page.goto("/preview/");
  await page.evaluate(() => LovePreview.model.scenario("login"));
  await page.reload();
  await page.getByRole("button", { name: "Сценарии", exact: true }).click();
  await page.locator("#login-user").fill("PreviewOwner");
  await page.locator("#login-pass").fill("PreviewOnly!25");
  await page.locator("#login-submit").click();
  await expect(page.locator("#code-2fa")).toBeVisible();
  await page.locator("#code-2fa").fill("123456");
  await page.locator("#form-2fa button[type=submit]").click();
  await expect(page.locator("#app")).toContainText("PreviewOwner");
});
test("navigation and ban form work; removed shifts are not exposed", async ({
  page,
}) => {
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/preview/");
  await expect(page.locator("#topbar-shift-btn")).toHaveCount(0);
  await page.evaluate(() => window.openQuickBanModal("Ada123"));
  await page.locator("#ban-reason-select").selectOption({ label: "Читы" });
  await page.locator("#ban-submit-btn").click();
  await page
    .getByRole("button", { name: "ЗАБЛОКИРОВАТЬ НАВСЕГДА", exact: true })
    .last()
    .click();
  await expect
    .poll(() => page.evaluate(() => LovePreview.model.snapshot().bans.length))
    .toBe(1);
  for (const section of [
    "journal",
    "punishments",
    "anticheat",
    "server",
    "auth",
    "staff",
    "database",
    "dashboard",
  ]) {
    await page.locator('[data-nav="' + section + '"]').click();
    await expect(page.locator("#topbar-page-title")).not.toBeEmpty();
  }
  expect(errors).toEqual([]);
  await page.screenshot({
    path: "test-results/preview-dashboard.png",
    fullPage: true,
  });
});
test("case evidence, ban correction and player portal share the workspace on mobile", async ({
  page,
}) => {
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/preview/");
  const ids = await page.evaluate(() => {
    LovePreview.model.scenario("ban");
    LovePreview.model.scenario("report");
    LovePreview.model.scenario("appeal");
    const s = LovePreview.model.snapshot();
    return { ban: s.bans[0].id, report: s.reports[0].id };
  });
  await page.goto("/preview/case.html?kind=report&id=" + ids.report);
  await expect(page.locator("#summary")).toContainText("Ada123");
  await page
    .locator("#evidence-value")
    .fill("Внутренний контекст для проверки");
  await page
    .getByRole("button", { name: "Добавить доказательство", exact: true })
    .click();
  await expect(page.locator("#evidence-list")).toContainText(
    "Внутренний контекст",
  );
  await page.goto("/preview/ban.html?id=" + ids.ban);
  await expect(page.locator("#heading")).toContainText("Ada123");
  await page.locator("#reason").fill("Исправленная тестовая причина");
  await page
    .locator("#explanation")
    .fill("Проверили контекст и исправили причину");
  await page.locator("#correction button").click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Подтвердить", exact: true })
    .click();
  await expect(page.locator("#history")).toContainText("Проверили контекст");
  const code = await page.evaluate(
    () => LovePreview.model.scenario("portal").code,
  );
  await page.goto("/preview/portal.html#" + code);
  await expect(page.locator("#cases")).toContainText("Апелляция");
  await page.locator("#cases button").first().click();
  await expect(page.locator("#conversation")).toBeVisible();
  await page.locator("#reply-form button").scrollIntoViewIfNeeded();
  await expect(page.locator("#reply-form button")).toBeInViewport();
  await expect(page.locator("#conversation")).not.toContainText(
    "Внутренний контекст",
  );
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth + 1,
    ),
  ).toBe(true);
  expect(errors).toEqual([]);
  await page.screenshot({
    path: "test-results/preview-mobile-portal.png",
    fullPage: true,
  });
});
test("late dashboard responses cannot replace a newer section", async ({
  page,
}) => {
  await page.goto("/preview/");
  await expect(page.locator("#app")).toContainText("PreviewOwner");
  await page.evaluate(() => {
    const transport = window.fetch;
    window.fetch = async (input, options) => {
      if (String(input).includes("/api/stats"))
        await new Promise((resolve) => setTimeout(resolve, 400));
      return transport(input, options);
    };
  });
  await page.locator('[data-nav="dashboard"]').click();
  await page.locator('[data-nav="staff"]').click();
  await expect(page.locator("#topbar-page-title")).toContainText("Персонал");
  await page.waitForTimeout(650);
  await expect(page.locator("#content-area")).not.toContainText(
    "Открыт другой раздел",
  );
  await expect(page.locator("#content-area")).toContainText("ПЕРСОНАЛ");
});
test("delivery reconciliation and panel recovery send the required JSON explanation", async ({
  page,
}) => {
  const mutations = [];
  let deliveryDone = false,
    panelDone = false;
  await page.addInitScript(() =>
    localStorage.setItem("wa_token", "test-session"),
  );
  // Serve the real production workspace only in this fixture; Preview's sentinel stays intact.
  const fs = require("node:fs/promises"),
    path = require("node:path"),
    root = path.resolve(__dirname, "../../main/resources/web");
  await page.route("**/*", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.startsWith("/api/")) return route.fallback();
    const file = path.resolve(root, "." + url.pathname);
    if (url.hostname !== "127.0.0.1" || !file.startsWith(root + path.sep))
      return route.abort();
    try {
      await route.fulfill({
        status: 200,
        contentType: file.endsWith(".html")
          ? "text/html"
          : file.endsWith(".css")
            ? "text/css"
            : "text/javascript",
        body: await fs.readFile(file),
      });
    } catch {
      await route.abort();
    }
  });
  await page.route("**/api/**", async (route) => {
    const request = route.request(),
      path = new URL(request.url()).pathname;
    let data = {};
    if (path === "/api/me")
      data = {
        id: 1,
        username: "Owner",
        isOwner: true,
        permissions: ["MANAGE_API", "MANAGE_INTEGRATIONS"],
      };
    else if (path === "/api/cases/deliveries")
      data = deliveryDone
        ? []
        : [
            {
              id: 1,
              event: "ban:12",
              state: "UNCERTAIN",
              attempts: 1,
              error: "Результат неизвестен",
            },
          ];
    else if (path === "/api/cases/discord-messages")
      data = panelDone
        ? []
        : [
            {
              slot: "panel:70000",
              channel: "70000",
              state: "UNCERTAIN",
              message: "",
              error: "Проверьте сообщение",
            },
          ];
    else if (request.method() === "POST") {
      mutations.push({ path, body: request.postDataJSON() });
      if (path.endsWith("/1/resolve")) deliveryDone = true;
      if (path.endsWith("/discord-messages/resolve")) panelDone = true;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ success: true, data }),
    });
  });
  await page.goto("/deliveries.html");
  await page
    .getByRole("button", {
      name: "Не доставлено: разрешить повтор",
      exact: true,
    })
    .click();
  await page
    .getByRole("dialog")
    .locator("textarea")
    .fill("Проверили Discord, сообщения нет");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Подтвердить", exact: true })
    .click();
  await expect.poll(() => mutations.length).toBe(1);
  expect(mutations[0].body).toEqual({
    delivered: false,
    explanation: "Проверили Discord, сообщения нет",
  });
  await page
    .getByLabel("ID найденного сообщения из Discord")
    .fill("123456789012345678");
  await page
    .getByRole("button", { name: "Связать найденное сообщение", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .locator("textarea")
    .fill("Нашли исходное сообщение в канале");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Подтвердить", exact: true })
    .click();
  await expect.poll(() => mutations.length).toBe(2);
  expect(mutations[1].body).toEqual({
    slot: "panel:70000",
    messageId: "123456789012345678",
    explanation: "Нашли исходное сообщение в канале",
  });
  await expect(page.locator("#discord-messages")).toContainText(
    "неизвестным результатом нет",
  );
});
