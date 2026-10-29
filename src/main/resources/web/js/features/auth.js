import { state } from "../state.js";
import {
  api,
  esc,
  initGlobalShortcuts,
  initTooltipEngine,
  renderSvgIcon,
  setToken,
  showToast,
} from "./ui.js";
import { renderAppLayout } from "./navigation.js";
// ---------- Boot & Authentication Gate ----------

export async function boot() {
  initTooltipEngine();
  initGlobalShortcuts();
  if (!state.token) {
    await renderAuthGate();
    return;
  }
  try {
    state.me = await api("GET", "/api/me");
    renderAppLayout();
  } catch (e) {
    setToken(null);
    await renderAuthGate();
  }
}
export async function renderAuthGate() {
  let status = {
    ownerExists: false,
    initialSetupNeeded: false,
    previewEnabled: false,
  };
  try {
    status = await api("GET", "/api/auth/status");
    state.serverStatus = status;
  } catch (e) {
    state.serverStatus = status;
    state.app.innerHTML = `
                <div class="auth-screen">
                    <div class="auth-card">
                        <div class="logo"><span style="color:var(--accent);">◈</span> WebAdmin</div>
                        <p class="error">${esc(e.message)}</p>
                        <button class="primary" onclick="location.reload()" style="width:100%;">ПОВТОРИТЬ ПОПЫТКУ</button>
                    </div>
                </div>`;
    return;
  }
  if (status.initialSetupNeeded || !status.ownerExists) {
    renderMasterOnboarding();
  } else {
    renderLogin();
  }
}

// ---------- 2FA & QR Code Utilities ----------
export function renderTotpQrCode(containerEl, otpUrl) {
  if (!containerEl) return;
  containerEl.innerHTML = "";
  try {
    if (typeof QRCode !== "undefined") {
      new QRCode(containerEl, {
        text: otpUrl,
        width: 180,
        height: 180,
        colorDark: "#000000",
        colorLight: "#ffffff",
        correctLevel:
          typeof QRCode.CorrectLevel !== "undefined"
            ? QRCode.CorrectLevel.H || QRCode.CorrectLevel.M
            : 0,
      });
      // qrcode.js: гарантируем корректное отображение img или canvas
      setTimeout(() => {
        const canvas = containerEl.querySelector("canvas");
        const img = containerEl.querySelector("img");
        if (img && img.src && img.src.length > 50) {
          img.style.display = "block";
          img.style.width = "180px";
          img.style.height = "180px";
          img.style.margin = "0 auto";
          if (canvas) canvas.style.display = "none";
        } else if (canvas) {
          canvas.style.display = "block";
          canvas.style.width = "180px";
          canvas.style.height = "180px";
          canvas.style.margin = "0 auto";
          if (img) img.style.display = "none";
        }
      }, 50);
      return;
    }
  } catch (e) {
    console.error("Ошибка создания QRCode:", e);
  }
  containerEl.innerHTML = `
            <div style="color:#111; font-size:12px; text-align:center; padding:24px 10px; font-weight:600; line-height:1.5;">
                <div style="margin-bottom:8px; display:flex; justify-content:center;">${renderSvgIcon("key", "violet", 24)}</div>
                Используйте вкладку <b>«Секретный код»</b><br>для ввода ключа в Authenticator.
            </div>`;
}
export function validatePasswordClient(p) {
  if (!p || p.length < 10) return "Минимум 12 символов";
  if (!/[A-Za-zА-Яа-я]/.test(p)) return "Нужна хотя бы одна буква";
  if (!/\d/.test(p)) return "Нужна хотя бы одна цифра";
  if (!/[^A-Za-zА-Яа-я0-9]/.test(p)) return "Нужен спецсимвол";
  return null;
}
export function updatePasswordMeter(password, fillEl, hintsEl) {
  if (!fillEl) return;
  const p = password || "";
  const hasLen = p.length >= 12;
  const hasLetter = /[A-Za-zА-Яа-я]/.test(p);
  const hasDigit = /\d/.test(p);
  const hasSpecial = /[^A-Za-zА-Яа-я0-9]/.test(p);
  if (hintsEl) {
    const hints = hintsEl.querySelectorAll("span");
    if (hints.length >= 4) {
      hints[0].className = hasLen ? "valid" : "invalid";
      hints[0].textContent = (hasLen ? "✓ " : "• ") + "10+ симв.";
      hints[1].className = hasLetter ? "valid" : "invalid";
      hints[1].textContent = (hasLetter ? "✓ " : "• ") + "буква";
      hints[2].className = hasDigit ? "valid" : "invalid";
      hints[2].textContent = (hasDigit ? "✓ " : "• ") + "цифра";
      hints[3].className = hasSpecial ? "valid" : "invalid";
      hints[3].textContent = (hasSpecial ? "✓ " : "• ") + "спецсимвол";
    }
  }
  const score =
    (hasLen ? 1 : 0) +
    (hasLetter ? 1 : 0) +
    (hasDigit ? 1 : 0) +
    (hasSpecial ? 1 : 0);
  const pct = (score / 4) * 100;
  fillEl.style.width = pct + "%";
  if (score <= 1) fillEl.style.background = "var(--red, #ef4444)";
  else if (score <= 3) fillEl.style.background = "var(--yellow, #eab308)";
  else fillEl.style.background = "var(--green, #22c55e)";
}
export function renderSecretMasked(el, secret) {
  if (!el || !secret) return;
  const groups = secret.match(/.{1,4}/g)?.join(" ") || secret;
  const maskedText = "•••• •••• •••• ••••";
  el.innerHTML = `
            <div style="display:flex; align-items:center; justify-content:space-between; gap:10px; background:var(--card-inner-bg, rgba(255,255,255,0.03)); border:1px solid var(--border); border-radius:6px; padding:8px 12px;">
                <span class="totp-secret-mask" title="Нажмите, чтобы показать/скрыть">${maskedText}</span>
                <button type="button" class="secondary btn-sm" id="btn-copy-secret" style="display:inline-flex; align-items:center; gap:4px; font-size:11px;">
                    ${renderSvgIcon("copy", "gray", 12)} Копировать
                </button>
            </div>
        `;
  const mask = el.querySelector(".totp-secret-mask");
  let shown = false;
  const show = () => {
    if (mask) {
      mask.textContent = groups;
      shown = true;
    }
  };
  const hide = () => {
    if (mask) {
      mask.textContent = maskedText;
      shown = false;
    }
  };
  mask?.addEventListener("click", () => (shown ? hide() : show()));
  mask?.addEventListener("mouseenter", show);
  mask?.addEventListener("mouseleave", hide);
  el.querySelector("#btn-copy-secret")?.addEventListener("click", async () => {
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(secret);
      } else {
        const ta = document.createElement("textarea");
        ta.value = secret;
        document.body.appendChild(ta);
        ta.select();
        document.execCommand("copy");
        document.body.removeChild(ta);
      }
      showToast(
        "Скопировано",
        "Секретный ключ скопирован в буфер обмена",
        "info",
      );
    } catch (_) {}
  });
}
export function bindTotpInput(input) {
  if (!input) return;
  input.classList.add("totp-input-masked");
  input.setAttribute("placeholder", "000-000");
  input.setAttribute("maxlength", "7");
  input.setAttribute("inputmode", "numeric");
  input.setAttribute("autocomplete", "one-time-code");
  if (input.dataset.totpBound === "1") return;
  input.dataset.totpBound = "1";
  input.addEventListener("input", () => {
    let d = (input.value || "").replace(/\D/g, "").slice(0, 6);
    input.value = d.length > 3 ? d.slice(0, 3) + "-" + d.slice(3) : d;
  });
}

