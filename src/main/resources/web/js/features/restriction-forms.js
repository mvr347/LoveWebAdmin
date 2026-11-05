import { api, esc, openModal, openChildModal, closeModal } from "./ui.js";
import { confirmDialog } from "../core/dialogs.js";

export function parseRestrictionDuration(value) {
  if (
    ["permanent", "бессрочно", "навсегда"].includes(value.trim().toLowerCase())
  )
    return -1;
  const match = /^([1-9][0-9]{0,8})([smhdw])$/.exec(value.trim().toLowerCase());
  if (!match) throw new Error("Укажите срок: 30m, 2h, 7d или бессрочно.");
  const seconds =
    Number(match[1]) * { s: 1, m: 60, h: 3600, d: 86400, w: 604800 }[match[2]];
  if (seconds < 60 || seconds > 315360000)
    throw new Error("Допустимый срок — от минуты до десяти лет.");
  return seconds;
}
const effects = {
  ban: "Игрок будет отключён и временно заблокирован. У вас будет пять минут на оформление причины, срока и доказательств. Без оформления бан снимется автоматически.",
  mute: "Игрок сможет отправлять сообщения, но остальные игроки их не увидят. Укажите срок и понятную причину.",
  freeze:
    "Игрок будет заморожен для проверки. Длительность ограничена настройками сервера. После проверки снимите ограничение.",
  kick: "Игрок будет отключён от сервера. Это не запрещает повторный вход.",
};
const duration =
  '<label>Срок<input name="duration" placeholder="Например, 30m, 2h, 7d или бессрочно" required><small>m — минуты, h — часы, d — дни, w — недели.</small></label>';
function wire(form, overlay, validate, submit, refresh) {
  let busy = false;
  const button = form.querySelector("[type=submit]");
  const error = form.querySelector("[role=alert]");
  function update() {
    form
      .querySelectorAll("input,textarea,select")
      .forEach((field) => (field.disabled = busy));
    if (busy) {
      button.disabled = true;
      return;
    }
    let message = "";
    try {
      validate(Object.fromEntries(new FormData(form)));
    } catch (e) {
      message = e.message;
    }
    button.disabled = busy || !!message || !form.checkValidity();
    error.textContent = message;
  }
  form.addEventListener("input", update);
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (busy || !overlay.isConnected) return;
    const values = Object.fromEntries(new FormData(form));
    try {
      validate(values);
      if (!form.reportValidity()) return;
    } catch (e) {
      error.textContent = e.message;
      return;
    }
    busy = true;
    update();
    try {
      const changed = await submit(values);
      if (changed !== false && closeModal(overlay)) await refresh();
    } catch (e) {
      error.textContent = e.message;
    } finally {
      busy = false;
      const responseError = error.textContent;
      update();
      if (responseError) error.textContent = responseError;
    }
  });
  update();
}
function reason(value) {
  if (!value || value.trim().length < 5)
    throw new Error("Укажите причину: минимум 5 символов.");
  if (value.length > 1000)
    throw new Error("Причина не должна превышать 1000 символов.");
}
export function openRestrictionForm(action, title, refresh, entry = null) {
  const removal = !!entry;
  const overlay =
    openModal(`<div class="modal-header"><h3>${esc(title)}</h3><button type="button" class="close-btn" data-modal-close="true" aria-label="Закрыть">×</button></div>
  <form id="restriction-form"><div class="modal-body restriction-form">
  <p class="settings-intro">${removal ? `Игрок: <strong>${esc(entry.name)}</strong>. ${action === "cancel-ban" ? "Отмена предварительного бана освобождает игрока от ожидания оформления." : "Снимается только выбранное ограничение. Остальные наказания сохраняются."}` : effects[action]}</p>
  ${removal ? "" : `<label>Ник игрока<input name="player" pattern="[A-Za-z0-9_]{1,16}" maxlength="16" autocomplete="off" required></label>`}
  ${action === "mute" ? duration : ""}
  ${action !== "ban" ? '<label>Причина<textarea name="reason" minlength="5" maxlength="1000" rows="3" placeholder="Кратко объясните действие" required></textarea></label>' : ""}
  <p role="alert" class="form-error"></p></div><div class="modal-footer"><button type="button" class="secondary" data-modal-close="true">Отмена</button><button type="submit" class="${removal ? "secondary" : "primary"}">${removal ? "Подтвердить снятие" : action === "ban" ? "Начать оформление" : "Применить"}</button></div></form>`);
  wire(
    overlay.querySelector("form"),
    overlay,
    (data) => {
      if (!removal && !/^[A-Za-z0-9_]{1,16}$/.test(data.player || ""))
        throw new Error(
          "Введите ник Minecraft: от 1 до 16 латинских букв, цифр или _.",
        );
      if (action !== "ban") reason(data.reason);
      if (action === "mute") parseRestrictionDuration(data.duration);
    },
    async (data) => {
      if (
        removal &&
        !(await confirmDialog(
          title,
          `${entry.name}\nПричина: ${data.reason}`,
          "Снять ограничение",
        ))
      )
        return false;
      if (!overlay.isConnected) return false;
      await api("POST", "/api/moderation", {
        action,
        ...data,
        ...(removal
          ? {
              player: entry.name,
              id: entry.id,
              expectedUuid: entry.uuid,
              expectedRevision: entry.revision,
            }
          : {}),
      });
    },
    refresh,
  );
}

