import { state } from "../state.js";
import { esc, hasPerm } from "./access.js";
const retired = new Set([
  "VIEW_PLAYER_INVENTORY",
  "CONFISCATE_ITEMS",
  "RESTORE_ITEMS",
]);
const dependencies = {
  MANAGE_BANS: "VIEW_BANS",
  MANAGE_REPORTS: "VIEW_REPORTS",
  MANAGE_APPEALS: "VIEW_APPEALS",
  MANAGE_VESUVIO: "VIEW_VESUVIO",
  VIEW_VESUVIO_ADVANCED: "VIEW_VESUVIO",
  REVIEW_VESUVIO_SAMPLES: "VIEW_VESUVIO_ADVANCED",
  MANAGE_VESUVIO_MODELS: "VIEW_VESUVIO_ADVANCED",
  MANAGE_LOVEAUTH: "VIEW_PLAYERS",
  KICK_PLAYERS: "VIEW_PLAYERS",
  FREEZE_PLAYERS: "VIEW_PLAYERS",
  MANAGE_ECONOMY: "VIEW_ECONOMY",
};
function group(p) {
  if (
    /OPERATORS|EXECUTE_COMMANDS|MANAGE_ROLES|MANAGE_API|MANAGE_SECURITY|MANAGE_ADMINS/.test(
      p,
    )
  )
    return "Опасные возможности";
  if (/VESUVIO/.test(p)) return "Античит";
  if (/REPORTS|APPEALS/.test(p)) return "Обращения";
  if (/BANS|MUTES|FREEZE|KICK|KILL/.test(p)) return "Наказания";
  if (/PLAYER|LOVEAUTH|TELEPORT|GAMEMODE/.test(p)) return "Игроки";
  if (/SERVER|WHITELIST|LOCKDOWN|ECONOMY|ANALYTICS|STATS/.test(p))
    return "Сервер и статистика";
  return "Вебадмин";
}
function canGrant(p) {
  if (state.me.isOwner) return true;
  let key = p;
  const visited = new Set();
  while (key && !visited.has(key)) {
    if (!hasPerm(key)) return false;
    visited.add(key);
    key = dependencies[key];
  }
  return true;
}
export function permissionEditor(active = []) {
  const groups = new Map();
  for (const p of state.ALL_PERMISSIONS.filter((p) => !retired.has(p))) {
    const key = group(p);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(p);
  }
  return `<section class="permission-editor"><div class="permission-tools"><input type="search" placeholder="Поиск права или действия" aria-label="Поиск прав"><label><input type="checkbox" data-selected-only> Только выданные</label></div><p class="permission-summary" aria-live="polite"></p><p class="muted">Просмотр, необходимый для нового действия, включается автоматически. Изменения вступят в силу после сохранения роли.</p>${[
    ...groups,
  ]
    .map(
      ([title, items]) =>
        `<details open class="permission-group"><summary>${esc(title)} <span data-group-count></span></summary><div class="permission-grid">${items
          .sort(
            (a, b) =>
              Number(!a.startsWith("VIEW_")) - Number(!b.startsWith("VIEW_")),
          )
          .map(
            (p) =>
              `<label class="permission-card" data-permission="${p}"><input type="checkbox" class="role-perm-cb" value="${p}" ${active.includes(p) ? "checked" : ""} ${!canGrant(p) ? "disabled" : ""}><span><small>${p.startsWith("VIEW_") ? "Просмотр" : "Действие"}</small><strong>${esc(state.PERMISSION_NAMES[p] || p)}</strong><span>${esc(state.PERMISSION_LABELS[p] || "Доступ к указанной функции.")}</span>${!canGrant(p) ? "<em>Нет права или необходимого доступа к просмотру</em>" : ""}</span></label>`,
          )
          .join("")}</div></details>`,
    )
    .join(
      "",
    )}<details class="permission-preview"><summary>Предпросмотр доступа</summary><div data-role-preview></div></details>${active
    .filter((p) => retired.has(p))
    .map(
      (p) =>
        `<input hidden type="checkbox" class="role-perm-cb" value="${p}" checked>`,
    )
    .join("")}</section>`;
}
export function enhancePermissionEditors() {
  for (const root of document.querySelectorAll(
    ".permission-editor:not([data-bound])",
  )) {
    root.dataset.bound = "true";
    const update = () => {
      for (const field of root.querySelectorAll("input[data-required-read]")) {
        field.disabled = !canGrant(field.value);
        delete field.dataset.requiredRead;
        field.closest(".permission-card").removeAttribute("title");
      }
      for (const action of root.querySelectorAll(
        ".permission-card input:checked",
      )) {
        let key = action.value;
        while (dependencies[key]) {
          key = dependencies[key];
          const field = root.querySelector(`input[value="${key}"]`);
          if (field?.checked) {
            field.dataset.requiredRead = "true";
            field.disabled = true;
            field.closest(".permission-card").title =
              "Необходимый просмотр для выбранных действий";
          }
        }
      }

      const query = root
          .querySelector("input[type=search]")
          .value.trim()
          .toLowerCase(),
        only = root.querySelector("[data-selected-only]").checked;
      const checked = [
        ...root.querySelectorAll(".permission-card input:checked"),
      ];
      root.querySelector(".permission-summary").textContent =
        `Выбрано прав: ${checked.length}`;
      for (const card of root.querySelectorAll(".permission-card"))
        card.hidden =
          (only && !card.querySelector("input").checked) ||
          !card.textContent.toLowerCase().includes(query);
      for (const group of root.querySelectorAll(".permission-group")) {
        const all = [...group.querySelectorAll(".permission-card")];
        group.hidden = all.every((c) => c.hidden);
        group.querySelector("[data-group-count]").textContent =
          `${all.filter((c) => c.querySelector("input").checked).length}/${all.length}`;
      }
      const granted = new Set(checked.map((c) => c.value));
      const sections = (state.SECTIONS || []).filter((section) =>
        section.anyPerms
          ? section.anyPerms.some((p) => granted.has(p))
          : granted.has(section.perm),
      );
      const preview = root.querySelector("[data-role-preview]");
      preview.replaceChildren();
      for (const [label, items] of [
        ["Разделы", sections.map((s) => s.label)],
        [
          "Действия",
          checked
            .filter((c) => !c.value.startsWith("VIEW_"))
            .map((c) => state.PERMISSION_NAMES[c.value] || c.value),
        ],
      ]) {
        const line = document.createElement("p");
        line.textContent = label + ": " + (items.join(" · ") || "Нет доступа");
        preview.append(line);
      }
    };
    root.addEventListener("input", update);
    root.addEventListener("change", (event) => {
      const field = event.target;
      if (field.matches(".role-perm-cb") && field.checked) {
        let key = field.value;
        while (dependencies[key]) {
          key = dependencies[key];
          const dep = root.querySelector(`input[value="${key}"]`);
          if (dep && !dep.disabled) dep.checked = true;
        }
      }
      update();
    });
    update();
  }
}
