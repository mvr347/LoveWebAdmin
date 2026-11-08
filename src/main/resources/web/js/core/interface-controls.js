import {enhancePermissionEditors} from "./permission-editor.js";
import { enhanceSelects, installSelectControls } from "./select-controls.js";
import { installFormValidation } from "./form-validation.js";
const icons = {
  next: "M9 5l7 7-7 7",
  close: "M6 6l12 12M18 6L6 18",
  check: "M5 12l4 4L19 6",
  back: "M15 5l-7 7 7 7",
  edit: "M4 20l4-1L20 7l-3-3L5 16z",
  plus: "M12 4v16M4 12h16",
};
let installed = false;
export function enhanceButtons(root = document) {
  enhancePermissionEditors();
  for(const link of root.querySelectorAll('a[href*="case.html"]'))link.classList.add("secondary");
  for (const button of root.querySelectorAll("button,a.secondary,a.primary")) {
    if(button.matches(".reference-list-item,.settings-home-card")) continue;
    button.classList.add("ui-button");
    if (
      button.classList.contains("close-btn") ||
      button.textContent.trim() === "×"
    ) {
      button.classList.add("ui-icon-button");
      button.setAttribute(
        "aria-label",
        button.getAttribute("aria-label") || "Закрыть",
      );
      continue;
    }
    if (
      !button.matches(
        ".primary,.secondary,.danger,.filter-tag-btn,.segmented-nav-tab,.settings-tab-btn,[role=tab]",
      )
    ) {
      const label = button.textContent.trim();
      if (/^(удалить|отклонить)/i.test(label)) button.classList.add("danger");
      else if (
        (button.closest("form") && button.type === "submit") ||
        /^(сохранить|добавить|подтвердить|применить|взять)/i.test(label)
      )
        button.classList.add("primary");
    }
    if (
      button.children.length > 0 ||
      button.querySelector(".ui-button__label") ||
      button.querySelector("svg") ||
      !button.textContent.trim()
    )
      continue;
    const text = button.textContent.trim(),
      key = /далее|следующ|открыть|перейти/i.test(text)
        ? "next"
        : /назад|вернуть/i.test(text)
          ? "back"
          : /отмен|закрыть|удал|отклон/i.test(text)
            ? "close"
            : /добав|созда/i.test(text)
              ? "plus"
              : /сохран|подтвер|примен|одобр/i.test(text)
                ? "check"
                : /измен|редакт/i.test(text)
                  ? "edit"
                  : null;
    if (!key) continue;
    const icon = document.createElement("span");
    icon.className = "ui-button__icon";
    icon.setAttribute("aria-hidden", "true");
    icon.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="${icons[key]}"/></svg>`;
    const label = document.createElement("span");
    label.className = "ui-button__label";
    label.textContent = text.replace(/^[←→↗]\s*|\s*[←→↗]$/g, "");
    button.replaceChildren(icon, label);
  }
}
export function installInterfaceControls() {
  if (installed || typeof document === "undefined") return;
  installed = true;
  installFormValidation();
  installSelectControls();
  enhanceButtons();
  let scheduled = false;
  new MutationObserver(records => {
    if (!records.some(record => record.type === "childList" || record.target.matches?.("select,option"))) return;
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(() => {
      scheduled = false;
      enhanceButtons();
      enhanceSelects();
    });
  }).observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ["disabled", "selected", "aria-invalid", "aria-describedby"] });
}
