import { api, esc, hasPerm, openModal, closeModal, showToast } from "./ui.js";
import { confirmDialog, explanationDialog } from "../core/dialogs.js";
import { parseDuration } from "./restrictions.js";

export async function renderWave(container) {
  let entries;
  try {
    entries = await api("GET", "/api/vesuvio/wave");
  } catch (error) {
    container.textContent = error.message;
    return;
  }
  if (!container.isConnected) return;
  const canManage = hasPerm("MANAGE_VESUVIO") && hasPerm("MANAGE_BANS");
  const names = {
    QUEUED: "В очереди",
    APPLYING: "Применяется",
    APPLIED: "Применено",
    REVIEW: "Требует проверки",
    REMOVED: "Исключён",
  };
  const queued = entries.filter((entry) => entry.state === "QUEUED").length;
  container.innerHTML = `<header class="wave-hero"><div><span class="ui-eyebrow">Автоматические наказания</span><h3>Волна наказаний</h3><p>Проверьте причины и сроки перед запуском. Спорные записи направляются на ручную проверку.</p></div><div class="wave-counter"><strong>${queued}</strong><small>ожидают применения</small></div></header>${canManage ? `<button type="button" class="primary" id="wave-execute" ${queued ? "" : "disabled"} title="${queued ? "Применить наказания в очереди" : "Нет записей для запуска"}">Запустить волну</button>` : ""}<div id="wave-entries"></div>`;

  const list = container.querySelector("#wave-entries");
  for (const entry of entries) {
    const card = document.createElement("article");
    card.className = "wave-card";
    card.innerHTML = `<strong>${esc(entry.name)}</strong> · ${esc(names[entry.state] || entry.state)}<p>${esc(entry.reason)}</p><p>${entry.duration < 0 ? "Бессрочно" : `${Math.floor(entry.duration / 60)} мин.`}</p>`;
    if (canManage && entry.state === "QUEUED") {
      const edit = document.createElement("button");
      edit.type = "button";
      edit.className = "secondary";
      edit.textContent = "Изменить наказание";
      edit.onclick = () => editWave(entry, () => renderWave(container));
      const remove = document.createElement("button");
      remove.type = "button";
      remove.className = "secondary";
      remove.textContent = "Исключить";
      remove.onclick = async () => {
        if (
          !(await confirmDialog(
            "Исключить из волны",
            `${entry.name} не будет наказан этой записью.`,
          ))
        )
          return;
        remove.disabled = true;
        try {
          await api("POST", "/api/vesuvio/wave", {
            action: "remove",
            event: entry.event,
          });
          await renderWave(container);
        } catch (error) {
          showToast("Волна", error.message, "error");
          remove.disabled = false;
        }
      };
      card.append(edit, remove);
    }
    list.append(card);
  }
  if (!entries.length)
    list.innerHTML =
      '<div class="reference-empty-state"><h3>Наказаний в очереди нет</h3><p>Здесь появятся записи, подготовленные автоматическими проверками.</p></div>';
  const execute = container.querySelector("#wave-execute");
  execute?.addEventListener("click", async () => {
    if (
      !(await confirmDialog(
        "Запустить волну",
        "Наказания для игроков в очереди будут применены автоматически.",
        "Запустить",
      ))
    )
      return;
    execute.disabled = true;
    try {
      await api("POST", "/api/vesuvio/wave", { action: "execute" });
      showToast(
        "Волна",
        "Передана серверу. Дождитесь статуса «Применено».",
        "success",
      );
      await renderWave(container);
    } catch (error) {
      showToast("Волна", error.message, "error");
      execute.disabled = false;
    }
  });
}
function editWave(entry, refresh) {
  const overlay = openModal(
    `<div class="modal-header"><h3>Наказание: ${esc(entry.name)}</h3><button type="button" class="close-btn" data-modal-close="true">×</button></div><form id="wave-edit-form"><div class="modal-body"><label>Причина<textarea name="reason" maxlength="1000" required>${esc(entry.reason)}</textarea></label><label>Срок<input name="duration" value="${entry.duration < 0 ? "permanent" : `${entry.duration}s`}" required></label><p id="wave-error" role="alert"></p></div><div class="modal-footer"><button type="button" class="secondary" data-modal-close="true">Отмена</button><button type="submit" class="primary">Сохранить</button></div></form>`,
  );
  const form = document.getElementById("wave-edit-form");
  let busy = false;
  form.onsubmit = async (event) => {
    event.preventDefault();
    if (busy) return;
    busy = true;
    const button = form.querySelector("[type=submit]");
    button.disabled = true;
    try {
      const data = Object.fromEntries(new FormData(form));
      await api("POST", "/api/vesuvio/wave", {
        action: "edit",
        event: entry.event,
        reason: data.reason,
        duration: parseDuration(data.duration),
      });
      if (closeModal(overlay)) await refresh();
    } catch (error) {
      form.querySelector("#wave-error").textContent = error.message;
    } finally {
      busy = false;
      button.disabled = false;
    }
  };
}
