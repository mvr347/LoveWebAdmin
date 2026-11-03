const { test, expect } = require("@playwright/test");
for (const mobile of [false, true]) {
  test(`case stays compact on ${mobile ? "mobile" : "desktop"} and retains its own actions`, async ({
    page,
  }) => {
    if (mobile) await page.setViewportSize({ width: 390, height: 844 });
    await page.goto("/preview/");
    await expect(page.locator("#app")).toContainText("PreviewOwner");
    await page.evaluate(() => {
      LovePreview.model.scenario("report");
      return window.renderPunishmentsView("reports");
    });
    await expect(page.locator("#tab-rep-pending-count")).toHaveCount(1);
    const link = page.locator('a[href*="case.html?kind=report"]').first();
    await link.click();
    const dialog = page.locator(".modal.case-dialog");
    await expect(dialog).toBeVisible();
    await expect(dialog.locator("#summary")).toContainText("Ada123");
    expect(new URL(page.url()).pathname).toBe("/preview/");
    const box = await dialog.boundingBox(),
      size = page.viewportSize();
    expect(box.width).toBeLessThan(size.width - 20);
    expect(box.height).toBeLessThan(size.height - 30);
    await dialog
      .locator("summary")
      .filter({ hasText: "Ответственный" })
      .click();
    await dialog.locator("#claim").click();
    await expect(dialog.locator("#owner")).toContainText("PreviewOwner");
    await dialog.locator("summary").filter({ hasText: "Решение" }).click();
    await expect(dialog.locator("#reject")).toBeDisabled();
    await dialog.locator("#verdict").fill("Достаточное объяснение решения");
    await expect(dialog.locator("#reject")).toBeEnabled();
    await page.screenshot({
      path: `test-results/compact-case-${mobile ? "mobile" : "desktop"}.png`,
    });
    await dialog.getByRole("button", { name: "Закрыть дело" }).click();
    await expect(dialog).toHaveCount(0);
    await expect(link).toBeVisible();
  });
}
test("onboarding blocks invalid names, weak and mismatched passwords before Next", async ({
  page,
}) => {
  await page.goto("/preview/");
  await expect(page.locator("#app")).toContainText("PreviewOwner");
  await page.evaluate(async () => {
    const module = await import("/preview/js/features/auth.js");
    module.renderMasterOnboardingPassword("preview-token");
  });
  const next = page.locator("#ob-step2-form button[type=submit]");
  await expect(next).toBeDisabled();
  await page.locator("#ob-user").fill("admin");
  await page.locator("#ob-pass").fill("alllower123!");
  await page.locator("#ob-pass2").fill("alllower123!");
  await expect(next).toBeDisabled();
  await expect(page.locator("#ob-user-validation")).toContainText(
    "персональный",
  );
  await page.locator("#ob-user").fill("NewOwnerName");
  await page.locator("#ob-pass").fill("StrongPassword123!");
  await page.locator("#ob-pass2").fill("different");
  await expect(next).toBeDisabled();
  await expect(page.locator("#ob-pass2-validation")).toContainText(
    "не совпадают",
  );
  await page.locator("#ob-pass2").fill("StrongPassword123!");
  await expect(next).toBeEnabled();
});
test("explanation is compact and cannot be confirmed until valid", async ({
  page,
}) => {
  await page.goto("/preview/");
  await expect(page.locator("#app")).toContainText("PreviewOwner");
  await page.evaluate(() => {
    import("/preview/js/core/dialogs.js").then((m) =>
      m
        .explanationDialog("Причина", "Объясните решение")
        .then((value) => (window.testReason = value)),
    );
  });
  const dialog = page.locator("dialog[open]");
  await expect(dialog).toBeVisible();
  const box = await dialog.boundingBox();
  expect(box.width).toBeLessThanOrEqual(480);
  expect(box.height).toBeLessThan(500);
  expect(box.x).toBeGreaterThan(50);
  const confirm = dialog.getByRole("button", {
    name: "Подтвердить",
    exact: true,
  });
  await expect(confirm).toBeDisabled();
  await dialog.locator("textarea").fill("abc");
  await expect(confirm).toBeDisabled();
  await dialog.locator("textarea").fill("Понятное объяснение");
  await expect(confirm).toBeEnabled();
  await confirm.click();
  await expect(dialog).toHaveCount(0);
  expect(await page.evaluate(() => window.testReason)).toBe(
    "Понятное объяснение",
  );
});
test("a late username check cannot enable Next for a newer invalid value", async ({
  page,
}) => {
  await page.goto("/preview/");
  await expect(page.locator("#app")).toContainText("PreviewOwner");
  await page.evaluate(async () => {
    const original = window.fetch;
    window.fetch = (url, options) =>
      String(url).includes("check-username")
        ? new Promise(
            (resolve) =>
              (window.finishUsername = () =>
                resolve(
                  new Response(
                    JSON.stringify({
                      success: true,
                      data: { available: true },
                    }),
                    { headers: { "Content-Type": "application/json" } },
                  ),
                )),
          )
        : original(url, options);
    const module = await import("/preview/js/features/auth.js");
    module.renderMasterOnboardingPassword("preview-token");
  });
  await page.locator("#ob-pass").fill("StrongPassword123!");
  await page.locator("#ob-pass2").fill("StrongPassword123!");
  await page.locator("#ob-user").fill("ValidName");
  await page.waitForFunction(() => !!window.finishUsername);
  await page.locator("#ob-user").fill("bad!");
  await page.evaluate(() => window.finishUsername());
  await expect(
    page.locator("#ob-step2-form button[type=submit]"),
  ).toBeDisabled();
  await expect(page.locator("#ob-user-validation")).toContainText("символов");
});

