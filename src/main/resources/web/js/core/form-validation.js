export function passwordError(value, minimum = 12) {
  const min = Math.max(12, Number(minimum) || 12);
  if (value.length < min) return `Минимум ${min} символов`;
  if (!/[a-zа-я]/.test(value)) return "Добавьте строчную букву";
  if (!/[A-ZА-Я]/.test(value)) return "Добавьте заглавную букву";
  if (!/\d/.test(value)) return "Добавьте цифру";
  if (!/[^A-Za-zА-Яа-я0-9]/.test(value)) return "Добавьте специальный символ";
  return "";
}
export function usernameError(
  value,
  { onboarding = false, minecraft = false } = {},
) {
  if (!value) return "Укажите никнейм";
  const max = minecraft ? 16 : 24;
  if (!new RegExp(`^[A-Za-z0-9_]{3,${max}}$`).test(value))
    return `От 3 до ${max} символов: латинские буквы, цифры и _`;
  return onboarding && value.toLowerCase() === "admin"
    ? "Укажите персональный логин вместо admin"
    : "";
}
export function confirmationCodeError(value, allowBackup = false) {
  const code = value.trim().replace(/[\s-]/g, "");
  if (/^\d{6}$/.test(code) || (allowBackup && /^[A-Za-z0-9]{8}$/.test(code)))
    return "";
  return allowBackup
    ? "Введите 6 цифр или резервный код из 8 символов"
    : "Введите 6 цифр из Authenticator";
}
export function durationError(value, minimum = 1) {
  if (
    ["permanent", "бессрочно", "навсегда"].includes(value.trim().toLowerCase())
  )
    return "";
  const match = /^([1-9][0-9]{0,8})([smhdw])$/.exec(value.trim().toLowerCase());
  if (!match) return "Укажите срок: 30m, 2h, 7d или permanent";
  const seconds =
    Number(match[1]) * { s: 1, m: 60, h: 3600, d: 86400, w: 604800 }[match[2]];
  return seconds < minimum || seconds > 315360000
    ? "Срок выходит за допустимые пределы"
    : "";
}
const bound = new WeakMap(),
  lastDisabled = new WeakMap(),
  availability = new WeakMap();
let installed = false,
  sequence = 0;
