const { test, expect } = require("@playwright/test");
const { create } = require("../../main/resources/web/preview/preview-model.js");

test("local preview explains that bot checks need a server sandbox and sends nothing", async ({
  page,
}) => {
  const live = [];
  page.on("request", (r) => {
    if (!new URL(r.url()).pathname.startsWith("/preview/")) live.push(r.url());
  });
  await page.goto("/preview/");
  await page.getByRole("button", { name: "Сценарии", exact: true }).click();
  await page
    .getByRole("button", { name: "Опубликовать тестовые кнопки", exact: true })
    .click();
  await expect(page.locator("[data-config]")).toContainText("Переключитесь");
  expect(live).toEqual([]);
  expect(
    await page.evaluate(() => LovePreview.model.snapshot().deliveries.length),
  ).toBe(0);
});

test("server Discord lab makes only explicit scoped requests and keeps human checks pending", async ({
  page,
}) => {
  const model = create(),
    state = model.snapshot(),
    id = "a".repeat(32),
    posts = [],
    errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.addInitScript(
    ({ id }) =>
      sessionStorage.setItem(
        "lwa-preview:v1:server",
        JSON.stringify({ id, token: "ONLY-PREVIEW-SECRET" }),
      ),
    { id },
  );
  await page.route("**/preview/api/**", async (route) => {
    const request = route.request(),
      action = new URL(request.url()).pathname.split("/").slice(4).join("/");
    let data;
    expect(request.headers()["x-preview-session"]).toBe("ONLY-PREVIEW-SECRET");
    expect(request.headers().authorization).toBeUndefined();
    if (action === "snapshot") data = state;
    else if (action === "discord-lab")
      data = {
        configured: true,
        botAvailable: true,
        channelId: "70000",
        recipientCount: 2,
        results: [],
        messages: [],
      };
    else if (action === "discord-lab/run") {
      const body = request.postDataJSON();
      posts.push(body);
      data =
        body.action === "model"
          ? {
              models: [
                {
                  name: "Потерянный ответ не создаёт дубль",
                  mode: "TRANSPORT_MODEL",
                  passed: true,
                  detail: "Discord не вызывался",
                },
              ],
            }
          : {
              results: [
                {
                  name: "Публикация трёх кнопок",
                  mode: "LIVE",
                  state: "PASS",
                  detail: "Discord подтвердил сообщение 100000",
                },
                {
                  name: "Кнопка: Жалоба",
                  mode: "LIVE",
                  state: "WAITING",
                  detail: "Нажмите кнопку в тестовой панели",
                },
              ],
            };
    } else {
      const result = model.request(
        request.method(),
        "/api/" + action,
        request.method() === "GET" ? {} : request.postDataJSON() || {},
      );
      data = result.body.data || {};
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ success: true, data, previewState: state }),
    });
  });
  await page.goto("/preview/");
  await page.getByRole("button", { name: "Сценарии", exact: true }).click();
  expect(posts).toEqual([]);
  await page
    .getByRole("button", { name: "Обновить результаты", exact: true })
    .click();
  await expect(page.locator("[data-config]")).toContainText("Бот доступен");
  expect(posts).toEqual([]);
  await page
    .getByRole("button", {
      name: "Проверить механизм без отправки",
      exact: true,
    })
    .click();
  await expect(page.locator("[data-results]")).toContainText(
    "Подменённый транспорт",
  );
  expect(posts).toEqual([{ action: "model" }]);
  await page
    .getByRole("button", { name: "Опубликовать тестовые кнопки", exact: true })
    .click();
  await expect(page.locator("[data-results]")).toContainText(
    "⏳ Кнопка: Жалоба",
  );
  expect(posts).toEqual([{ action: "model" }, { action: "panel" }]);
  expect(errors).toEqual([]);
  await page.screenshot({
    path: "test-results/discord-lab.png",
    fullPage: true,
  });
});