export async function openPendingBanForm(id, refresh, nested = false) {
  const parent = document.getElementById("modal-overlay");
  const ban = await api("GET", `/api/bans/${id}`);
  if (nested && (!parent?.isConnected || parent.id !== "modal-overlay")) return;
  const overlay = (
    nested ? openChildModal : openModal
  )(`<div class="modal-header"><h3>Оформить бан №${Number(id)} · ${esc(ban.targetName)}</h3><button type="button" class="close-btn" data-modal-close="true" aria-label="Закрыть">×</button></div>
  <form id="pending-ban-form"><div class="modal-body restriction-form"><p class="settings-intro">Укажите нарушение, объяснение и срок. Срок считается с первого отключения игрока. Если пять минут уже истекли, обновите список и начните оформление заново.</p>
  <label>Нарушение<input name="ruleReason" minlength="5" maxlength="256" required></label><label>Описание<textarea name="description" minlength="5" maxlength="4000" rows="3" required></textarea></label>${duration}
  <label>Доказательства<textarea name="proofs" maxlength="8000" rows="2" placeholder="Ссылки по одной на строке"></textarea></label><p role="alert" class="form-error"></p></div>
  <div class="modal-footer"><button type="button" class="secondary" data-modal-close="true">Продолжить позже</button><button type="submit" class="primary">Подтвердить бан</button></div></form>`);
  wire(
    overlay.querySelector("form"),
    overlay,
    (data) => {
      reason(data.ruleReason);
      if (data.description.trim().length < 5)
        throw new Error("Добавьте описание нарушения: минимум 5 символов.");
      parseRestrictionDuration(data.duration);
      for (const link of data.proofs
        .split("\n")
        .map((v) => v.trim())
        .filter(Boolean)) {
        let url;
        try {
          url = new URL(link);
        } catch (_) {
          throw new Error("Каждое доказательство должно быть полной ссылкой.");
        }
        if (!["http:", "https:"].includes(url.protocol))
          throw new Error(
            "Для доказательств используйте ссылки http или https.",
          );
      }
    },
    async (data) => {
      await api("POST", "/api/bans", {
        id,
        targetName: ban.targetName,
        ruleReason: data.ruleReason,
        description: data.description,
        durationSeconds: parseRestrictionDuration(data.duration),
        proofUrls: data.proofs
          .split("\n")
          .map((v) => v.trim())
          .filter(Boolean),
        isIpBan: true,
      });
    },
    refresh,
  );
}
