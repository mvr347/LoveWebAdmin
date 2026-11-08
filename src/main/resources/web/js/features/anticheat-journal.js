import { api, esc, fmtTime, hasPerm } from "./ui.js";
import { state } from "../state.js";

// Bounded recent events. The full ban history lives in the punishment workspace.
export function mountAnticheatJournal(host) {
  host.innerHTML = `<section class="anticheat-journal"><header class="view-header"><div><h3>Журнал автоматических наказаний</h3><p class="muted">Последние 100 событий. Полная история банов доступна в разделе «Наказания».</p></div>${hasPerm("VIEW_BANS") ? '<button class="secondary" type="button" data-open-history>История банов</button>' : ""}</header><div class="journal-filters"><label>Игрок или причина<input type="search" data-event-search placeholder="Ник или текст причины" maxlength="128"></label><label>Действие<select data-event-action><option value="">Все действия</option></select></label></div><p role="alert" data-journal-error></p><div data-events aria-live="polite"></div><div class="pagination punishment-pagination" data-event-pager hidden><button class="secondary" type="button" data-prev>Предыдущая</button><span data-event-count></span><button class="secondary" type="button" data-next>Следующая</button></div></section>`;
  let entries = [],
    busy = false,
    page = 0,
    markup = "";
  const labels = {
    BAN: "Бан",
    TEMPBAN: "Временный бан",
    PERMABAN: "Бессрочный бан",
    MUTE: "Мут",
    KICK: "Исключение с сервера",
    UNBAN: "Снятие бана",
    REVIEW: "Ручная проверка",
    WARN: "Предупреждение",
  };
  const input = host.querySelector("[data-event-search]"),
    select = host.querySelector("[data-event-action]"),
    results = host.querySelector("[data-events]"),
    pager = host.querySelector("[data-event-pager]");
  const actionName = (action) =>
    labels[String(action).toUpperCase()] || "Другое действие";
  function draw() {
    const query = input.value.trim().toLowerCase();
    const filtered = entries.filter(
      (row) =>
        (!select.value || row.action === select.value) &&
        [row.name, row.reason].some((text) =>
          String(text || "")
            .toLowerCase()
            .includes(query),
        ),
    );
    page = Math.min(page, Math.max(0, Math.ceil(filtered.length / 25) - 1));
    const rows = filtered.slice(page * 25, page * 25 + 25);
    const next = rows.length
      ? `<div class="table-wrap"><table><thead><tr><th>Игрок</th><th>Действие</th><th>Причина</th><th>Время</th></tr></thead><tbody>${rows.map((row) => `<tr><td>${esc(row.name)}</td><td><span class="badge gray">${esc(actionName(row.action))}</span></td><td>${esc(row.reason)}</td><td>${esc(fmtTime(row.timestamp))}</td></tr>`).join("")}</tbody></table></div>`
      : `<div class="reference-empty-state"><h3>${query || select.value ? "Событий по выбранным фильтрам нет" : "Журнал наказаний пуст"}</h3><p>Новые события появляются автоматически.</p></div>`;
    if (next !== markup) {
      markup = next;
      results.innerHTML = next;
    }
    pager.hidden = filtered.length <= 25;
    pager.querySelector("[data-prev]").disabled = page === 0;
    pager.querySelector("[data-next]").disabled =
      (page + 1) * 25 >= filtered.length;
    pager.querySelector("[data-event-count]").textContent =
      `${page + 1} из ${Math.max(1, Math.ceil(filtered.length / 25))}`;
  }
  async function refresh() {
    if (busy || !host.isConnected || document.hidden) return;
    busy = true;
    try {
      const response = await api("GET", "/api/vesuvio/punishments?limit=100");
      if (!host.isConnected) return;
      entries = Array.isArray(response) ? response.slice(0, 100) : [];
      const actions = [
        ...new Set(entries.map((row) => String(row.action || ""))),
      ].sort();
      const signature = JSON.stringify(actions);
      if (select.dataset.actions !== signature) {
        const selected = select.value;
        select.innerHTML =
          '<option value="">Все действия</option>' +
          actions
            .map(
              (action) =>
                `<option value="${esc(action)}">${esc(actionName(action))}</option>`,
            )
            .join("");
        select.value = actions.includes(selected) ? selected : "";
        select.dataset.actions = signature;
      }
      host.querySelector("[data-journal-error]").textContent = "";
      draw();
    } catch (error) {
      if (host.isConnected)
        host.querySelector("[data-journal-error]").textContent = error.message;
    } finally {
      busy = false;
    }
  }
  input.oninput = select.onchange = () => {
    page = 0;
    draw();
  };
  pager.querySelector("[data-prev]").onclick = () => {
    page--;
    draw();
  };
  pager.querySelector("[data-next]").onclick = () => {
    page++;
    draw();
  };
  const history = host.querySelector("[data-open-history]");
  if (history)
    history.onclick = async () => {
      state.punishmentWorkspace = {
        kind: "ban",
        stage: "history",
        page: 0,
        query: "",
        actor: "",
        from: "",
        to: "",
      };
      const { navigateTo } = await import("./navigation.js");
      await navigateTo("restrictions");
    };
  refresh();
  clearInterval(state.pollers.anticheat_journal);
  const timer = setInterval(() => {
    if (!host.isConnected) {
      clearInterval(timer);
      return;
    }
    refresh();
  }, 8000);
  state.pollers.anticheat_journal = timer;
}