/** Alias used by login 2FA screen (was missing → ReferenceError, form submit never attached) */
export function bindLogin2faInput(input) {
  if (!input) return;
  input.setAttribute("maxlength", "9");
  input.setAttribute("placeholder", "000-000");
  input.classList.add("totp-input-masked");
  input.setAttribute("inputmode", "numeric");
  input.setAttribute("autocomplete", "one-time-code");
  if (input.dataset.totpBound === "1") return;
  input.dataset.totpBound = "1";
  input.addEventListener("input", () => {
    const raw = (input.value || "").replace(/[^0-9A-Za-z]/g, "");
    if (/^\d*$/.test(raw)) {
      const d = raw.slice(0, 6);
      input.value = d.length > 3 ? d.slice(0, 3) + "-" + d.slice(3) : d;
    } else {
      const d = raw.slice(0, 8).toUpperCase();
      input.value = d.length > 4 ? d.slice(0, 4) + "-" + d.slice(4) : d;
    }
  });
}

/** Убирает пробелы и дефисы из кода 2FA (000-000 → 000000) */
export function normalizeAuthCode(value) {
  return (value || "").trim().replace(/[\s-]/g, "");
}
export function setupSecretCopy(buttonId, textToCopy) {
  const btn = document.getElementById(buttonId);
  if (!btn) return;
  btn.addEventListener("click", async () => {
    const originalText = btn.innerHTML;
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(textToCopy);
      } else {
        const ta = document.createElement("textarea");
        ta.value = textToCopy;
        ta.style.position = "fixed";
        ta.style.opacity = "0";
        document.body.appendChild(ta);
        ta.select();
        document.execCommand("copy");
        document.body.removeChild(ta);
      }
      btn.innerHTML = `${renderSvgIcon("check", "green", 12)} Скопировано!`;
      btn.classList.add("copied");
      setTimeout(() => {
        btn.innerHTML = originalText;
        btn.classList.remove("copied");
      }, 2000);
    } catch (e) {
      console.error("Copy error:", e);
    }
  });
}
export function renderBackupCodesScreen(username, backupCodes, onComplete) {
  const codes = backupCodes && backupCodes.length ? backupCodes : [];
  state.app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card" style="max-width:520px;">
                    <div class="logo"><span style="color:var(--green); display:inline-flex; vertical-align:middle; margin-right:6px;">${renderSvgIcon("check", "green", 18)}</span> 2FA Настроена</div>
                    <div class="sub">Аккаунт <b>${esc(username)}</b> успешно привязан! Сохраните эти <b>8 резервных кодов</b>. Каждый код можно использовать один раз для входа, если у вас не будет доступа к приложению аутентификатора:</div>

                    <div class="backup-codes-grid">
                        ${codes.map((c) => `<div class="backup-code-pill">${esc(c)}</div>`).join("")}
                    </div>

                    <div style="display:flex; gap:10px; margin-bottom:16px;">
                        <button type="button" class="secondary" id="btn-copy-backup-codes" style="flex:1; display:inline-flex; align-items:center; justify-content:center; gap:6px;">${renderSvgIcon("copy", "gray", 14)} Скопировать все</button>
                        <button type="button" class="secondary" id="btn-download-backup-codes" style="flex:1; display:inline-flex; align-items:center; justify-content:center; gap:6px;">${renderSvgIcon("download", "gray", 14)} Скачать (.txt)</button>
                    </div>

                    <label style="display:flex; align-items:center; gap:8px; font-size:12px; color:var(--text-muted); cursor:pointer; margin-bottom:16px; user-select:none;">
                        <input type="checkbox" id="check-backup-saved" style="accent-color:var(--accent); width:16px; height:16px; cursor:pointer;">
                        <span>Я сохранил резервные коды в надёжном месте</span>
                    </label>

                    <button type="button" class="primary" id="btn-finish-onboarding" style="width:100%;" disabled>
                        ПЕРЕЙТИ В ПАНЕЛЬ УПРАВЛЕНИЯ
                    </button>
                </div>
            </div>`;
  const chk = document.getElementById("check-backup-saved");
  const finishBtn = document.getElementById("btn-finish-onboarding");
  chk?.addEventListener("change", () => {
    if (finishBtn) finishBtn.disabled = !chk.checked;
  });
  document
    .getElementById("btn-copy-backup-codes")
    ?.addEventListener("click", async (e) => {
      const btn = e.currentTarget;
      const text = codes.join("\n");
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          await navigator.clipboard.writeText(text);
        } else {
          const ta = document.createElement("textarea");
          ta.value = text;
          document.body.appendChild(ta);
          ta.select();
          document.execCommand("copy");
          document.body.removeChild(ta);
        }
        btn.innerHTML = `${renderSvgIcon("check", "green", 14)} Коды скопированы!`;
        setTimeout(() => {
          btn.innerHTML = `${renderSvgIcon("copy", "gray", 14)} Скопировать все`;
        }, 2000);
      } catch (_) {}
    });
  document
    .getElementById("btn-download-backup-codes")
    ?.addEventListener("click", () => {
      const content =
        `WebAdmin 2FA Backup Codes\nAccount: ${username}\nCreated: ${new Date().toLocaleString("ru-RU")}\n\n` +
        codes.join("\n");
      const blob = new Blob([content], {
        type: "text/plain;charset=utf-8",
      });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `webadmin-backup-codes-${username}.txt`;
      a.click();
      URL.revokeObjectURL(url);
    });
  finishBtn?.addEventListener("click", () => {
    onComplete();
  });
}
export function renderLogin() {
  state.app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card">
                    <div class="logo"><span style="color:var(--accent);">◈</span> WebAdmin</div>
                    <div class="sub">Панель управления Minecraft-сервером</div>
                    ${state.serverStatus?.previewEnabled ? '<a class="secondary" href="/preview/">Открыть превью · тестовые данные</a>' : ""}
                    <form id="login-form">
                        <div class="form-group">
                            <label>Никнейм сотрудника</label>
                            <input type="text" id="login-user" placeholder="Например: Lovelace" required autofocus autocomplete="username">
                        </div>
                        <div class="form-group">
                            <label>Пароль</label>
                            <input type="password" id="login-pass" placeholder="••••••••" required autocomplete="current-password">
                        </div>
                        <div id="login-err" class="error" style="display:none;"></div>
                        <button type="submit" class="primary" id="login-submit" style="width:100%; margin-top:10px;">ВОЙТИ В СИСТЕМУ</button>
                    </form>
                    <div class="auth-switch-box">
                        Новый сотрудник? <a href="#" class="auth-switch-link" id="link-register-invite">Регистрация по приглашению</a>
                    </div>
                </div>
            </div>`;
  document
    .getElementById("link-register-invite")
    ?.addEventListener("click", (e) => {
      e.preventDefault();
      renderInviteRegister();
    });
  document
    .getElementById("login-form")
    ?.addEventListener("submit", async (e) => {
      e.preventDefault();
      const u = document.getElementById("login-user").value.trim();
      const p = document.getElementById("login-pass").value;
      const err = document.getElementById("login-err");
      const btn = document.getElementById("login-submit");
      err.style.display = "none";
      btn.disabled = true;
      btn.textContent = "ВХОД...";
      try {
        const res = await api("POST", "/api/auth/login", {
          username: u,
          password: p,
        });
        if (res.status === "NEED_2FA") {
          render2faVerification(res.username || u, res.challenge);
          return;
        }
        setToken(res.token);
        state.me = await api("GET", "/api/me");
        renderAppLayout();
      } catch (ex) {
        err.textContent = ex.message;
        err.style.display = "block";
        btn.disabled = false;
        btn.textContent = "ВОЙТИ В СИСТЕМУ";
      }
    });
}
export function renderInviteRegister() {
  state.app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card" style="max-width:480px;">
                    <div class="logo"><span style="color:var(--accent);">◈</span> Регистрация по приглашению</div>
                    <div class="sub">Введите код приглашения, полученный от руководства сервера</div>

                    <!-- Step A: Code Form -->
                    <form id="invite-code-form">
                        <div class="form-group">
                            <label>Код приглашения</label>
                            <input id="reg-invite-code" placeholder="LWA-XXXXXXXX"
                                   autocomplete="one-time-code" style="text-transform:uppercase; text-align:center; font-family:'JetBrains Mono'; font-size:18px; font-weight:700; letter-spacing:2px;" required autofocus>
                        </div>
                        <div id="invite-code-err" class="error" style="display:none; margin-bottom:12px;"></div>
                        <button type="submit" class="primary" id="btn-validate-code" style="width:100%; margin-top:6px;">ПРОВЕРИТЬ КОД</button>
                        <button type="button" class="secondary" id="btn-invite-cancel" style="width:100%; margin-top:8px;">← ВЕРНУТЬСЯ КО ВХОДУ</button>
                    </form>

                    <!-- Step B: Account + Password + 2FA Form (Hidden initially) -->
                    <div id="invite-step-b" style="display:none;"></div>
                </div>
            </div>`;
  document
    .getElementById("btn-invite-cancel")
    ?.addEventListener("click", () => {
      renderLogin();
    });
  document
    .getElementById("invite-code-form")
    ?.addEventListener("submit", async (e) => {
      e.preventDefault();
      const codeInput = document.getElementById("reg-invite-code");
      const code = (codeInput?.value || "").trim().toUpperCase();
      const errEl = document.getElementById("invite-code-err");
      const btn = document.getElementById("btn-validate-code");
      if (!code) return;
      if (errEl) errEl.style.display = "none";
      btn.disabled = true;
      btn.textContent = "ПРОВЕРКА...";
      try {
        const inviteData = await api("POST", "/api/auth/validate-invite", {
          code,
        });
        renderInviteStepB(code, inviteData);
      } catch (ex) {
        if (errEl) {
          errEl.textContent = ex.message || "Недействительный код приглашения";
          errEl.style.display = "block";
        }
        btn.disabled = false;
        btn.textContent = "ПРОВЕРИТЬ КОД";
      }
    });
}
export async function renderInviteStepB(inviteCode, inviteData) {
  const stepB = document.getElementById("invite-step-b");
  const codeForm = document.getElementById("invite-code-form");
  if (!stepB) return;
  codeForm.style.display = "none";
  stepB.style.display = "block";
  const username = inviteData.username || "";
  const roleName = inviteData.roleName || "Сотрудник";
  stepB.innerHTML = `
            <div style="background:var(--card-inner-bg, rgba(255,255,255,0.03)); border:1px solid var(--border); border-radius:6px; padding:12px 14px; margin-bottom:16px;">
                <div style="font-size:13px; color:var(--text-main);">Сотрудник: <b>${esc(username)}</b></div>
                <div style="font-size:12px; color:var(--text-muted); margin-top:3px;">Назначенная роль: <b style="color:var(--accent);">${esc(roleName)}</b></div>
            </div>

            <form id="invite-reg-final-form" novalidate>
                <div class="form-group">
                    <label>Придумайте пароль *</label>
                    <input type="password" id="reg-password"
                           autocomplete="new-password"
                           placeholder="Минимум 12 символов"
                           passwordrules="minlength: 10; required: lower; required: upper; required: digit; required: special;" required>
                    <div class="password-strength-bar">
                        <div class="password-strength-fill" id="reg-pass-strength-fill"></div>
                    </div>
                    <div class="password-strength-hints" id="reg-pass-strength-hints">
                        <span class="invalid">• 12+ симв.</span>
                        <span class="invalid">• буква</span>
                        <span class="invalid">• цифра</span>
                        <span class="invalid">• спецсимвол</span>
                    </div>
                </div>

                <div class="form-group">
                    <label>Повторите пароль *</label>
                    <input type="password" id="reg-password2" autocomplete="new-password" placeholder="••••••••" required>
                </div>

                <!-- 2FA Section -->
                <div style="margin-top:16px; margin-bottom:12px;">
                    <div style="font-weight:700; font-size:13px; color:var(--text-heading); margin-bottom:4px;">Двухфакторная защита (2FA)</div>
                    <div style="font-size:11.5px; color:var(--text-muted); line-height:1.4;">
                        Отсканируйте QR в Google Authenticator. Кликните по QR для обновления ключа.
                    </div>
                </div>

                <div class="totp-qr-wrapper" style="margin-bottom:12px;">
                    <div class="totp-qr-box" id="reg-qrcode-box" title="Нажмите, чтобы перегенерировать QR-код" style="cursor:pointer;"></div>
                </div>

                <div class="form-group" style="margin-bottom:8px;">
                    <label>Секретный ключ (для ручного ввода в приложение)</label>
                    <div id="reg-secret-container"></div>
                </div>

                <div class="form-group" style="margin-top:20px;">
                    <label>Код из приложения Authenticator *</label>
                    <div style="font-size:11px;color:var(--text-muted);margin-bottom:8px;line-height:1.4;">После добавления ключа введите 6 цифр из приложения (формат 000-000)</div>
                    <input id="reg-totp" inputmode="numeric" autocomplete="one-time-code"
                           placeholder="000-000" maxlength="7" required
                           class="totp-input-masked"
                           style="text-align:center;font-size:20px;font-weight:700;letter-spacing:4px;font-family:'JetBrains Mono',monospace;">
                </div>

                <div id="reg-final-err" class="error" style="display:none; margin-bottom:12px;"></div>
                <button type="submit" class="primary" id="btn-reg-finish" style="width:100%; margin-top:6px;">ЗАВЕРШИТЬ РЕГИСТРАЦИЮ</button>
                <button type="button" class="secondary" id="btn-reg-back" style="width:100%; margin-top:8px;">← НАЗАД К ВВОДУ КОДА</button>
            </form>
        `;
  const passInput = document.getElementById("reg-password");
  const pass2Input = document.getElementById("reg-password2");
  const fillEl = document.getElementById("reg-pass-strength-fill");
  const hintsEl = document.getElementById("reg-pass-strength-hints");
  const qrBox = document.getElementById("reg-qrcode-box");
  const secretContainer = document.getElementById("reg-secret-container");
  const totpInput = document.getElementById("reg-totp");
  passInput?.addEventListener("input", () => {
    updatePasswordMeter(passInput.value, fillEl, hintsEl);
  });
  bindTotpInput(totpInput);
  let currentSecret = inviteData.totpSecret || "";
  if (currentSecret && qrBox) {
    renderTotpQrCode(
      qrBox,
      inviteData.otpUrl ||
        "otpauth://totp/WebAdmin:" +
          encodeURIComponent(username) +
          "?secret=" +
          currentSecret +
          "&issuer=WebAdmin",
    );
  }
  if (currentSecret && secretContainer)
    renderSecretMasked(secretContainer, currentSecret);
  const fetchAndRenderTotp = async () => {
    try {
      const res = await api("POST", "/api/auth/validate-invite", {
        code: inviteCode,
      });
      currentSecret = res.totpSecret || res.secret || currentSecret;
      if (qrBox) renderTotpQrCode(qrBox, res.otpUrl);
      if (secretContainer) renderSecretMasked(secretContainer, currentSecret);
    } catch (e) {
      console.error("TOTP setup error:", e);
    }
  };
  qrBox?.addEventListener("click", async () => {
    await fetchAndRenderTotp();
    showToast("2FA обновлен", "Сгенерирован новый секретный ключ", "info");
  });
  document.getElementById("btn-reg-back")?.addEventListener("click", () => {
    renderInviteRegister();
  });
  document
    .getElementById("invite-reg-final-form")
    ?.addEventListener("submit", async (e) => {
      e.preventDefault();
      const password = passInput.value;
      const pass2 = pass2Input.value;
      const digitsOnly =
        typeof normalizeAuthCode === "function"
          ? normalizeAuthCode(totpInput.value || "")
          : (totpInput.value || "").replace(/\D/g, "");
      const errEl = document.getElementById("reg-final-err");
      const submitBtn = document.getElementById("btn-reg-finish");
      if (errEl) errEl.style.display = "none";
      const passErr = validatePasswordClient(password);
      if (passErr) {
        if (errEl) {
          errEl.textContent = passErr;
          errEl.style.display = "block";
        }
        return;
      }
      if (password !== pass2) {
        if (errEl) {
          errEl.textContent = "Пароли не совпадают";
          errEl.style.display = "block";
        }
        return;
      }
      if (digitsOnly.length !== 6) {
        if (errEl) {
          errEl.textContent = "Введите 6 цифр кода подтверждения 2FA";
          errEl.style.display = "block";
        }
        return;
      }
      submitBtn.disabled = true;
      submitBtn.textContent = "РЕГИСТРАЦИЯ...";
      try {
        const res = await api("POST", "/api/auth/register", {
          inviteCode,
          password,
          totpSecret: currentSecret,
          totpCode: digitsOnly,
        });
        renderBackupCodesScreen(username, res.backupCodes || [], () => {
          renderLogin();
        });
      } catch (ex) {
        if (errEl) {
          errEl.textContent = ex.message || "Ошибка регистрации";
          errEl.style.display = "block";
        }
        submitBtn.disabled = false;
        submitBtn.textContent = "ЗАВЕРШИТЬ РЕГИСТРАЦИЮ";
      }
    });
}
export function render2faVerification(username, challenge) {
  state.app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card">
                    <div class="logo"><span style="color:var(--accent);">◈</span> 2FA Подтверждение</div>
                    <div class="sub">Вход для аккаунта <b>${esc(username)}</b>. Код из Google Authenticator (000-000) или резервный код (XXXX-XXXX) — в это же поле.</div>
                    <form id="form-2fa">
                        <div class="form-group">
                            <label>Код подтверждения</label>
                            <input type="text" id="code-2fa" class="totp-input-masked" inputmode="numeric" autocomplete="one-time-code" placeholder="000-000" maxlength="9" autofocus
                                   style="text-align:center; font-size:20px; font-weight:700; letter-spacing:4px;">
                        </div>
                        <div id="err-2fa" class="error" style="display:none;"></div>
                        <button type="submit" class="primary" style="width:100%; margin-top:10px;">ПОДТВЕРДИТЬ</button>
                        <button type="button" class="secondary" onclick="location.reload()" style="width:100%; margin-top:8px;">ОТМЕНА</button>
                    </form>
                </div>
            </div>`;
  bindLogin2faInput(document.getElementById("code-2fa"));
  document.getElementById("form-2fa")?.addEventListener("submit", async (e) => {
    e.preventDefault();
    const raw2fa = document.getElementById("code-2fa")?.value || "";
    const code =
      typeof normalizeAuthCode === "function"
        ? normalizeAuthCode(raw2fa)
        : raw2fa.replace(/\D/g, "");
    const err = document.getElementById("err-2fa");
    err.style.display = "none";
    try {
      const res = await api("POST", "/api/auth/verify-2fa", {
        username,
        code,
        challenge,
      });
      setToken(res.token);
      state.me = await api("GET", "/api/me");
      renderAppLayout();
    } catch (ex) {
      err.textContent = ex.message;
      err.style.display = "block";
    }
  });
}
export function renderMasterOnboarding() {
  state.app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card" style="max-width:480px;">
                    <div class="logo">Первичная настройка</div>
                    <div class="sub">Создание учётной записи Управляющего</div>
                    <div class="stepper">
                        <div class="step-item active"><span class="step-badge">1</span> Токен</div>
                        <div class="step-connector"></div>
                        <div class="step-item"><span class="step-badge">2</span> Пароль</div>
                        <div class="step-connector"></div>
                        <div class="step-item"><span class="step-badge">3</span> 2FA</div>
                    </div>
                    <form id="ob-step1-form">
                        <div class="form-group">
                            <label>Токен из консоли сервера *</label>
                            <input type="text" id="ob-token" placeholder="LWA-..." required autofocus style="font-family:'JetBrains Mono',monospace;">
                            <div style="font-size:11px;color:var(--text-muted);margin-top:6px;line-height:1.4;">
                                Токен печатается в <b>консоль при запуске</b> сервера, если Управляющий ещё не создан.
                            </div>
                        </div>
                        <div id="ob-step1-err" class="error" style="display:none;"></div>
                        <button type="submit" class="primary" style="width:100%;">ДАЛЕЕ</button>
                    </form>
                </div>
            </div>`;
  document.getElementById("ob-step1-form")?.addEventListener("submit", (e) => {
    e.preventDefault();
    const setupToken = document.getElementById("ob-token").value.trim();
    const err = document.getElementById("ob-step1-err");
    if (!setupToken) {
      err.textContent = "Укажите токен из консоли";
      err.style.display = "block";
      return;
    }
    renderMasterOnboardingPassword(setupToken);
  });
}
export function renderMasterOnboardingPassword(setupToken) {
  state.app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card" style="max-width:480px;">
                    <div class="logo">Аккаунт</div>
                    <div class="sub">Логин и пароль Управляющего</div>
                    <div class="stepper">
                        <div class="step-item completed"><span class="step-badge">✓</span> Токен</div>
                        <div class="step-connector"></div>
                        <div class="step-item active"><span class="step-badge">2</span> Пароль</div>
                        <div class="step-connector"></div>
                        <div class="step-item"><span class="step-badge">3</span> 2FA</div>
                    </div>
                    <form id="ob-step2-form">
                        <div class="form-group">
                            <label>Логин *</label>
                            <input type="text" id="ob-user" required autofocus autocomplete="username">
                        </div>
                        <div class="form-group">
                            <label>Пароль *</label>
                            <input type="password" id="ob-pass" placeholder="Минимум 12 символов" required autocomplete="new-password">
                            <div class="password-strength-bar"><div class="password-strength-fill" id="ob-pass-fill"></div></div>
                            <div class="password-strength-hints" id="ob-pass-hints">
                                <span class="invalid">• 12+</span><span class="invalid">• а/А</span>
                                <span class="invalid">• цифра</span><span class="invalid">• спец</span>
                            </div>
                        </div>
                        <div class="form-group">
                            <label>Повтор пароля *</label>
                            <input type="password" id="ob-pass2" required autocomplete="new-password">
                        </div>
                        <div id="ob-step2-err" class="error" style="display:none;"></div>
                        <button type="submit" class="primary" style="width:100%;">ДАЛЕЕ — 2FA</button>
                        <button type="button" class="secondary" id="btn-ob-back1" style="width:100%;margin-top:8px;">НАЗАД</button>
                    </form>
                </div>
            </div>`;
  const passInput = document.getElementById("ob-pass");
  passInput?.addEventListener("input", () =>
    updatePasswordMeter(
      passInput.value,
      document.getElementById("ob-pass-fill"),
      document.getElementById("ob-pass-hints"),
    ),
  );
  document
    .getElementById("btn-ob-back1")
    ?.addEventListener("click", () => renderMasterOnboarding());
  document
    .getElementById("ob-step2-form")
    ?.addEventListener("submit", async (e) => {
      e.preventDefault();
      const username = document.getElementById("ob-user").value.trim();
      const password = document.getElementById("ob-pass").value;
      const pass2 = document.getElementById("ob-pass2").value;
      const err = document.getElementById("ob-step2-err");
      const btn = e.target.querySelector('button[type="submit"]');
      err.style.display = "none";
      if (!username || username.toLowerCase() === "admin") {
        err.textContent = "Укажите персональный логин";
        err.style.display = "block";
        return;
      }
      const pe = validatePasswordClient(password);
      if (pe) {
        err.textContent = pe;
        err.style.display = "block";
        return;
      }
      if (password !== pass2) {
        err.textContent = "Пароли не совпадают";
        err.style.display = "block";
        return;
      }
      btn.disabled = true;
      btn.textContent = "ПОДГОТОВКА 2FA...";
      try {
        const totp = await api("POST", "/api/auth/prepare-setup-owner", {
          setupToken,
          username,
        });
        renderMasterOnboarding2fa(
          username,
          password,
          setupToken,
          totp.secret,
          totp.otpUrl,
        );
      } catch (ex) {
        err.textContent = ex.message || "Проверьте токен";
        err.style.display = "block";
        btn.disabled = false;
        btn.textContent = "ДАЛЕЕ — 2FA";
      }
    });
}
export function renderMasterOnboarding2fa(
  username,
  password,
  setupToken,
  totpSecret,
  otpUrl,
) {
  state.app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card" style="max-width:500px;">
                    <div class="logo">Привязка 2FA</div>
                    <div class="sub">Аккаунт <b>${esc(username)}</b>. QR в <b>Google Authenticator</b>.</div>
                    <div class="stepper">
                        <div class="step-item completed"><span class="step-badge">✓</span> Токен</div>
                        <div class="step-connector"></div>
                        <div class="step-item completed"><span class="step-badge">✓</span> Пароль</div>
                        <div class="step-connector"></div>
                        <div class="step-item active"><span class="step-badge">3</span> 2FA</div>
                    </div>
                    <div class="totp-method-toggle">
                        <button type="button" class="totp-toggle-btn active" id="ob-tab-qr">QR-код</button>
                        <button type="button" class="totp-toggle-btn" id="ob-tab-code">Секретный код</button>
                    </div>
                    <div id="ob-pane-qr">
                        <div class="totp-qr-wrapper"><div class="totp-qr-box" id="ob-qrcode-box" title="Клик — обновить"></div></div>
                    </div>
                    <div id="ob-pane-code" style="display:none;">
                        <div style="font-size:12px;color:var(--text-muted);margin-bottom:8px;">Введите этот ключ вручную в Google Authenticator:</div>
                        <div id="ob-secret-box"></div>
                    </div>
                    <form id="ob-2fa-form" novalidate>
                        <div class="form-group" style="margin-top:20px;">
                            <label>Код из приложения Authenticator *</label>
                            <div style="font-size:11px;color:var(--text-muted);margin-bottom:8px;line-height:1.4;">Введите 6 цифр из приложения (формат 000-000)</div>
                            <input type="text" id="ob-code" placeholder="000-000" maxlength="7" required autofocus
                                   style="text-align:center;font-size:20px;font-weight:700;letter-spacing:4px;font-family:'JetBrains Mono',monospace;">
                        </div>
                        <div id="ob-2fa-err" class="error" style="display:none;"></div>
                        <button type="submit" class="primary" id="btn-ob-finish" style="width:100%;">ЗАВЕРШИТЬ НАСТРОЙКУ</button>
                        <button type="button" class="secondary" id="btn-ob-back2" style="width:100%;margin-top:8px;">НАЗАД</button>
                    </form>
                </div>
            </div>`;
  renderTotpQrCode(document.getElementById("ob-qrcode-box"), otpUrl);
  if (typeof renderSecretMasked === "function")
    renderSecretMasked(document.getElementById("ob-secret-box"), totpSecret);
  bindTotpInput(document.getElementById("ob-code"));
  document.getElementById("ob-tab-qr")?.addEventListener("click", () => {
    document.getElementById("ob-tab-qr").classList.add("active");
    document.getElementById("ob-tab-code").classList.remove("active");
    document.getElementById("ob-pane-qr").style.display = "block";
    document.getElementById("ob-pane-code").style.display = "none";
  });
  document.getElementById("ob-tab-code")?.addEventListener("click", () => {
    document.getElementById("ob-tab-code").classList.add("active");
    document.getElementById("ob-tab-qr").classList.remove("active");
    document.getElementById("ob-pane-code").style.display = "block";
    document.getElementById("ob-pane-qr").style.display = "none";
  });
  document
    .getElementById("btn-ob-back2")
    ?.addEventListener("click", () =>
      renderMasterOnboardingPassword(setupToken),
    );
  document
    .getElementById("ob-qrcode-box")
    ?.addEventListener("click", async () => {
      try {
        const totp = await api("POST", "/api/auth/prepare-setup-owner", {
          setupToken,
          username,
        });
        totpSecret = totp.secret;
        otpUrl = totp.otpUrl;
        renderTotpQrCode(document.getElementById("ob-qrcode-box"), otpUrl);
        if (typeof renderSecretMasked === "function")
          renderSecretMasked(
            document.getElementById("ob-secret-box"),
            totpSecret,
          );
      } catch (e) {
        showToast("2FA", e.message || "Ошибка", "error");
      }
    });
  document
    .getElementById("ob-2fa-form")
    ?.addEventListener("submit", async (e) => {
      e.preventDefault();
      const rawCode = document.getElementById("ob-code")?.value || "";
      const code =
        typeof normalizeAuthCode === "function"
          ? normalizeAuthCode(rawCode)
          : rawCode.replace(/\D/g, "");
      const err = document.getElementById("ob-2fa-err");
      const btn = document.getElementById("btn-ob-finish");
      err.style.display = "none";
      if (code.length !== 6) {
        if (err) {
          err.textContent = "Введите 6 цифр из приложения (формат 000-000)";
          err.style.display = "block";
        }
        return;
      }
      if (!totpSecret) {
        if (err) {
          err.textContent =
            "Секрет 2FA не готов. Обновите QR или откройте вкладку «Секретный код».";
          err.style.display = "block";
        }
        return;
      }
      if (btn) {
        btn.disabled = true;
        btn.textContent = "ПРОВЕРКА...";
      }
      try {
        const res = await api("POST", "/api/auth/setup-owner", {
          setupToken,
          username,
          password,
          totpSecret,
          totpCode: code,
        });
        setToken(res.token);
        state.me = await api("GET", "/api/me");
        renderBackupCodesScreen(username, res.backupCodes || [], () =>
          renderAppLayout(),
        );
      } catch (ex) {
        if (err) {
          err.textContent = ex.message || "Ошибка настройки";
          err.style.display = "block";
        }
        if (btn) {
          btn.disabled = false;
          btn.textContent = "ЗАВЕРШИТЬ НАСТРОЙКУ";
        }
      }
    });
}

// ---------- Main Application Layout (Sidebar + Viewport) ----------
