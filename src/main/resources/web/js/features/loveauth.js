import { noticeDialog } from "../core/dialogs.js";
import { state } from "../state.js";
import {
  api,
  closeModal,
  confirmAction,
  esc,
  fmtTime,
  openModal,
  parseDeviceString,
  renderSvgIcon,
  showToast,
} from "./ui.js";
// ==========================================================================
// 6. АУТЕНТИФИКАЦИЯ (СЕССИИ, ИСТОРИЯ ВХОДОВ, LOVEAUTH)
// ==========================================================================

export async function renderAuthView() {
  const area = document.getElementById("content-area");
  let currentAuthTab = "web";
  let inspectedPlayer = null;
  area.innerHTML = `
            <div class="view-header">
                <div class="view-title-block">
                    <h2>АУТЕНТИФИКАЦИЯ И БЕЗОПАСНОСТЬ</h2>
                    <p>Управление сессиями персонала и игровыми аккаунтами авторизации</p>
                </div>
                <div class="view-actions" id="auth-header-actions">
                    <!-- Dynamic actions per subtab -->
                </div>
            </div>

            <!-- Subtabs Navigation -->
            <div class="sub-tabs-bar" id="auth-subtabs">
                <button type="button" class="sub-tab-btn active" data-subtab="web" style="display:inline-flex; align-items:center; gap:6px;">
                    ${renderSvgIcon("server", "violet", 15)} ВЕБ-ПАНЕЛЬ (СЕССИИ СТАФФА)
                </button>
                <button type="button" class="sub-tab-btn" data-subtab="loveauth" style="display:inline-flex; align-items:center; gap:6px;">
                    ${renderSvgIcon("users", "violet", 15)} ИГРОВОЙ СЕРВЕР (АВТОРИЗАЦИЯ)
                </button>
            </div>

            <!-- Tab Content Container -->
            <div id="auth-tab-content">
                <!-- Rendered dynamically -->
            </div>
        `;

  // Switcher Logic
  const renderCurrentSubTab = async () => {
    const container = document.getElementById("auth-tab-content");
    const actions = document.getElementById("auth-header-actions");
    if (!container) return;
    document.querySelectorAll("#auth-subtabs .sub-tab-btn").forEach((btn) => {
      btn.classList.toggle("active", btn.dataset.subtab === currentAuthTab);
    });
    if (currentAuthTab === "web") {
      if (actions) {
        actions.innerHTML = `
                        <button type="button" class="danger" id="btn-terminate-all-other">ЗАВЕРШИТЬ ВСЕ ЧУЖИЕ СЕССИИ</button>
                    `;
        document
          .getElementById("btn-terminate-all-other")
          ?.addEventListener("click", () => {
            confirmAction(
              "ЗАВЕРШЕНИЕ СЕССИЙ",
              "Завершить все активные сессии на всех других устройствах?",
              async () => {
                await api("POST", "/api/me/sessions/other/terminate");
                showToast(
                  "Готово",
                  "Все остальные сессии были завершены",
                  "info",
                );
                refreshWebAuthData();
              },
              "ЗАВЕРШИТЬ ВСЕ",
            );
          });
      }
      container.innerHTML = `
                    <!-- Active Sessions Card -->
                    <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:18px; margin-bottom:20px;">
                        <h3 style="font-size:15px; margin-bottom:12px; color:var(--text-heading);">АКТИВНЫЕ СЕССИИ ВЕБ-ПАНЕЛИ</h3>
                        <div class="table-wrap">
                            <table>
                                <thead>
                                    <tr>
                                        <th>СОТРУДНИК</th>
                                        <th>РОЛЬ</th>
                                        <th>IP АДРЕС</th>
                                        <th>УСТРОЙСТВО / БРАУЗЕР</th>
                                        <th>АКТИВНОСТЬ</th>
                                        <th style="text-align:right;">ДЕЙСТВИЕ</th>
                                    </tr>
                                </thead>
                                <tbody id="sessions-table-body">
                                    <tr><td colspan="6" style="text-align:center; padding:16px; color:var(--text-dim);">Загрузка сессий...</td></tr>
                                </tbody>
                            </table>
                        </div>
                    </div>

                    <!-- Login History Card -->
                    <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:18px;">
                        <h3 style="font-size:15px; margin-bottom:12px; color:var(--text-heading);">ИСТОРИЯ ВХОДОВ И БЕЗОПАСНОСТЬ ПАНЕЛИ</h3>
                        <div class="table-wrap">
                            <table>
                                <thead>
                                    <tr>
                                        <th>ВРЕМЯ</th>
                                        <th>ПОЛЬЗОВАТЕЛЬ</th>
                                        <th>СОБЫТИЕ АВТОРИЗАЦИИ</th>
                                    </tr>
                                </thead>
                                <tbody id="login-history-body">
                                    <tr><td colspan="3" style="text-align:center; padding:16px; color:var(--text-dim);">Загрузка истории...</td></tr>
                                </tbody>
                            </table>
                        </div>
                    </div>
                `;
      await refreshWebAuthData();
    } else {
      // LoveAuth Subtab
      if (actions) {
        actions.innerHTML = "";
      }
      await renderLoveAuthSubTab();
    }
  };
  const refreshWebAuthData = async () => {
    const sBody = document.getElementById("sessions-table-body");
    const hBody = document.getElementById("login-history-body");
    try {
      const sessions = await api("GET", "/api/auth/sessions/all").catch(
        () => [],
      );
      if (sBody) {
        if (!sessions.length) {
          sBody.innerHTML = `<tr><td colspan="6" style="text-align:center; padding:16px; color:var(--text-dim);">Активных сессий не найдено</td></tr>`;
        } else {
          sBody.innerHTML = sessions
            .map(
              (s) => `
                            <tr>
                                <td><b style="color:var(--text-heading);">${esc(s.username)}</b></td>
                                <td><span class="badge purple">${esc(s.role)}</span></td>
                                <td class="font-mono" style="font-size:12px;">${esc(s.ip)}</td>
                                <td style="color:var(--text-secondary);">${esc(parseDeviceString(s.userAgent))}</td>
                                <td class="font-mono" style="font-size:12px; color:var(--text-muted);">${fmtTime(s.lastUsedAt || s.createdAt)}</td>
                                <td style="text-align:right;">
                                    <button type="button" class="danger btn-sm" onclick="window.terminateStaffSession('${esc(s.token)}')">ЗАВЕРШИТЬ</button>
                                </td>
                            </tr>
                        `,
            )
            .join("");
        }
      }
      const history = await api("GET", "/api/auth/login-history").catch(
        () => [],
      );
      if (hBody) {
        if (!history.length) {
          hBody.innerHTML = `<tr><td colspan="3" style="text-align:center; padding:16px; color:var(--text-dim);">Истории входов пока нет</td></tr>`;
        } else {
          hBody.innerHTML = history
            .slice(0, 15)
            .map(
              (h) => `
                            <tr>
                                <td class="font-mono" style="font-size:12px; color:var(--text-muted);">${fmtTime(h.timestamp)}</td>
                                <td><b>${esc(h.username)}</b></td>
                                <td style="color:var(--text);">${esc(h.action)}</td>
                            </tr>
                        `,
            )
            .join("");
        }
      }
    } catch (e) {}
  };
  const renderLoveAuthSubTab = async () => {
    const container = document.getElementById("auth-tab-content");
    if (!container) return;
    let isAvailable = false;
    try {
      const statusRes = await api("GET", "/api/loveauth/status").catch(() => ({
        available: false,
      }));
      isAvailable = !!statusRes.available;
    } catch (e) {
      isAvailable = false;
    }
    let onlineList = [];
    try {
      const s = await api("GET", "/api/stats").catch(() => ({
        players: [],
      }));
      onlineList = s.players || [];
    } catch (e) {}
    container.innerHTML = `
                <!-- Auth Status Banner -->
                ${
                  isAvailable
                    ? `
                    <div class="loveauth-banner">
                        <div style="display:flex; align-items:center; gap:14px;">
                            <span style="display:inline-flex; align-items:center;">${renderSvgIcon("shield", "violet", 26)}</span>
                            <div>
                                <div style="font-weight:700; color:var(--text-main); font-size:15px; display:flex; align-items:center; gap:8px;">
                                    <span>СИСТЕМА АВТОРИЗАЦИИ</span>
                                    <span class="badge green">● АКТИВНА</span>
                                </div>
                                <div style="font-size:12px; color:var(--text-muted); margin-top:2px;">
                                    Управление игровыми сессиями, шифрованием паролей и двухфакторной защитой
                                </div>
                            </div>
                        </div>
                    </div>
                `
                    : `
                    <div class="loveauth-banner" style="background:rgba(239, 68, 68, 0.08); border-color:rgba(239, 68, 68, 0.25);">
                        <div style="display:flex; align-items:center; gap:14px;">
                            <span style="display:inline-flex; align-items:center;">${renderSvgIcon("shieldAlert", "red", 26)}</span>
                            <div>
                                <div style="font-weight:700; color:var(--text-main); font-size:15px; display:flex; align-items:center; gap:8px;">
                                    <span>СИСТЕМА АВТОРИЗАЦИИ</span>
                                    <span class="badge red">● НЕДОСТУПНА</span>
                                </div>
                                <div style="font-size:12px; color:var(--text-muted); margin-top:2px;">
                                    Модуль игровой авторизации не подключен или отключен на сервере.
                                </div>
                            </div>
                        </div>
                        <span class="badge red">НЕДОСТУПЕН</span>
                    </div>
                `
                }

                <!-- Account Search & Online Quick-Chips -->
                <div class="loveauth-card" style="margin-bottom:20px;">
                    <h3 style="font-size:14px; margin-bottom:10px; color:var(--text-main);">ИНСПЕКЦИЯ ИГРОВОГО АККАУНТА</h3>
                    <div style="display:flex; gap:10px; margin-bottom:14px;">
                        <input type="text" id="loveauth-search-input" placeholder="Введите точный ник игрока..." style="flex:1;">
                        <button type="button" class="primary" id="btn-loveauth-search">НАЙТИ АККАУНТ</button>
                    </div>

                    <div style="display:flex; align-items:center; gap:8px; font-size:12px; color:var(--text-dim); flex-wrap:wrap;">
                        <span>Игроки онлайн прямо сейчас:</span>
                        <div id="loveauth-online-chips" style="display:inline-flex; gap:6px; flex-wrap:wrap;">
                            ${
                              onlineList.length
                                ? onlineList
                                    .slice(0, 12)
                                    .map(
                                      (p) => `
                                <button type="button" class="secondary btn-sm" onclick="window.inspectLoveAuthPlayer('${esc(p.name)}')">
                                    ${esc(p.name)}
                                </button>
                            `,
                                    )
                                    .join("")
                                : '<span style="color:var(--text-dim); font-size:11.5px;">(сервер пуст)</span>'
                            }
                        </div>
                    </div>
                </div>

                <!-- Inspected Player Details -->
                <div id="loveauth-player-result">
                    <div style="background:var(--card-bg); border:1px dashed var(--border); border-radius:var(--radius-md); padding:40px 20px; text-align:center; color:var(--text-dim);">
                        <div style="margin-bottom:8px; display:inline-flex; align-items:center;">${renderSvgIcon("search", "violet", 28)}</div>
                        <div>Введите никнейм или нажмите на игрока онлайн выше, чтобы загрузить данные авторизации</div>
                    </div>
                </div>
            `;
    const searchBtn = document.getElementById("btn-loveauth-search");
    const searchInput = document.getElementById("loveauth-search-input");
    const doSearch = () => {
      const name = searchInput?.value.trim();
      if (name) window.inspectLoveAuthPlayer(name);
    };
    searchBtn?.addEventListener("click", doSearch);
    searchInput?.addEventListener("keydown", (e) => {
      if (e.key === "Enter") doSearch();
    });
    if (inspectedPlayer) {
      window.inspectLoveAuthPlayer(inspectedPlayer);
    }
  };
  window.inspectLoveAuthPlayer = async (name) => {
    inspectedPlayer = name;
    const resBox = document.getElementById("loveauth-player-result");
    const searchInput = document.getElementById("loveauth-search-input");
    if (searchInput) searchInput.value = name;
    if (!resBox) return;
    resBox.innerHTML = `
                <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:30px; text-align:center; color:var(--text-muted);">
                    Связываемся с базой авторизации...
                </div>
            `;
    try {
      const info = await api(
        "GET",
        `/api/loveauth/player/${encodeURIComponent(name)}`,
      );
      if (!info || !info.found) {
        resBox.innerHTML = `
                        <div class="loveauth-card" style="text-align:center; padding:32px;">
                            <div style="margin-bottom:8px; display:inline-flex; align-items:center;">${renderSvgIcon("alert", "yellow", 24)}</div>
                            <h3 style="color:var(--text-main); margin-bottom:6px;">Игрок «${esc(name)}» не найден в базе авторизации</h3>
                            <p style="color:var(--text-dim); font-size:12.5px;">Возможно, игрок еще ни разу не заходил на сервер или никнейм введен с опечаткой.</p>
                        </div>
                    `;
        return;
      }
      const isLocked = !!info.isLocked;
      const isIpBlocked = !!info.isIpBlocked;
      const isReg = !!info.isRegistered;
      const hasDc = !!info.hasDiscord;
      const alts = Array.isArray(info.alts) ? info.alts : [];
      resBox.innerHTML = `
                    <div class="loveauth-card">
                        <!-- Header -->
                        <div style="display:flex; justify-content:space-between; align-items:flex-start; border-bottom:1px solid var(--border); padding-bottom:16px; margin-bottom:18px; flex-wrap:wrap; gap:14px;">
                            <div style="display:flex; align-items:center; gap:14px;">
                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(info.username)}/48" class="player-avatar-sm" style="width:48px; height:48px; border-radius:8px;" alt="">
                                <div>
                                    <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
                                        <h3 style="font-size:18px; color:var(--text-main); margin:0;">${esc(info.username)}</h3>
                                        <span class="badge ${isReg ? "green" : "yellow"}" style="display:inline-flex; align-items:center; gap:4px;">${isReg ? renderSvgIcon("check", "green", 11) : ""} ${isReg ? "ЗАРЕГИСТРИРОВАН" : "НЕ ЗАРЕГИСТРИРОВАН"}</span>
                                        <span class="badge ${isLocked ? "red" : "green"}" style="display:inline-flex; align-items:center; gap:4px;">${isLocked ? renderSvgIcon("lock", "red", 11) : renderSvgIcon("check", "green", 11)} ${isLocked ? "ЗАБЛОКИРОВАН (LOCKOUT)" : "ДОСТУП РАЗРЕШЕН"}</span>
                                    </div>
                                    <div class="font-mono" style="font-size:11.5px; color:var(--text-dim); margin-top:4px;">
                                        UUID: ${esc(info.uuid || "—")}
                                    </div>
                                </div>
                            </div>

                            <div style="text-align:right;">
                                <div style="font-size:11px; color:var(--text-dim); text-transform:uppercase;">Последний IP адрес:</div>
                                <div class="font-mono" style="font-size:13px; font-weight:700; color:var(--accent-light); margin-top:2px;">
                                    ${esc(info.lastIp || "—")}
                                </div>
                            </div>
                        </div>

                        <!-- 3 Meta Cards -->
                        <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap:12px; margin-bottom:20px;">
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">БРУТФОРС & БЛОКИРОВКА</div>
                                <div style="font-size:14px; font-weight:700; margin-top:4px; color:${isLocked ? "var(--red)" : "var(--green)"};">
                                    ${isLocked ? "Сработала защита от подбора" : "Ошибок входа нет"}
                                </div>
                                <div class="stat-sub">${isLocked ? "Требуется разблокировка" : "Счетчик попыток в норме"}</div>
                            </div>

                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">2FA / DISCORD ВЕРИФИКАЦИЯ</div>
                                <div style="font-size:14px; font-weight:700; margin-top:4px; color:${hasDc ? "var(--accent-light)" : "var(--text-dim)"}; display:flex; align-items:center; gap:4px;">
                                    ${hasDc ? renderSvgIcon("check", "green", 12) + " Discord 2FA подключен" : "Без двухфакторной защиты"}
                                </div>
                                <div class="stat-sub">${hasDc ? "Подтверждение через бота" : "Вход только по паролю"}</div>
                            </div>

                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">СВЯЗАННЫЕ АККАУНТЫ ПО IP</div>
                                <div style="margin-top:6px; display:flex; gap:4px; flex-wrap:wrap;">
                                    ${
                                      alts.length > 1
                                        ? alts
                                            .filter(
                                              (a) =>
                                                a.toLowerCase() !==
                                                info.username.toLowerCase(),
                                            )
                                            .map(
                                              (a) => `
                                            <button type="button" class="secondary btn-sm" style="font-size:11px; padding:2px 7px;" onclick="window.inspectLoveAuthPlayer('${esc(a)}')">
                                                ${esc(a)}
                                            </button>
                                        `,
                                            )
                                            .join("")
                                        : '<span style="font-size:12px; color:var(--text-dim);">Других аккаунтов не найдено</span>'
                                    }
                                </div>
                            </div>
                        </div>

                        <!-- Action Buttons Grid -->
                        <div style="font-size:12px; font-weight:700; color:var(--text-dim); text-transform:uppercase; letter-spacing:0.04em; margin-bottom:10px;">
                            Действия администратора:
                        </div>

                        <div class="loveauth-actions-grid">
                            <button type="button" class="secondary" onclick="window.loveAuthResetSession('${esc(info.username)}')" style="display:inline-flex; align-items:center; gap:6px;">
                                ${renderSvgIcon("refresh", "violet", 13)} СБРОСИТЬ СЕССИЮ (КИК)
                            </button>
                            <button type="button" class="secondary" onclick="window.loveAuthChangePassword('${esc(info.username)}')" style="display:inline-flex; align-items:center; gap:6px;">
                                ${renderSvgIcon("key", "violet", 13)} СМЕНИТЬ ПАРОЛЬ
                            </button>
                            ${
                              isLocked
                                ? `
                                <button type="button" class="primary" onclick="window.loveAuthUnlock('${esc(info.username)}')" style="display:inline-flex; align-items:center; gap:6px;">
                                    ${renderSvgIcon("lock", "green", 13)} РАЗБЛОКИРОВАТЬ АККАУНТ
                                </button>
                            `
                                : `
                                <button type="button" class="secondary" disabled style="opacity:0.5; cursor:not-allowed; display:inline-flex; align-items:center; gap:6px;" title="Аккаунт не заблокирован">
                                    ${renderSvgIcon("check", "gray", 13)} АККАУНТ ДОСТУПЕН
                                </button>
                            `
                            }
                            ${
                              isIpBlocked
                                ? `
                                <button type="button" class="primary" onclick="window.loveAuthUnblockIp('${esc(info.lastIp)}')" style="display:inline-flex; align-items:center; gap:6px;">
                                    ${renderSvgIcon("globe", "green", 13)} РАЗБЛОКИРОВАТЬ IP (${esc(info.lastIp)})
                                </button>
                            `
                                : `
                                <button type="button" class="secondary" disabled style="opacity:0.5; cursor:not-allowed; display:inline-flex; align-items:center; gap:6px;" title="IP не заблокирован">
                                    ${renderSvgIcon("check", "gray", 13)} IP НЕ ЗАБЛОКИРОВАН
                                </button>
                            `
                            }
                            <button type="button" class="danger" onclick="window.loveAuthDelete('${esc(info.username)}')" style="display:inline-flex; align-items:center; gap:6px;">
                                ${renderSvgIcon("trash", "white", 13)} УДАЛИТЬ АККАУНТ
                            </button>
                        </div>
                    </div>
                `;
    } catch (e) {
      resBox.innerHTML = `
                    <div style="color:var(--red); padding:20px; text-align:center; background:var(--card-bg); border-radius:var(--radius-md); border:1px solid var(--border);">
                        Ошибка запроса к авторизации: ${esc(e.message)}
                    </div>
                `;
    }
  };

  // Auth Operations Handlers
  window.loveAuthResetSession = (name) => {
    confirmAction(
      "СБРОС СЕССИИ",
      `Завершить сессию игрока LoveWebAdmin на игровом сервере? Он будет немедленно отключен.`,
      async () => {
        try {
          await api(
            "POST",
            `/api/loveauth/player/${encodeURIComponent(name)}/reset-session`,
          );
          showToast(
            "Сессия сброшена",
            `Игрок LoveWebAdmin отключен от игрового сервера`,
            "info",
          );
          window.inspectLoveAuthPlayer(name);
        } catch (e) {
          await noticeDialog(
            "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
            "Ошибка: " + e.message,
          );
        }
      },
      "СБРОСИТЬ СЕССИЮ",
    );
  };
  window.loveAuthChangePassword = (name) => {
    openModal(
      `
                <div class="modal-header">
                    <h3>СМЕНА ПАРОЛЯ: ${esc(name)}</h3>
                    <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon("close", "gray", 16)}</button>
                </div>
                <div class="modal-body">
                    <div class="form-group">
                        <label>Новый пароль для входа в игру</label>
                        <input type="text" id="loveauth-new-pass" placeholder="Минимум 4 символа..." required autofocus>
                    </div>
                    <div style="font-size:12px; color:var(--text-muted);">
                        Пароль будет захеширован и сохранен в базе данных авторизации.
                    </div>
                </div>
                <div class="modal-footer">
                    <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                    <button type="button" class="primary" id="btn-submit-loveauth-pass">СОХРАНИТЬ ПАРОЛЬ</button>
                </div>
            `,
      () => {
        document
          .getElementById("btn-submit-loveauth-pass")
          ?.addEventListener("click", async () => {
            const pass = document
              .getElementById("loveauth-new-pass")
              .value.trim();
            if (!pass || pass.length < 4) {
              await noticeDialog(
                "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
                "Пароль должен содержать минимум 4 символа",
              );
              return;
            }
            try {
              await api(
                "POST",
                `/api/loveauth/player/${encodeURIComponent(name)}/password`,
                {
                  password: pass,
                },
              );
              showToast(
                "Пароль обновлен",
                `Пароль для LoveWebAdmin успешно изменен`,
                "success",
              );
              closeModal();
              window.inspectLoveAuthPlayer(name);
            } catch (e) {
              await noticeDialog(
                "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
                "Ошибка смены пароля: " + e.message,
              );
            }
          });
      },
    );
  };
  window.loveAuthUnlock = (name) => {
    confirmAction(
      "РАЗБЛОКИРОВКА АККАУНТА",
      `Снять блокировку подбора пароля (lockout) с аккаунта LoveWebAdmin?`,
      async () => {
        try {
          await api(
            "POST",
            `/api/loveauth/player/${encodeURIComponent(name)}/unlock`,
          );
          showToast(
            "Разблокирован",
            `Аккаунт LoveWebAdmin успешно разблокирован`,
            "success",
          );
          window.inspectLoveAuthPlayer(name);
        } catch (e) {
          await noticeDialog(
            "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
            "Ошибка разблокировки: " + e.message,
          );
        }
      },
      "РАЗБЛОКИРОВАТЬ",
    );
  };
  window.loveAuthUnblockIp = (ip) => {
    confirmAction(
      "РАЗБЛОКИРОВКА IP",
      `Снять блокировку брутфорса с IP адреса ${ip}?`,
      async () => {
        try {
          await api("POST", `/api/loveauth/player/system/unblock-ip`, {
            ip,
          });
          showToast(
            "IP разблокирован",
            `IP адрес ${ip} разблокирован в системе`,
            "success",
          );
          if (inspectedPlayer) window.inspectLoveAuthPlayer(inspectedPlayer);
        } catch (e) {
          await noticeDialog(
            "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
            "Ошибка: " + e.message,
          );
        }
      },
      "РАЗБЛОКИРОВАТЬ IP",
    );
  };
  window.loveAuthDelete = (name) => {
    confirmAction(
      "УДАЛЕНИЕ АККАУНТА",
      `ВНИМАНИЕ! Вы действительно хотите безвозвратно удалить регистрацию игрока LoveWebAdmin?`,
      async () => {
        try {
          await api(
            "POST",
            `/api/loveauth/player/${encodeURIComponent(name)}/delete`,
          );
          showToast(
            "Аккаунт удален",
            `Регистрация LoveWebAdmin удалена из авторизации`,
            "warning",
          );
          window.inspectLoveAuthPlayer(name);
        } catch (e) {
          await noticeDialog(
            "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
            "Ошибка удаления: " + e.message,
          );
        }
      },
      "УДАЛИТЬ БЕЗВОЗВРАТНО",
    );
  };
  window.terminateStaffSession = (tokenToKill) => {
    confirmAction(
      "ЗАВЕРШЕНИЕ СЕССИИ",
      "Принудительно отозвать токен и завершить данную сессию сотрудника?",
      async () => {
        await api(
          "POST",
          `/api/auth/sessions/${encodeURIComponent(tokenToKill)}/terminate`,
        );
        showToast(
          "Сессия закрыта",
          "Пользователь был отключен от панели",
          "info",
        );
        refreshWebAuthData();
      },
      "ЗАВЕРШИТЬ",
    );
  };

  // Subtab click handlers
  document.querySelectorAll("#auth-subtabs .sub-tab-btn").forEach((btn) => {
    btn.addEventListener("click", () => {
      currentAuthTab = btn.dataset.subtab;
      renderCurrentSubTab();
    });
  });
  await renderCurrentSubTab();
}

// ==========================================================================
// 7. ПЕРСОНАЛ И РОЛИ (СОТРУДНИКИ, ПРИГЛАШЕНИЯ, ИСПЫТАТЕЛЬНЫЙ СРОК, РОЛИ)
// ==========================================================================
