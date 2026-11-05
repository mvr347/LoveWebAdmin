import {
  renderSettingsSection,
  renderPersonalNotifications,
} from "./settings-sections.js";
import { noticeDialog } from "../core/dialogs.js";
import { state } from "../state.js";
import {
  api,
  closeModal,
  confirmAction,
  esc,
  hasPerm,
  isModeratorRole,
  openModal,
  renderSvgIcon,
  showToast,
} from "./ui.js";
import { applyUserTheme } from "./notifications.js";
// ==========================================================================
// UNIFIED ADMIN SETTINGS MODAL (REAL SETTINGS & ICONS)
// ==========================================================================

export async function openAdminSettingsModal(initialTab = "general") {
  const requestGeneration = state.requestGeneration;
  const token = state.token;
  const isMod = isModeratorRole();
  const roleName = state.me.isOwner
    ? "Управляющий"
    : state.me.role || (isMod ? "Модератор" : "Администратор");
  let userPrefs = {};
  try {
    userPrefs =
      typeof state.me.uiPreferences === "string"
        ? JSON.parse(state.me.uiPreferences)
        : state.me.uiPreferences || {};
  } catch (_) {
    userPrefs = {};
  }
  let serverConfig = {
    webPort: 8080,
    webHost: "0.0.0.0",
    sessionLifetimeMinutes: 60,
    keepLogCount: 5000,
    strictIp: false,
    loginMaxAttempts: 5,
    loginLockoutMinutes: 15,
    minPasswordLength: 12,
    previewEnabled: false,
    commandBlacklist: ["stop", "restart", "op", "deop", "sudo", "pex", "lp"],
    webhooksEnabled: false,
    discordWebhookUrl: "",
  };
  let configError = "";
  const drafts = {};
  try {
    const fetchedConfig = [
      "VIEW_SERVER_SETTINGS",
      "MANAGE_PANEL_SETTINGS",
      "MANAGE_INTEGRATIONS",
      "MANAGE_SECURITY_SETTINGS",
      "MANAGE_PREVIEW",
      "MANAGE_MAINTENANCE",
    ].some(hasPerm)
      ? await api("GET", "/api/server/config")
      : {};
    if (fetchedConfig && typeof fetchedConfig === "object") {
      serverConfig = {
        ...serverConfig,
        ...fetchedConfig,
      };
    }
  } catch (error) {
    configError = error.message;
  }
  if (requestGeneration !== state.requestGeneration || token !== state.token)
    return;
  let currentTab = initialTab || "general";
  const tabs = [
    {
      id: "general",
      label: "Мои настройки",
      icon: renderSvgIcon("gear", "violet", 16),
    },
    {
      id: "server",
      label: "Сайт и сессии",
      icon: renderSvgIcon("server", "violet", 16),
    },
    {
      id: "security",
      label: "Пароль и вход",
      icon: renderSvgIcon("shield", "green", 16),
    },
    {
      id: "panel-security",
      label: "Правила входа",
      icon: renderSvgIcon("shield", "green", 16),
    },
    {
      id: "blacklist",
      label: "Команды веб-консоли",
      icon: renderSvgIcon("ban", "red", 16),
    },
    {
      id: "webhooks",
      label: "Уведомления безопасности",
      icon: renderSvgIcon("bell", "violet", 16),
    },
    {
      id: "discord",
      label: "Бот и обращения",
      icon: renderSvgIcon("bell", "violet", 16),
    },
    {
      id: "preview",
      label: "Preview и тесты",
      icon: renderSvgIcon("server", "violet", 16),
    },
    {
      id: "notifications",
      label: "Мои уведомления",
      icon: renderSvgIcon("bell", "violet", 16),
    },
    {
      id: "maintenance",
      label: "Обслуживание панели",
      icon: renderSvgIcon("wrench", "gray", 16),
    },
    {
      id: "danger",
      label: "Служебные действия",
      icon: renderSvgIcon("alert", "red", 16),
    },
  ].filter(
    (tab) =>
      ({
        server: hasPerm("MANAGE_PANEL_SETTINGS"),
        discord: hasPerm("MANAGE_INTEGRATIONS"),
        webhooks: hasPerm("MANAGE_INTEGRATIONS"),
        preview: hasPerm("MANAGE_PREVIEW"),
        "panel-security": hasPerm("MANAGE_SECURITY_SETTINGS"),
        blacklist: hasPerm("MANAGE_SECURITY_SETTINGS"),
        maintenance: hasPerm("MANAGE_MAINTENANCE"),
        danger: state.me.isOwner,
      })[tab.id] ?? true,
  );
  if (!tabs.some((tab) => tab.id === currentTab)) currentTab = "general";
  const groups = [
    ["Личный аккаунт", ["general", "security", "notifications"]],
    [
      "Веб-панель",
      ["server", "panel-security", "blacklist", "maintenance", "danger"],
    ],
    ["Discord и тестирование", ["discord", "webhooks", "preview"]],
  ];
  openModal(
    `
            <div class="modal-header">
                <h3>Настройки</h3>
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon("close", "gray", 16)}</button>
            </div>
            <div class="modal-body" style="padding:0; max-height:82vh; overflow:hidden; display:flex; flex-direction:column;">
                <div class="settings-modal-layout" style="flex:1; min-height:480px; display:flex; width:100%;">
                    <!-- Left Navigation Sidebar -->
                    <aside class="settings-nav-sidebar">
                        ${groups
                          .map(([label, ids]) => {
                            const visible = ids
                              .map((id) => tabs.find((t) => t.id === id))
                              .filter(Boolean);
                            if (!visible.length) return "";
                            return `<div class="settings-nav-group"><div class="nav-section-title">${esc(label)}</div>${visible.map((t) => `<button type="button" class="settings-tab-btn ${t.id === currentTab ? "active" : ""}" data-tab="${t.id}"><span>${t.icon}</span><span>${esc(t.label)}</span></button>`).join("")}</div>`;
                          })
                          .join("")}
                    </aside>

                    <!-- Right Tab Pane -->
                    <section class="settings-content-area settings-tab-pane active" id="settings-tab-content">
                        <div style="color:var(--text-muted);font-size:13px;">Загрузка настроек…</div>
                    </section>
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button>
            </div>
        `,
    () => {
      const contentPane = document.getElementById("settings-tab-content");
      const owner = contentPane.closest(".modal-overlay");
      const switchSettingsTab = async (tabId) => {
        if (!contentPane.isConnected) return;
        currentTab = tabId;
        document.querySelectorAll(".settings-tab-btn").forEach((b) => {
          b.classList.toggle("active", b.dataset.tab === tabId);
        });
        if (!contentPane) return;
        contentPane.classList.add("active");
        contentPane
          .closest(".modal")
          .querySelector(`.settings-tab-btn[data-tab="${tabId}"]`)
          ?.scrollIntoView({ block: "nearest", inline: "nearest" });
        contentPane.style.cssText =
          "display:block!important;visibility:visible!important;opacity:1!important;flex:1;min-width:0;min-height:360px;padding:20px 24px;overflow-y:auto;color:var(--text-heading);background:var(--card-bg);";
        try {
          if (tabId === "general") {
            // TAB 1: ОБЩИЕ
            const isLight = document.body.classList.contains("theme-light");
            contentPane.innerHTML = `
                        <h4 style="color:var(--text-heading); margin-bottom:14px;">ОБЩИЙ ПРОФИЛЬ СОТРУДНИКА</h4>
                        <div style="background:var(--card-inner-bg); padding:16px; border-radius:var(--radius-sm); border:1px solid var(--border); margin-bottom:16px;">
                            <div style="display:flex; align-items:center; gap:12px;">
                                <div class="sidebar-user-avatar" style="width:46px; height:46px; font-size:18px;">
                                    ${esc((state.me.username || "A").substring(0, 2).toUpperCase())}
                                </div>
                                <div>
                                    <div style="font-size:17px; font-weight:800; color:var(--text-heading);">
                                        ${esc(state.me.username)} <span class="badge purple">${esc(roleName)}</span>
                                    </div>
                                    <div style="font-size:12px; color:var(--text-muted); margin-top:2px;">
                                        2FA Статус: <b>${state.me.totpEnabled ? "Включена (TOTP)" : "Отключена"}</b> • IP последнего входа: ${esc(state.me.last2faIp || "—")}
                                    </div>
                                </div>
                            </div>
                        </div>

                        <div class="form-group">
                            <label>Привязанный игровой никнейм Minecraft</label>
                            <input type="text" id="gen-mc-nick" value="${esc(userPrefs.minecraftNick || state.me.username || "")}" placeholder="Никнейм в игре...">
                            <div style="font-size:11.5px; color:var(--text-muted); margin-top:4px;">Используется для быстрого автозаполнения и синхронизации смен в игре</div>
                        </div>

                        <div style="background:var(--card-inner-bg); padding:16px; border-radius:var(--radius-sm); border:1px solid var(--border); margin-bottom:16px;">
                            <b style="color:var(--text-heading); font-size:13.5px;">Тема оформления интерфейса</b>
                            <div style="font-size:12px; color:var(--text-muted); margin-bottom:12px;">Выберите цветовую схему для комфортной работы:</div>

                            <div style="display:flex; gap:12px;">
                                <button type="button" class="secondary ${!isLight ? "active" : ""}" id="btn-theme-dark" style="flex:1; padding:12px; display:flex; align-items:center; justify-content:center; gap:8px;">
                                    ${renderSvgIcon("moon", "violet", 16)} ТЁМНАЯ ТЕМА (DARK)
                                </button>
                                <button type="button" class="secondary ${isLight ? "active" : ""}" id="btn-theme-light" style="flex:1; padding:12px; display:flex; align-items:center; justify-content:center; gap:8px;">
                                    ${renderSvgIcon("sun", "yellow", 16)} СВЕТЛАЯ ТЕМА (LIGHT)
                                </button>
                            </div>
                        </div>

                        <div style="background:var(--card-inner-bg); padding:16px; border-radius:var(--radius-sm); border:1px solid var(--border); margin-bottom:16px;">
                            <b style="color:var(--text-heading); font-size:13.5px;">Раскладка дашборда</b>
                            <div style="font-size:12px; color:var(--text-muted); margin-bottom:10px;">
                                Плитки дашборда можно свободно перетаскивать и настраивать прямо на главной странице.
                            </div>
                            <button type="button" class="secondary btn-sm" onclick="window.openDashboardCustomizerModal()" style="display:inline-flex; align-items:center; gap:6px;">
                                ${renderSvgIcon("gear", "gray", 14)} НАСТРОИТЬ АКТИВНЫЕ ПЛИТКИ
                            </button>
                        </div>

                        <button type="button" class="primary btn-sm" id="btn-save-general-settings" style="width:100%; margin-top:6px; display:flex; align-items:center; justify-content:center; gap:6px;">
                            ${renderSvgIcon("save", "white", 14)} СОХРАНИТЬ ОБЩИЕ НАСТРОЙКИ
                        </button>
                    `;
            document
              .getElementById("btn-theme-dark")
              ?.addEventListener("click", async () => {
                applyUserTheme("dark");
                userPrefs.theme = "dark";
                try {
                  await api("PUT", "/api/me/preferences", {
                    uiPreferences: userPrefs,
                  });
                } catch (_) {}
                switchSettingsTab("general");
              });
            document
              .getElementById("btn-theme-light")
              ?.addEventListener("click", async () => {
                applyUserTheme("light");
                userPrefs.theme = "light";
                try {
                  await api("PUT", "/api/me/preferences", {
                    uiPreferences: userPrefs,
                  });
                } catch (_) {}
                switchSettingsTab("general");
              });
            document
              .getElementById("btn-save-general-settings")
              ?.addEventListener("click", async () => {
                userPrefs.minecraftNick = document
                  .getElementById("gen-mc-nick")
                  .value.trim();
                try {
                  await api("PUT", "/api/me/preferences", {
                    uiPreferences: userPrefs,
                  });
                  state.me.uiPreferences = JSON.stringify(userPrefs);
                  showToast(
                    "Сохранено",
                    "Общие настройки профиля обновлены",
                    "success",
                  );
                } catch (e) {
                  showToast("Ошибка", e.message, "error");
                }
              });
          } else if (
            [
              "server",
              "discord",
              "preview",
              "webhooks",
              "panel-security",
              "maintenance",
              "blacklist",
            ].includes(tabId)
          ) {
            renderSettingsSection(contentPane, tabId, {
              api,
              config: serverConfig,
              hasPerm,
              onSaved: (response) => {
                serverConfig = { ...serverConfig, ...response };
              },
              drafts,
              unavailable: configError,
            });
          } else if (tabId === "notifications") {
            renderPersonalNotifications(contentPane, {
              api,
              me: state.me,
              preferences: userPrefs,
              onSaved: (next) => {
                userPrefs = next;
                state.me.uiPreferences = JSON.stringify(next);
              },
            });
          } else if (tabId === "security") {
            // TAB 3: БЕЗОПАСНОСТЬ (СИСТЕМА + СМЕНА ПАРОЛЯ + 2FA)
            contentPane.innerHTML = `
                        <h4 style="color:var(--text-heading); margin-bottom:14px;">БЕЗОПАСНОСТЬ И АВТОРИЗАЦИЯ</h4>

                        <p class="settings-intro">Настройки только вашей учётной записи. Общие требования к паролям находятся в разделе «Правила входа».</p>
                        <!-- Sessions & 2FA Info Card -->
                        <div style="background:var(--card-inner-bg); padding:14px; border-radius:var(--radius-sm); border:1px solid var(--border); margin-bottom:16px;">
                            <div style="display:flex; justify-content:space-between; align-items:center;">
                                <div>
                                    <b style="color:var(--text-heading); font-size:13px;">Управление сессиями вашей учетной записи</b>
                                    <div style="font-size:11.5px; color:var(--text-muted);">Завершите активные сессии на всех остальных смартфонах и ПК</div>
                                </div>
                                <button type="button" class="danger btn-sm" id="btn-sec-terminate-other" style="display:flex; align-items:center; gap:6px;">
                                    ${renderSvgIcon("ban", "white", 12)} ЗАВЕРШИТЬ ДРУГИЕ
                                </button>
                            </div>
                            ${
                              state.me.totpEnabled
                                ? `
                                <button type="button" class="secondary btn-sm" id="btn-sec-regen-codes" style="width:100%; font-size:11px; margin-top:10px; display:flex; align-items:center; justify-content:center; gap:6px;">
                                    ${renderSvgIcon("refresh", "gray", 12)} СГЕНЕРИРОВАТЬ НОВЫЕ РЕЗЕРВНЫЕ КОДЫ 2FA
                                </button>
                            `
                                : ""
                            }
                        </div>

                        <!-- Password Change -->
                        <div style="background:var(--card-inner-bg); padding:16px; border-radius:var(--radius-sm); border:1px solid var(--border);">
                            <div style="font-weight:700; color:var(--text-heading); margin-bottom:4px; font-size:13.5px;">СМЕНА ПАРОЛЯ УЧЁТНОЙ ЗАПИСИ</div>
                            <div style="font-size:11.5px; color:var(--yellow); margin-bottom:12px;">
                                Внимание: ввод неверного старого пароля приведёт к аннулированию сессии!
                            </div>

                            <div class="form-group">
                                <label>1. Текущий пароль *</label>
                                <input type="password" id="sec-old-pass" placeholder="••••••••" autocomplete="current-password">
                            </div>
                            <div class="form-group">
                                <label>2. Новый надёжный пароль (минимум ${serverConfig.minPasswordLength || 12} символов) *</label>
                                <input type="password" id="sec-new-pass" placeholder="Новый пароль" autocomplete="new-password">
                            </div>
                            <div class="form-group">
                                <label>3. Подтвердите новый пароль *</label>
                                <input type="password" id="sec-confirm-pass" placeholder="Повторите новый пароль" autocomplete="new-password">
                            </div>
                            ${
                              state.me.totpEnabled
                                ? `
                            <div class="form-group">
                                <label>4. 6-значный код 2FA подтверждения *</label>
                                <input type="text" id="sec-totp-code" placeholder="123456" maxlength="8" style="letter-spacing:0.2em; font-family:'JetBrains Mono';">
                            </div>
                            `
                                : ""
                            }

                            <div id="sec-pass-err" class="error" style="display:none; margin-bottom:10px;"></div>
                            <button type="button" class="primary" id="btn-submit-change-pass" style="width:100%; display:flex; align-items:center; justify-content:center; gap:6px;">
                                ${renderSvgIcon("key", "white", 14)} ИЗМЕНИТЬ ПАРОЛЬ
                            </button>
                        </div>
                    `;
            document
              .getElementById("btn-sec-terminate-other")
              ?.addEventListener("click", () => {
                confirmAction(
                  "ЗАВЕРШЕНИЕ СЕССИЙ",
                  "Завершить все активные сессии на всех других устройствах?",
                  async () => {
                    try {
                      await api("POST", "/api/me/sessions/other/terminate");
                      showToast(
                        "Готово",
                        "Все остальные сессии успешно завершены",
                        "info",
                      );
                    } catch (e) {
                      showToast("Ошибка", e.message, "error");
                    }
                  },
                  "ЗАВЕРШИТЬ",
                );
              });
            document
              .getElementById("btn-sec-regen-codes")
              ?.addEventListener("click", async () => {
                confirmAction(
                  "РЕГЕНЕРАЦИЯ КОДОВ 2FA",
                  "Старые резервные коды станут недействительными. Будут сгенерированы 8 новых кодов.",
                  async () => {
                    try {
                      const res = await api(
                        "POST",
                        "/api/me/backup-codes/regenerate",
                      );
                      const codes = res.backupCodes || [];
                      await noticeDialog(
                        "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
                        "Новые резервные коды 2FA:\n\n" + codes.join("\n"),
                      );
                    } catch (e) {
                      showToast("Ошибка", e.message, "error");
                    }
                  },
                );
              });
            document
              .getElementById("btn-submit-change-pass")
              ?.addEventListener("click", async () => {
                const oldPassword =
                  document.getElementById("sec-old-pass").value;
                const newPassword =
                  document.getElementById("sec-new-pass").value;
                const confirmPass =
                  document.getElementById("sec-confirm-pass").value;
                const totpCode = document
                  .getElementById("sec-totp-code")
                  ?.value.trim();
                const err = document.getElementById("sec-pass-err");
                err.style.display = "none";
                const minRequired = serverConfig.minPasswordLength || 12;
                if (!oldPassword || !newPassword || !confirmPass) {
                  err.textContent = "Заполните все обязательные поля";
                  err.style.display = "block";
                  return;
                }
                if (newPassword.length < minRequired) {
                  err.textContent = `Новый пароль должен содержать минимум ${minRequired} символов`;
                  err.style.display = "block";
                  return;
                }
                if (newPassword !== confirmPass) {
                  err.textContent = "Новый пароль и подтверждение не совпадают";
                  err.style.display = "block";
                  return;
                }
                try {
                  await api("POST", "/api/me/password", {
                    oldPassword,
                    newPassword,
                    confirmPassword: confirmPass,
                    totpCode,
                  });
                  showToast(
                    "Пароль обновлен",
                    "Ваш пароль успешно изменен",
                    "success",
                  );
                  closeModal(owner);
                } catch (e) {
                  err.textContent = e.message;
                  err.style.display = "block";
                }
              });
          } else if (tabId === "danger") {
            // TAB 7: ОПАСНЫЕ ЗОНЫ
            contentPane.innerHTML = `
                        <h4 style="color:var(--red); margin-bottom:14px;">ОПАСНЫЕ ЗОНЫ И СБРОС</h4>
                        <div style="display:flex; flex-direction:column; gap:14px;">
                            <div style="background:rgba(239, 68, 68, 0.08); border:1px solid rgba(239, 68, 68, 0.3); border-radius:var(--radius-sm); padding:16px;">
                                <b style="color:var(--text-heading);">Принудительный выход всех сотрудников</b>
                                <div style="font-size:12px; color:var(--text-muted); margin-top:2px; margin-bottom:12px;">
                                    Немедленно аннулирует все выданные сессии и токены всех пользователей, кроме вашей текущей сессии.
                                </div>
                                <button type="button" class="danger btn-sm" id="btn-danger-term-all" style="display:flex; align-items:center; gap:6px;">
                                    ${renderSvgIcon("ban", "white", 14)} ЗАВЕРШИТЬ ВСЕ СЕССИИ ПЕРСОНАЛА
                                </button>
                            </div>

                            <div style="background:var(--card-inner-bg); border:1px solid var(--border); border-radius:var(--radius-sm); padding:16px;">
                                <b style="color:var(--text-heading);">Перезагрузка конфигурации плагина</b>
                                <div style="font-size:12px; color:var(--text-muted); margin-top:2px; margin-bottom:12px;">
                                    Выполняет команду /lovewebadmin reload на сервере без перезапуска ядра Minecraft.
                                </div>
                                <button type="button" class="secondary btn-sm" id="btn-danger-reload-plugin" style="display:flex; align-items:center; gap:6px;">
                                    ${renderSvgIcon("refresh", "gray", 14)} ПЕРЕЗАГРУЗИТЬ LOVEWEBADMIN
                                </button>
                            </div>
                        </div>
                    `;
            document
              .getElementById("btn-danger-term-all")
              ?.addEventListener("click", () => {
                confirmAction(
                  "СБРОС ВСЕХ СЕССИЙ",
                  "Вы действительно хотите принудительно разлогинить всех сотрудников на всех устройствах?",
                  async () => {
                    try {
                      await api("POST", "/api/admins/terminate-all");
                      showToast(
                        "Готово",
                        "Все сессии персонала успешно завершены",
                        "info",
                      );
                    } catch (e) {
                      showToast("Ошибка", e.message, "error");
                    }
                  },
                  "ЗАВЕРШИТЬ ВСЕ",
                );
              });
            document
              .getElementById("btn-danger-reload-plugin")
              ?.addEventListener("click", async () => {
                try {
                  await api("POST", "/api/command", {
                    command: "lovewebadmin reload",
                  });
                  showToast(
                    "Перезагрузка",
                    "Конфигурация плагина перезагружена",
                    "success",
                  );
                } catch (e) {
                  showToast("Ошибка", e.message, "error");
                }
              });
          }
        } catch (e) {
          console.error("settings tab error", e);
          contentPane.innerHTML = `<div class="error" style="display:block;padding:16px;">Не удалось загрузить вкладку: ${esc(e.message || String(e))}</div>`;
        }
      };

      // Sidebar tab click handler
      document.querySelectorAll(".settings-tab-btn").forEach((btn) => {
        btn.addEventListener("click", () => {
          const tid = btn.dataset.tab;
          if (tid) switchSettingsTab(tid);
        });
      });
      switchSettingsTab(currentTab);
    },
    "modal-settings",
  );
}

// Backwards-compatible alias for user profile trigger
