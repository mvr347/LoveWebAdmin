(function () {
  "use strict";
  if (!location.pathname.startsWith("/preview/")) return;
  const prefix = "lwa-preview:v1:",
    backing = window.sessionStorage;
  let model = LovePreviewModel.create({
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
  const nativeFetch = window.fetch.bind(window);
  let context = JSON.parse(backing.getItem(prefix + "server") || "null");
  let ready = Promise.resolve();
  function loadSnapshot(value) {
    backing.setItem(prefix + "state", JSON.stringify(value));
    model = LovePreviewModel.create({
      origin: location.origin,
      load: () => value,
      save: () => {},
    });
    syncIdentity();
    const select = document.getElementById("preview-role");
    if (select) {
      select.replaceChildren();
      for (const admin of value.admins || []) {
        const option = document.createElement("option");
        option.value = String(admin.id);
        option.textContent =
          (value.roles.find((role) => role.id === admin.roleId)?.name ||
            "Роль") +
          " · " +
          admin.username;
        option.disabled = admin.status !== "ACTIVE";
        select.append(option);
      }
      select.value = String(value.viewer || 1);
    }
  }
  async function serverRequest(method, path, body = {}, extra = {}) {
    if (!context) throw Error("Серверная песочница не создана");
    const route = path.replace(/^\/(?:preview\/)?api\/?/, "");
    const headers = new Headers(extra);
    headers.delete("Authorization");
    headers.delete("X-API-Key");
    headers.set("X-Preview-Session", context.token);
    headers.set("Content-Type", "application/json");
    const response = await nativeFetch(
      "/preview/api/" + context.id + "/" + route,
      {
        method,
        headers,
        body: method === "GET" ? undefined : JSON.stringify(body),
        credentials: "omit",
      },
    );
    const value = await response.clone().json();
    if (value.previewState) loadSnapshot(value.previewState);
    return { response, value };
  }
  if (context)
    ready = serverRequest("GET", "/api/snapshot")
      .then(({ response, value }) => {
        if (!response.ok)
          throw Error(value.error || "Сессия Preview недоступна");
        loadSnapshot(value.data);
      })
      .catch((error) => {
        context.error = error.message;
      });
  syncIdentity();
  window.LovePreview = Object.freeze({
    storage,
    get model() {
      return model;
    },
    get mode() {
      return context ? "server" : "local";
    },
    get contextId() {
      return context?.id || "local";
    },
    ready,
  });
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
      throw Error("Preview блокирует запрос за пределы песочницы");
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
    const method =
      options.method || (input instanceof Request ? input.method : "GET");
    await ready;
    if (context) {
      if (context.error) throw Error(context.error);
      return (
        await serverRequest(
          method,
          url.pathname + url.search,
          body || {},
          headers,
        )
      ).response;
    }
    const result = model.request(method, url.href, body || {}, headers);
    return new Response(JSON.stringify(result.body), {
      status: result.status,
      headers: { "Content-Type": "application/json" },
    });
  };
  async function control(scenario, body = {}) {
    if (!context) {
      let result;
      if (scenario === "role") model.switchRole(body.id);
      else result = model.scenario(scenario);
      syncIdentity();
      return result;
    }
    const { response, value } = await serverRequest("POST", "/api/control", {
      scenario,
      ...body,
    });
    if (!response.ok) throw Error(value.error || "Сценарий не выполнен");
    if (value.data?.totpSecret) {
      backing.setItem(prefix + "totp", value.data.totpSecret);
    }
    return value.data;
  }
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
      '<strong>ПРЕВЬЮ · тестовые данные</strong><span id="preview-mode-label">Локально: сервер и Discord не изменяются</span><button id="preview-server">Серверный Preview</button><select id="preview-role" aria-label="Тестовый аккаунт"><option value="1">Управляющий</option><option value="2">Модератор</option><option value="3">Наблюдатель</option></select><button id="preview-scenarios">Сценарии</button><button id="preview-reset">Сбросить</button><a data-preview-exit href="/">В настоящую панель</a>';
    const tools = document.createElement("section");
    tools.id = "love-preview-tools";
    tools.hidden = true;
    tools.innerHTML =
      '<h2>Проверка механик</h2><p>Демопароль: <code>PreviewOnly!25</code>, локальный код 2FA: <code>123456</code>. Серверный режим использует настоящий TOTP из тестового секрета. <output id="preview-totp"></output></p><div id="preview-scenario-buttons"></div><p id="preview-feedback" role="status"></p><h3>Тестовый Discord</h3><div id="preview-discord-feed"></div>';
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
    role.onchange = async () => {
      try {
        await control("role", { id: Number(role.value) });
        location.reload();
      } catch (e) {
        document.getElementById("preview-feedback").textContent = e.message;
      }
    };
    document.getElementById("preview-mode-label").textContent = context
      ? "Серверная песочница · Discord только в тестовых каналах"
      : "Локально: сервер и Discord не изменяются";
    document.getElementById("preview-totp").textContent =
      context && backing.getItem(prefix + "totp")
        ? "Тестовый секрет TOTP: " + backing.getItem(prefix + "totp")
        : "";
    const serverButton = document.getElementById("preview-server");
    serverButton.textContent = context
      ? "Локальный режим"
      : "Серверный Preview";
    serverButton.onclick = async () => {
      serverButton.disabled = true;
      try {
        if (context) {
          await serverRequest("DELETE", "/api/session");
          backing.removeItem(prefix + "server");
          backing.removeItem(prefix + "state");
          location.reload();
          return;
        }
        const token =
          localStorage.getItem("wa_token") || localStorage.getItem("lwa_token");
        if (!token)
          throw Error(
            "Сначала войдите в настоящую панель с правом управления Preview",
          );
        const creationKey =
          backing.getItem(prefix + "creation") || crypto.randomUUID();
        backing.setItem(prefix + "creation", creationKey);
        const response = await nativeFetch("/api/preview-sessions", {
          method: "POST",
          headers: {
            Authorization: "Bearer " + token,
            "Content-Type": "application/json",
            "Idempotency-Key": creationKey,
          },
          body: "{}",
        });
        const value = await response.json();
        if (!response.ok)
          throw Error(value.error || "Не удалось создать песочницу");
        backing.removeItem(prefix + "creation");
        context = { id: value.data.id, token: value.data.token };
        backing.setItem(prefix + "server", JSON.stringify(context));
        loadSnapshot(value.data.state);
        location.reload();
      } catch (e) {
        document.getElementById("preview-feedback").textContent = e.message;
        tools.hidden = false;
        serverButton.disabled = false;
      }
    };
    document.getElementById("preview-scenarios").onclick = () =>
      (tools.hidden = !tools.hidden);
    document.getElementById("preview-reset").onclick = async () => {
      if (
        await (
          await import("/preview/js/core/dialogs.js")
        ).confirmDialog(
          "Сброс Preview",
          "Сбросить только тестовые данные превью?",
        )
      ) {
        const keys = Array.from({ length: backing.length }, (_, i) =>
          backing.key(i),
        );
        for (const key of keys)
          if (key?.startsWith(prefix + "ui:")) backing.removeItem(key);
        if (context) {
          await serverRequest("DELETE", "/api/session");
          backing.removeItem(prefix + "server");
          backing.removeItem(prefix + "state");
        } else model.scenario("reset");
        syncIdentity();
        location.href = "/preview/";
      }
    };
    const check = document.createElement("button");
    check.textContent = "Проверить доставку в Discord";
    check.onclick = async () => {
      try {
        if (!context)
          throw Error("Локальный режим: доставка только моделируется");
        const { response, value } = await serverRequest(
          "GET",
          "/api/delivery-status",
        );
        if (!response.ok) throw Error(value.error);
        const feed = document.getElementById("preview-discord-feed");
        feed.replaceChildren();
        for (const item of value.data) {
          const row = document.createElement("p");
          row.textContent =
            "№" +
            item.id +
            " · " +
            item.state +
            " · " +
            (item.lastError || item.last_error || "");
          feed.append(row);
        }
        if (!value.data.length) feed.textContent = "Доставок ещё нет";
      } catch (e) {
        document.getElementById("preview-feedback").textContent = e.message;
      }
    };
    document.getElementById("preview-scenario-buttons").append(check);
    const run = document.createElement("button");
    run.textContent = "Запустить проверки сервера";
    run.onclick = async () => {
      run.disabled = true;
      try {
        if (!context)
          throw Error(
            "Эти проверки требуют серверного Preview. Локальные игровые действия являются моделированием.",
          );
        const results = await control("run-checks");
        const feed = document.getElementById("preview-discord-feed");
        feed.replaceChildren();
        for (const result of results) {
          const row = document.createElement("p");
          row.textContent =
            (result.mode === "LIVE_REQUIRED"
              ? "⏳"
              : result.passed
                ? "✓"
                : "✗") +
            " " +
            result.name +
            " · " +
            result.detail;
          feed.append(row);
        }
      } catch (e) {
        document.getElementById("preview-feedback").textContent = e.message;
      } finally {
        run.disabled = false;
      }
    };
    document.getElementById("preview-scenario-buttons").append(run);
    const sessionsButton = document.createElement("button");
    sessionsButton.textContent = "Мои серверные песочницы";
    sessionsButton.onclick = async () => {
      try {
        const token =
          localStorage.getItem("wa_token") || localStorage.getItem("lwa_token");
        if (!token) throw Error("Сначала войдите в настоящую панель");
        const response = await nativeFetch("/api/preview-sessions", {
          headers: { Authorization: "Bearer " + token },
        });
        const value = await response.json();
        if (!response.ok) throw Error(value.error);
        const feed = document.getElementById("preview-discord-feed");
        feed.replaceChildren();
        for (const item of value.data) {
          const row = document.createElement("p");
          row.textContent =
            "Песочница " +
            item.id.slice(0, 8) +
            " · " +
            new Date(item.createdAt * 1000).toLocaleString("ru-RU");
          const close = document.createElement("button");
          close.textContent = "Удалить тестовые данные";
          close.onclick = async () => {
            const { confirmDialog } = await import(
              "/preview/js/core/dialogs.js"
            );
            if (
              !(await confirmDialog(
                "Удалить песочницу",
                "Данные только этой песочницы будут удалены, её тестовые тикеты закроются.",
              ))
            )
              return;
            const result = await nativeFetch(
              "/api/preview-sessions/" + item.id,
              {
                method: "DELETE",
                headers: { Authorization: "Bearer " + token },
              },
            );
            if (!result.ok) throw Error((await result.json()).error);
            backing.removeItem(prefix + "creation");
            if (context?.id === item.id) {
              backing.removeItem(prefix + "server");
              backing.removeItem(prefix + "state");
              location.reload();
            }
            row.remove();
          };
          row.append(close);
          feed.append(row);
        }
        if (!value.data.length) feed.textContent = "Серверных песочниц нет";
      } catch (e) {
        document.getElementById("preview-feedback").textContent = e.message;
      }
    };
    document.getElementById("preview-scenario-buttons").append(sessionsButton);
    const scenarios = [
      ["portal", "Одноразовый вход игрока"],
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
      b.onclick = async () => {
        try {
          b.disabled = true;
          const result = await control(name);
          if (result?.code) {
            location.href = "/preview/portal.html#" + result.code;
            return;
          }
          syncIdentity();
          location.reload();
        } catch (e) {
          document.getElementById("preview-feedback").textContent = e.message;
          b.disabled = false;
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
        {
          PENDING: "В очереди",
          SENDING: "Отправляется",
          DELIVERED: context ? "Принято Discord" : "Доставка смоделирована",
          SENT: context ? "Принято Discord" : "Доставка смоделирована",
          FAILED: "Ошибка",
          UNCERTAIN: "Требует проверки",
        }[event.state];
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
