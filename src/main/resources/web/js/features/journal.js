import { state } from "../state.js";
import { api, esc, fmtTime, hasPerm, openModal, renderSvgIcon } from "./ui.js";
// ==========================================================================
// 2. ЖУРНАЛ (ПОЛНЫЙ ЛОГ, ФИЛЬТРЫ, ДЕТАЛИ, ЭКСПОРТ)
// ==========================================================================

export async function renderJournalView() {
  const area = document.getElementById("content-area");
  const canViewWebLogs = hasPerm("VIEW_WEB_LOGS");
  let primaryMode = canViewWebLogs ? "web" : "server"; // 'web' or 'server'
  let webSubfilter = "ALL"; // ALL, AUTH, PUNISHMENTS, REPORTS, ROLES
  let serverSubfilter = "ALL"; // ALL, COMMANDS, GAME, WARNINGS
  let filterStaff = "";
  let filterActionType = "ALL";
  let filterDateFrom = "";
  let filterDateTo = "";
  let searchQuery = "";
  let cachedWebLogs = [];
  let cachedServerLogs = [];
  let sortKey = "time";
  let sortDir = "desc"; // 'asc' | 'desc'

  area.innerHTML = `
            <div class="view-header">
                <div class="view-title-block">
                    <h2>ЖУРНАЛ СОБЫТИЙ</h2>
                    <p>Аудит действий веб-панели и серверных событий Minecraft</p>
                </div>
            </div>

            <!-- Primary Mode Tabs (Web vs Server) -->
            <div class="segmented-nav-tabs" style="margin-bottom:18px;">
                ${
                  canViewWebLogs
                    ? `
                <button type="button" class="segmented-nav-tab ${primaryMode === "web" ? "active" : ""}" data-mode="web">
                    ${renderSvgIcon("globe", "violet", 16)}
                    <span>Веб журнал</span>
                </button>`
                    : ""
                }
                <button type="button" class="segmented-nav-tab ${primaryMode === "server" ? "active" : ""}" data-mode="server">
                    ${renderSvgIcon("server", "gray", 16)}
                    <span>Серверный журнал</span>
                </button>
            </div>

            <!-- Toolbar & Subfilters -->
            <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:14px; margin-bottom:18px; display:flex; flex-direction:column; gap:12px;">
                <div class="filter-tags" id="journal-subfilters">
                    <!-- Dynamic subfilter buttons inserted by renderSubfilters() -->
                </div>

                <!-- Audit Log Filters: Staff, Action, Date Range -->
                <div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap;">
                    <div style="flex:1; min-width:200px; position:relative;">
                        <input type="text" id="journal-search-input" placeholder="Поиск по содержанию лога..." style="padding-left:34px;">
                        <span style="position:absolute; left:12px; top:50%; transform:translateY(-50%); display:inline-flex; align-items:center;">${renderSvgIcon("search", "gray", 14)}</span>
                    </div>

                    <input type="text" id="journal-filter-staff" placeholder="Сотрудник (ник)..." style="width:160px;">

                    <select id="journal-filter-action" style="width:170px;">
                        <option value="ALL">Все типы действий</option>
                        <option value="AUTH">Авторизация / Сессии</option>
                        <option value="PUNISHMENTS">Баны / Наказания</option>
                        <option value="REPORTS">Жалобы игроков</option>
                        <option value="ROLES">Роли и персонал</option>
                        <option value="MAINTENANCE">Технические работы</option>
                    </select>

                    <div style="display:flex; align-items:center; gap:6px;">
                        <span style="font-size:12px; color:var(--text-dim);">С:</span>
                        <input type="date" id="journal-filter-from" style="width:130px; font-size:12px; padding:6px;">
                        <span style="font-size:12px; color:var(--text-dim);">По:</span>
                        <input type="date" id="journal-filter-to" style="width:130px; font-size:12px; padding:6px;">
                    </div>

                    <button type="button" class="secondary btn-sm" id="journal-reset-filters">СБРОС</button>
                </div>
            </div>

            <!-- Logs Table Container -->
            <div class="table-wrap">
                <table>
                    <thead id="journal-table-head">
                        <!-- Dynamic headers -->
                    </thead>
                    <tbody id="journal-table-body">
                        <tr><td colspan="5" style="text-align:center; color:var(--text-dim); padding:24px;">Загрузка журнала...</td></tr>
                    </tbody>
                </table>
            </div>
        `;
  const renderSubfilters = () => {
    const container = document.getElementById("journal-subfilters");
    if (!container) return;
    if (primaryMode === "web") {
      container.innerHTML = `
                    <button type="button" class="filter-tag-btn ${webSubfilter === "ALL" ? "active" : ""}" data-sub="ALL">Все веб-действия</button>
                    <button type="button" class="filter-tag-btn ${webSubfilter === "AUTH" ? "active" : ""}" data-sub="AUTH">Входы и выходы</button>
                    <button type="button" class="filter-tag-btn ${webSubfilter === "PUNISHMENTS" ? "active" : ""}" data-sub="PUNISHMENTS">Баны и наказания</button>
                    <button type="button" class="filter-tag-btn ${webSubfilter === "REPORTS" ? "active" : ""}" data-sub="REPORTS">Рассмотренные жалобы</button>
                    <button type="button" class="filter-tag-btn ${webSubfilter === "ROLES" ? "active" : ""}" data-sub="ROLES">Роли и персонал</button>
                `;
    } else {
      container.innerHTML = `
                    <button type="button" class="filter-tag-btn ${serverSubfilter === "ALL" ? "active" : ""}" data-sub="ALL">Все серверные логи</button>
                    <button type="button" class="filter-tag-btn ${serverSubfilter === "COMMANDS" ? "active" : ""}" data-sub="COMMANDS">Команды персонала</button>
                    <button type="button" class="filter-tag-btn ${serverSubfilter === "GAME" ? "active" : ""}" data-sub="GAME">Игровые события</button>
                    <button type="button" class="filter-tag-btn ${serverSubfilter === "WARNINGS" ? "active" : ""}" data-sub="WARNINGS">Ошибки и предупреждения</button>
                `;
    }
  };
  const sortIndicator = (key) => {
    if (sortKey !== key) return ' <span style="opacity:.35">↕</span>';
    return sortDir === "asc"
      ? ' <span style="color:var(--accent)">↑</span>'
      : ' <span style="color:var(--accent)">↓</span>';
  };
  const normalizeLogTime = (t) => {
    if (t == null || t === "") return 0;
    if (typeof t === "number") return t > 1e12 ? Math.floor(t / 1000) : t;
    const n = Number(t);
    if (!Number.isNaN(n) && String(t).trim() !== "")
      return n > 1e12 ? Math.floor(n / 1000) : n;
    const d = Date.parse(String(t));
    return Number.isNaN(d) ? 0 : Math.floor(d / 1000);
  };
  const applySort = (list) => {
    const dir = sortDir === "asc" ? 1 : -1;
    return [...list].sort((a, b) => {
      if (sortKey === "time") {
        return (normalizeLogTime(a.time) - normalizeLogTime(b.time)) * dir;
      }
      const va = String(a[sortKey] || "").toLowerCase();
      const vb = String(b[sortKey] || "").toLowerCase();
      if (va < vb) return -1 * dir;
      if (va > vb) return 1 * dir;
      return normalizeLogTime(b.time) - normalizeLogTime(a.time);
    });
  };
  const renderTableHead = () => {
    const thead = document.getElementById("journal-table-head");
    if (!thead) return;
    const th = (key, label, style) =>
      `<th data-sort="${key}" style="cursor:pointer;user-select:none;${style || ""}" title="Сортировать по колонке">${label}${sortIndicator(key)}</th>`;
    if (primaryMode === "web") {
      thead.innerHTML = `
                    <tr>
                        ${th("time", "ВРЕМЯ", "width:170px;")}
                        ${th("actor", "АДМИНИСТРАТОР", "width:170px;")}
                        ${th("type", "КАТЕГОРИЯ", "width:140px;")}
                        ${th("action", "ДЕЙСТВИЕ", "")}
                        <th style="width:90px; text-align:right;">ДЕТАЛИ</th>
                    </tr>
                `;
    } else {
      thead.innerHTML = `
                    <tr>
                        ${th("time", "ВРЕМЯ", "width:170px;")}
                        ${th("actor", "ИСТОЧНИК", "width:160px;")}
                        ${th("type", "ТИП", "width:130px;")}
                        ${th("action", "СООБЩЕНИЕ / КОМАНДА", "")}
                        <th style="width:90px; text-align:right;">ДЕТАЛИ</th>
                    </tr>
                `;
    }
    thead.querySelectorAll("th[data-sort]").forEach((el) => {
      el.addEventListener("click", () => {
        const key = el.getAttribute("data-sort");
        if (sortKey === key) {
          sortDir = sortDir === "asc" ? "desc" : "asc";
        } else {
          sortKey = key;
          sortDir = key === "time" ? "desc" : "asc";
        }
        renderTableHead();
        renderTableRows();
      });
    });
  };
  const sanitizeWebAction = (actionStr) => {
    if (!actionStr) return "";
    return actionStr
      .replace(/\(IP:\s*[^)]+\)/gi, "")
      .replace(
        /\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\b/g,
        "",
      )
      .replace(/\s{2,}/g, " ")
      .trim();
  };
  const categorizeWebAction = (act) => {
    const s = (act || "").toLowerCase();
    if (
      s.includes("вошёл") ||
      s.includes("вышел") ||
      s.includes("вход") ||
      s.includes("сесси") ||
      s.includes("2fa") ||
      s.includes("парол")
    ) {
      return {
        type: "АВТОРИЗАЦИЯ",
        typeClass: "blue",
      };
    }
    if (
      s.includes("бан") ||
      s.includes("разбан") ||
      s.includes("кик") ||
      s.includes("наказан")
    ) {
      return {
        type: "НАКАЗАНИЕ",
        typeClass: "red",
      };
    }
    if (s.includes("жалоб") || s.includes("репорт")) {
      return {
        type: "ЖАЛОБЫ",
        typeClass: "purple",
      };
    }
    if (
      s.includes("рол") ||
      s.includes("персонал") ||
      s.includes("сотрудник") ||
      s.includes("прав") ||
      s.includes("приглаш") ||
      s.includes("инвайт")
    ) {
      return {
        type: "ПЕРСОНАЛ",
        typeClass: "yellow",
      };
    }
    if (s.includes("тех") || s.includes("maintenance")) {
      return {
        type: "ТЕХ. РАБОТЫ",
        typeClass: "yellow",
      };
    }
    return {
      type: "ДЕЙСТВИЕ",
      typeClass: "cyan",
    };
  };
  const loadLogs = async () => {
    const body = document.getElementById("journal-table-body");
    try {
      if (primaryMode === "web") {
        let queryUrl = "/api/logs/web?limit=300";
        if (filterStaff)
          queryUrl += `&staff=${encodeURIComponent(filterStaff)}`;
        if (filterActionType && filterActionType !== "ALL")
          queryUrl += `&action=${encodeURIComponent(filterActionType)}`;
        if (filterDateFrom) {
          const fromSec = Math.floor(new Date(filterDateFrom).getTime() / 1000);
          queryUrl += `&from=${fromSec}`;
        }
        if (filterDateTo) {
          const toSec =
            Math.floor(new Date(filterDateTo).getTime() / 1000) + 86399;
          queryUrl += `&to=${toSec}`;
        }
        const rawLogs = await api("GET", queryUrl).catch(() => []);
        cachedWebLogs = rawLogs
          .filter((l) => {
            const act = (l.action || "").toLowerCase();
            if (act.includes("открыл раздел") || act.includes("открыл вкладку"))
              return false;
            return true;
          })
          .map((l) => {
            const cleanAction = sanitizeWebAction(l.action);
            const cat = categorizeWebAction(cleanAction);
            return {
              id: l.id,
              time: normalizeLogTime(l.timestamp),
              actor: l.actor || "Система",
              action: cleanAction,
              type: cat.type,
              typeClass: cat.typeClass,
              raw: l,
            };
          });
        cachedWebLogs = applySort(cachedWebLogs);
      } else {
        const [sLogs, cmdLogs] = await Promise.all([
          api("GET", "/api/logs/server?limit=200").catch(() => []),
          api("GET", "/api/staff-audit/logs?limit=100").catch(() => ({
            logs: [],
          })),
        ]);
        let list = [];
        (Array.isArray(sLogs) ? sLogs : []).forEach((s) => {
          // API отдаёт { id, actor, action, timestamp }
          const msg = String(s.message || s.action || "");
          const msgLow = msg.toLowerCase();
          let type = "ИНФО";
          let typeClass = "gray";
          const lvl = String(s.level || "").toUpperCase();
          if (
            lvl === "WARN" ||
            lvl === "WARNING" ||
            /\bwarn(ing)?\b/i.test(msg)
          ) {
            type = "WARN";
            typeClass = "yellow";
          } else if (
            lvl === "ERROR" ||
            lvl === "SEVERE" ||
            /\b(error|severe|exception)\b/i.test(msg)
          ) {
            type = "ОШИБКА";
            typeClass = "red";
          } else if (
            msgLow.includes("joined the game") ||
            msgLow.includes("left the game") ||
            msgLow.includes("joined") ||
            msgLow.includes("left the")
          ) {
            type = "СЕРВЕР";
            typeClass = "green";
          } else if (
            msg.trim().startsWith("/") ||
            msgLow.includes("issued server command")
          ) {
            type = "КОМАНДА";
            typeClass = "cyan";
          }
          list.push({
            id: "srv_" + (s.id != null ? s.id : Math.random()),
            time: normalizeLogTime(s.timestamp),
            actor: s.logger || s.actor || "Сервер",
            action: msg,
            type,
            typeClass,
            raw: s,
          });
        });
        const cmdArr = Array.isArray(cmdLogs)
          ? cmdLogs
          : (cmdLogs && cmdLogs.logs) || [];
        cmdArr.forEach((c) => {
          const cmd = c.command != null ? String(c.command) : c.action || "";
          list.push({
            id: "cmd_" + (c.id != null ? c.id : Math.random()),
            time: normalizeLogTime(c.timestamp),
            actor: c.adminUsername || c.actor || "Персонал",
            action: cmd ? (cmd.startsWith("/") ? cmd : "/" + cmd) : "",
            type: "КОМАНДА",
            typeClass: c.isSuspicious ? "yellow" : "cyan",
            raw: c,
          });
        });
        cachedServerLogs = applySort(list);
      }
      renderTableRows();
    } catch (e) {
      if (body)
        body.innerHTML = `<tr><td colspan="5" style="text-align:center; color:var(--red); padding:24px;">Ошибка загрузки журнала: ${esc(e.message)}</td></tr>`;
    }
  };
  const renderTableRows = () => {
    const body = document.getElementById("journal-table-body");
    if (!body) return;
    let source = primaryMode === "web" ? cachedWebLogs : cachedServerLogs;

    // Apply subfilters
    if (primaryMode === "web") {
      if (webSubfilter === "AUTH") {
        source = source.filter((x) => x.type === "АВТОРИЗАЦИЯ");
      } else if (webSubfilter === "PUNISHMENTS") {
        source = source.filter((x) => x.type === "НАКАЗАНИЕ");
      } else if (webSubfilter === "REPORTS") {
        source = source.filter((x) => x.type === "ЖАЛОБЫ");
      } else if (webSubfilter === "ROLES") {
        source = source.filter((x) => x.type === "ПЕРСОНАЛ");
      }
    } else {
      if (serverSubfilter === "COMMANDS") {
        source = source.filter((x) => x.type === "КОМАНДА");
      } else if (serverSubfilter === "GAME") {
        source = source.filter((x) => x.type === "СЕРВЕР" || x.type === "ИНФО");
      } else if (serverSubfilter === "WARNINGS") {
        source = source.filter((x) => x.type === "WARN" || x.type === "ОШИБКА");
      }
    }

    // Apply staff filter in server mode if specified
    if (filterStaff && primaryMode === "server") {
      const fs = filterStaff.toLowerCase();
      source = source.filter(
        (e) => e.actor && e.actor.toLowerCase().includes(fs),
      );
    }

    // Search query filter
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      source = source.filter(
        (e) =>
          (e.actor && e.actor.toLowerCase().includes(q)) ||
          (e.action && e.action.toLowerCase().includes(q)) ||
          (e.type && e.type.toLowerCase().includes(q)),
      );
    }
    source = applySort(source);
    if (!source.length) {
      body.innerHTML = `<tr><td colspan="5" style="text-align:center; color:var(--text-dim); padding:28px;">Записи журнала не найдены</td></tr>`;
      return;
    }
    body.innerHTML = source
      .slice(0, 200)
      .map(
        (row, idx) => `
                <tr>
                    <td class="font-mono" style="font-size:12px; color:var(--text-muted);">${fmtTime(row.time)}</td>
                    <td style="font-weight:700;">
                        <span style="cursor:pointer; color:var(--text-heading);" onclick="window.viewPlayerProfile('${esc(row.actor)}')">${esc(row.actor)}</span>
                    </td>
                    <td><span class="badge ${row.typeClass}">${esc(row.type)}</span></td>
                    <td style="color:var(--text-secondary); line-height:1.4; word-break:break-word;">${esc(row.action)}</td>
                    <td style="text-align:right;">
                        <button type="button" class="secondary btn-sm" data-detail-idx="${idx}">ИНФО</button>
                    </td>
                </tr>
            `,
      )
      .join("");
    body.querySelectorAll("[data-detail-idx]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const idx = parseInt(btn.dataset.detailIdx, 10);
        if (source[idx]) openLogDetailModal(source[idx]);
      });
    });
  };
  const openLogDetailModal = (item) => {
    if (!item) return;
    openModal(`
                <div class="modal-header">
                    <h3>ДЕТАЛИЗАЦИЯ ЗАПИСИ ЖУРНАЛА</h3>
                    <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon("close", "gray", 16)}</button>
                </div>
                <div class="modal-body">
                    <div style="display:grid; grid-template-columns:120px 1fr; gap:10px; font-size:13px; margin-bottom:16px;">
                        <div style="color:var(--text-dim); font-weight:700;">ВРЕМЯ:</div>
                        <div class="font-mono">${fmtTime(item.time)}</div>
                        <div style="color:var(--text-dim); font-weight:700;">АКТОР:</div>
                        <div><b>${esc(item.actor)}</b></div>
                        <div style="color:var(--text-dim); font-weight:700;">ТИП СОБЫТИЯ:</div>
                        <div><span class="badge ${item.typeClass}">${esc(item.type)}</span></div>
                        <div style="color:var(--text-dim); font-weight:700;">СОДЕРЖАНИЕ:</div>
                        <div style="color:var(--text-main);">${esc(item.action)}</div>
                    </div>

                    <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase; margin-bottom:6px;">Сырые данные:</div>
                    <pre style="background:var(--card-inner-bg); padding:12px; border-radius:6px; font-size:12px; color:var(--accent-light); border:1px solid var(--border); overflow-x:auto;">${esc(JSON.stringify(item.raw, null, 2))}</pre>
                </div>
                <div class="modal-footer">
                    <button type="button" class="primary" onclick="window.viewPlayerProfile('${esc(item.actor)}');">КАРТОЧКА ИГРОКА</button>
                    <button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button>
                </div>
            `);
  };

  // Primary Tab Switcher (Web vs Server)
  document.querySelectorAll(".segmented-nav-tab").forEach((btn) => {
    btn.addEventListener("click", () => {
      document
        .querySelectorAll(".segmented-nav-tab")
        .forEach((b) => b.classList.remove("active"));
      btn.classList.add("active");
      primaryMode = btn.dataset.mode;
      renderSubfilters();
      renderTableHead();
      loadLogs();
    });
  });

  // Subfilters Switcher
  document
    .getElementById("journal-subfilters")
    ?.addEventListener("click", (e) => {
      const btn = e.target.closest(".filter-tag-btn");
      if (!btn) return;
      document
        .querySelectorAll("#journal-subfilters .filter-tag-btn")
        .forEach((b) => b.classList.remove("active"));
      btn.classList.add("active");
      if (primaryMode === "web") {
        webSubfilter = btn.dataset.sub;
      } else {
        serverSubfilter = btn.dataset.sub;
      }
      renderTableRows();
    });

  // Filter Inputs
  document
    .getElementById("journal-filter-staff")
    ?.addEventListener("input", (e) => {
      filterStaff = e.target.value.trim();
      loadLogs();
    });
  document
    .getElementById("journal-filter-action")
    ?.addEventListener("change", (e) => {
      filterActionType = e.target.value;
      loadLogs();
    });
  document
    .getElementById("journal-filter-from")
    ?.addEventListener("change", (e) => {
      filterDateFrom = e.target.value;
      loadLogs();
    });
  document
    .getElementById("journal-filter-to")
    ?.addEventListener("change", (e) => {
      filterDateTo = e.target.value;
      loadLogs();
    });
  document
    .getElementById("journal-reset-filters")
    ?.addEventListener("click", () => {
      filterStaff = "";
      filterActionType = "ALL";
      filterDateFrom = "";
      filterDateTo = "";
      searchQuery = "";
      const isStaff = document.getElementById("journal-filter-staff");
      if (isStaff) isStaff.value = "";
      const isAct = document.getElementById("journal-filter-action");
      if (isAct) isAct.value = "ALL";
      const isFrom = document.getElementById("journal-filter-from");
      if (isFrom) isFrom.value = "";
      const isTo = document.getElementById("journal-filter-to");
      if (isTo) isTo.value = "";
      const isQ = document.getElementById("journal-search-input");
      if (isQ) isQ.value = "";
      loadLogs();
    });

  // Search input
  document
    .getElementById("journal-search-input")
    ?.addEventListener("input", (e) => {
      searchQuery = e.target.value.trim();
      renderTableRows();
    });
  renderSubfilters();
  renderTableHead();
  await loadLogs();

  // Auto-refresh without manual button
  state.pollers["journal_auto_refresh"] = setInterval(loadLogs, 10000);
}

// ==========================================================================
// 3. НАКАЗАНИЯ (ЖАЛОБЫ, БАНЫ, АПЕЛЛЯЦИИ DISCORD)
// ==========================================================================
