import {trustScoreCard} from "../core/trust-score.js";
import { renderGameAccess } from "./server-access.js";
import { noticeDialog } from "../core/dialogs.js";
import { state } from "../state.js";
import {
  api,
  confirmAction,
  esc,
  fmtDuration,
  fmtTime,
  hasPerm,
  openModal,
  renderSvgIcon,
  showToast,
} from "./ui.js";
// ==========================================================================
// 5. СЕРВЕР (ИГРОКИ, КОНСОЛЬ, WHITELIST & OPs)
// ==========================================================================

export async function renderServerView(initialTab = "players") {
  const area = document.getElementById("content-area");
  const tabs = [
    ["players", "Список игроков", hasPerm("VIEW_PLAYERS")],
    ["console", "Консоль сервера", hasPerm("EXECUTE_COMMANDS")],
    [
      "whitelist",
      "Вход и операторы",
      hasPerm("MANAGE_WHITELIST") || hasPerm("MANAGE_OPERATORS"),
    ],
  ].filter((t) => t[2]);
  let subTab = tabs.some((t) => t[0] === initialTab)
    ? initialTab
    : tabs[0]?.[0];

  area.innerHTML = `
            <div class="view-header">
                <div class="view-title-block">
                    <h2>УПРАВЛЕНИЕ СЕРВЕРОМ</h2>
                    <p>Досье игроков, интерактивная консоль и администрирование списков доступа</p>
                </div>
            </div>

            <!-- Sub Tabs -->
            <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:10px 16px; margin-bottom:18px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px;">
                <div class="panel-tabs" id="server-sub-tabs">
                    ${tabs.map(([id, label]) => `<button type="button" class="filter-tag-btn ${id === subTab ? "active" : ""}" data-sub="${id}">${label}</button>`).join("")}
                </div>
            </div>

            <div id="server-tab-container">
                <!-- Dynamic tab view -->
            </div>
        `;
  document.getElementById("server-sub-tabs")?.addEventListener("click", (e) => {
    const btn = e.target.closest(".filter-tag-btn");
    if (!btn) return;
    document
      .querySelectorAll("#server-sub-tabs .filter-tag-btn")
      .forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    subTab = btn.dataset.sub;
    mountSubTab();
  });

  // Server auto-refresh
  state.pollers["server_auto_refresh"] = setInterval(() => {
    if (subTab === "players") {
      if(!document.hidden)document.getElementById("server-tab-container")?.refreshPlayers?.();
    }
  }, 8000);
  const mountSubTab = () => {
    const container = document.getElementById("server-tab-container");
    if (!container) return;
    delete container.refreshPlayers;
    clearInterval(state.pollers.console_logs_poller);
    delete state.pollers.console_logs_poller;
    if (subTab === "players") renderServerPlayersTab(container);
    else if (subTab === "console") renderServerConsoleTab(container);
    else if (subTab === "whitelist") renderServerWhitelistTab(container);
  };
  mountSubTab();
}
export async function renderServerPlayersTab(container) {
  container.innerHTML = `
            <div style="margin-bottom:16px; display:flex; gap:12px;">
                <div style="flex:1;">
                    <input type="text" id="srv-player-search" placeholder="Поиск игрока по нику...">
                </div>
                <div style="width:160px;">
                    <select id="srv-player-status-filter">
                        <option value="all">Все игроки</option>
                        <option value="online" selected>Только Онлайн</option>
                    </select>
                </div>
            </div>

            <div class="table-wrap">
                <table>
                    <thead>
                        <tr>
                            <th>ИГРОК</th>
                            <th>СТАТУС</th>
                            <th>ПИНГ</th>
                            <th>IP АДРЕС</th>
                            <th>НАИГРАНО</th>
                            <th style="text-align:right;">ДЕЙСТВИЯ</th>
                        </tr>
                    </thead>
                    <tbody id="srv-players-tbody">
                        <tr><td colspan="6" style="text-align:center; padding:24px; color:var(--text-dim);">Загрузка игроков...</td></tr>
                    </tbody>
                </table>
            </div>
        `;
  let loadVersion=0;
  const loadList = async () => {
    const version=++loadVersion;
    const tbody = document.getElementById("srv-players-tbody");
    const q = (
      document.getElementById("srv-player-search")?.value || ""
    ).trim();
    const onlyOnline =
      document.getElementById("srv-player-status-filter")?.value === "online";
    try {
      const stats = await api("GET", "/api/stats");
      const onlineNames = new Set(
        (stats.players || []).map((p) => p.name.toLowerCase()),
      );
      let playerList = [];
      if (onlyOnline) {
        playerList = (stats.players || []).map((p) => ({
          name: p.name,
          uuid: p.uuid,
          isOnline: true,
          ping: p.ping,
          ip: "—",
          playtimeSeconds: 0,
        }));
      } else {
        playerList = await api(
          "GET",
          `/api/players/search?q=${encodeURIComponent(q)}`,
        );
      }
      if(!tbody?.isConnected||version!==loadVersion)return;
      if(onlyOnline&&q)playerList=playerList.filter(player=>player.name.toLowerCase().includes(q.toLowerCase()));
      if (!playerList.length) {
        tbody.innerHTML = `<tr><td colspan="6" style="text-align:center; color:var(--text-dim); padding:24px;">Игроки не найдены</td></tr>`;
        return;
      }
      tbody.innerHTML = playerList
        .map((p) => {
          const isOnline = onlineNames.has(p.name.toLowerCase()) || p.isOnline;
          return `
                        <tr>
                            <td>
                                <div style="display:flex; align-items:center; gap:8px; cursor:pointer;" onclick="window.viewPlayerProfile('${esc(p.name)}')">
                                    <img src="https://mc-heads.net/avatar/${encodeURIComponent(p.name)}/24" class="player-avatar-sm" alt="">
                                    <b>${esc(p.name)}</b>
                                </div>
                            </td>
                            <td>
                                <span class="badge ${isOnline ? "green" : "gray"}">${isOnline ? "● ОНЛАЙН" : "ОФФЛАЙН"}</span>
                            </td>
                            <td>${isOnline ? `<span class="badge gray">${p.ping || 0} мс</span>` : "—"}</td>
                            <td class="font-mono" style="font-size:12px; color:var(--text-dim);">${esc(p.lastIp || p.ip || "—")}</td>
                            <td style="color:var(--text-muted);">${fmtDuration(p.totalPlaytimeSeconds || p.playtimeSeconds || 0)}</td>
                            <td style="text-align:right;">
                                <div style="display:flex; justify-content:flex-end; gap:6px;">
                                    <button type="button" class="secondary btn-sm" onclick="window.viewPlayerProfile('${esc(p.name)}')">ДОСЬЕ</button>
                                    <button type="button" class="danger btn-sm" onclick="window.openQuickBanModal('${esc(p.name)}')">БАН</button>
                                </div>
                            </td>
                        </tr>
                    `;
        })
        .join("");
    } catch (e) {
      if(tbody?.isConnected&&version===loadVersion)tbody.innerHTML = `<tr><td colspan="6" style="text-align:center; color:var(--red); padding:20px;">Ошибка загрузки списка</td></tr>`;
    }
  };
  document
    .getElementById("srv-player-search")
    ?.addEventListener("input", loadList);
  document
    .getElementById("srv-player-status-filter")
    ?.addEventListener("change", loadList);
  container.refreshPlayers=loadList;
  await loadList();
}

