import { api, esc, fmtTime, hasPerm, openChildModal, showToast } from "./ui.js";
import { explanationDialog, confirmDialog } from "../core/dialogs.js";
import { formatViolationLevel } from "../core/event-time.js";

export function drawViolations(container, rows, refresh) {
  const manage = hasPerm("MANAGE_VESUVIO") && hasPerm("VIEW_VESUVIO_ADVANCED");
  const markup = rows
    .map((row, index) => {
      const name = row.playerName || row.name || "Игрок",
        check = row.checkName || row.check || row.type || "Проверка";
      const decision =
        row.verdict === "legit"
          ? "Чистый · ложное срабатывание"
          : row.verdict === "cheat"
            ? "Чит · подтверждено сотрудником"
            : "Не разобрано";
      const reviewed = row.verdict === "legit" || row.verdict === "cheat";
      return `<article class="violation-card ${row.verdict === "legit" ? "violation-legit" : ""}" data-violation-card="${index}"><div class="violation-info"><img src="https://mc-heads.net/avatar/${encodeURIComponent(name)}/32" class="player-avatar-sm" width="32" height="32" alt=""><div class="violation-meta"><h4>${esc(name)} <span class="badge gray">${esc(check)}</span></h4><div class="violation-details">Срабатывание · VL: <b>${formatViolationLevel(row.vl ?? row.vlScore)}</b> · ${fmtTime(row.timestamp)}</div><p class="violation-verdict ${row.verdict === "legit" ? "legit" : ""}">${esc(decision)}</p>${reviewed ? `<small>${esc(row.reviewer || "Сотрудник")} · ${fmtTime(row.reviewedAt)}</small>` : ""}${row.explanation ? `<details><summary>Причина срабатывания</summary><p>${esc(row.explanation)}</p></details>` : ""}${row.reviewReason ? `<p class="violation-review-reason">${esc(row.reviewReason)}</p>` : ""}</div></div><div class="violation-actions"><div class="violation-review-buttons">${manage ? `<button class="secondary" type="button" data-review="legit" ${row.id ? "" : "disabled"} title="${row.id ? "Отметить это срабатывание как ложное" : "Обновите античит для разбора исторических срабатываний"}">Чистый</button><button class="secondary" type="button" data-review="cheat" ${row.id ? "" : "disabled"}>Чит</button>` : ""}</div><div class="violation-other-actions"><button class="secondary" type="button" data-event-action="observe">Наблюдение</button>${hasPerm("MANAGE_BANS") ? '<button class="secondary" type="button" data-event-action="ban">Выдать бан</button>' : ""}${hasPerm("VIEW_PLAYERS") ? '<button class="secondary" type="button" data-event-action="profile">Досье</button>' : ""}</div></div></article>`;
    })
    .join("");
  if (container.streamMarkup !== markup) {
    container.innerHTML = markup;
    container.streamMarkup = markup;
  }
  container.onclick = async (event) => {
    const button = event.target.closest("button"),
      card = button?.closest("[data-violation-card]");
    if (!button || button.disabled || !card) return;
    const row = rows[Number(card.dataset.violationCard)];
    const name = row.playerName || row.name || "Игрок";
    if (button.dataset.eventAction === "observe")
      return window.openObservationView(name, row.uuid || row.playerUuid || "");
    if (button.dataset.eventAction === "profile")
      return window.viewPlayerProfile(name);
    if (button.dataset.eventAction === "ban")
      return window.openQuickBanModal(name, "");
    if (!button.dataset.review || !manage || !row.id) return;
    const verdict = button.dataset.review;
    const reason = await explanationDialog(
      verdict === "legit"
        ? "Чистый: ложное срабатывание"
        : "Подтвердить использование чита",
      `Игрок: ${name}. Проверка: ${row.checkName || row.check}. Укажите обоснование разбора этой записи. Решение сохраняется в журнале; оно не выдаёт наказание автоматически.`,
    );
    if (!reason || !container.isConnected) return;
    const buttons = [...card.querySelectorAll("[data-review]")];
    buttons.forEach((b) => (b.disabled = true));
    try {
      await api("POST", `/api/vesuvio/violation/${Number(row.id)}/review`, {
        verdict,
        reason,
        revision: row.revision || 0,
      });
      showToast(
        "Разбор сохранён",
        verdict === "legit"
          ? "Срабатывание отмечено как ложное"
          : "Срабатывание подтверждено сотрудником",
        "success",
      );
      await refresh();
    } catch (error) {
      showToast("Разбор", error.message, "error");
      await refresh();
    } finally {
      buttons.forEach((b) => {
        if (b.isConnected) b.disabled = false;
      });
    }
  };
}

export function openLearningReviews() {
  const overlay = openChildModal(
    '<div class="modal-header"><h3>Образцы для разбора</h3><button class="close-btn" data-modal-close aria-label="Закрыть">×</button></div><div class="modal-body learning-reviews"><p class="muted">Загрузка образцов…</p></div>',
    null,
    "case-dialog",
  );
  const host = overlay.querySelector(".learning-reviews");
  async function load() {
    try {
      const rows = await api("GET", "/api/vesuvio/reviews?limit=50");
      if (!host.isConnected) return;
      host.innerHTML =
        '<p class="muted">Решение обучает модель на данных выбранного образца. Исторические события разбираются в основном потоке.</p>' +
        (rows.length
          ? rows
              .map(
                (row, index) =>
                  `<article class="learning-review"><h4>${esc(row.name)} · ${row.domain === "aim" ? "Прицеливание" : "Клики"}</h4><p>${fmtTime(row.timestamp)}</p><div class="row"><button class="secondary" data-sample="${index}" data-verdict="legit">Чистый</button><button class="secondary" data-sample="${index}" data-verdict="cheat">Чит</button></div></article>`,
              )
              .join("")
          : '<div class="reference-empty-state"><h3>Образцов для обучения пока нет</h3><p>Старые записи без исходных признаков не используются для обучения модели.</p></div>');
      host.onclick = async (event) => {
        const button = event.target.closest("[data-sample]");
        if (!button || button.disabled) return;
        const row = rows[Number(button.dataset.sample)],
          verdict = button.dataset.verdict;
        if (
          !(await confirmDialog(
            "Разбор образца",
            `${row.name}: ${verdict === "legit" ? "чистый" : "чит"}. Передать эту отметку в обучение?`,
          ))
        )
          return;
        if (!host.isConnected) return;
        button.disabled = true;
        try {
          await api(
            "POST",
            `/api/vesuvio/sample/${encodeURIComponent(row.id)}/review`,
            { verdict },
          );
          await load();
        } catch (error) {
          showToast("Обучение", error.message, "error");
          if (button.isConnected) button.disabled = false;
        }
      };
    } catch (error) {
      if (host.isConnected) host.textContent = error.message;
    }
  }
  load();
  return overlay;
}
