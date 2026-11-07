import { state } from "../state.js";
export function esc(s) {
  return String(s ?? "").replace(
    /[&<>"']/g,
    (char) =>
      ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#39;",
      })[char],
  );
}

export function hasPerm(perm) {
  if (!state.me) return false;
  if (state.me.isOwner) return true;
  return Boolean(state.me.permissions && state.me.permissions.includes(perm));
}
