import { workspaceBack } from "./view-state.js";
import { confirmDialog } from "./dialogs.js";
export { confirmDialog };
export function installWorkspace() {
  workspaceBack();
  const storage = window.LovePreview?.storage || localStorage;
  if (storage.getItem("wa_theme") === "light")
    document.body.classList.add("theme-light");
  const scope =
    (location.pathname.startsWith("/preview/")
      ? "preview:" + window.LovePreview?.contextId + ":"
      : "live:") +
    (storage.getItem("wa_token") || storage.getItem("lwa_token") || "author") +
    ":" +
    location.pathname +
    location.search;
  const fieldKey = (field) => "lwa-draft:" + scope + ":" + field.id;
  for (const button of document.querySelectorAll("button"))
    if (
      !button.classList.contains("primary") &&
      !button.classList.contains("danger")
    )
      button.classList.add(
        ["reject", "merge", "kill"].includes(button.id)
          ? "danger"
          : "secondary",
      );
  for (const field of document.querySelectorAll(
    "textarea[id],input[id]:not([type=password])",
  )) {
    if (/secret|token|code|password|totp|nick/i.test(field.id)) continue;
    try {
      const saved = sessionStorage.getItem(fieldKey(field));
      if (saved && !field.value) field.value = saved;
    } catch {}
    field.addEventListener("input", () => {
      try {
        if (field.value) sessionStorage.setItem(fieldKey(field), field.value);
        else sessionStorage.removeItem(fieldKey(field));
      } catch {}
    });
  }
  document.addEventListener("lwa:mutation-complete", (event) => {
    for (const field of event.detail?.form?.querySelectorAll("[id]") || [])
      try {
        sessionStorage.removeItem(fieldKey(field));
      } catch {}
  });
}
