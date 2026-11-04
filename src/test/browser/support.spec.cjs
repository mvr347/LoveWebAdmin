const { test, expect } = require("@playwright/test");

// The Preview model does not simulate support, so the list endpoint is answered by the test.

test("support tab lists requests, escapes text and links to the case page", async ({ page }) => {
  await page.goto("/preview/");
  await expect(page.locator("#app")).toContainText("PreviewOwner");
  await page.evaluate(() => {
    window.__supportCalls = [];
    const original = window.fetch.bind(window);
    window.fetch = (input, init) => {
      const url = String(input instanceof Request ? input.url : input);
      if (!url.includes("/api/support")) return original(input, init);
      window.__supportCalls.push(url);
      const closed = url.includes("status=closed");
      const items = closed
        ? [{ id: 1, nick: "Closed1", topic: "bug", topicLabel: "Ошибка", status: "CLOSED", deskState: "CLOSED", hasChannel: false, assignee: "", createdAt: 1700000000 }]
        : [
            { id: 7, nick: "<img src=x onerror=window.__xss=1>", topic: "bug", topicLabel: "Ошибка / баг", status: "OPEN", deskState: "OPEN", hasChannel: true, assignee: "Moderator", createdAt: 1700000000 },
            { id: 8, nick: "Fresh", topic: "other", topicLabel: "Другое", status: "OPEN", deskState: "OPEN", hasChannel: false, assignee: "", createdAt: 1700000100 },
          ];
      return Promise.resolve(
        new Response(JSON.stringify({ success: true, data: { items, total: items.length, open: 2, pageSize: 20, offset: 0 } }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );
    };
    return window.renderPunishmentsView("reports");
  });
  await page.locator('#punishments-subtabs-bar [data-subtab="support"]').click();
  const rows = page.locator("#support-subtab-tbody tr");
  await expect(rows).toHaveCount(2);
  await expect(rows.first()).toContainText("#7");
  await expect(rows.first()).toContainText("Ошибка / баг");
  await expect(rows.first()).toContainText("Moderator");
  await expect(rows.first()).toContainText("Открыто");
  await expect(rows.nth(1)).toContainText("Не назначен");
  await expect(rows.nth(1)).toContainText("Канал создаётся");
  await expect(page.locator("#support-open-count")).toHaveText("2");
  // Player-controlled text must be shown as text, never run.
  expect(await page.evaluate(() => window.__xss)).toBeUndefined();
  await expect(rows.first().locator("strong")).toContainText("<img");
  await expect(rows.first().locator("a")).toHaveAttribute("href", /\/case\.html\?kind=support&id=7$/);

  await page.locator('#support-status-filters [data-status="closed"]').click();
  await expect(page.locator("#support-subtab-tbody tr")).toHaveCount(1);
  await expect(page.locator("#support-subtab-tbody")).toContainText("Closed1");
  await expect(page.locator("#support-subtab-tbody")).toContainText("Закрыто");
  const calls = await page.evaluate(() => window.__supportCalls);
  expect(calls.some((url) => url.includes("status=open"))).toBe(true);
  expect(calls.some((url) => url.includes("status=closed"))).toBe(true);
});
