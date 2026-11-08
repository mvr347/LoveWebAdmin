import { api, esc, fmtTime, hasPerm, openModal } from "./ui.js";
import { state } from "../state.js";
import { confirmDialog } from "../core/dialogs.js";
import {
  openRestrictionForm,
  openPendingBanForm,
} from "./restriction-forms.js";
const names = { ban: "Баны", mute: "Муты", freeze: "Заморозки" };
const stages = {
  active: "Действующие",
  pending: "На оформлении",
  history: "История",
  archive: "Архив материалов",
};
const allowed = (kind) =>
  kind === "ban"
    ? hasPerm("VIEW_BANS") || hasPerm("MANAGE_BANS")
    : hasPerm(kind === "mute" ? "MANAGE_MUTES" : "FREEZE_PLAYERS");
export async function renderPunishments(area, historyOnly = false) {
  const kinds = Object.keys(names).filter(allowed);
  if (!kinds.length) {
    area.textContent = "Нет права просмотра наказаний";
    return;
  }
  state.punishmentWorkspace ||= {
    kind: kinds[0],
    stage: historyOnly
      ? "history"
      : hasPerm("VIEW_BANS")
        ? "active"
        : kinds[0] === "ban"
          ? "pending"
          : "active",
    page: 0,
    query: "",
    actor: "",
    from: "",
    to: "",
  };
  const memory = state.punishmentWorkspace;
  if (!kinds.includes(memory.kind)) memory.kind = kinds[0];
  if (historyOnly) memory.stage = "history";
  const lifetime = state.requestGeneration;
  area.innerHTML = `<section class="punishments-workspace"><header class="view-header"><div class="view-title-block"><h2>${historyOnly ? "История наказаний" : "Наказания"}</h2><p>Ограничения игроков, причины и решения сотрудников.</p></div><button type="button" class="primary" data-create-punishment></button></header><nav class="workspace-primary-tabs" aria-label="Тип наказания">${kinds.map((kind) => `<button type="button" data-punishment-kind="${kind}">${names[kind]}</button>`).join("")}</nav><nav class="panel-tabs" data-stages aria-label="Раздел наказаний"></nav><div class="punishment-filters"><label>Игрок или причина<input type="search" data-query maxlength="128" value="${esc(memory.query)}" placeholder="Ник или текст причины"></label><label>Сотрудник<input type="search" data-actor maxlength="128" value="${esc(memory.actor)}" placeholder="Кто выдал"></label><label>С даты<input type="date" data-from value="${esc(memory.from)}"></label><label>По дату<input type="date" data-to value="${esc(memory.to)}"></label></div><p data-description class="muted"></p><p data-error role="alert" class="form-error"></p><div data-punishment-results aria-live="polite"></div><div class="punishment-pagination" data-pager hidden><button type="button" class="secondary" data-page="-1">Предыдущая</button><span data-count></span><button type="button" class="secondary" data-page="1">Следующая</button></div></section>`;
  const host = area.firstElementChild,
    results = host.querySelector("[data-punishment-results]"),
    pager = host.querySelector("[data-pager]");
  const alive = () => host.isConnected && state.requestGeneration === lifetime;
  let loading = false,
    queued = false,
    timer,
    rows = [],
    total = 0,
    version = 0,
    lastMarkup = null;
  function options() {
    return memory.kind === "ban"
      ? Object.keys(stages).filter((stage) =>
          stage === "pending"
            ? hasPerm("MANAGE_BANS") || hasPerm("VIEW_BANS")
            : hasPerm("VIEW_BANS"),
        )
      : ["active", "history"];
  }
  function header() {
    if (!options().includes(memory.stage)) memory.stage = options()[0];
    for (const button of host.querySelectorAll("[data-punishment-kind]")) {
      button.classList.toggle(
        "active",
        button.dataset.punishmentKind === memory.kind,
      );
      button.setAttribute(
        "aria-pressed",
        String(button.dataset.punishmentKind === memory.kind),
      );
    }
    host.querySelector("[data-stages]").innerHTML = options()
      .map(
        (stage) =>
          `<button type="button" class="secondary ${stage === memory.stage ? "active" : ""}" data-stage="${stage}">${stages[stage]}</button>`,
      )
      .join("");
    const create = host.querySelector("[data-create-punishment]");
    create.hidden = !hasPerm(
      memory.kind === "ban"
        ? "MANAGE_BANS"
        : memory.kind === "mute"
          ? "MANAGE_MUTES"
          : "FREEZE_PLAYERS",
    );
    create.textContent =
      memory.kind === "ban"
        ? "Выдать бан"
        : memory.kind === "mute"
          ? "Выдать мут"
          : "Заморозить игрока";
    host.querySelector("[data-description]").textContent =
      memory.stage === "pending"
        ? "Ручной бан ожидает оформления пять минут. Автоматические наказания выдаются сразу."
        : memory.stage === "archive"
          ? "Сохранённые материалы завершённых обращений."
          : memory.stage === "history"
            ? "Завершённые ограничения и история действий. Поиск, сотрудник и даты фильтруют записи."
            : "Только действующие ограничения. Изменения появляются автоматически.";
    host.querySelector("[data-actor]").closest("label").hidden =
      memory.stage === "archive";
    host.querySelector("[data-from]").closest("label").hidden =
      memory.stage === "archive";
    host.querySelector("[data-to]").closest("label").hidden =
      memory.stage === "archive";
  }
  function query() {
    const p = new URLSearchParams({
      search: memory.query,
      actor: memory.actor,
      offset: String(memory.page * 25),
    });
    if (memory.from)
      p.set(
        "from",
        String(
          Math.floor(new Date(memory.from + "T00:00:00").getTime() / 1000),
        ),
      );
    if (memory.to)
      p.set(
        "to",
        String(Math.floor(new Date(memory.to + "T23:59:59").getTime() / 1000)),
      );
    return p;
  }
  function matches(row) {
    const at = Number(row.at ?? row.createdAt ?? row.created ?? 0);
    return (
      [row.name, row.targetName, row.reason, row.ruleReason].some((v) =>
        String(v || "")
          .toLowerCase()
          .includes(memory.query.toLowerCase()),
      ) &&
      String(row.actor || row.creatorName || "")
        .toLowerCase()
        .includes(memory.actor.toLowerCase()) &&
      (!memory.from || at >= Number(query().get("from"))) &&
      (!memory.to || at <= Number(query().get("to")))
    );
  }
  function draw() {
    const empty =
      memory.stage === "pending"
        ? "Банов на оформлении нет"
        : memory.stage === "archive"
          ? "Архив материалов пуст"
          : memory.stage === "history"
            ? "Записей по выбранным фильтрам нет"
            : "Действующих ограничений не найдено";
    let html = rows.length
      ? `<div class="punishment-table table-wrap"><table><thead><tr><th>${memory.stage === "archive" ? "Материал" : "Игрок"}</th><th>Причина / состояние</th><th>Сотрудник</th><th>Дата / срок</th><th>Действия</th></tr></thead><tbody>${rows
          .map((row, i) => {
            const pending = memory.stage === "pending",
              historic = memory.stage === "history",
              file = memory.stage === "archive";
            const name = file ? row.file : row.name || row.targetName;
            const status = historic
              ? {
                  APPLIED: "Выдано",
                  REMOVED: "Снято",
                  ACTIVE: "Действует",
                  UNBANNED: "Снят",
                  EXPIRED: "Срок истёк",
                  CANCELLED: "Отменён",
                  DRAFT: "Черновик",
                }[row.action || row.status] || "Завершено"
              : pending
                ? "Ожидает оформления"
                : "Действует";
            const remaining = pending
              ? Math.max(0, (row.deadline || 0) - Math.floor(Date.now() / 1000))
              : 0;
            return `<tr><td><strong>${esc(name)}</strong>${row.id ? `<small>№${Number(row.id)}</small>` : ""}</td><td>${esc(row.reason || row.ruleReason || "")}<span class="badge ${pending ? "yellow" : "gray"}">${file ? "Сохранено" : esc(status)}</span></td><td>${file ? "—" : esc(row.actor || row.creatorName || "Система")}</td><td>${file ? "—" : esc(fmtTime(row.at || row.createdAt || row.created))}<small>${pending ? `${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, "0")} на оформление` : row.expiresAt || row.expires ? ((row.expiresAt ?? row.expires) < 0 ? "Бессрочно" : "До " + esc(fmtTime(row.expiresAt ?? row.expires))) : ""}</small></td><td><div class="punishment-row-actions">${!file ? `<button type="button" class="secondary" data-detail="${i}">Подробнее</button>` : ""}${pending && hasPerm("MANAGE_BANS") ? `<button type="button" class="primary" data-finalize="${i}">Оформить</button><button type="button" class="secondary" data-remove="${i}">Отменить</button>` : !historic && !file && hasPerm(memory.kind === "ban" ? "MANAGE_BANS" : memory.kind === "mute" ? "MANAGE_MUTES" : "FREEZE_PLAYERS") ? `<button type="button" class="secondary" data-remove="${i}">Снять</button>` : ""}</div></td></tr>`;
          })
          .join("")}</tbody></table></div>`
      : `<div class="reference-empty-state"><h3>${empty}</h3><p>${memory.query || memory.actor || memory.from || memory.to ? "Попробуйте изменить фильтры." : "Новые записи появятся здесь автоматически."}</p></div>`;
    if (lastMarkup !== html) {
      results.innerHTML = html;
      lastMarkup = html;
    }
    pager.hidden = total <= 25;
    pager.querySelector("[data-count]").textContent =
      `${total ? memory.page * 25 + 1 : 0}–${Math.min(total, (memory.page + 1) * 25)} из ${total}`;
    pager.querySelector('[data-page="-1"]').disabled = memory.page === 0;
    pager.querySelector('[data-page="1"]').disabled =
      (memory.page + 1) * 25 >= total;
  }
  async function load() {
    if (!alive()) return;
    const request = ++version;
    if (loading) {
      queued = true;
      return;
    }
    loading = true;
    const signature = JSON.stringify(memory);
    try {
      let next, count;
      const params = query();
      if (memory.kind === "ban" && memory.stage === "archive") {
        const response = await api("GET", "/api/bans/archive");
        const files = (response.files || []).filter((file) =>
          String(file).toLowerCase().includes(memory.query.toLowerCase()),
        );
        count = files.length;
        next = files
          .slice(memory.page * 25, memory.page * 25 + 25)
          .map((file) => ({ file }));
      } else if (memory.kind === "ban" && memory.stage !== "pending") {
        params.set("status", memory.stage === "history" ? "ALL" : "ACTIVE");
        const response = await api("GET", "/api/bans/list?" + params);
        next = response.items;
        count = response.total;
      } else if (memory.stage === "history") {
        params.set("kind", memory.kind === "mute" ? "MUTE" : "FREEZE");
        const response = await api("GET", "/api/moderation/history?" + params);
        next = response.items;
        count = response.total;
      } else {
        const response = await api("GET", "/api/moderation");
        const list = (
          memory.stage === "pending"
            ? response.pending
            : response.restrictions.filter(
                (row) =>
                  row.kind === (memory.kind === "mute" ? "MUTE" : "FREEZE"),
              )
        ).filter(matches);
        count = list.length;
        next = list.slice(memory.page * 25, memory.page * 25 + 25);
      }
      if (
        !alive() ||
        signature !== JSON.stringify(memory) ||
        request !== version
      )
        return;
      rows = next || [];
      total = Number(count) || 0;
      if (memory.page && memory.page * 25 >= total) {
        memory.page = Math.max(0, Math.ceil(total / 25) - 1);
        queued = true;
        return;
      }
      host.querySelector("[data-error]").textContent = "";
      draw();
    } catch (error) {
      if (alive())
        host.querySelector("[data-error]").textContent = error.message;
    } finally {
      loading = false;
      if (queued && alive()) {
        queued = false;
        load();
      }
    }
  }
  host.addEventListener("click", async (event) => {
    const b = event.target.closest("button");
    if (!b || b.disabled) return;
    if (b.dataset.punishmentKind) {
      memory.kind = b.dataset.punishmentKind;
      memory.stage = "active";
      memory.page = 0;
      header();
      load();
    }
    if (b.dataset.stage) {
      memory.stage = b.dataset.stage;
      memory.page = 0;
      header();
      load();
    }
    if (b.dataset.page) {
      memory.page += Number(b.dataset.page);
      load();
    }
    if (b.hasAttribute("data-create-punishment"))
      openRestrictionForm(
        memory.kind,
        memory.kind === "ban"
          ? "Предварительный бан"
          : memory.kind === "mute"
            ? "Выдать мут"
            : "Заморозить игрока",
        async () => {
          if (memory.kind === "ban") memory.stage = "pending";
          memory.page = 0;
          header();
          await load();
        },
      );
    if (b.dataset.finalize) {
      const row = rows[Number(b.dataset.finalize)];
      openPendingBanForm(row.id, load);
    }
    if (b.dataset.remove) {
      const row = rows[Number(b.dataset.remove)];
      if (memory.kind === "ban" && memory.stage !== "pending") {
        if (
          !(await confirmDialog(
            "Снять блокировку",
            `${row.targetName} сможет снова войти на сервер. Снять бан №${row.id}?`,
          ))
        )
          return;
        b.disabled = true;
        try {
          await api("POST", `/api/bans/${row.id}/unban`);
          await load();
        } catch (error) {
          if (alive())
            host.querySelector("[data-error]").textContent = error.message;
        } finally {
          if (b.isConnected) b.disabled = false;
        }
        return;
      }
      const action =
        memory.stage === "pending"
          ? "cancel-ban"
          : memory.kind === "mute"
            ? "unmute"
            : "unfreeze";
      openRestrictionForm(action, "Снять ограничение", load, row);
    }
    if (b.dataset.detail) {
      const row = rows[Number(b.dataset.detail)];
      if (memory.kind === "ban" && memory.stage !== "pending") {
        window.viewBanDetails?.(row.id);
        return;
      }
      openModal(
        `<div class="modal-header"><h3>${esc(row.name || row.targetName)}</h3><button class="close-btn" data-modal-close aria-label="Закрыть">×</button></div><div class="modal-body"><h4>${names[memory.kind]}</h4><p>${esc(row.reason || row.ruleReason)}</p><p>Сотрудник: ${esc(row.actor || row.creatorName || "Система")}</p><p>Создано: ${esc(fmtTime(row.at || row.created || row.createdAt))}</p></div>`,
      );
    }
  });
  for (const [key, selector] of Object.entries({
    query: "[data-query]",
    actor: "[data-actor]",
    from: "[data-from]",
    to: "[data-to]",
  }))
    host.querySelector(selector).addEventListener("input", (event) => {
      memory[key] = event.target.value;
      memory.page = 0;
      clearTimeout(timer);
      timer = setTimeout(load, 250);
    });
  header();
  await load();
  clearInterval(state.pollers.punishment_workspace);
  state.pollers.punishment_workspace = setInterval(() => {
    if (
      alive() &&
      !document.hidden &&
      !document.querySelector(".modal-overlay") &&
      !results.contains(document.activeElement)
    )
      load();
  }, 8000);
}
