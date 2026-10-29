import { noticeDialog } from "../core/dialogs.js";
import { state } from "../state.js";
import {
  api,
  attachPlayerAutocomplete,
  closeModal,
  confirmAction,
  esc,
  fmtTime,
  formatUptime,
  isModeratorRole,
  openModal,
  renderSvgIcon,
  showToast,
} from "./ui.js";
export function getActiveQuickActions() {
  try {
    const saved = state.browserStorage.getItem("wa_quick_actions");
    if (saved) {
      const parsed = JSON.parse(saved);
      const valid = parsed.filter((k) => state.ALL_QUICK_ACTIONS[k]);
      if (valid.length > 0) return valid;
    }
  } catch (e) {}
  return ["ban", "mute", "kick", "vanish", "clear_chat", "dossier"];
}
export function saveActiveQuickActions(keys) {
  state.browserStorage.setItem("wa_quick_actions", JSON.stringify(keys));
}
export function exposeOpenQuickActionsConfigModal() {
  window.openQuickActionsConfigModal = () => {
    const active = getActiveQuickActions();
    openModal(
      `
            <div class="modal-header">
                <h3>НАСТРОЙКА БЫСТРЫХ ДЕЙСТВИЙ</h3>
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon("close", "gray", 16)}</button>
            </div>
            <div class="modal-body">
                <div style="font-size:12px; color:var(--text-muted); margin-bottom:14px;">
                    Выберите действия персонала для быстрого реагирования на дашборде:
                </div>
                <div style="display:flex; flex-direction:column; gap:10px;">
                    ${Object.values(state.ALL_QUICK_ACTIONS)
                      .map(
                        (a) => `
                        <label class="toggle-switch-wrap" style="justify-content:space-between;">
                            <div style="display:flex; align-items:center; gap:10px;">
                                <span style="font-size:18px; color:${a.color}; display:flex; align-items:center;">${a.icon}</span>
                                <div>
                                    <b style="color:var(--text-heading); font-size:13px;">${esc(a.label)}</b>
                                    <div style="font-size:11px; color:var(--text-dim);">${esc(a.sub)}</div>
                                </div>
                            </div>
                            <input type="checkbox" class="qa-toggle-cb toggle-switch-input" value="${a.id}" ${active.includes(a.id) ? "checked" : ""}>
                            <span class="toggle-switch"></span>
                        </label>
                    `,
                      )
                      .join("")}
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                <button type="button" class="primary" id="btn-save-qa-config">СОХРАНИТЬ</button>
            </div>
        `,
      () => {
        document
          .getElementById("btn-save-qa-config")
          ?.addEventListener("click", () => {
            const selected = Array.from(
              document.querySelectorAll(".qa-toggle-cb:checked"),
            ).map((cb) => cb.value);
            saveActiveQuickActions(
              selected.length ? selected : ["ban", "mute", "kick"],
            );
            closeModal();
            showToast(
              "Сохранено",
              "Список быстрых действий обновлен",
              "success",
            );
            renderDashboardView();
          });
      },
    );
  };
}
export function exposeOpenQuickPlayerSearchModal() {
  window.openQuickPlayerSearchModal = function () {
    openModal(
      `
            <div class="modal-header">
                <h3>ПОИСК ДОСЬЕ ИГРОКА</h3>
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon("close", "gray", 16)}</button>
            </div>
            <div class="modal-body">
                <div class="form-group">
                    <label>Никнейм игрока</label>
                    <input type="text" id="quick-dossier-search-name" placeholder="Введите ник игрока..." required autofocus autocomplete="off">
                </div>
                <div style="font-size:12px; color:var(--text-muted); line-height:1.5;">
                    Мгновенный переход к полному досье игрока: альты, история наказаний, активные ограничения, инвентарь и статистика.
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                <button type="button" class="primary" id="btn-submit-quick-dossier">ОТКРЫТЬ ДОСЬЕ</button>
            </div>
        `,
      () => {
        const input = document.getElementById("quick-dossier-search-name");
        if (input) attachPlayerAutocomplete(input);
        const submit = () => {
          const name = input ? input.value.trim() : "";
          if (!name) return;
          closeModal();
          window.viewPlayerProfile(name);
        };
        input?.addEventListener("keydown", (e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            submit();
          }
        });
        document
          .getElementById("btn-submit-quick-dossier")
          ?.addEventListener("click", submit);
      },
    );
  };
}
export function exposeQuickClearChatAction() {
  window.quickClearChatAction = async () => {
    confirmAction(
      "ОЧИСТКА ЧАТА",
      "Очистить глобальный чат сервера для всех игроков от флуда и спама?",
      async () => {
        try {
          await api("POST", "/api/command", {
            command:
              "broadcast &r \n \n \n \n \n \n \n \n \n \n \n \n &eГлобальный чат был очищен администратором.",
          });
          showToast("Чат очищен", "Команда очистки чата выполнена", "info");
        } catch (e) {
          showToast("Чат очищен", "Сообщение отправлено на сервер", "info");
        }
      },
      "ОЧИСТИТЬ",
      false,
    );
  };
}
export function getActiveDashboardTiles() {
  let prefs = {};
  try {
    prefs =
      typeof state.me?.uiPreferences === "string"
        ? JSON.parse(state.me.uiPreferences)
        : state.me?.uiPreferences || {};
  } catch (_) {}
  if (Array.isArray(prefs.dashboardTiles) && prefs.dashboardTiles.length) {
    return prefs.dashboardTiles;
  }
  try {
    const saved = state.browserStorage.getItem("wa_dashboard_tiles");
    if (saved) return JSON.parse(saved);
  } catch (e) {}
  return state.DEFAULT_DASHBOARD_TILES;
}
export async function saveDashboardTiles(tiles) {
  state.browserStorage.setItem("wa_dashboard_tiles", JSON.stringify(tiles));
  let prefs = {};
  try {
    prefs =
      typeof state.me?.uiPreferences === "string"
        ? JSON.parse(state.me.uiPreferences)
        : state.me?.uiPreferences || {};
  } catch (_) {}
  prefs.dashboardTiles = tiles;
  if (state.me) state.me.uiPreferences = JSON.stringify(prefs);
  try {
    await api("PUT", "/api/me/preferences", {
      uiPreferences: prefs,
    });
  } catch (_) {}
}
export async function renderDashboardView() {
  const area = document.getElementById("content-area");
  let activeTiles = getActiveDashboardTiles();
  area.innerHTML = `
            <div class="view-header">
                <div class="view-title-block">
                    <h2>ДАШБОРД</h2>
                    <p>Оперативная панель мониторинга сервера и быстрого реагирования (перетаскивайте плитки для настройки порядка)</p>
                </div>
                <div class="view-actions">
                    <button type="button" class="secondary" id="dash-customize-btn" style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon("gear", "gray", 14)} НАСТРОЙКА ПЛИТОК</button>
                </div>
            </div>

            <!-- Dynamic Tiles Grid -->
            <div class="dashboard-tiles-grid" id="dashboard-grid">
                <!-- Rendered dynamically -->
            </div>
        `;

  // Customize button
  document
    .getElementById("dash-customize-btn")
    ?.addEventListener("click", () => {
      openDashboardCustomizerModal();
    });
  const grid = document.getElementById("dashboard-grid");
  grid.innerHTML = activeTiles
    .map((tileKey, idx) => {
      const html = getTileContainerHtml(tileKey);
      return html.replace(
        '<div class="tile ',
        `<div draggable="true" data-tile-key="${tileKey}" data-tile-idx="${idx}" class="tile `,
      );
    })
    .join("");

  // Mount Drag & Drop handlers for tiles
  let draggedTileKey = null;
  let draggedTileIdx = null;
  grid.querySelectorAll('.tile[draggable="true"]').forEach((el) => {
    el.addEventListener("dragstart", (e) => {
      draggedTileKey = el.dataset.tileKey;
      draggedTileIdx = parseInt(el.dataset.tileIdx, 10);
      el.classList.add("dragging");
      e.dataTransfer.effectAllowed = "move";
      e.dataTransfer.setData("text/plain", draggedTileKey);
    });
    el.addEventListener("dragenter", (e) => {
      e.preventDefault();
      if (el.dataset.tileKey !== draggedTileKey) {
        el.classList.add("drag-over");
      }
    });
    el.addEventListener("dragover", (e) => {
      e.preventDefault();
      e.dataTransfer.dropEffect = "move";
    });
    el.addEventListener("dragleave", (e) => {
      if (!el.contains(e.relatedTarget)) {
        el.classList.remove("drag-over");
      }
    });
    el.addEventListener("drop", async (e) => {
      e.preventDefault();
      el.classList.remove("drag-over");
      const targetKey = el.dataset.tileKey;
      const targetIdx = parseInt(el.dataset.tileIdx, 10);
      if (draggedTileKey && targetKey && draggedTileKey !== targetKey) {
        const newTiles = [...activeTiles];
        const [removed] = newTiles.splice(draggedTileIdx, 1);
        newTiles.splice(targetIdx, 0, removed);
        activeTiles = newTiles;
        await saveDashboardTiles(newTiles);
        renderDashboardView();
        showToast(
          "Порядок сохранён",
          "Порядок плиток обновлён и сохранён в профиль",
          "info",
        );
      }
    });
    el.addEventListener("dragend", () => {
      el.classList.remove("dragging");
      grid
        .querySelectorAll(".tile")
        .forEach((t) => t.classList.remove("drag-over"));
      draggedTileKey = null;
      draggedTileIdx = null;
    });
  });

  // Mount tile logic
  activeTiles.forEach((tileKey) => mountTileLogic(tileKey));
}
export function getTileContainerHtml(key) {
  switch (key) {
    case "server_stats":
      return `
                    <div class="tile tile-lg" id="tile-server_stats">
                        <div class="tile-header">
                            <span class="tile-title" style="display:flex; align-items:center; gap:8px;">${renderSvgIcon("server", "violet", 16)} СТАТИСТИКА СЕРВЕРА</span>
                            <span class="badge purple">LIVE 3S</span>
                        </div>
                        <div class="tile-body" id="tile-body-server_stats">Загрузка метрик...</div>
                    </div>`;
    case "quick_actions": {
      const activeKeys = getActiveQuickActions();
      return `
                    <div class="tile tile-sm" id="tile-quick_actions">
                        <div class="tile-header">
                            <span class="tile-title" style="display:flex; align-items:center; gap:8px;">
                                ${renderSvgIcon("zap", "violet", 16)}
                                БЫСТРЫЕ ДЕЙСТВИЯ
                            </span>
                            <button type="button" class="secondary btn-sm" onclick="window.openQuickActionsConfigModal()" title="Настроить состав действий">НАСТРОИТЬ</button>
                        </div>
                        <div class="tile-body" id="tile-body-quick_actions">
                            <div class="quick-actions-grid">
                                ${activeKeys
                                  .map((k) => {
                                    const act = state.ALL_QUICK_ACTIONS[k];
                                    if (!act) return "";
                                    return `
                                        <button type="button" class="quick-action-btn" onclick="${act.action}">
                                            <span class="qa-icon" style="color:${act.color};">${act.icon}</span>
                                            <span class="qa-label">${esc(act.label)}</span>
                                            <span class="qa-sub">${esc(act.sub || "")}</span>
                                        </button>
                                    `;
                                  })
                                  .join("")}
                            </div>
                        </div>
                    </div>`;
    }
    case "vesuvio_flags":
      return `
                    <div class="tile tile-lg" id="tile-vesuvio_flags">
                        <div class="tile-header">
                            <span class="tile-title" style="display:flex; align-items:center; gap:8px;">${renderSvgIcon("shieldAlert", "violet", 16)} ПОСЛЕДНИЕ СРАБАТЫВАНИЯ АНТИЧИТА</span>
                            <button type="button" class="secondary btn-sm" onclick="window.navigateTo('anticheat')">ВСЕ ФЛАГИ →</button>
                        </div>
                        <div class="tile-body" id="tile-body-vesuvio_flags" style="padding:10px 18px;">Загрузка потока...</div>
                    </div>`;
    case "attention":
      return `
                    <div class="tile tile-sm" id="tile-attention">
                        <div class="tile-header">
                            <span class="tile-title" style="display:flex; align-items:center; gap:8px;">${renderSvgIcon("alert", "red", 16)} ТРЕБУЕТ ВНИМАНИЯ</span>
                            <span class="badge yellow">ФЛАГИ & РИСК</span>
                        </div>
                        <div class="tile-body" id="tile-body-attention">Загрузка...</div>
                    </div>`;
    case "punishments_stats":
      return `
                    <div class="tile tile-sm" id="tile-punishments_stats">
                        <div class="tile-header">
                            <span class="tile-title" style="display:flex; align-items:center; gap:8px;">${renderSvgIcon("scales", "violet", 16)} СТАТИСТИКА НАКАЗАНИЙ</span>
                        </div>
                        <div class="tile-body" id="tile-body-punishments_stats">Загрузка...</div>
                    </div>`;
    case "online_players":
      return `
                    <div class="tile tile-sm" id="tile-online_players">
                        <div class="tile-header">
                            <span class="tile-title" style="display:flex; align-items:center; gap:8px;">${renderSvgIcon("users", "violet", 16)} ОНЛАЙН-ИГРОКИ</span>
                            <button type="button" class="secondary btn-sm" onclick="window.navigateTo('server')">ИГРОКИ →</button>
                        </div>
                        <div class="tile-body" id="tile-body-online_players" style="max-height:280px; overflow-y:auto; padding:8px 14px;">Загрузка...</div>
                    </div>`;
    case "admin_actions":
      return `
                    <div class="tile tile-sm" id="tile-admin_actions">
                        <div class="tile-header">
                            <span class="tile-title" style="display:flex; align-items:center; gap:8px;">${renderSvgIcon("fileText", "gray", 16)} ПОСЛЕДНИЕ ДЕЙСТВИЯ ${isModeratorRole() ? "МОИ" : "АДМИНИСТРАЦИИ"}</span>
                            <button type="button" class="secondary btn-sm" onclick="window.navigateTo('journal')">ЖУРНАЛ →</button>
                        </div>
                        <div class="tile-body" id="tile-body-admin_actions" style="max-height:280px; overflow-y:auto; padding:10px 16px;">Загрузка...</div>
                    </div>`;
    case "my_shift":
      return `
                    <div class="tile tile-sm" id="tile-my_shift">
                        <div class="tile-header">
                            <span class="tile-title" style="display:flex; align-items:center; gap:8px;">${renderSvgIcon("clock", "green", 16)} МОЯ СМЕНА</span>
                            <span class="badge green">АКТИВНА</span>
                        </div>
                        <div class="tile-body" id="tile-body-my_shift">Загрузка...</div>
                    </div>`;
    default:
      return "";
  }
}
export async function mountTileLogic(key) {
  if (key === "server_stats") {
    const body = document.getElementById("tile-body-server_stats");
    const refresh = async () => {
      try {
        const s = await api("GET", "/api/stats");
        if (!body) return;
        const tps = s.tps && s.tps.length ? s.tps : [20.0, 20.0, 20.0];
        const mem = s.memory || {
          usedMb: 1024,
          maxMb: 4096,
          percent: 25,
        };
        const mspt = s.mspt != null ? s.mspt : 20.0;
        body.innerHTML = `
                        <div style="display:grid; grid-template-columns: repeat(4, 1fr); gap:12px; margin-bottom:16px;">
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">TPS (1 МИН)</div>
                                <div class="stat-value" style="font-size:22px; color:${tps[0] >= 18 ? "var(--green)" : "var(--yellow)"};">${tps[0].toFixed(2)}</div>
                            </div>
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">MSPT</div>
                                <div class="stat-value" style="font-size:22px; color:${mspt <= 40 ? "var(--green)" : "var(--red)"};">${mspt} <span style="font-size:12px; font-family:'Inter'; color:var(--text-dim);">мс</span></div>
                            </div>
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">ОНЛАЙН</div>
                                <div class="stat-value" style="font-size:22px;">${s.onlinePlayers} <span style="font-size:12px; font-family:'Inter'; color:var(--text-dim);">/ ${s.maxPlayers}</span></div>
                            </div>
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">АПТАЙМ</div>
                                <div class="stat-value" style="font-size:16px; margin-top:4px;">${formatUptime(s.uptime)}</div>
                            </div>
                        </div>

                        <div class="stat-bars-container">
                            <div class="stat-bar-item">
                                <div class="stat-bar-meta">
                                    <span class="stat-bar-label">ИСПОЛЬЗОВАНИЕ ОЗУ (JAVA HEAP)</span>
                                    <span class="stat-bar-val">${mem.usedMb} МБ / ${mem.maxMb} МБ (${mem.percent}%)</span>
                                </div>
                                <div class="stat-bar-track">
                                    <div class="stat-bar-fill ${mem.percent > 85 ? "red" : mem.percent > 65 ? "yellow" : "purple"}" style="width:${Math.min(100, mem.percent)}%;"></div>
                                </div>
                            </div>
                        </div>
                    `;
      } catch (e) {
        if (body)
          body.innerHTML = `<div style="color:var(--red);">Ошибка получения телеметрии</div>`;
      }
    };
    await refresh();
    state.pollers["dash_server_stats"] = setInterval(refresh, 3000);
  }
  if (key === "vesuvio_flags") {
    const body = document.getElementById("tile-body-vesuvio_flags");
    try {
      const res = await api("GET", "/api/vesuvio/violations?limit=5").catch(
        () => [],
      );
      const list = Array.isArray(res) ? res : res.violations || [];
      if (!body) return;
      if (!list.length) {
        body.innerHTML = `<div style="padding:20px; text-align:center; color:var(--text-dim);">Срабатываний античита пока нет (система чиста)</div>`;
      } else {
        body.innerHTML = list
          .slice(0, 4)
          .map((v) => {
            const name = v.playerName || v.name || "Игрок";
            const check = v.check || v.type || "Movement";
            const vl = v.vl || v.vlScore || 1;
            return `
                            <div class="violation-card">
                                <div class="violation-info">
                                    <img src="https://mc-heads.net/avatar/${encodeURIComponent(name)}/28" class="player-avatar-sm" alt="">
                                    <div class="violation-meta">
                                        <h4>${esc(name)} <span class="badge red">${esc(check)}</span></h4>
                                        <div class="violation-details">Уверенность VL: <b>${vl}</b> • ${fmtTime(v.timestamp || Date.now() / 1000)}</div>
                                    </div>
                                </div>
                                <div style="display:flex; gap:6px;">
                                    <button type="button" class="danger btn-sm" onclick="window.openQuickBanModal('${esc(name)}', 'Читы')">БАН</button>
                                    <button type="button" class="secondary btn-sm" onclick="window.viewPlayerProfile('${esc(name)}')">ДОСЬЕ</button>
                                </div>
                            </div>`;
          })
          .join("");
      }
    } catch (e) {
      if (body)
        body.innerHTML = `<div style="color:var(--text-dim); text-align:center; padding:16px;">Античит недоступен или отключен</div>`;
    }
  }
  if (key === "attention") {
    const body = document.getElementById("tile-body-attention");
    try {
      const suspects = await api("GET", "/api/vesuvio/suspects").catch(
        () => [],
      );
      const list = Array.isArray(suspects) ? suspects : [];
      if (!body) return;
      if (!list.length) {
        body.innerHTML = `
                        <div style="text-align:center; padding:24px; color:var(--text-dim);">
                            <div style="margin-bottom:6px; display:inline-flex; align-items:center;">${renderSvgIcon("check", "green", 24)}</div>
                            <div>Подозрительных игроков нет</div>
                        </div>`;
      } else {
        body.innerHTML = list
          .map(
            (p) => `
                        <div style="display:flex; align-items:center; justify-content:space-between; padding:8px 0; border-bottom:1px solid var(--border);">
                            <div style="display:flex; align-items:center; gap:8px;">
                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(p.name || "")}/24" class="player-avatar-sm" alt="">
                                <div>
                                    <div style="font-weight:700; color:var(--text-main);">${esc(p.name)}</div>
                                    <div style="font-size:11px; color:var(--yellow);">Режим наблюдения</div>
                                </div>
                            </div>
                            <button type="button" class="secondary btn-sm" onclick="window.viewPlayerProfile('${esc(p.name)}')">ПРОВЕРКА</button>
                        </div>
                    `,
          )
          .join("");
      }
    } catch (e) {
      if (body)
        body.innerHTML = `<div style="color:var(--text-dim); padding:16px; text-align:center;">Нет данных</div>`;
    }
  }
  if (key === "punishments_stats") {
    const body = document.getElementById("tile-body-punishments_stats");
    try {
      const bans = await api("GET", "/api/bans/all").catch(() => []);
      if (!body) return;
      const now = Date.now() / 1000;
      const todayBans = bans.filter((b) => now - b.createdAt < 86400).length;
      const weekBans = bans.filter((b) => now - b.createdAt < 7 * 86400).length;
      body.innerHTML = `
                    <div style="display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:12px;">
                        <div class="stat-card" style="padding:12px;">
                            <div class="stat-label">СЕГОДНЯ</div>
                            <div class="stat-value" style="color:var(--red); font-size:24px;">${todayBans}</div>
                        </div>
                        <div class="stat-card" style="padding:12px;">
                            <div class="stat-label">ЗА 7 ДНЕЙ</div>
                            <div class="stat-value" style="color:var(--yellow); font-size:24px;">${weekBans}</div>
                        </div>
                    </div>
                    <div style="font-size:12px; color:var(--text-muted); text-align:center;">
                        Всего активных банов в базе: <b>${bans.length}</b>
                    </div>
                `;
    } catch (e) {
      if (body)
        body.innerHTML = `<div style="color:var(--red);">Ошибка статистики наказаний</div>`;
    }
  }
  if (key === "online_players") {
    const body = document.getElementById("tile-body-online_players");
    try {
      const s = await api("GET", "/api/stats").catch(() => ({
        players: [],
      }));
      if (!body) return;
      const players = s.players || [];
      if (!players.length) {
        body.innerHTML = `<div style="text-align:center; padding:20px; color:var(--text-dim);">Сейчас на сервере нет игроков онлайн</div>`;
      } else {
        body.innerHTML = players
          .map(
            (p) => `
                        <div style="display:flex; align-items:center; justify-content:space-between; padding:6px 0; border-bottom:1px solid rgba(255,255,255,0.04);">
                            <div style="display:flex; align-items:center; gap:8px;">
                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(p.name)}/22" class="player-avatar-sm" style="width:22px; height:22px;" alt="">
                                <span style="font-weight:600; color:var(--text-heading);">${esc(p.name)}</span>
                            </div>
                            <div style="display:flex; align-items:center; gap:6px;">
                                <span class="badge gray">${p.ping} мс</span>
                                <button type="button" class="secondary btn-sm" onclick="window.viewPlayerProfile('${esc(p.name)}')">ДОСЬЕ</button>
                            </div>
                        </div>
                    `,
          )
          .join("");
      }
    } catch (e) {
      if (body)
        body.innerHTML = `<div style="color:var(--text-dim);">Ошибка загрузки игроков</div>`;
    }
  }
  if (key === "admin_actions") {
    const body = document.getElementById("tile-body-admin_actions");
    try {
      const logs = await api("GET", "/api/logs/web?limit=10").catch(() => []);
      if (!body) return;
      const filtered = isModeratorRole()
        ? logs.filter(
            (l) =>
              l.actor &&
              l.actor.toLowerCase() === (state.me.username || "").toLowerCase(),
          )
        : logs;
      if (!filtered.length) {
        body.innerHTML = `<div style="text-align:center; padding:20px; color:var(--text-dim);">Действий пока не зафиксировано</div>`;
      } else {
        body.innerHTML = filtered
          .slice(0, 5)
          .map(
            (l) => `
                        <div style="padding:6px 0; border-bottom:1px solid rgba(255,255,255,0.04); font-size:12px;">
                            <div style="display:flex; justify-content:space-between; color:var(--text-dim); font-size:11px; margin-bottom:2px;">
                                <b style="color:var(--accent-light);">${esc(l.actor)}</b>
                                <span>${fmtTime(l.timestamp)}</span>
                            </div>
                            <div style="color:var(--text-secondary);">${esc(l.action)}</div>
                        </div>
                    `,
          )
          .join("");
      }
    } catch (e) {
      if (body)
        body.innerHTML = `<div style="color:var(--text-dim);">Ошибка загрузки журнала</div>`;
    }
  }
}
export function openDashboardCustomizerModal() {
  const currentTiles = getActiveDashboardTiles();
  const allTileKeys = [
    {
      key: "server_stats",
      title: "Статистика сервера (TPS, MSPT, память, онлайн)",
    },
    {
      key: "quick_actions",
      title: "Быстрые действия (Бан, Мут, Кик, Спавн)",
    },
    {
      key: "vesuvio_flags",
      title: "Последние срабатывания античита",
    },
    {
      key: "attention",
      title: "«Требует внимания» (подозрительные игроки)",
    },
    {
      key: "punishments_stats",
      title: "Статистика наказаний (сегодня / неделя)",
    },
    {
      key: "online_players",
      title: "Онлайн-игроки с быстрыми действиями",
    },
    {
      key: "admin_actions",
      title: "Последние действия администрации",
    },
  ];
  openModal(
    `
            <div class="modal-header">
                <h3>НАСТРОЙКА ПЛИТОК ДАШБОРДА</h3>
                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">${renderSvgIcon("close", "gray", 16)}</button>
            </div>
            <div class="modal-body">
                <p style="color:var(--text-muted); font-size:13px; margin-bottom:16px;">
                    Включайте или отключайте плитки, которые должны отображаться на вашем рабочем столе.
                </p>
                <div style="display:flex; flex-direction:column; gap:10px;">
                    ${allTileKeys
                      .map((t) => {
                        const checked = currentTiles.includes(t.key)
                          ? "checked"
                          : "";
                        return `
                            <label style="display:flex; align-items:center; gap:10px; cursor:pointer; padding:8px 12px; background:rgba(255,255,255,0.03); border:1px solid var(--border); border-radius:6px;">
                                <input type="checkbox" class="dash-tile-cb" value="${t.key}" ${checked} style="width:18px; height:18px; accent-color:var(--accent);">
                                <span style="font-size:13px; font-weight:600; color:var(--text-heading);">${esc(t.title)}</span>
                            </label>`;
                      })
                      .join("")}
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" id="dash-reset-default">СБРОСИТЬ ПО УМОЛЧАНИЮ</button>
                <button type="button" class="primary" id="dash-save-tiles">СОХРАНИТЬ РАСКЛАДКУ</button>
            </div>
        `,
    () => {
      document
        .getElementById("dash-save-tiles")
        ?.addEventListener("click", async () => {
          const checked = Array.from(
            document.querySelectorAll(".dash-tile-cb:checked"),
          ).map((cb) => cb.value);
          if (!checked.length) {
            await noticeDialog(
              "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
              "Выберите хотя бы одну плитку",
            );
            return;
          }
          saveDashboardTiles(checked);
          closeModal();
          renderDashboardView();
          showToast(
            "Раскладка сохранена",
            "Дашборд успешно обновлен",
            "success",
          );
        });
      document
        .getElementById("dash-reset-default")
        ?.addEventListener("click", () => {
          saveDashboardTiles(state.DEFAULT_DASHBOARD_TILES);
          closeModal();
          renderDashboardView();
          showToast("Сброшено", "Установлена стандартная раскладка", "info");
        });
    },
  );
}

// ==========================================================================
// 2. ЖУРНАЛ (ПОЛНЫЙ ЛОГ, ФИЛЬТРЫ, ДЕТАЛИ, ЭКСПОРТ)
// ==========================================================================
