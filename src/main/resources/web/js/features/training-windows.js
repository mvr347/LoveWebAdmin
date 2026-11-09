import { api, esc, fmtTime, hasPerm, openChildModal, showToast } from "./ui.js";
import { explanationDialog } from "../core/dialogs.js";

const stateName = { pending: "Ожидает проверки", approved: "Подтверждено" };
const sourceName = { BAN: "Автоматическое наказание", TRUSTED: "Доверенная сессия", TRAP: "Ловушка", STAFF: "Решение сотрудника", UNKNOWN: "Старый импорт" };

export function openTrainingWindows() {
  const overlay = openChildModal(`<div class="modal-header"><h3>Обучающие данные</h3><button type="button" class="close-btn" data-modal-close aria-label="Закрыть">×</button></div><div class="modal-body training-windows"><p class="muted">Проверка относится к записанному фрагменту, а не ко всем будущим действиям игрока.</p><div class="row"><label>Игрок<input data-player type="search" placeholder="Найти по нику"></label><label>Состояние<select data-state><option value="pending">Ожидают проверки</option><option value="approved">Подтверждённые</option><option value="">Все</option></select></label></div><label>Связанный бан (необязательно)<input data-ban type="number" min="1" step="1" placeholder="Номер окончательного бана"></label><p class="muted">Связанные метки будут отозваны при снятии этого бана. Без связи решение относится только к выбранным окнам.</p><button type="button" class="secondary" data-verify disabled>Подтвердить выбранный фрагмент как чистый</button><div data-list aria-live="polite">Загрузка…</div><div class="pagination" data-pages hidden><button type="button" class="secondary" data-prev>Предыдущая</button><span data-page></span><button type="button" class="secondary" data-next>Следующая</button></div></div>`, null, "case-dialog");
  const host = overlay.querySelector(".training-windows");
  const manage = hasPerm("MANAGE_VESUVIO") && hasPerm("VIEW_VESUVIO_ADVANCED");
  let rows = [], page = 0, busy = false;
  const selected = new Set();
  const list = host.querySelector("[data-list]");
  function draw() {
    const name = host.querySelector("[data-player]").value.trim().toLowerCase();
    const state = host.querySelector("[data-state]").value;
    const filtered = rows.filter(row => (!state || row.state === state) && (!name || row.name.toLowerCase().includes(name)));
    page = Math.max(0, Math.min(page, Math.ceil(filtered.length / 20) - 1));
    const visible = filtered.slice(page * 20, page * 20 + 20);
    list.innerHTML = visible.length ? visible.map(row => `<article class="learning-review"><h4>${manage && row.state === "pending" ? `<input type="checkbox" data-select="${row.id}" aria-label="Выбрать фрагмент ${esc(row.name)}" ${selected.has(row.id) ? "checked" : ""}>` : ""} ${esc(row.name)} · ${row.domain === "aim" ? "Прицеливание" : "Клики"}</h4><p>${fmtTime(row.captured)} · Клиент: ${esc(row.client || "Неизвестен")}</p><p>${esc(sourceName[row.source] || "Неизвестный источник")} · ${esc(stateName[row.state] || row.state)} · ${row.label === 1 ? "Чит" : "Чистый"}</p>${row.reason ? `<p>${esc(row.reviewer)}: ${esc(row.reason)}</p>` : ""}${row.reference ? `<small>Бан №${esc(row.reference.split(":").at(-1))}</small>` : ""}${manage ? `<div class="row"><button type="button" class="secondary" data-id="${row.id}" data-verdict="legit">Чистый</button><button type="button" class="secondary" data-id="${row.id}" data-verdict="cheat">Чит</button>${row.state === "approved" ? `<button type="button" class="secondary" data-id="${row.id}" data-verdict="pending">На пересмотр</button>` : ""}</div>` : ""}</article>`).join("") : '<div class="reference-empty-state"><h3>Фрагментов пока нет</h3><p>Автоматические метки попадут сюда для проверки. Старый CSV сохраняется отдельно и не используется для обучения.</p></div>';
    const pages = host.querySelector("[data-pages]");
    pages.hidden = filtered.length <= 20;
    host.querySelector("[data-page]").textContent = `${page + 1} / ${Math.max(1, Math.ceil(filtered.length / 20))}`;
    host.querySelector("[data-prev]").disabled = page === 0;
    host.querySelector("[data-next]").disabled = (page + 1) * 20 >= filtered.length;
    host.querySelector("[data-verify]").disabled = !manage || !selected.size || busy;
  }
  async function load() {
    try {
      const response = await api("GET", "/api/vesuvio/training/windows");
      if (!host.isConnected) return;
      rows = Array.isArray(response) ? response : [];
      selected.clear(); draw();
    } catch (error) { if (host.isConnected) list.textContent = error.message; }
  }
  host.oninput = event => { if (event.target.matches("[data-player],[data-state]")) { page = 0; selected.clear(); draw(); } };
  host.onchange = event => {
    if (event.target.dataset.select) {
      const id = Number(event.target.dataset.select);
      if (event.target.checked) selected.add(id); else selected.delete(id);
      host.querySelector("[data-verify]").disabled = !manage || !selected.size || busy;
    }
    if (event.target.matches("[data-state]")) { page = 0; selected.clear(); draw(); }
  };
  host.onclick = async event => {
    const button = event.target.closest("button");
    if (!button || button.disabled || busy) return;
    if (button.hasAttribute("data-prev")) { page--; draw(); return; }
    if (button.hasAttribute("data-next")) { page++; draw(); return; }
    const verify = button.hasAttribute("data-verify");
    if (!verify && !button.dataset.verdict) return;
    const banText = host.querySelector("[data-ban]").value.trim();
    const banId = banText ? Number(banText) : null;
    if (banText && (!Number.isSafeInteger(banId) || banId <= 0)) { showToast("Обучение", "Укажите целый номер бана", "error"); return; }
    const selectedRows = rows.filter(row => selected.has(row.id));
    if (verify && (selectedRows.length === 0 || selectedRows.length > 100 || new Set(selectedRows.map(row => `${row.uuid}/${row.session}/${row.client}`)).size !== 1 || selectedRows.some(row => !row.session || !row.client || row.client === "unknown"))) {
      showToast("Проверенный фрагмент", "Выберите окна одного игрока, одного входа и одной известной версии клиента", "error"); return;
    }
    const current = rows.find(row => row.id === Number(button.dataset.id));
    if (!verify && button.dataset.verdict === "cheat" && current?.source === "BAN" && !banId && !current.reference) {
      showToast("Основание решения", "Для кандидата от автобана укажите номер окончательного бана", "error"); return;
    }
    busy = true;
    try {
      const reason = await explanationDialog(verify ? "Проверенный фрагмент" : "Разбор окна", verify ? "Что подтвердило честную игру в выбранном фрагменте? Будущие действия игрока продолжат проверяться." : "Укажите основание решения. Автоматическое наказание само по себе не является доказательством.");
      if (!reason || !host.isConnected) return;
      const body = { reason, ...(banId ? { banId } : {}) };
      if (verify) { body.ids = selectedRows.map(row => row.id); await api("POST", "/api/vesuvio/training/verify", body); }
      else {
        const row = rows.find(row => row.id === Number(button.dataset.id));
        body.verdict = button.dataset.verdict; body.revision = row.revision;
        // Preserve the original evidence reference when returning an approved window for review.
        if (!body.banId && row.reference?.startsWith("lovewebadmin:ban:")) body.banId = Number(row.reference.split(":").at(-1));
        await api("POST", `/api/vesuvio/training/${row.id}/review`, body);
      }
      showToast("Обучение", "Решение сохранено", "success"); await load();
    } catch (error) { showToast("Обучение", error.message, "error"); }
    finally { busy = false; if (host.isConnected) draw(); }
  };
  load(); return overlay;
}
