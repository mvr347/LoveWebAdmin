import { api, esc, hasPerm } from "./ui.js";
import { state } from "../state.js";
import {
  openRestrictionForm,
  openPendingBanForm,
  parseRestrictionDuration,
} from "./restriction-forms.js";

const permissions = [
  "VIEW_BANS",
  "MANAGE_BANS",
  "MANAGE_MUTES",
  "FREEZE_PLAYERS",
];
const date = (seconds) =>
  Number(seconds) > 0
    ? new Date(Number(seconds) * 1000).toLocaleString("ru-RU")
    : "—";
export async function renderRestrictions(container) {
  if (!permissions.some(hasPerm)) {
    container.innerHTML =
      '<p class="settings-intro">Нет права просмотра ограничений.</p>';
    return;
  }
  const generation = state.requestGeneration;
  container.innerHTML = `<section class="restrictions-workspace"><div class="view-header"><div class="view-title-block"><h2>Баны и ограничения</h2><p>Оформление банов, управление мутами и проверками игроков.</p></div><div class="restriction-actions" id="restrictions-actions"></div><button type="button" class="secondary" data-refresh>Обновить</button></div>
  <p class="settings-intro">Предварительный бан действует пять минут. Без оформления он снимается автоматически. Окончательные баны доступны во вкладке этого раздела.</p>
  <div class="segmented-nav restriction-tabs" role="tablist" aria-label="Виды ограничений"></div>
  <div class="restriction-toolbar"><label>Поиск<input type="search" maxlength="100" placeholder="Ник, сотрудник или причина" data-search></label><label>Порядок<select data-sort><option value="deadline">Ближайший срок</option><option value="newest">Сначала новые</option><option value="name">По нику</option></select></label></div>
  <p data-error class="form-error" role="alert"></p><div data-results aria-live="polite"><p>Загрузка ограничений…</p></div><div class="restriction-pagination"><button type="button" class="secondary" data-prev>Предыдущая</button><span data-page></span><button type="button" class="secondary" data-next>Следующая</button></div></section>`;
  const workspace = container.firstElementChild;
  const alive = () =>
    workspace.isConnected && generation === state.requestGeneration;
  const tabs = [
    [
      "pending",
      "Оформление банов",
      hasPerm("VIEW_BANS") || hasPerm("MANAGE_BANS"),
    ],
    ["mute", "Муты", hasPerm("MANAGE_MUTES")],
    ["freeze", "Проверки", hasPerm("FREEZE_PLAYERS")],
    [
      "history",
      "История",
      hasPerm("MANAGE_MUTES") || hasPerm("FREEZE_PLAYERS"),
    ],
  ].filter((t) => t[2]);
  let selected = tabs[0]?.[0] || "pending",
    page = 0,
    activeSort = "deadline",
    snapshot = { pending: [], restrictions: [] },
    history = { items: [], total: 0 },
    offset = 0,
    loading = false,
    reload = false,
    historyRequest = 0;
  const search = workspace.querySelector("[data-search]");
  const sort = workspace.querySelector("[data-sort]");
  const results = workspace.querySelector("[data-results]");
  function clock() {
    if (!alive()) return;
    const now = Math.floor(Date.now() / 1000) + offset;
    for (const el of results.querySelectorAll("[data-deadline]")) {
      const remaining = Math.max(0, Number(el.dataset.deadline) - now);
      const finalize = el
        .closest(".restriction-card")
        ?.querySelector("[data-finalize]");
      if (finalize) finalize.disabled = !remaining;
      el.textContent = remaining
        ? `${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, "0")} на оформление`
        : "Срок оформления истёк — обновите список";
    }
  }
  function draw() {
    if (!alive()) return;
    sort.value = selected === "history" ? "newest" : activeSort;
    workspace.querySelector(".restriction-tabs").innerHTML = tabs
      .map(
        ([id, label]) =>
          `<button type="button" role="tab" aria-selected="${selected === id}" class="segmented-nav-tab ${selected === id ? "active" : ""}" data-tab="${id}">${label}${id === "history" ? "" : ` <span class="badge">${id === "pending" ? snapshot.pending.length : snapshot.restrictions.filter((r) => r.kind === (id === "mute" ? "MUTE" : "FREEZE")).length}</span>`}</button>`,
      )
      .join("");
    const query = search.value.trim().toLocaleLowerCase("ru");
    let list =
      selected === "history"
        ? history.items
        : selected === "pending"
          ? snapshot.pending
          : snapshot.restrictions.filter(
              (r) => r.kind === (selected === "mute" ? "MUTE" : "FREEZE"),
            );
    if (selected !== "history") {
      list = list.filter((r) =>
        [r.name, r.actor, r.reason].some((v) =>
          String(v || "")
            .toLocaleLowerCase("ru")
            .includes(query),
        ),
      );
      list = [...list].sort((a, b) =>
        sort.value === "name"
          ? a.name.localeCompare(b.name, "ru")
          : sort.value === "newest"
            ? (b.createdAt || b.created) - (a.createdAt || a.created)
            : (a.deadline || (a.expires < 0 ? Infinity : a.expires)) -
              (b.deadline || (b.expires < 0 ? Infinity : b.expires)),
      );
    }
    const total = selected === "history" ? history.total : list.length;
    page = Math.min(page, Math.max(0, Math.ceil(total / 25) - 1));
    const visible =
      selected === "history" ? list : list.slice(page * 25, page * 25 + 25);
    results.innerHTML = `<div id="pending-bans" ${selected !== "pending" ? "hidden" : ""}></div><div id="active-restrictions" ${!["mute", "freeze"].includes(selected) ? "hidden" : ""}></div><div id="restriction-history" ${selected !== "history" ? "hidden" : ""}></div>`;
    const target = results.querySelector(
      selected === "pending"
        ? "#pending-bans"
        : selected === "history"
          ? "#restriction-history"
          : "#active-restrictions",
    );
    target.innerHTML = visible.length
      ? visible
          .map(
            (r, i) =>
              `<article class="restriction-card"><div class="restriction-card-heading"><strong>${esc(r.name || "Имя не сохранено")}</strong><span class="badge">${selected === "pending" ? "Ожидает оформления" : r.kind === "MUTE" ? "Мут" : "Проверка"}${selected === "history" ? ` · ${r.action === "APPLIED" ? "Выдано" : "Снято"}` : ""}</span></div><p>${esc(r.reason || "Нужно указать нарушение, срок и доказательства.")}</p><dl class="restriction-metadata"><div><dt>Сотрудник</dt><dd>${esc(r.actor)}</dd></div><div><dt>${selected === "history" ? "Когда" : "Начало"}</dt><dd>${date(r.at || r.createdAt || r.created)}</dd></div>${selected === "history" ? "" : `<div><dt>${selected === "pending" ? "Оформить до" : "Окончание"}</dt><dd>${selected === "pending" ? `<span data-deadline="${Number(r.deadline)}"></span>` : r.expires < 0 ? "Бессрочно" : date(r.expires)}</dd></div>`}</dl><div class="restriction-card-actions">${selected === "pending" ? (hasPerm("MANAGE_BANS") ? `<button type="button" class="primary" data-finalize="${Number(r.id)}">Оформить №${Number(r.id)}</button><button type="button" class="secondary" data-remove="${i}">Отменить предварительный бан</button>` : "<small>Оформление доступно сотруднику с правом управления банами.</small>") : selected === "history" ? "" : `<button type="button" class="secondary" data-remove="${i}">${r.kind === "MUTE" ? "Снять мут" : "Завершить проверку"}</button>`}</div></article>`,
          )
          .join("")
      : `<div class="restriction-empty"><h3>${query ? "Ничего не найдено" : "Список пуст"}</h3><p>${query ? "Измените запрос или выберите другую вкладку." : selected === "history" ? "Здесь появятся выдачи и снятия мутов и проверок. Полная история доступна в пункте «История» бокового меню." : "В этой категории сейчас нет ограничений."}</p></div>`;
    target
      .querySelectorAll("[data-finalize]")
      .forEach(
        (button) =>
          (button.onclick = () =>
            openPendingBan(Number(button.dataset.finalize), load).catch(
              showError,
            )),
      );
    target.querySelectorAll("[data-remove]").forEach(
      (button) =>
        (button.onclick = () => {
          const r = visible[Number(button.dataset.remove)];
          openRestrictionForm(
            selected === "pending"
              ? "cancel-ban"
              : r.kind === "MUTE"
                ? "unmute"
                : "unfreeze",
            selected === "pending"
              ? "Отменить предварительный бан"
              : r.kind === "MUTE"
                ? "Снять мут"
                : "Завершить проверку",
            load,
            r,
          );
        }),
    );
    workspace.querySelector("[data-prev]").disabled = page === 0;
    workspace.querySelector("[data-next]").disabled = (page + 1) * 25 >= total;
    workspace.querySelector("[data-page]").textContent =
      `${total ? page * 25 + 1 : 0}–${Math.min((page + 1) * 25, total)} из ${total}`;
    sort.disabled = selected === "history";
    clock();
  }
  function showError(error) {
    if (alive())
      workspace.querySelector("[data-error]").textContent = error.message;
  }
  async function loadHistory() {
    const request = ++historyRequest;
    try {
      const data = await api(
        "GET",
        `/api/moderation/history?search=${encodeURIComponent(search.value.trim())}&offset=${page * 25}`,
      );
      if (!alive() || request !== historyRequest || selected !== "history")
        return;
      history = data;
      draw();
    } catch (error) {
      if (request === historyRequest) showError(error);
    }
  }
  async function load() {
    if (!alive()) return;
    if (loading) {
      reload = true;
      return;
    }
    loading = true;
    workspace.querySelector("[data-refresh]").disabled = true;
    try {
      const data = await api("GET", "/api/moderation");
      if (!alive()) return;
      snapshot = data;
      offset = Number(data.serverTime) - Math.floor(Date.now() / 1000);
      workspace.querySelector("[data-error]").textContent = "";
      if (selected === "history") await loadHistory();
      else draw();
    } catch (error) {
      showError(error);
    } finally {
      loading = false;
      if (alive()) workspace.querySelector("[data-refresh]").disabled = false;
      if (reload && alive()) {
        reload = false;
        load();
      }
    }
  }
  workspace
    .querySelector(".restriction-tabs")
    .addEventListener("click", (event) => {
      const button = event.target.closest("[data-tab]");
      if (!button) return;
      selected = button.dataset.tab;
      page = 0;
      historyRequest++;
      if (selected === "history") {
        history = { items: [], total: 0 };
        draw();
        loadHistory();
      } else draw();
    });
  for (const [action, label, permission] of [
    ["ban", "Предварительный бан", "MANAGE_BANS"],
    ["mute", "Мут", "MANAGE_MUTES"],
    ["freeze", "Проверка", "FREEZE_PLAYERS"],
  ]) {
    if (!hasPerm(permission)) continue;
    const button = document.createElement("button");
    button.type = "button";
    button.className = "secondary";
    button.textContent = label;
    button.onclick = () =>
      openRestrictionForm(action, label, async () => {
        selected =
          action === "ban"
            ? "pending"
            : action === "mute"
              ? "mute"
              : action === "freeze"
                ? "freeze"
                : selected;
        page = 0;
        await load();
      });
    workspace.querySelector("#restrictions-actions").append(button);
  }
  search.oninput = () => {
    page = 0;
    if (selected === "history") loadHistory();
    else draw();
  };
  sort.onchange = () => {
    activeSort = sort.value;
    page = 0;
    draw();
  };
  workspace.querySelector("[data-refresh]").onclick = load;
  for (const [selector, delta] of [
    ["[data-prev]", -1],
    ["[data-next]", 1],
  ])
    workspace.querySelector(selector).onclick = () => {
      page += delta;
      if (selected === "history") loadHistory();
      else draw();
    };
  await load();
  if (!alive()) return;
  for (const key of ["restrictions", "restrictions_clock"])
    clearInterval(state.pollers[key]);
  state.pollers.restrictions = setInterval(() => {
    if (alive() && !document.hidden) load();
  }, 10000);
  state.pollers.restrictions_clock = setInterval(clock, 1000);
}
export async function openPendingBan(id, refresh, nested = false) {
  return openPendingBanForm(id, refresh, nested);
}
export function parseDuration(value) {
  return parseRestrictionDuration(value);
}
