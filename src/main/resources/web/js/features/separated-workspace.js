import {
  api,
  esc,
  fmtTime,
  hasPerm,
  showToast,
} from "./ui.js";
import { state } from "../state.js";
import { renderRestrictions } from "./restrictions.js";
import { renderPunishmentsView } from "./moderation.js";
const definitions = {
  report: {
    title: "Жалобы",
    singular: "Жалоба",
    intro:
      "Репорты игроков. Апелляции и поддержка находятся в соседних разделах.",
    permission: "VIEW_REPORTS",
    endpoint: "/api/reports",
  },
  appeal: {
    title: "Апелляции",
    singular: "Апелляция",
    intro:
      "Просьбы пересмотреть наказание. Переписка и решение сохраняются в деле.",
    permission: "VIEW_APPEALS",
    endpoint: "/api/appeals",
  },
  support: {
    title: "Поддержка",
    singular: "Обращение",
    intro: "Вопросы и помощь игрокам. Обращения не являются наказаниями.",
    permission: "VIEW_REPORTS",
    endpoint: "/api/support?status=all",
  },
};
const isClosed = (row) => !["PENDING", "OPEN"].includes(row.status);
function caseLink(kind, id) {
  return `/case.html?kind=${kind}&id=${Number(id)}`;
}
export async function renderSeparatedCases(area, kind = "report") {
  const definition = definitions[kind];
  if (!hasPerm(definition.permission)) {
    area.textContent = "Нет права просмотра этого раздела.";
    return;
  }
  state.separatedViews ||= {};
  const memory = (state.separatedViews[kind] ||= {
    query: "",
    filter: "new",
    page: 0,
    selected: null,
    offset: 0,
  });
  area.innerHTML = `<section class="reference-cases"><div class="view-header"><div class="view-title-block"><h2>${definition.title.toUpperCase()}</h2><p>${definition.intro}</p></div><div class="view-actions">${kind === "report" && hasPerm("MANAGE_BANS") ? '<button type="button" class="primary" data-new-ban>Выдать бан</button>' : '<button type="button" class="secondary" data-refresh-cases>Обновить список</button>'}</div></div><div class="reference-case-layout"><section class="reference-panel reference-list-panel"><div class="reference-panel-head"><h3>${kind === "report" ? "Жалобы игроков" : definition.title}</h3><div class="filter-tags">${[
    ["new", "Новые"],
    ["working", "В работе"],
    ["all", "Все"],
  ]
    .map(
      ([id, label]) =>
        `<button type="button" class="filter-tag-btn ${memory.filter === id ? "active" : ""}" data-case-filter="${id}">${label}</button>`,
    )
    .join(
      "",
    )}</div></div><div class="reference-search"><input type="search" aria-label="Поиск обращений" placeholder="Поиск по игроку, причине или заявителю…" maxlength="100" value="${esc(memory.query)}"></div><div class="reference-case-list" aria-live="polite">Загрузка обращений…</div><div class="reference-case-pager"></div></section><aside class="reference-panel reference-case-preview" aria-label="Краткая карточка дела"><p class="muted">Выбери обращение в списке.</p></aside></div><p class="reference-help">Нажатие на строку показывает краткую карточку. «Открыть дело» открывает полную работу с обращением в панели справа.</p></section>`;
  const host = area.firstElementChild,
    generation = state.requestGeneration,
    list = host.querySelector(".reference-case-list"),
    preview = host.querySelector(".reference-case-preview");
  const alive = () =>
    host.isConnected && generation === state.requestGeneration;
  let sourceSize = 0;
  const batchSize = kind === "support" ? 20 : 50;
  let rows = [],
    loading = false,
    selectedGeneration = 0,
    workSnapshot = {};
  function workFor(row) {
    return workSnapshot[kind + ":" + row.id] || {};
  }
  function normalise(row) {
    return {
      raw: row,
      id: Number(row.id),
      name: row.targetName || row.playerName || row.nick || "Игрок",
      author: row.reporterName || row.playerName || row.nick || "Игрок",
      reason: Array.isArray(row.reasons)
        ? row.reasons.join(", ")
        : row.topicLabel ||
          row.topic ||
          row.ruleReason ||
          "Пересмотр наказания",
      description:
        row.description ||
        row.reason ||
        row.body ||
        "Подробности находятся в деле.",
      status: row.status,
      created: row.createdAt,
    };
  }
  function filtered() {
    const query = memory.query.toLocaleLowerCase("ru");
    return rows.filter(
      (row) =>
        (memory.filter === "all" ||
          (!isClosed(row) &&
            (memory.filter === "working"
              ? !!workFor(row).assignee
              : !workFor(row).assignee))) &&
        [row.name, row.author, row.reason, row.description].some((text) =>
          String(text).toLocaleLowerCase("ru").includes(query),
        ),
    );
  }
  function drawList() {
    if (!alive()) return;

    const filteredRows = filtered(),
      pageSize = 6;
    memory.page = Math.min(
      memory.page,
      Math.max(0, Math.ceil(filteredRows.length / pageSize) - 1),
    );
    const visible = filteredRows.slice(
      memory.page * pageSize,
      memory.page * pageSize + pageSize,
    );
    if (!visible.some((row) => row.id === memory.selected))
      memory.selected = visible[0]?.id || null;
    list.innerHTML = visible.length
      ? visible
          .map(
            (row) =>
              `<button type="button" class="reference-list-item ${memory.selected === row.id ? "selected" : ""}" data-case-id="${row.id}" aria-pressed="${memory.selected === row.id}"><span class="reference-type-dot ${isClosed(row) ? "closed" : ""}"></span><span class="reference-item-content"><strong>${esc(row.name)} · ${esc(row.reason)}</strong><small>${esc(row.author)} · ${workFor(row).assignee ? "В работе: " + esc(workFor(row).assignee) : isClosed(row) ? "Закрыто" : "Новая запись"}</small></span><span class="reference-item-time">№${row.id}</span></button>`,
          )
          .join("")
      : `<div class="reference-empty">${memory.query ? "Ничего не найдено. Измени поиск." : "В этой категории обращений пока нет."}</div>`;
    host.querySelector(".reference-case-pager").innerHTML =
      `<span>${filteredRows.length ? memory.page * pageSize + 1 : 0}–${Math.min((memory.page + 1) * pageSize, filteredRows.length)} из ${filteredRows.length}</span><div><button type="button" class="secondary btn-sm" data-page="-1" ${memory.page === 0 ? "disabled" : ""}>Назад</button><button type="button" class="secondary btn-sm" data-page="1" ${(memory.page + 1) * pageSize >= filteredRows.length ? "disabled" : ""}>Вперёд</button></div><div class="reference-source-pages"><span>Поиск и фильтр в текущих ${sourceSize} записях</span><button type="button" class="secondary btn-sm" data-batch="-1" ${!memory.offset ? "disabled" : ""}>Более новые</button><button type="button" class="secondary btn-sm" data-batch="1" ${sourceSize < batchSize ? "disabled" : ""}>Более ранние</button></div>`;
  }
  async function showPreview(id) {
    const request = ++selectedGeneration;
    memory.selected = id;
    drawList();
    const row = rows.find((item) => item.id === id);
    if (!row) {
      preview.innerHTML =
        '<div class="reference-empty">Выбери обращение в списке.</div>';
      return;
    }
    preview.innerHTML = '<p class="muted">Загружается карточка…</p>';
    try {
      const details = await api("GET", `/api/cases/${kind}/${id}`);
      if (!alive() || request !== selectedGeneration) return;
      const assignee = details.work?.assignee || "";
      preview.innerHTML = `<div class="reference-preview-player"><div class="sidebar-user-avatar">${esc(row.name.slice(0, 2).toUpperCase())}</div><div><h3>${esc(row.name)}</h3><p>${definition.singular} №${id} · ${esc(fmtTime(row.created))}</p></div></div><div class="reference-badges"><span class="badge purple">${definition.singular}</span><span class="badge ${isClosed(row) ? "gray" : assignee ? "purple" : "green"}">${isClosed(row) ? "Закрыто" : assignee ? "В работе" : "Открыто"}</span></div><div class="reference-detail-section"><h4>Суть</h4><p>${esc(row.description)}</p></div><div class="reference-detail-section"><h4>${kind === "report" ? "От кого" : "Автор обращения"}</h4><p>${esc(row.author)}</p></div><div class="reference-detail-section"><h4>Ответственный</h4><p>${esc(assignee || "Пока не назначен")}</p></div><div class="reference-detail-section"><h4>Материалы</h4><p>${details.evidence?.length || 0} доказательств · ${details.notes?.length || 0} заметок</p></div><div class="reference-preview-actions">${!assignee && !isClosed(row) && hasPerm(kind === "appeal" ? "MANAGE_APPEALS" : "MANAGE_REPORTS") ? '<button type="button" class="primary" data-claim>Взять в работу</button>' : ""}<a class="secondary" href="${caseLink(kind, id)}">Открыть дело</a>${kind === "report" && !isClosed(row) && hasPerm("MANAGE_BANS") ? '<button type="button" class="secondary" data-case-ban>Оформить бан</button>' : ""}</div><p class="reference-preview-hint">Полная переписка, доказательства и решение — в окне дела.</p>`;
      preview
        .querySelector("[data-claim]")
        ?.addEventListener("click", async (event) => {
          const button = event.currentTarget;
          button.disabled = true;
          try {
            await api("POST", `/api/cases/${kind}/${id}/claim`, {});
            if (alive()) {
              workSnapshot[kind + ":" + id] = { assignee: state.me.username };
              memory.filter = "working";
              memory.page = 0;
              memory.selected = id;
              host
                .querySelectorAll("[data-case-filter]")
                .forEach((item) =>
                  item.classList.toggle(
                    "active",
                    item.dataset.caseFilter === "working",
                  ),
                );
              drawList();
              await showPreview(memory.selected);
            }
          } catch (error) {
            if (alive())
              showToast("Не удалось взять дело", error.message, "error");
          } finally {
            if (button.isConnected) button.disabled = false;
          }
        });
      preview
        .querySelector("[data-case-ban]")
        ?.addEventListener("click", () =>
          window.openQuickBanModal(row.name, row.reason, row.description, id),
        );
    } catch (error) {
      if (alive() && request === selectedGeneration)
        preview.innerHTML = `<p class="error">${esc(error.message)}</p>`;
    }
  }
  async function load() {
    if (!alive() || loading) return;
    loading = true;
    try {
      const data = await api(
        "GET",
        definition.endpoint +
          (definition.endpoint.includes("?") ? "&" : "?") +
          `offset=${memory.offset || 0}&limit=${batchSize}`,
      );
      if (!alive()) return;
      rows = (
        Array.isArray(data)
          ? data
          : data.items || data.reports || data.appeals || []
      ).map(normalise);
      sourceSize = rows.length;
      workSnapshot = rows.length
        ? await api(
            "GET",
            `/api/cases/assignments?kind=${kind}&ids=${rows.map((row) => row.id).join(",")}`,
          )
        : {};
      if (!alive()) return;
      memory.selected ||= rows[0]?.id || null;
      drawList();
      await showPreview(memory.selected);
    } catch (error) {
      if (alive()) list.textContent = error.message;
    } finally {
      loading = false;
    }
  }
  host.querySelector("input").oninput = (event) => {
    memory.query = event.target.value;
    memory.page = 0;
    drawList();
    showPreview(memory.selected);
  };
  host.addEventListener("click", (event) => {
    const button = event.target.closest("button");
    if (!button || button.disabled) return;
    if (button.dataset.caseFilter) {
      memory.filter = button.dataset.caseFilter;
      memory.page = 0;
      host
        .querySelectorAll("[data-case-filter]")
        .forEach((item) => item.classList.toggle("active", item === button));
      drawList();
      showPreview(memory.selected);
    }
    if (button.dataset.caseId) showPreview(Number(button.dataset.caseId));
    if (button.dataset.page) {
      memory.page += Number(button.dataset.page);
      drawList();
      showPreview(memory.selected);
    }
    if (button.dataset.batch && !loading) {
      memory.offset = Math.max(
        0,
        (memory.offset || 0) + Number(button.dataset.batch) * batchSize,
      );
      memory.page = 0;
      memory.selected = null;
      load();
    }
    if (button.hasAttribute("data-new-ban")) window.openQuickBanModal("");
    if (button.hasAttribute("data-refresh-cases")) load();
  });
  await load();
  if (alive()) {
    clearInterval(state.pollers.separated_cases);
    state.pollers.separated_cases = setInterval(() => {
      if (alive() && !document.hidden) load();
    }, 12000);
  }
}
export async function renderSeparatedPenalties(area, history = false) {
  area.classList.remove("brand-penalty-view");
  const memory = state.separatedPenaltyView || "limits";
  if (!history && memory === "bans" && hasPerm("VIEW_BANS")) {
    await renderPunishmentsView("bans");
    if (!area.querySelector("#punishments-subtab-container")) return;
    area.querySelector("#punishments-subtabs-bar")?.remove();
    const title = area.querySelector(".view-title-block h2"),
      intro = area.querySelector(".view-title-block p");
    if (title) title.textContent = "БАНЫ И ОГРАНИЧЕНИЯ";
    if (intro)
      intro.textContent =
        "Баны, муты и проверки игроков. Обращения находятся в отдельных разделах.";
    const tabs = penaltyTabs("bans");
    area.querySelector(".view-header")?.after(tabs);
    area
      .querySelector("#punishments-subtab-container")
      ?.classList.add("brand-penalty-view");
    return;
  }
  area.innerHTML =
    "<section data-penalty-tabs></section><section data-penalty-body></section>";
  const host = area.querySelector("[data-penalty-body]");
  if (history) {
    host.innerHTML =
      '<div class="view-header"><div class="view-title-block"><h2>ИСТОРИЯ НАКАЗАНИЙ</h2><p>Выдача и снятие ограничений. Переписка по обращениям остаётся в их делах.</p></div></div><div data-history-content>Загрузка истории…</div>';
    try {
      const [historyData, bans] = await Promise.all([
        api("GET", "/api/moderation/history"),
        hasPerm("VIEW_BANS") ? api("GET", "/api/bans") : Promise.resolve([]),
      ]);
      if (!host.isConnected) return;
      const rows = [
        ...(historyData.items || []).map((item) => ({
          name: item.name || "Имя не сохранено",
          action:
            (item.kind === "MUTE" ? "Мут" : "Проверка") +
            " · " +
            (item.action === "APPLIED" ? "выдано" : "снято"),
          actor: item.actor,
          reason: item.reason,
          at: item.at,
        })),
        ...(Array.isArray(bans) ? bans : []).map((item) => ({
          name: item.targetName,
          action:
            "Бан · " +
            ({
              ACTIVE: "действует",
              DRAFT: "ожидает оформления",
              UNBANNED: "снят",
              EXPIRED: "истёк",
            }[item.status] || "завершён"),
          actor: item.creatorName,
          reason: item.ruleReason,
          at: item.createdAt,
        })),
      ].sort((a, b) => b.at - a.at);
      host.querySelector("[data-history-content]").innerHTML = rows.length
        ? `<div class="table-wrap"><table><thead><tr><th>Игрок</th><th>Действие</th><th>Причина</th><th>Сотрудник</th><th>Когда</th></tr></thead><tbody>${rows.map((row) => `<tr><td>${esc(row.name)}</td><td>${esc(row.action)}</td><td>${esc(row.reason)}</td><td>${esc(row.actor)}</td><td>${esc(fmtTime(row.at))}</td></tr>`).join("")}</tbody></table></div>`
        : "<p>История пока пуста.</p>";
    } catch (error) {
      if (host.isConnected)
        host.querySelector("[data-history-content]").textContent =
          error.message;
    }
    return;
  }
  await renderRestrictions(host);
  if (!host.isConnected) return;
  host.querySelector(".view-header")?.after(penaltyTabs("limits"));
  area.querySelector("[data-penalty-tabs]")?.remove();
  const title = host.querySelector("h2");
  if (title) title.textContent = "Баны и ограничения";
  const intro = host.querySelector(".view-title-block p");
  if (intro)
    intro.textContent =
      "Баны, муты и проверки собраны в одном разделе. Обращения находятся отдельно.";
}
function penaltyTabs(active) {
  const nav = document.createElement("nav");
  nav.className = "reference-penalty-tabs";
  nav.setAttribute("aria-label", "Виды наказаний");
  nav.innerHTML = `<button type="button" class="secondary ${active === "limits" ? "active" : ""}" data-penalty="limits">Оформление, муты и проверки</button><button type="button" class="secondary ${active === "bans" ? "active" : ""}" data-penalty="bans">Окончательные баны</button>`;
  nav.querySelector('[data-penalty="bans"]').hidden = !hasPerm("VIEW_BANS");
  nav.onclick = (event) => {
    const button = event.target.closest("[data-penalty]");
    if (button) {
      state.separatedPenaltyView = button.dataset.penalty;
      window.navigateTo("restrictions");
    }
  };
  return nav;
}