test("invitation Next requires a checked nickname and an explicit role", async ({
  page,
}) => {
  await page.goto("/preview/");
  await expect(page.locator("#app")).toContainText("PreviewOwner");
  await page.evaluate(async () => {
    const module = await import("/preview/js/features/staff.js");
    return module.renderStaffView();
  });
  await page.locator("#btn-add-staff").click();
  const next = page.locator("#invite-next");
  await expect(next).toBeDisabled();
  await page.locator("#invite-username").fill("bad!");
  await expect(next).toBeDisabled();
  await expect(page.locator("#invite-username-validation")).toContainText(
    "символов",
  );
  await page.locator("#invite-username").fill("FutureMod");
  await expect(next).toBeDisabled();
  await page.locator("#invite-role").selectOption("2");
  await expect(next).toBeEnabled();
});
test("evidence fields explain the selected type before submitting", async ({
  page,
}) => {
  await page.goto("/preview/");
  await expect(page.locator("#app")).toContainText("PreviewOwner");
  const id = await page.evaluate(() => {
    LovePreview.model.scenario("report");
    return LovePreview.model.snapshot().reports.at(-1).id;
  });
  await page.goto("/preview/case.html?kind=report&id=" + id);
  await expect(page.locator("#status")).toContainText("Обновлено");
  const form = page.locator("#evidence-form"),
    submit = form.getByRole("button", {
      name: "Добавить доказательство",
      exact: true,
    });
  await expect(page.locator("#evidence-file")).toBeHidden();
  await page.locator("#evidence-type").selectOption("LINK");
  await page.locator("#evidence-value").fill("не ссылка");
  await expect(submit).toBeDisabled();
  await expect(page.locator("#evidence-value-validation")).toContainText(
    "ссылку",
  );
  await page.locator("#evidence-value").fill("https://example.com/proof");
  await expect(submit).toBeEnabled();
  await page.locator("#evidence-type").selectOption("IMAGE");
  await expect(page.locator("#evidence-file")).toBeVisible();
  await expect(submit).toBeDisabled();
  await page.locator("#evidence-file").setInputFiles({
    name: "bad.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("text"),
  });
  await expect(submit).toBeDisabled();
  await expect(page.locator("#evidence-file-validation")).toContainText("PNG");
  await page.locator("#evidence-file").setInputFiles({
    name: "proof.png",
    mimeType: "image/png",
    buffer: Buffer.from(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jwvkAAAAASUVORK5CYII=",
      "base64",
    ),
  });
  await expect(submit).toBeEnabled();
});
