import { api, esc, fmtTime, openChildModal } from "../features/ui.js";
import { state } from "../state.js";

export function mountWorkSummary(host) {
  let scope = "mine",
    busy = false,
    disposed = false,
    clockOffset = 0;
  const marker = {};
  host.workSummary = marker;
  host.innerHTML =
    '<div class="work-summary-header"><div><h3>Рабочая сводка</h3><p class="muted">Задачи, требующие внимания. Доступ определяется вашими правами.</p></div><label>Показать<select id="work-summary-scope"><option value="mine">Мои и свободные</option><option value="all">Вся очередь</option></select></label></div><div id="work-summary-cards" aria-live="polite"></div>';
  const list = host.querySelector("#work-summary-cards"),
    select = host.querySelector("select");
  const alive = () =>
    !disposed && host.isConnected && host.workSummary === marker;
  async function refresh() {
    if (!alive() || busy || document.hidden) return;
    busy = true;
    const requested = scope;
    try {
      const data = await api("GET", "/api/work-summary?scope=" + scope);
      if (!alive() || requested !== scope) return;
      clockOffset = data.serverTime - Math.floor(Date.now() / 1000);
      list.innerHTML = (data.cards || []).length
        ? data.cards
            .map(
              (card) =>
                `<article class="work-summary-card"><h4>${esc(card.title)} <span class="badge purple">${Number(card.count)}</span></h4><p>${esc(card.description)}</p>${card.deadline ? `<p title="${esc(fmtTime(card.deadline))}">Осталось: <span data-work-deadline="${Number(card.deadline)}"></span></p>` : ""}<button type="button" class="secondary" data-work-bucket="${esc(card.id)}">Открыть задачи</button></article>`,
            )
            .join("")
        : '<p class="muted">Нет задач, требующих вашего внимания.</p>';
      drawCountdowns();
    } catch (error) {
      if (alive())
        list.textContent =
          "Не удалось загрузить рабочую сводку: " + error.message;
    } finally {
      busy = false;
      if (alive() && requested !== scope) refresh();
    }
  }
  function drawCountdowns() {
    if (!alive()) return;
    for (const element of host.querySelectorAll("[data-work-deadline]")) {
      const left = Math.max(
        0,
        Number(element.dataset.workDeadline) -
          Math.floor(Date.now() / 1000) -
          clockOffset,
      );
      element.textContent = left
        ? `${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}`
        : "Проверьте состояние бана";
    }
  }
  clearInterval(state.pollers.work_summary_clock);
  state.pollers.work_summary_clock = setInterval(drawCountdowns, 1000);
  select.onchange = () => {
    scope = select.value;
    refresh();
  };
  list.onclick = (event) => {
    const button = event.target.closest("[data-work-bucket]");
    if (button) openQueue(button.dataset.workBucket, scope, refresh);
  };
  refresh();
  clearInterval(state.pollers.work_summary);
  state.pollers.work_summary = setInterval(() => {
    if (!alive()) {
      clearInterval(state.pollers.work_summary);
      return;
    }
    refresh();
  }, 15000);
  return () => {
    disposed = true;
    clearInterval(state.pollers.work_summary);
    clearInterval(state.pollers.work_summary_clock);
  };
}
function openQueue(bucket, scope, onChange) {
  openChildModal(
    '<div class="modal-header"><h3>Рабочая очередь</h3><button type="button" class="close-btn" data-modal-close aria-label="Закрыть очередь">×</button></div><div class="modal-body work-queue"></div>',
    (overlay) => {
      const host = overlay.querySelector(".work-queue");
      let offset = 0,
        generation = 0;
      async function load() {
        const request = ++generation;
        host.textContent = "Загрузка задач…";
        try {
          const data = await api(
            "GET",
            "/api/work-summary?" +
              new URLSearchParams({ scope, bucket, offset: String(offset) }),
          );
          if (!overlay.isConnected || request !== generation) return;
          const card = data.cards.find((card) => card.id === bucket);
          if (!card) {
            host.textContent = "Задачи уже выполнены или больше недоступны.";
            return;
          }
          overlay.querySelector("h3").textContent = card.title;
          if (card.destination === "wave") {
            host.innerHTML =
              '<p>Очередь наказаний доступна в разделе античита.</p><button type="button" class="secondary" id="open-wave">Открыть волну</button>';
            host.querySelector("button").onclick = async () => {
              const { navigateTo } = await import("../features/navigation.js");
              await navigateTo("anticheat");
              document
                .querySelector('#anticheat-tabs [data-tab="wave"]')
                ?.click();
            };
            return;
          }
          if (card.destination === "deliveries") {
            host.innerHTML =
              '<p>Проверьте доставку перед повторной отправкой.</p><a class="secondary" href="/deliveries.html">Открыть очередь доставки</a>';
            return;
          }
          host.innerHTML = `<p>${esc(card.description)}</p><div class="work-queue-items">${card.items.map((item) => (item.kind ? `<a class="secondary" href="/case.html?kind=${esc(item.kind)}&id=${Number(item.id)}">${esc(item.name)} · ${item.kind === "report" ? "Жалоба" : item.kind === "appeal" ? "Апелляция" : "Поддержка"} №${Number(item.id)}</a>` : `<button type="button" class="secondary" data-pending-id="${Number(item.id)}">${esc(item.name)} · оформить бан №${Number(item.id)}</button>`)).join("")}</div><div class="row pagination" ${offset === 0 && !(card.nextOffset >= 0) ? "hidden" : ""}><button type="button" class="secondary" id="work-prev" ${offset === 0 ? "disabled" : ""}>Предыдущие</button><button type="button" class="secondary" id="work-next" ${!(card.nextOffset >= 0) ? "disabled" : ""}>Следующие</button></div>`;
          host.querySelector("#work-prev").onclick = () => {
            offset = Math.max(0, offset - 20);
            load();
          };
          host.querySelector("#work-next").onclick = () => {
            offset = card.nextOffset;
            load();
          };
          host.querySelectorAll("[data-pending-id]").forEach(
            (button) =>
              (button.onclick = async () => {
                const { openPendingBan } = await import(
                  "../features/restrictions.js"
                );
                openPendingBan(
                  Number(button.dataset.pendingId),
                  () => {
                    onChange?.();
                    load();
                  },
                  true,
                );
              }),
          );
        } catch (error) {
          if (overlay.isConnected && request === generation)
            host.textContent = error.message;
        }
      }
      load();
      return () => {
        generation++;
      };
    },
    "case-dialog",
  );
}
