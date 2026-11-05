export function showReportPicker({ targetPlayer, onSelected, api, esc, fmtTime, openChildModal, closeModal }) {
  return openChildModal(`
    <div class="modal-header"><h3>Прикрепить жалобу</h3><button type="button" class="close-btn" data-modal-close aria-label="Вернуться к бану">×</button></div>
    <div class="modal-body">
      <p class="muted">Выберите открытую жалобу${targetPlayer ? " на игрока «" + esc(targetPlayer) + "»" : ""}. Если подходящей нет, вернитесь к бану — введённые данные сохранятся.</p>
      <label>Поиск по жалобам<input id="report-picker-search" type="search" placeholder="Ник, причина или описание" autocomplete="off"></label>
      <div id="report-picker-list" class="report-picker-modal-list" aria-live="polite"></div>
      <div class="report-picker-pagination" hidden><button type="button" class="secondary" id="report-picker-prev">Предыдущие</button><span id="report-picker-page"></span><button type="button" class="secondary" id="report-picker-next">Следующие</button></div>
      <details><summary>Найти по номеру жалобы</summary><form id="report-picker-number"><label>Номер жалобы<input name="id" type="number" min="1" step="1" required></label><button type="submit" class="secondary">Прикрепить</button><p role="status" id="report-picker-error"></p></form></details>
    </div>
    <div class="modal-footer"><button type="button" class="secondary" data-modal-close>Вернуться к бану</button></div>`, overlay => {
    const find = selector => overlay.querySelector(selector);
    const list = find("#report-picker-list"), search = find("#report-picker-search");
    const previous = find("#report-picker-prev"), next = find("#report-picker-next");
    const error = find("#report-picker-error");
    let page = 0, generation = 0, disposed = false, timer, choosing = false;
    const valid = report => Number.isSafeInteger(Number(report?.id)) && Number(report.id) > 0
      && report.status === "PENDING"
      && (!targetPlayer || report.targetName?.toLowerCase() === targetPlayer.trim().toLowerCase());
    function choose(report) {
      if (disposed || choosing) return;
      if (!valid(report)) { error.textContent = "Нужна открытая жалоба на выбранного игрока."; return; }
      choosing = true;
      if (closeModal(overlay)) onSelected?.(report);
    }
    async function load() {
      const request = ++generation;
      const query = new URLSearchParams({ status: "PENDING", compact: "true", limit: "51", offset: String(page * 50) });
      if (targetPlayer) query.set("target", targetPlayer.trim());
      if (search.value.trim()) query.set("search", search.value.trim());
      previous.disabled = next.disabled = true;
      list.textContent = "Загружаем открытые жалобы…";
      try {
        const data = await api("GET", "/api/reports?" + query);
        if (disposed || request !== generation || !overlay.isConnected) return;
        const reports = (data.reports || []).filter(valid);
        previous.disabled = page === 0; next.disabled = reports.length <= 50;
        find(".report-picker-pagination").hidden = page === 0 && reports.length <= 50;
        find("#report-picker-page").textContent = "Страница " + (page + 1);
        if (!reports.length) {
          list.innerHTML = '<div class="report-picker-empty"><p>Подходящих открытых жалоб нет</p><span>Можно оформить бан без жалобы. Поля формы сохранены.</span></div>';
          return;
        }
        list.innerHTML = reports.slice(0, 50).map(report => `<button type="button" class="report-picker-card" data-pick-id="${Number(report.id)}">
          <span><b>Жалоба №${Number(report.id)} · ${esc(report.targetName)}</b><br>От ${esc(report.reporterName)}<br>${esc((report.reasons || []).join(", "))}<br>${esc(report.description || "Без описания")}</span>
          <span>${esc(fmtTime(report.createdAt))}<br>Прикрепить</span></button>`).join("");
        for (const button of list.querySelectorAll("[data-pick-id]"))
          button.onclick = () => choose(reports.find(report => Number(report.id) === Number(button.dataset.pickId)));
      } catch (failure) {
        if (!disposed && request === generation) {
          list.textContent = "Не удалось загрузить жалобы: " + failure.message;
          previous.disabled = page === 0;
        }
      }
    }
    search.oninput = () => { ++generation; clearTimeout(timer); page = 0; timer = setTimeout(load, 250); };
    previous.onclick = () => { if (page > 0) { page--; load(); } };
    next.onclick = () => { page++; load(); };
    find("#report-picker-number").onsubmit = async event => {
      event.preventDefault();
      const form = event.currentTarget, input = form.elements.id, button = form.querySelector("button");
      if (button.disabled || !form.reportValidity()) return;
      const id = Number(input.value);
      if (!Number.isSafeInteger(id) || id < 1) return;
      button.disabled = true; error.textContent = "Проверяем жалобу…";
      try {
        const result = await api("GET", "/api/reports/" + id);
        if (!disposed) { error.textContent = ""; choose(result.report); }
      } catch (failure) { if (!disposed) error.textContent = failure.message; }
      finally { button.disabled = false; }
    };
    load();
    return () => { disposed = true; ++generation; clearTimeout(timer); };
  }, "modal-lg");
}
