(function () {
  "use strict";
  if (!location.pathname.startsWith("/preview/")) return;
  const prefix = "lwa-preview:v1:",
    backing = window.sessionStorage;
  const model = LovePreviewModel.create({
    origin: location.origin,
    load: () => JSON.parse(backing.getItem(prefix + "state") || "null"),
    save: (value) => backing.setItem(prefix + "state", JSON.stringify(value)),
  });
  const storage = {
    getItem: (key) => backing.getItem(prefix + "ui:" + key),
    setItem: (key, value) =>
      backing.setItem(prefix + "ui:" + key, String(value)),
    removeItem: (key) => backing.removeItem(prefix + "ui:" + key),
  };
  function syncIdentity() {
    const current = model.snapshot().viewer;
    for (const key of ["wa_token", "lwa_token"])
      if (current) storage.setItem(key, "preview-session-" + current);
      else storage.removeItem(key);
  }
  syncIdentity();
  window.LovePreview = Object.freeze({ storage, model });
  window.fetch = async function (input, options = {}) {
    if (options.signal?.aborted)
      throw new DOMException("Отменено", "AbortError");
    const url = new URL(
      typeof input === "string" ? input : input.url,
      location.href,
    );
    if (
      url.origin !== location.origin ||
      !/^\/(preview\/)?api(?:\/|$)/.test(url.pathname)
    )
      throw new Error(
        "Превью не выполняет внешние или настоящие сетевые запросы",
      );
    const headers = Object.fromEntries(
      new Headers(
        options.headers || (input instanceof Request ? input.headers : {}),
      ).entries(),
    );
    let body = options.body;
    if (
      body === undefined &&
      input instanceof Request &&
      input.method !== "GET"
    )
      body = await input.text();
    if (typeof body === "string") {
      try {
        body = JSON.parse(body);
      } catch {
        body = {};
      }
    }
    const response = model.request(
      options.method || (input instanceof Request ? input.method : "GET"),
      url.href,
      body || {},
      headers,
    );
    return new Response(JSON.stringify(response.body), {
      status: response.status,
      headers: { "Content-Type": "application/json" },
    });
  };
  function previewUrl(value) {
    const url = new URL(value, location.href);
    if (url.origin !== location.origin) return null;
    if (!url.pathname.startsWith("/preview/"))
      url.pathname = "/preview" + (url.pathname === "/" ? "/" : url.pathname);
    return url.href;
  }
  function rewriteLinks(node) {
    const links = node.querySelectorAll
      ? Array.from(node.querySelectorAll("a[href]"))
      : [];
    if (node.matches?.("a[href]")) links.push(node);
    for (const a of links) {
      if (a.hasAttribute("data-preview-exit") || a.hasAttribute("download"))
        continue;
      const raw = a.getAttribute("href");
      if (!raw || raw.startsWith("#") || raw.startsWith("blob:")) continue;
      const next = previewUrl(raw);
      if (next) {
        if (a.href !== next) a.setAttribute("href", next);
      } else {
        a.setAttribute("href", "#");
        a.title = "Внешняя ссылка отключена в превью";
      }
    }
  }
  window.open = (value) => {
    const url = previewUrl(value);
    if (url) location.href = url;
    else alert("Внешние переходы и Discord в превью моделируются локально.");
    return null;
  };
  document.addEventListener("DOMContentLoaded", () => {
    const banner = document.createElement("aside");
    banner.id = "love-preview-banner";
    banner.innerHTML =
      '<strong>ПРЕВЬЮ · тестовые данные</strong><span>Настоящий сервер и Discord не изменяются</span><select id="preview-role" aria-label="Тестовый аккаунт"><option value="1">Управляющий</option><option value="2">Модератор</option><option value="3">Наблюдатель</option></select><button id="preview-scenarios">Сценарии</button><button id="preview-reset">Сбросить</button><a data-preview-exit href="/">В настоящую панель</a>';
    const tools = document.createElement("section");
    tools.id = "love-preview-tools";
    tools.hidden = true;
    tools.innerHTML =
      '<h2>Проверка механик</h2><p>Демопароль: <code>PreviewOnly!25</code>, код 2FA: <code>123456</code>. Он действует только в этой песочнице.</p><div id="preview-scenario-buttons"></div><p id="preview-feedback" role="status"></p><h3>Тестовый Discord</h3><div id="preview-discord-feed"></div>';
    document.body.prepend(tools);
    document.body.prepend(banner);
    document.body.classList.add("love-preview");
    const resize = () =>
      document.documentElement.style.setProperty(
        "--love-preview-height",
        Math.ceil(banner.getBoundingClientRect().height) + "px",
      );
    resize();
    new ResizeObserver(resize).observe(banner);

    const role = document.getElementById("preview-role");
    role.value = String(model.snapshot().viewer || 1);
    role.onchange = () => {
      model.switchRole(role.value);
      syncIdentity();
      location.reload();
    };
    document.getElementById("preview-scenarios").onclick = () =>
      (tools.hidden = !tools.hidden);
    document.getElementById("preview-reset").onclick = () => {
      if (confirm("Сбросить только тестовые данные превью?")) {
        const keys = Array.from({ length: backing.length }, (_, i) =>
          backing.key(i),
        );
        for (const key of keys)
          if (key?.startsWith(prefix + "ui:")) backing.removeItem(key);
        model.scenario("reset");
        syncIdentity();
        location.href = "/preview/";
      }
    };
    const scenarios = [
      ["ban", "Выдать тестовый бан"],
      ["report", "Подать жалобу"],
      ["appeal", "Апелляция привязанного игрока"],
      ["unverified", "Апелляция по нику без привязки"],
      ["discord-reply", "Ответ игрока из Discord"],
      ["clarification-reply", "Уточнение по жалобе из Discord"],
      ["deliver", "Обработать доставки Discord"],
      ["failure", "Вкл/выкл ошибку доставки"],
      ["quiet", "Вкл/выкл тихие часы"],
      ["reconnect", "Вернуть игроков онлайн"],
      ["login", "Проверить вход с 2FA"],
      ["onboarding", "Первичная настройка"],
    ];
    for (const [name, label] of scenarios) {
      const b = document.createElement("button");
      b.textContent = label;
      b.onclick = () => {
        try {
          model.scenario(name);
          syncIdentity();
          location.reload();
        } catch (e) {
          document.getElementById("preview-feedback").textContent = e.message;
        }
      };
      document.getElementById("preview-scenario-buttons").append(b);
    }
    const state = model.snapshot();
    document.getElementById("preview-feedback").textContent =
      "Ошибка доставки: " +
      (state.discord.fail ? "включена" : "выключена") +
      " · Тихие часы: " +
      (state.discord.quiet ? "включены" : "выключены");
    for (const event of state.deliveries.slice(-12).reverse()) {
      const p = document.createElement("p");
      p.textContent =
        event.text +
        " · " +
        { PENDING: "В очереди", DELIVERED: "Доставлено", FAILED: "Ошибка" }[
          event.state
        ];
      document.getElementById("preview-discord-feed").append(p);
    }
    if (["login", "onboarding"].includes(state.scenario) || !state.viewer)
      tools.hidden = false;
    rewriteLinks(document);
    new MutationObserver((changes) => {
      for (const change of changes) {
        if (change.type === "attributes") rewriteLinks(change.target);
        else for (const node of change.addedNodes) rewriteLinks(node);
      }
    }).observe(document.body, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["href"],
    });
  });
})();