// Player Dossier Modal
export function exposeViewPlayerProfile() {
  window.viewPlayerProfile = async function (playerName) {
    openModal(
      `
            <div class="modal-header">
                <h3>ДОСЬЕ ИГРОКА: ${esc(playerName)}</h3>
                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">${renderSvgIcon("close", "gray", 16)}</button>
            </div>
            <div class="modal-body" id="player-dossier-body">
                <div style="text-align:center; padding:30px; color:var(--text-muted);">Загрузка полного досье игрока...</div>
            </div>
        `,
      async () => {
        const body = document.getElementById("player-dossier-body");
        body.closest(".modal")?.classList.add("workspace-drawer");
        try {
          const p = await api(
            "GET",
            `/api/players/${encodeURIComponent(playerName)}`,
          );
          const trustColor = p.trustColor || "#10b981";
          const trustGrade = p.trustGrade || "A";
          const trustScore = p.trustScore != null ? p.trustScore : 85;
          const notes = p.staffNotes || [];
          const bans = p.bans || [];
          if (!body.isConnected) return;
          body.innerHTML = `
                    <!-- Header Info Bar -->
                    <div style="display:flex; align-items:center; justify-content:space-between; background:var(--card-inner-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:16px; margin-bottom:18px;">
                        <div style="display:flex; align-items:center; gap:14px;">
                            <img src="https://mc-heads.net/avatar/${encodeURIComponent(p.name)}/48" style="width:48px; height:48px; border-radius:6px; background:var(--bg-surface); border:1px solid var(--border-light);" alt="">
                            <div>
                                <h3 style="font-size:18px; margin-bottom:2px; color:var(--text-main);">${esc(p.name)}</h3>
                                <div style="font-size:11.5px; color:var(--text-muted); font-family:'JetBrains Mono';">${esc(p.uuid)}</div>
                                <div style="margin-top:4px;">
                                    <span class="badge ${p.isOnline ? "green" : "gray"}">${p.isOnline ? `● В СЕТИ (${p.ping} мс)` : "ОФФЛАЙН"}</span>
                                    <span class="badge purple">IP: ${esc(p.ip || "—")}</span>
                                </div>
                            </div>
                        </div>

                        <!-- Trust Score Pill -->
                        <div style="text-align:right; background:var(--card-bg); padding:10px 14px; border-radius:8px; border:1px solid var(--border);">
                            ${trustScoreCard(p)}
                        </div>
                    </div>

                    <!-- Dossier Sub-tabs -->
                    <div class="filter-tags" id="dossier-tabs" style="margin-bottom:14px;">
                        <button type="button" class="filter-tag-btn active" data-tab="overview">Обзор</button>
                        <button type="button" class="filter-tag-btn" data-tab="actions">Быстрые действия</button>
                        <button type="button" class="filter-tag-btn" data-tab="notes">Заметки стаффа (${notes.length})</button>
                        <button type="button" class="filter-tag-btn" data-tab="bans">Наказания (${bans.length})</button>
                    </div>

                    <div id="dossier-tab-content">
                        <!-- Overview by default -->
                    </div>
                `;
          const dossierContainer = document.getElementById(
            "dossier-tab-content",
          );
          const renderOverview = () => {
            dossierContainer.innerHTML = `
                        <div style="display:grid; grid-template-columns:1fr 1fr; gap:12px; margin-bottom:16px;">
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">Первый вход</div>
                                <div class="font-mono" style="font-size:13px; color:var(--text);">${fmtTime(p.firstJoinedAt)}</div>
                            </div>
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">Последняя активность</div>
                                <div class="font-mono" style="font-size:13px; color:var(--text);">${fmtTime(p.lastSeenAt)}</div>
                            </div>
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">Общее время в игре</div>
                                <div style="font-size:16px; font-weight:700; color:var(--accent-light);">${fmtDuration(p.playtimeSeconds || 0)}</div>
                            </div>
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">Баланс монет (Экономика)</div>
                                <div style="font-size:16px; font-weight:700; color:var(--text-main);">${p.economy?.balance != null ? p.economy.balance.toLocaleString() + " монет" : "Нет актуальных данных"}</div>
                            </div>
                        </div>

                        <div style="display:flex; justify-content:space-between; align-items:center; background:var(--card-inner-bg); padding:12px; border-radius:6px; border:1px solid var(--border);">
                            <span style="font-size:12px; color:var(--text-muted);">Проверка на связанные аккаунты по IP графу:</span>
                            <button type="button" class="secondary btn-sm" onclick="window.openPlayerAltsModal('${esc(p.name)}')">ПРОВЕРИТЬ АЛЬТЫ</button>
                        </div>
                    `;
          };
          const renderActions = () => {
            if (!p.isOnline) {
              dossierContainer.innerHTML = `<div style="text-align:center; padding:24px; color:var(--text-dim);">Игрок не в сети. Интерактивные действия доступны только когда игрок онлайн на сервере.</div>`;
              return;
            }
            dossierContainer.innerHTML = `
                        <div style="display:flex; gap:12px; margin-bottom:16px; flex-wrap:wrap;">
                            <button type="button" class="quick-action-btn" id="act-kick" style="min-width:160px; flex:1; padding:18px;">
                                <span class="qa-icon" style="margin-bottom:6px;">${renderSvgIcon("userMinus", "red", 24)}</span>
                                <span style="font-weight:700;">КИКНУТЬ ИГРОКА</span>
                            </button>
                        </div>
                    `;
            const execPlayerAction = async (action, bodyObj = {}) => {
              try {
                const res = await api(
                  "POST",
                  `/api/players/${encodeURIComponent(p.name)}/action`,
                  {
                    action,
                    ...bodyObj,
                  },
                );
                showToast(
                  "Действие выполнено",
                  res.message || "Успешно",
                  "success",
                );
              } catch (e) {
                if (e.message && e.message.includes("не в сети")) {
                  showToast(
                    "Игрок оффлайн",
                    `Игрок ${p.name} не в сети или вышел с сервера`,
                    "warning",
                  );
                } else {
                  showToast(
                    "Ошибка действия",
                    e.message || "Не удалось выполнить действие",
                    "danger",
                  );
                }
              }
            };
            document
              .getElementById("act-kick")
              ?.addEventListener("click", () => {
                const reason = prompt(
                  `Причина кика игрока ${p.name}:`,
                  "Кикнут администратором через веб-панель",
                );
                if (reason !== null && reason.trim())
                  execPlayerAction("kick", {
                    reason: reason.trim(),
                  });
              });
          };
          const renderNotes = () => {
            dossierContainer.innerHTML = `
                        <div style="margin-bottom:14px; background:var(--card-inner-bg); padding:12px; border-radius:6px; border:1px solid var(--border);">
                            <label>Добавить внутреннюю заметку стаффа:</label>
                            <div style="display:flex; gap:8px;">
                                <input type="text" id="new-note-text" placeholder="Заметка о поведении игрока (видна только стаффу)...">
                                <button type="button" class="primary btn-sm" id="btn-add-note">ДОБАВИТЬ</button>
                            </div>
                        </div>

                        <div id="notes-list-box" style="max-height:220px; overflow-y:auto;">
                            ${
                              notes.length
                                ? notes
                                    .map(
                                      (n) => `
                                <div style="background:var(--card-inner-bg); padding:10px 12px; border-radius:6px; border:1px solid var(--border); margin-bottom:8px; display:flex; justify-content:space-between; align-items:flex-start;">
                                    <div>
                                        <div style="font-size:11px; color:var(--text-dim); margin-bottom:3px;">
                                            Автор: <b style="color:var(--accent-light);">${esc(n.author)}</b> • ${fmtTime(n.createdAt)}
                                        </div>
                                        <div style="color:var(--text-main); font-size:13px;">${esc(n.note)}</div>
                                    </div>
                                    <button type="button" class="secondary btn-sm" onclick="window.deleteStaffNote('${esc(p.name)}', ${n.id})" style="color:var(--red); padding:4px 8px; display:inline-flex; align-items:center;">${renderSvgIcon("trash", "red", 13)}</button>
                                </div>
                            `,
                                    )
                                    .join("")
                                : '<div style="color:var(--text-dim); font-size:12px; text-align:center; padding:16px;">Заметок пока нет</div>'
                            }
                        </div>
                    `;
            document
              .getElementById("btn-add-note")
              ?.addEventListener("click", async () => {
                const text = document
                  .getElementById("new-note-text")
                  .value.trim();
                if (!text) return;
                try {
                  await api(
                    "POST",
                    `/api/players/${encodeURIComponent(p.name)}/notes`,
                    {
                      note: text,
                    },
                  );
                  showToast(
                    "Заметка сохранена",
                    "Внутренняя заметка успешно добавлена",
                    "success",
                  );
                  window.viewPlayerProfile(p.name);
                } catch (e) {
                  await noticeDialog(
                    "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
                    "Ошибка добавления заметки: " + e.message,
                  );
                }
              });
          };
          const renderBans = () => {
            dossierContainer.innerHTML = `
                        <div style="max-height:240px; overflow-y:auto;">
                            ${
                              bans.length
                                ? bans
                                    .map(
                                      (b) => `
                                <div style="background:var(--card-inner-bg); padding:10px 12px; border-radius:6px; border:1px solid var(--border); margin-bottom:8px;">
                                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:4px;">
                                        <span class="badge red">${esc(b.ruleReason)}</span>
                                        <span class="badge ${b.status === "ACTIVE" ? "red" : "green"}">${b.status === "ACTIVE" ? "АКТИВЕН" : "СНЯТ"}</span>
                                    </div>
                                    <div style="font-size:12px; color:var(--text-muted);">${esc(b.description || "Без описания")}</div>
                                    <div style="font-size:11px; color:var(--text-dim); margin-top:4px;">Источник: ${esc(b.sourceDisplay || b.creatorName)} • ${fmtTime(b.createdAt)}</div>
                                </div>
                            `,
                                    )
                                    .join("")
                                : '<div style="color:var(--text-dim); font-size:12px; text-align:center; padding:20px;">Наказаний нет</div>'
                            }
                        </div>
                    `;
          };
          document
            .getElementById("dossier-tabs")
            ?.addEventListener("click", (e) => {
              const btn = e.target.closest(".filter-tag-btn");
              if (!btn) return;
              document
                .querySelectorAll("#dossier-tabs .filter-tag-btn")
                .forEach((b) => b.classList.remove("active"));
              btn.classList.add("active");
              const tab = btn.dataset.tab;
              if (tab === "overview") renderOverview();
              else if (tab === "actions") renderActions();
              else if (tab === "notes") renderNotes();
              else if (tab === "bans") renderBans();
            });
          renderOverview();
        } catch (e) {
          body.innerHTML = `<div style="color:var(--red); padding:20px;">Ошибка загрузки досье игрока: ${esc(e.message)}</div>`;
        }
      },
      "modal-lg",
    );
  };
}
export function exposeDeleteStaffNote() {
  window.deleteStaffNote = async function (playerName, noteId) {
    try {
      await api(
        "DELETE",
        `/api/players/${encodeURIComponent(playerName)}/notes/${noteId}`,
      );
      showToast("Заметка удалена", "Заметка стаффа успешно удалена", "info");
      window.viewPlayerProfile(playerName);
    } catch (e) {
      await noticeDialog(
        "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
        "Ошибка удаления заметки: " + e.message,
      );
    }
  };

  // Server Console Tab
}
export function renderServerConsoleTab(container) {
  container.innerHTML = `
            <div class="terminal-window">
                <div class="terminal-header">
                    <span style="font-family:'Outfit'; font-weight:700; color:var(--text-heading); font-size:13px;">КОНСОЛЬ СЕРВЕРА MINECRAFT</span>
                    <span class="badge green">Доступ к командам сервера</span>
                </div>
                <div class="terminal-logs-body" id="console-logs-body">
                    ${hasPerm("VIEW_SERVER_LOGS") ? "Загрузка последних серверных логов…" : "Просмотр журнала недоступен: требуется право просмотра серверных логов. Выполнение команд остаётся доступным."}
                </div>
                <div class="terminal-input-row">
                    <span class="terminal-prompt">&gt;</span>
                    <input type="text" class="terminal-input" id="console-cmd-input" placeholder="Введите команду (например: list, tps, say Привет)..." autocomplete="off">
                    <button type="button" class="primary btn-sm" id="console-send-btn">ВЫПОЛНИТЬ</button>
                </div>
            </div>
        `;
  const logBody = document.getElementById("console-logs-body");
  const cmdInput = document.getElementById("console-cmd-input");
  const cmdHistory = [];
  let historyIdx = -1;
  const refreshConsoleLogs = async () => {
    if (!hasPerm("VIEW_SERVER_LOGS") || !logBody?.isConnected) return;
    try {
      const logs = await api("GET", "/api/logs/server?mode=recent");
      if (!logBody?.isConnected) return;
      const wasBottom =
        logBody.scrollHeight - logBody.scrollTop <= logBody.clientHeight + 40;
      logBody.innerHTML = logs
        .map((l) => {
          const msg = l.action || l.message || "";
          let cls = "info";
          if (msg.includes("WARN")) cls = "warn";
          if (msg.includes("ERROR") || msg.includes("Exception")) cls = "error";
          return `<div class="terminal-log-line ${cls}">[${fmtTime(l.timestamp)}] ${esc(msg)}</div>`;
        })
        .join("");
      if (wasBottom) logBody.scrollTop = logBody.scrollHeight;
    } catch (e) {}
  };
  refreshConsoleLogs();
  if (hasPerm("VIEW_SERVER_LOGS")) state.pollers["console_logs_poller"] = setInterval(refreshConsoleLogs, 3000);
  const sendCmd = async () => {
    const cmd = cmdInput.value.trim();
    if (!cmd) return;
    cmdHistory.push(cmd);
    historyIdx = cmdHistory.length;
    cmdInput.value = "";
    try {
      await api("POST", "/api/command", {
        command: cmd,
      });
      showToast("Команда отправлена", `/${cmd}`, "info");
      setTimeout(refreshConsoleLogs, 500);
    } catch (e) {
      await noticeDialog(
        "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
        "Ошибка выполнения: " + e.message,
      );
    }
  };
  document
    .getElementById("console-send-btn")
    ?.addEventListener("click", sendCmd);
  cmdInput?.addEventListener("keydown", (e) => {
    if (e.key === "Enter") sendCmd();
    else if (e.key === "ArrowUp") {
      if (historyIdx > 0) {
        historyIdx--;
        cmdInput.value = cmdHistory[historyIdx] || "";
      }
    } else if (e.key === "ArrowDown") {
      if (historyIdx < cmdHistory.length - 1) {
        historyIdx++;
        cmdInput.value = cmdHistory[historyIdx] || "";
      } else {
        historyIdx = cmdHistory.length;
        cmdInput.value = "";
      }
    }
  });
}

// Server Whitelist & Operators Tab
export async function renderServerWhitelistTab(container) {
  return renderGameAccess(container);
}

// ==========================================================================
// 6. АУТЕНТИФИКАЦИЯ (СЕССИИ, ИСТОРИЯ ВХОДОВ, LOVEAUTH)
// ==========================================================================
