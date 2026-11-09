import { api, esc, fmtTime, hasPerm, openChildModal, showToast } from "./ui.js";
import { explanationDialog } from "../core/dialogs.js";

export function openBanReviews() {
  const overlay = openChildModal(
    '<div class="modal-header"><h3>Баны для разбора</h3><button type="button" class="close-btn" data-modal-close aria-label="Закрыть">×</button></div><div class="modal-body ban-review-list"><p class="muted">Загрузка очереди…</p></div>',
    null,
    "case-dialog",
  );
  const host = overlay.querySelector(".ban-review-list");
  let offset = 0,
    busy = false;
  async function load() {
    if (busy || !host.isConnected) return;
    busy = true;
    let reload = false;
    try {
      const response = await api(
        "GET",
        `/api/vesuvio/ban-reviews?offset=${offset}`,
      );
      if (!host.isConnected) return;
      const rows = response.items || [],
        total = Number(response.total) || 0;
      if (offset >= total && offset) {
        offset = Math.max(0, Math.ceil(total / 25) - 1) * 25;
        reload = true;
        return;
      }
      host.innerHTML =
        '<p class="muted">Окончательные баны с причиной «Читы». Сначала показаны записи без решения. Разбор сохраняется отдельно от наказания и не обучает модель без проверенного образца.</p>' +
        (rows.length
          ? rows
              .map(
                (row, index) =>
                  `<article class="learning-review"><h4>${esc(row.name)} · Бан №${Number(row.id)}</h4><p>${esc(row.reason)} · ${esc(row.actor)} · ${fmtTime(row.createdAt)}</p><p class="badge gray">${{ ACTIVE: "Бан действует", UNBANNED: "Бан снят", EXPIRED: "Срок бана истёк" }[row.status] || "История наказания"}</p><p>${row.verdict === "cheat" ? "Чит · подтверждено" : row.verdict === "legit" ? "Чистый · подтверждено" : "Ожидает разбора"}</p>${row.verdict ? `<p>${esc(row.reviewer)} · ${fmtTime(row.reviewedAt)}<br>${esc(row.reviewReason)}</p>` : ""}<div class="row">${hasPerm("MANAGE_VESUVIO") ? `<button type="button" class="secondary" data-ban-index="${index}" data-ban-verdict="legit">Чистый</button><button type="button" class="secondary" data-ban-index="${index}" data-ban-verdict="cheat">Чит</button>` : ""}<button type="button" class="secondary" data-open-ban="${Number(row.id)}">Открыть наказание</button></div></article>`,
              )
              .join("")
          : '<div class="reference-empty-state"><h3>Банов для разбора пока нет</h3><p>Бан появится здесь после окончательного оформления с причиной «Читы».</p></div>') +
        `<div class="pagination punishment-pagination" ${total <= 25 ? "hidden" : ""}><button type="button" class="secondary" data-prev ${offset === 0 ? "disabled" : ""}>Предыдущая</button><span>${total ? offset + 1 : 0}–${Math.min(total, offset + 25)} из ${total}</span><button type="button" class="secondary" data-next ${offset + 25 >= total ? "disabled" : ""}>Следующая</button></div>`;
      host.onclick = async (event) => {
        const button = event.target.closest("button");
        if (!button || button.disabled || busy) return;
        if (button.hasAttribute("data-prev")) {
          offset = Math.max(0, offset - 25);
          return load();
        }
        if (button.hasAttribute("data-next")) {
          offset += 25;
          return load();
        }
        if (button.dataset.openBan)
          return window.viewBanDetails(Number(button.dataset.openBan), true);
        if (!button.dataset.banVerdict) return;
        const row = rows[Number(button.dataset.banIndex)],
          verdict = button.dataset.banVerdict;
        const reason = await explanationDialog(
          "Разбор бана",
          `${row.name} · Бан №${row.id}. Решение: ${verdict === "legit" ? "Чистый" : "Чит"}. Укажите обоснование. Это не снимает и не меняет наказание.`,
        );
        if (!reason || !host.isConnected) return;
        const controls = [
          ...button.closest("article").querySelectorAll("button"),
        ];
        controls.forEach((b) => (b.disabled = true));
        try {
          await api("POST", `/api/vesuvio/ban-review/${row.id}`, {
            verdict,
            reason,
            revision: row.revision || 0,
          });
          showToast(
            "Разбор сохранён",
            `${row.name}: ${verdict === "legit" ? "Чистый" : "Чит"}`,
            "success",
          );
          await load();
        } catch (error) {
          showToast("Разбор бана", error.message, "error");
        } finally {
          controls.forEach((b) => {
            if (b.isConnected) b.disabled = false;
          });
        }
      };
    } catch (error) {
      if (host.isConnected) host.textContent = error.message;
    } finally {
      busy = false;
      if (reload && host.isConnected) load();
    }
  }
  load();
  return overlay;
}