function fields(scope) {
  return [...scope.querySelectorAll("input,select,textarea")].filter(
    (f) => !f.disabled && f.type !== "hidden" && f.getClientRects().length > 0,
  );
}
function localError(field, scope) {
  const value = field.value || "",
    id = field.id;
  if (
    field.required &&
    !value.trim() &&
    !["checkbox", "radio"].includes(field.type)
  )
    return "Заполните это поле";
  if (
    field.name === "duration" &&
    ["restriction-form", "pending-ban-form", "wave-edit-form"].includes(
      scope.id,
    )
  )
    return durationError(value, scope.id === "restriction-form" ? 1 : 60);
  const evidenceType = scope.querySelector("#evidence-type")?.value;
  if (id === "evidence-file" && evidenceType === "IMAGE") {
    const file = field.files?.[0];
    if (!file) return "Выберите скриншот";
    if (file.size > 700000) return "Скриншот должен быть не больше 700 КБ";
    if (!["image/png", "image/jpeg", "image/webp"].includes(file.type))
      return "Выберите PNG, JPEG или WebP";
  }
  if (id === "evidence-value" && evidenceType === "LINK") {
    try {
      const url = new URL(value);
      if (!["https:", "http:"].includes(url.protocol))
        return "Нужна ссылка http:// или https://";
    } catch {
      return "Введите полную ссылку http:// или https://";
    }
  }
  if (["ob-code", "reg-totp", "code-2fa"].includes(id))
    return confirmationCodeError(value, id === "code-2fa");
  if ((id === "ob-user" || id === "invite-username") && value)
    return usernameError(value.trim(), {
      onboarding: id === "ob-user",
      minecraft: id === "invite-username",
    });
  if (
    (field.autocomplete === "new-password" && /pass(?:word)?/i.test(id)) ||
    id === "ob-pass2"
  ) {
    const repeat = id.endsWith("2") || field.dataset.repeatFor;
    if (repeat) {
      const first = field.dataset.repeatFor
        ? scope.querySelector("#" + CSS.escape(field.dataset.repeatFor))
        : scope.querySelector("#" + CSS.escape(id.slice(0, -1)));
      if (first && value !== first.value) return "Пароли не совпадают";
    } else
      return passwordError(
        value,
        document.documentElement.dataset.passwordMinimum || 12,
      );
  }
  if (field.validity.valueMissing)
    return field.type === "checkbox"
      ? "Подтвердите согласие"
      : "Выберите значение";
  if (field.validity.typeMismatch) return "Проверьте формат значения";
  if (field.validity.patternMismatch)
    return field.dataset.formatHint || "Проверьте формат значения";
  if (field.validity.tooShort) return `Минимум ${field.minLength} символов`;
  if (field.validity.tooLong) return `Не более ${field.maxLength} символов`;
  if (field.validity.rangeUnderflow) return `Минимум ${field.min}`;
  if (field.validity.rangeOverflow) return `Максимум ${field.max}`;
  if (field.validity.badInput || field.validity.stepMismatch)
    return "Введите допустимое число";
  const remote = availability.get(field);
  if (remote && remote.value === value.trim())
    return remote.pending ? "Проверяем, свободен ли никнейм…" : remote.error;
  if ((id === "ob-user" || id === "invite-username") && value)
    return "Проверяем, свободен ли никнейм…";
  return "";
}
function feedback(field, error) {
  field.id ||= `ui-field-${++sequence}`;
  if (!field.labels?.length) {
    const label = field.parentElement.querySelector(
      ":scope > label:not([for])",
    );
    if (label && !label.querySelector("input,select,textarea"))
      label.htmlFor = field.id;
  }
  let hint = document.getElementById(field.id + "-validation");
  if (!hint) {
    hint = document.createElement("p");
    hint.id = field.id + "-validation";
    hint.className = "ui-field-feedback";
    field.after(hint);
    const described = new Set(
      (field.getAttribute("aria-describedby") || "").split(" ").filter(Boolean),
    );
    described.add(hint.id);
    field.setAttribute("aria-describedby", [...described].join(" "));
  }
  const touched = field.dataset.uiTouched === "true",
    visible = !!error && touched;
  if (hint.textContent !== (visible ? error : ""))
    hint.textContent = visible ? error : "";
  hint.hidden = !visible;
  field.setAttribute("aria-invalid", String(visible));
  let retry = document.getElementById(field.id + "-retry");
  const canRetry = visible && availability.get(field)?.retry;
  if (canRetry && !retry) {
    retry = document.createElement("button");
    retry.type = "button";
    retry.id = field.id + "-retry";
    retry.className = "secondary ui-field-retry";
    retry.textContent = "Повторить проверку";
    hint.after(retry);
    retry.onclick = () => {
      const scope =
        field.closest("[data-validation-scope]") || field.closest("form");
      remoteCheck(field, scope, 0);
      refreshValidation(scope);
    };
  }
  if (retry) retry.hidden = !canRetry;
}
export function refreshValidation(scope) {
  const buttons = bound.get(scope);
  if (!buttons) return true;
  let valid = true;
  for (const field of fields(scope)) {
    const error = localError(field, scope);
    feedback(field, error);
    valid &&= !error;
  }
  for (const button of buttons) {
    if (button.dataset.permissionDisabled === "true") {
      button.disabled = true;
      continue;
    }
    if (!button.isConnected || button.getAttribute("aria-busy") === "true")
      continue;
    if (button.disabled && lastDisabled.get(button) !== true) continue;
    const required = button.dataset.requires
      ? scope.querySelector(button.dataset.requires)
      : null;
    const disabled = !valid || !!(required && !required.value.trim());
    button.disabled = disabled;
    lastDisabled.set(button, disabled);
  }
  return valid;
}
export function bindValidation(scope, buttons) {
  if (!scope) return;
  bound.set(scope, [...buttons]);
  refreshValidation(scope);
}
function remoteCheck(field, scope, delay = 300) {
  if (!["ob-user", "invite-username"].includes(field.id)) return;
  const value = field.value.trim(),
    old = availability.get(field);
  if (old?.timer) clearTimeout(old.timer);
  old?.controller?.abort();
  const state = {
    value,
    pending: true,
    error: "",
    controller: new AbortController(),
  };
  availability.set(field, state);
  if (
    usernameError(value, {
      onboarding: field.id === "ob-user",
      minecraft: field.id === "invite-username",
    })
  )
    return;
  state.timer = setTimeout(async () => {
    if (!field.isConnected) return;
    try {
      const response = await fetch(
        `/api/auth/check-username?username=${encodeURIComponent(value)}&mode=${field.id === "ob-user" ? "onboarding" : "invite"}`,
        { signal: state.controller.signal },
      );
      const json = await response.json();
      if (availability.get(field) !== state || !field.isConnected) return;
      state.error =
        response.ok && json.success
          ? ""
          : json.error || "Этот никнейм недоступен";
      state.retry = response.status >= 500;
    } catch (error) {
      if (error.name === "AbortError") return;
      state.error = "Не удалось проверить никнейм. Повторите проверку.";
      state.retry = true;
    }
    if (availability.get(field) !== state) return;
    state.pending = false;
    refreshValidation(scope);
  }, delay);
}
export function installFormValidation() {
  if (installed) return;
  installed = true;
  function scopeFor(field) {
    return field.closest("[data-validation-scope]") || field.closest("form");
  }
  function scan() {
    for (const field of document.querySelectorAll(
      "#ob-user,#invite-username",
    )) {
      const scope = scopeFor(field),
        old = availability.get(field);
      if (
        scope &&
        field.value.trim() &&
        (!old || old.value !== field.value.trim())
      )
        remoteCheck(field, scope);
    }
    for (const form of document.querySelectorAll("form"))
      if (!bound.has(form))
        bindValidation(
          form,
          form.querySelectorAll("button[type=submit],button:not([type])"),
        );
    for (const scope of document.querySelectorAll(
      "form,[data-validation-scope]",
    ))
      refreshValidation(scope);
  }
  for (const name of ["input", "change"])
    document.addEventListener(name, (event) => {
      const field = event.target;
      if (!field.matches("input,select,textarea")) return;
      const scope = scopeFor(field);
      if (!scope) return;
      field.dataset.uiTouched = "true";
      remoteCheck(field, scope);
      refreshValidation(scope);
    });
  document.addEventListener(
    "submit",
    (event) => {
      const scope = event.target;
      if (!bound.has(scope)) return;
      for (const field of fields(scope)) field.dataset.uiTouched = "true";
      if (!refreshValidation(scope)) {
        event.preventDefault();
        event.stopImmediatePropagation();
        scope.querySelector("[aria-invalid=true]")?.focus();
      }
    },
    true,
  );
  document.addEventListener("toggle", scan, true);
  let scheduled = false;
  new MutationObserver(() => {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(() => {
      scheduled = false;
      scan();
    });
  }).observe(document.body, { childList: true, subtree: true });
  scan();
}
