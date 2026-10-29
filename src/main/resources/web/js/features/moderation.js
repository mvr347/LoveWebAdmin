import { confirmCaseDecision } from "../core/decision-preview.js";
import { explanationDialog, noticeDialog } from "../core/dialogs.js";
import { state } from "../state.js";
import {
  api,
  attachPlayerAutocomplete,
  clearPollers,
  closeModal,
  confirmAction,
  esc,
  fmtTime,
  getBanReasons,
  hasPerm,
  isModeratorRole,
  openModal,
  openReportPickerModal,
  renderSvgIcon,
  showToast,
} from "./ui.js";
export async function getAppealTemplates() {
  if (state.appealTemplatesCache) return state.appealTemplatesCache;
  try {
    state.appealTemplatesCache = await api("GET", "/api/bans/appeal-templates");
  } catch (e) {
    state.appealTemplatesCache = {
      form: {
        header: "ФОРМА ПОДАЧИ АПЕЛЛЯЦИИ",
        fields: [
          "1. Ваш никнейм: {targetName}",
          "2. Кем выдано: {creatorName}",
          "3. Причина: {ruleReason}",
          "4. Ваши комментарии:",
        ],
        footer: "Срок рассмотрения: до 24 часов.",
      },
      verdicts: [
        {
          id: "v_approved_error",
          name: "Одобрено (Ошибочный бан)",
          type: "APPROVE",
          template:
            "**[ОДОБРЕНО] АПЕЛЛЯЦИЯ ОДОБРЕНА**\n\nУважаемый **{targetName}**!\nБлокировка **#{id}** была пересмотрена. В ходе повторной проверки доказательств была установлена ошибка.\nБлокировка полностью снята, аккаунт разбанен. Приносим извинения за неудобства.\n\n*Администрация сервера*",
        },
        {
          id: "v_approved_amnesty",
          name: "Одобрено (Амнистия)",
          type: "APPROVE",
          template:
            "**[ОДОБРЕНО] АПЕЛЛЯЦИЯ ОДОБРЕНА (АМНИСТИЯ)**\n\nУважаемый **{targetName}**!\nАдминистрация приняла решение удовлетворить вашу просьбу об амнистии по блокировке **#{id}** ({ruleReason}).\nБан снят. Пожалуйста, соблюдайте правила проекта во избежание повторного бессрочного бана.",
        },
        {
          id: "v_rejected_cheats",
          name: "Отклонено (Читы доказаны)",
          type: "REJECT",
          template:
            "**[ОТКЛОНЕНО] АПЕЛЛЯЦИЯ ОТКЛОНЕНА**\n\nУважаемый **{targetName}**!\nВаша апелляция по бану **#{id}** рассмотрена.\nФакт использования запрещённого ПО ({ruleReason}) подтверждён видеозаписью и телеметрией античита.\nБлокировка остаётся в силе и является **бессрочной**.",
        },
        {
          id: "v_rejected_expired",
          name: "Отклонено (Истёк срок)",
          type: "REJECT",
          template:
            "**[ОТКЛОНЕНО] АПЕЛЛЯЦИЯ ОТКЛОНЕНА (ИСТЁК СРОК)**\n\nУважаемый **{targetName}**!\nСрок подачи апелляции на блокировку от {createdAt} истёк.\nАпелляция не подлежит дальнейшему рассмотрению.",
        },
        {
          id: "v_info_request",
          name: "Запрос доп. информации",
          type: "INFO",
          template:
            "**[ТРЕБУЕТСЯ УТОЧНЕНИЕ]**\n\nУважаемый **{targetName}**!\nДля вынесения вердикта по блокировке **#{id}** предоставьте дополнительную информацию или опровержение в течение 24 часов.",
        },
      ],
    };
  }
  return state.appealTemplatesCache;
}
export async function updateReportsBadge() {
  if (!hasPerm("VIEW_REPORTS") && !hasPerm("VIEW_BANS")) return;
  try {
    const data = await api("GET", "/api/reports?limit=1");
    const pending = data?.stats?.pending || 0;
    const badge = document.getElementById("reports-pending-badge");
    if (badge) {
      if (pending > 0) {
        badge.textContent = pending > 99 ? "99+" : pending;
        badge.style.display = "inline-block";
      } else {
        badge.style.display = "none";
      }
    }
    const tabBadge = document.getElementById("punishments-pending-badge-tab");
    if (tabBadge) {
      tabBadge.textContent = pending;
    }
  } catch (e) {}
}
export async function renderPunishmentsView(
  defaultSubtab = "reports",
  initialBanId = null,
) {
  const area = document.getElementById("content-area");
  if (!area) return;
  state.currentPunishmentsSubtab = defaultSubtab || "reports";
  const isMod = isModeratorRole();
  area.innerHTML = `
            <div class="view-header">
                <div class="view-title-block">
                    <h2>УПРАВЛЕНИЕ НАКАЗАНИЯМИ</h2>
                    <p>Единый центр обработки жалоб игроков, управления банами и работы с апелляциями</p>
                </div>
                <div class="view-actions" id="punishments-top-actions">
                    <button type="button" class="primary" id="btn-ban-archive" class="secondary">Архив</button><button type="button" class="primary" id="btn-create-ban-top">+ ВЫДАТЬ БАН</button>
                </div>
            </div>

            <!-- Subtabs Navigation -->
            <div class="segmented-nav-tabs" id="punishments-subtabs-bar" style="margin-bottom:20px;">
                <button type="button" class="segmented-nav-tab ${state.currentPunishmentsSubtab === "reports" ? "active" : ""}" data-subtab="reports">
                    <span>${renderSvgIcon("fileText", "violet", 15)}</span>
                    <span>ЖАЛОБЫ И РЕПОРТЫ</span>
                    <span class="badge yellow" id="punishments-pending-badge-tab" style="margin-left:4px; font-size:10px;">0</span>
                </button>
                <button type="button" class="segmented-nav-tab ${state.currentPunishmentsSubtab === "bans" ? "active" : ""}" data-subtab="bans">
                    <span>${renderSvgIcon("shield", "red", 15)}</span>
                    <span>БАНЫ СЕРВЕРА</span>
                </button>
                <button type="button" class="segmented-nav-tab ${state.currentPunishmentsSubtab === "appeals" ? "active" : ""}" data-subtab="appeals">
                    <span>${renderSvgIcon("scales", "violet", 15)}</span>
                    <span>АПЕЛЛЯЦИИ (DISCORD)</span>
                </button>
            </div>

            <!-- Subtab Content Area -->
            <div id="punishments-subtab-container"></div>
        `;
  document
    .getElementById("btn-create-ban-top")
    ?.addEventListener("click", () => openQuickBanModal(""));
  document
    .getElementById("btn-ban-archive")
    ?.addEventListener("click", async () => {
      try {
        const data = await api("GET", "/api/bans/archive");
        const files = data.files || [];
        openModal(`<div class="modal-header"><h3>Архив банов</h3><button type="button" class="close-btn" data-modal-close="true">×</button></div>
                <div class="modal-body"><p style="font-size:12px;color:var(--text-muted);margin-bottom:12px;">Zip: plugins/LoveWebAdmin/archive/ (${data.retentionDays || 90} дн.)</p>
                ${files.length ? '<ul style="font-size:13px;">' + files.map((f) => "<li>" + esc(f) + "</li>").join("") + "</ul>" : '<div style="text-align:center;color:var(--text-dim);padding:16px;">Пусто</div>'}</div>
                <div class="modal-footer"><button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button></div>`);
      } catch (e) {
        showToast("Архив", e.message || "Ошибка", "error");
      }
    });

  // Auto-refresh pollers for punishments
  state.pollers["punishments_sync"] = setInterval(() => {
    if (state.currentPunishmentsSubtab === "reports") loadReportsSubtab();
    else if (state.currentPunishmentsSubtab === "bans") loadBansSubtab();
    else if (state.currentPunishmentsSubtab === "appeals") {
      const listWrap = document.getElementById("live-appeals-list-wrap");
      if (listWrap && typeof renderLiveAppealsList === "function")
        renderLiveAppealsList();
    }
  }, 10000);

  // Wire subtab buttons
  document
    .querySelectorAll("#punishments-subtabs-bar .segmented-nav-tab")
    .forEach((btn) => {
      btn.addEventListener("click", () => {
        document
          .querySelectorAll("#punishments-subtabs-bar .segmented-nav-tab")
          .forEach((b) => b.classList.remove("active"));
        btn.classList.add("active");
        state.currentPunishmentsSubtab = btn.dataset.subtab;
        switchSubtab(state.currentPunishmentsSubtab);
      });
    });
  const switchSubtab = (tab, param = null) => {
    state.currentPunishmentsSubtab = tab;
    clearPollers();
    if (tab === "reports") loadReportsSubtab();
    else if (tab === "bans") loadBansSubtab();
    else if (tab === "appeals") loadAppealsSubtab(param || initialBanId);
  };

  // ----------------------------------------------------------------------
  // 1. SUBTAB: REPORTS (ЖАЛОБЫ) - NO STAT CARDS, 4+ OFFENDERS PROMINENT
  // ----------------------------------------------------------------------
  async function loadReportsSubtab() {
    const container = document.getElementById("punishments-subtab-container");
    if (!container) return;
    let currentStatus = "ALL";
    let searchQuery = "";
    container.innerHTML = `
                <!-- Frequent Repeat Offenders Section (4+ reports) -->
                <div id="frequent-offenders-wrap" style="margin-bottom:18px;"></div>

                <!-- Filters & Search Toolbar -->
                <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:14px; margin-bottom:18px; display:flex; gap:12px; flex-wrap:wrap; align-items:center;">
                    <div style="display:flex; gap:6px; flex-wrap:wrap;" id="reports-status-filters">
                        <button type="button" class="filter-tag-btn active" data-status="ALL">Все</button>
                        <button type="button" class="filter-tag-btn" data-status="PENDING">Ожидают <span class="badge yellow" id="tab-rep-pending-count" style="margin-left:4px;">0</span></button>
                        <button type="button" class="filter-tag-btn" data-status="ACCEPTED">Принятые</button>
                        <button type="button" class="filter-tag-btn" data-status="REJECTED">Ложные</button>
                        <button type="button" class="filter-tag-btn" data-status="EXPIRED">Истёкшие</button>
                    </div>
                    <div style="flex:1; min-width:260px;">
                        <input type="text" id="reports-search-box" placeholder="Поиск по нарушителю, заявителю, причине или описанию...">
                    </div>
                </div>

                <!-- Reports Table -->
                <div class="table-wrap">
                    <table>
                        <thead>
                            <tr>
                                <th style="width:55px;">ID</th>
                                <th>НАРУШИТЕЛЬ</th>
                                <th>ОТПРАВИТЕЛЬ</th>
                                <th>ПРИЧИНЫ</th>
                                <th>ОПИСАНИЕ</th>
                                <th>ВРЕМЯ</th>
                                <th>ДАТА</th>
                                <th>СТАТУС</th>
                                <th style="text-align:right;">ДЕЙСТВИЯ</th>
                            </tr>
                        </thead>
                        <tbody id="reports-subtab-tbody">
                            <tr><td colspan="9" style="text-align:center; padding:32px; color:var(--text-dim);">Загрузка жалоб...</td></tr>
                        </tbody>
                    </table>
                </div>
            `;
    const fetchReports = async () => {
      const tbody = document.getElementById("reports-subtab-tbody");
      try {
        const params = new URLSearchParams();
        if (currentStatus && currentStatus !== "ALL")
          params.set("status", currentStatus);
        if (searchQuery && searchQuery.trim())
          params.set("search", searchQuery.trim());
        params.set("limit", "150");
        const data = await api("GET", `/api/reports?${params.toString()}`);
        state.lastLoadedReports = data.reports || [];
        const stats = data.stats || {};

        // Update pending count badges
        const countEl = document.getElementById("tab-rep-pending-count");
        if (countEl) countEl.textContent = stats.pending || 0;
        const topTabBadge = document.getElementById(
          "punishments-pending-badge-tab",
        );
        if (topTabBadge) topTabBadge.textContent = stats.pending || 0;

        // Render 4+ Offenders Block
        renderFrequentOffenders(state.lastLoadedReports);

        // Render table
        renderReportsTableRows(state.lastLoadedReports);
      } catch (e) {
        if (tbody)
          tbody.innerHTML = `<tr><td colspan="9" style="text-align:center; padding:24px; color:var(--red);">Ошибка загрузки: ${esc(e.message)}</td></tr>`;
      }
    };
    const renderFrequentOffenders = (reports) => {
      const wrap = document.getElementById("frequent-offenders-wrap");
      if (!wrap) return;

      // Group by targetName
      const counts = {};
      reports.forEach((r) => {
        const name = r.targetName;
        if (!name) return;
        counts[name] = (counts[name] || 0) + 1;
      });
      const offenders = Object.keys(counts)
        .filter((name) => counts[name] >= 4)
        .map((name) => ({
          name,
          count: counts[name],
        }))
        .sort((a, b) => b.count - a.count);
      if (!offenders.length) {
        wrap.style.display = "none";
        wrap.innerHTML = "";
        return;
      }
      wrap.style.display = "block";
      wrap.innerHTML = `
                    <div class="frequent-offenders-card">
                        <div class="frequent-offenders-title">
                            <span style="display:flex; align-items:center; gap:6px;">${renderSvgIcon("alert", "red", 14)} ПОВТОРНЫЕ ЖАЛОБЫ (4+)</span>
                            <span class="badge red">ТРЕБУЮТ ПРИОРИТЕТНОГО ВНИМАНИЯ</span>
                        </div>
                        <div class="frequent-offenders-grid">
                            ${offenders
                              .map(
                                (o) => `
                                <div class="frequent-offender-item">
                                    <div style="display:flex; align-items:center; gap:10px;">
                                        <img src="https://mc-heads.net/avatar/${encodeURIComponent(o.name)}/32" class="player-avatar-sm" alt="" style="width:32px; height:32px; border-radius:4px;">
                                        <div>
                                            <div style="font-weight:700; color:var(--text-main); font-size:13.5px;">${esc(o.name)}</div>
                                            <span class="badge red" style="display:inline-flex; align-items:center; gap:4px; font-size:10px; padding:2px 6px;">${renderSvgIcon("alert", "red", 11)} ${o.count} жалоб</span>
                                        </div>
                                    </div>
                                    <div style="display:flex; gap:6px;">
                                        <button type="button" class="danger btn-sm" data-offender-ban="${esc(o.name)}">БАН</button>
                                        <button type="button" class="secondary btn-sm" data-offender-profile="${esc(o.name)}">ДОСЬЕ</button>
                                        <button type="button" class="secondary btn-sm" data-offender-filter="${esc(o.name)}" title="Показать только жалобы на этого игрока">ФИЛЬТР</button>
                                    </div>
                                </div>
                            `,
                              )
                              .join("")}
                        </div>
                    </div>
                `;
      wrap.querySelectorAll("[data-offender-ban]").forEach((b) => {
        b.addEventListener("click", () => {
          const name = b.dataset.offenderBan;
          openQuickBanModal(
            name,
            "Рецидив / Многократные нарушения правил",
            `Игрок имеет 4+ активных жалоб в системе.`,
          );
        });
      });
      wrap.querySelectorAll("[data-offender-profile]").forEach((b) => {
        b.addEventListener("click", () => {
          window.viewPlayerProfile(b.dataset.offenderProfile);
        });
      });
      wrap.querySelectorAll("[data-offender-filter]").forEach((b) => {
        b.addEventListener("click", () => {
          const searchBox = document.getElementById("reports-search-box");
          if (searchBox) {
            searchBox.value = b.dataset.offenderFilter;
            searchQuery = b.dataset.offenderFilter;
            fetchReports();
          }
        });
      });
    };
    const renderReportsTableRows = (list) => {
      const tbody = document.getElementById("reports-subtab-tbody");
      if (!tbody) return;
      if (!list.length) {
        tbody.innerHTML = `<tr><td colspan="9" style="text-align:center; padding:32px; color:var(--text-dim);">Жалобы не найдены</td></tr>`;
        return;
      }
      tbody.innerHTML = list
        .map((r) => {
          let statusBadge;
          switch (r.status) {
            case "ACCEPTED":
              statusBadge = '<span class="badge green">ПРИНЯТ</span>';
              break;
            case "REJECTED":
              statusBadge = '<span class="badge gray">ЛОЖНЫЙ</span>';
              break;
            case "MERGED":
              statusBadge = '<span class="badge gray">ОБЪЕДИНЕНА</span>';
              break;
            case "EXPIRED":
              statusBadge = '<span class="badge gray">ИСТЁК</span>';
              break;
            default:
              statusBadge = '<span class="badge yellow">ОЖИДАЕТ</span>';
              break;
          }
          const reasonsHtml =
            r.reasons && r.reasons.length
              ? r.reasons
                  .map(
                    (res) =>
                      `<span class="badge purple" style="margin:2px 2px 2px 0; font-size:10.5px;">${esc(res)}</span>`,
                  )
                  .join("")
              : '<span style="color:var(--text-dim); font-size:12px;">Не указано</span>';
          const descHtml =
            r.description &&
            r.description.trim() &&
            r.description !== "не указано"
              ? `<span style="color:var(--text-main);" title="${esc(r.description)}">${esc(r.description.length > 35 ? r.description.substring(0, 35) + "..." : r.description)}</span>`
              : '<i style="color:var(--text-dim); font-size:12px;">не указано</i>';
          const recentBadge = r.isRecent
            ? `<span class="badge green" style="font-size:10.5px; display:inline-flex; align-items:center; gap:4px;" title="Произошло менее 5 минут назад">${renderSvgIcon("clock", "green", 11)} &lt; 5 мин</span>`
            : `<span class="badge gray" style="font-size:10.5px; display:inline-flex; align-items:center; gap:4px;" title="Произошло более 5 минут назад">${renderSvgIcon("clock", "gray", 11)} &gt; 5 мин</span>`;
          const suspBadge = r.targetSuspicious
            ? `<span class="badge danger" style="margin-left:4px; font-size:10px; font-weight:800; display:inline-flex; align-items:center; gap:3px;" title="Более 4-х активных жалоб!">${renderSvgIcon("alert", "red", 10)} 4+</span>`
            : "";
          const isPending = r.status === "PENDING";
          return `
                        <tr>
                            <td class="font-mono" style="color:var(--text-dim);">#${r.id}</td>
                            <td>
                                <div style="display:flex; align-items:center; gap:6px;">
                                    <img src="https://mc-heads.net/avatar/${encodeURIComponent(r.targetName)}/24" style="width:24px; height:24px; border-radius:4px;" alt="">
                                    <a href="javascript:void(0)" onclick="window.viewPlayerProfile('${esc(r.targetName)}')" style="color:var(--text-main); font-weight:700; text-decoration:none;">${esc(r.targetName)}</a>
                                    ${suspBadge}
                                </div>
                            </td>
                            <td>
                                <div style="display:flex; align-items:center; gap:6px;">
                                    <img src="https://mc-heads.net/avatar/${encodeURIComponent(r.reporterName)}/20" style="width:20px; height:20px; border-radius:3px;" alt="">
                                    <span style="color:var(--text-secondary); font-size:12.5px;">${esc(r.reporterName)}</span>
                                </div>
                            </td>
                            <td><div style="max-width:200px; display:flex; flex-wrap:wrap;">${reasonsHtml}</div></td>
                            <td><div style="max-width:200px; line-height:1.3;">${descHtml}</div></td>
                            <td>${recentBadge}</td>
                            <td style="font-size:11.5px; color:var(--text-dim); white-space:nowrap;">${fmtTime(r.createdAt)}</td>
                            <td>
                                <div style="display:flex; align-items:center; gap:4px; flex-wrap:wrap;">
                                    ${statusBadge}
                                    ${r.linkedBanId && r.linkedBanId > 0 ? `<button type="button" class="btn-tag-link" style="color:#f87171; border-color:rgba(239, 68, 68, 0.4); display:inline-flex; align-items:center; gap:4px;" onclick="window.viewBanDetails(${r.linkedBanId})" title="Открыть примененный бан #${r.linkedBanId}">${renderSvgIcon("shield", "red", 12)} БАН #${r.linkedBanId}</button>` : ""}
                                </div>
                            </td>
                            <td style="text-align:right; white-space:nowrap;">
                                <div style="display:inline-flex; gap:5px;">
                                    <a class="secondary btn-sm" href="/case.html?kind=report&id=${r.id}">РАБОТА С ДЕЛОМ</a><button type="button" class="secondary btn-sm" onclick="window.openReportDetailModal(${r.id})" title="Просмотреть детали и контекст чата">ИНФО</button>
                                    ${
                                      isPending
                                        ? `
                                        <button type="button" class="primary btn-sm" onclick="window.handleAcceptReport(${r.id}, '${esc(r.targetName)}')" title="Принять и наказать (начислить репутацию заявителю)">${renderSvgIcon("check", "white", 12)}</button>
                                        <button type="button" class="secondary btn-sm" onclick="window.handleRejectReport(${r.id}, '${esc(r.targetName)}')" title="Пометить как ложную (вернуть репутацию цели)">ЛОЖЬ</button>
                                        <button type="button" class="danger btn-sm" onclick="window.handleBanFromReportRow(${r.id})" title="Выдать бан по жалобе #${r.id}">БАН</button>
                                    `
                                        : ""
                                    }
                                    <button type="button" class="danger btn-sm" onclick="window.handleDeleteReport(${r.id})" title="Удалить запись жалобы">${renderSvgIcon("close", "white", 12)}</button>
                                </div>
                            </td>
                        </tr>
                    `;
        })
        .join("");
    };

    // Status filter buttons
    document
      .getElementById("reports-status-filters")
      ?.addEventListener("click", (e) => {
        const btn = e.target.closest(".filter-tag-btn");
        if (!btn) return;
        document
          .querySelectorAll("#reports-status-filters .filter-tag-btn")
          .forEach((b) => b.classList.remove("active"));
        btn.classList.add("active");
        currentStatus = btn.dataset.status;
        fetchReports();
      });

    // Search input
    let sTimer = null;
    document
      .getElementById("reports-search-box")
      ?.addEventListener("input", (e) => {
        searchQuery = e.target.value;
        clearTimeout(sTimer);
        sTimer = setTimeout(fetchReports, 300);
      });
    await fetchReports();
    state.pollers["punishments_reports"] = setInterval(fetchReports, 10000);
  }

  // ----------------------------------------------------------------------
  // 2. SUBTAB: BANS (БАНЫ СЕРВЕРА)
  // ----------------------------------------------------------------------
  async function loadBansSubtab() {
    const container = document.getElementById("punishments-subtab-container");
    if (!container) return;
    container.innerHTML = `
                <!-- Search and Reason Filters -->
                <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:14px; margin-bottom:18px; display:flex; gap:12px; flex-wrap:wrap;">
                    <div style="flex:1; min-width:240px;">
                        <input type="text" id="bans-search-input" placeholder="Поиск по нику игрока или администратору...">
                    </div>
                    <div style="width:240px;">
                        <select id="bans-filter-reason">
                            <option value="">Все причины</option>
                        </select>
                    </div>
                    <div style="width:160px;">
                        <select id="bans-filter-status">
                            <option value="">Все статусы</option>
                            <option value="ACTIVE" selected>Активные</option>
                            <option value="UNBANNED">Разбаненные</option>
                        </select>
                    </div>
                </div>

                <!-- Bans Table -->
                <div class="table-wrap">
                    <table>
                        <thead>
                            <tr>
                                <th>ИГРОК</th>
                                <th>ПРИЧИНА</th>
                                <th>АДМИНИСТРАТОР</th>
                                <th>ДАТА ВЫДАЧИ</th>
                                <th>СРОК</th>
                                <th>СТАТУС</th>
                                <th style="text-align:right;">ДЕЙСТВИЯ</th>
                            </tr>
                        </thead>
                        <tbody id="bans-subtab-tbody">
                            <tr><td colspan="7" style="text-align:center; padding:24px; color:var(--text-dim);">Загрузка списка банов...</td></tr>
                        </tbody>
                    </table>
                </div>
            `;

    // Populate reasons
    const reasons = await getBanReasons();
    const reasonSelect = document.getElementById("bans-filter-reason");
    if (reasonSelect) {
      reasons.forEach((r) => {
        const opt = document.createElement("option");
        opt.value = r.name;
        opt.textContent = r.name;
        reasonSelect.appendChild(opt);
      });
    }
    const fetchBans = async () => {
      const body = document.getElementById("bans-subtab-tbody");
      try {
        state.lastLoadedBans = await api("GET", "/api/bans/all");
        renderBansTableRows();
      } catch (e) {
        if (body)
          body.innerHTML = `<tr><td colspan="7" style="text-align:center; color:var(--red); padding:24px;">Ошибка загрузки банов: ${esc(e.message)}</td></tr>`;
      }
    };
    const renderBansTableRows = () => {
      const body = document.getElementById("bans-subtab-tbody");
      if (!body) return;
      const q = (document.getElementById("bans-search-input")?.value || "")
        .trim()
        .toLowerCase();
      const reasonFilter =
        document.getElementById("bans-filter-reason")?.value || "";
      const statusFilter =
        document.getElementById("bans-filter-status")?.value || "";
      let list = state.lastLoadedBans;
      if (q) {
        list = list.filter(
          (b) =>
            (b.targetName && b.targetName.toLowerCase().includes(q)) ||
            (b.creatorName && b.creatorName.toLowerCase().includes(q)),
        );
      }
      if (reasonFilter) {
        list = list.filter((b) => b.ruleReason === reasonFilter);
      }
      if (statusFilter) {
        list = list.filter((b) => b.status === statusFilter);
      }
      if (!list.length) {
        body.innerHTML = `<tr><td colspan="7" style="text-align:center; color:var(--text-dim); padding:24px;">Баны не найдены</td></tr>`;
        return;
      }
      body.innerHTML = list
        .map((b) => {
          const isActive = b.status === "ACTIVE";
          const canUnban = !isMod || state.me.isOwner;
          const proofUrl =
            b.screenshotUrl ||
            (b.proofUrls && b.proofUrls.length ? b.proofUrls[0] : null);
          return `
                        <tr>
                            <td>
                                <div style="display:flex; align-items:center; gap:10px; cursor:pointer;" onclick="window.viewPlayerProfile('${esc(b.targetName)}')">
                                    <img src="https://mc-heads.net/avatar/${encodeURIComponent(b.targetName)}/24" class="player-avatar-sm" alt="">
                                    <div>
                                        <div style="font-weight:700; color:var(--text-heading);">${esc(b.targetName)}</div>
                                        ${b.isIpBan ? '<span class="badge yellow" style="font-size:9px; padding:1px 4px;">IP-БАН</span>' : ""}
                                    </div>
                                </div>
                            </td>
                            <td>
                                <div style="display:flex; align-items:center; gap:6px; flex-wrap:wrap;">
                                    <span class="badge red">${esc(b.ruleReason)}</span>
                                    ${b.linkedReportId ? `<button type="button" class="btn-tag-link" style="color:#c084fc; border-color:rgba(192, 132, 252, 0.4); display:inline-flex; align-items:center; gap:4px;" onclick="window.openReportDetailModal(${b.linkedReportId})" title="Открыть прикрепленную жалобу #${b.linkedReportId}">${renderSvgIcon("fileText", "violet", 12)} ЖАЛОБА #${b.linkedReportId}</button>` : ""}
                                    ${proofUrl ? `<button type="button" class="screenshot-thumb-btn" onclick="window.openScreenshotLightbox('${esc(proofUrl)}')">${renderSvgIcon("fileText", "gray", 12)} СКРИНШОТ</button>` : ""}
                                </div>
                                ${b.description ? `<div style="font-size:11.5px; color:var(--text-muted); margin-top:4px;">${esc(b.description)}</div>` : ""}
                            </td>
                            <td style="font-weight:600; color:var(--accent-light);">${esc(b.sourceDisplay || b.creatorName)}</td>
                            <td class="font-mono" style="font-size:12px; color:var(--text-muted);">${fmtTime(b.createdAt)}</td>
                            <td><span class="badge purple">БЕССРОЧНО</span></td>
                            <td>
                                <span class="badge ${isActive ? "red" : "green"}">${isActive ? "АКТИВЕН" : "СНЯТ"}</span>
                            </td>
                            <td style="text-align:right;">
                                <div style="display:flex; justify-content:flex-end; gap:6px;">
                                    <button type="button" class="secondary btn-sm" onclick="window.viewBanDetails(${b.id})" title="Детали блокировки">ИНФО</button>
                                    <button type="button" class="secondary btn-sm" onclick="window.renderPunishmentsView('appeals', ${b.id})" title="Перейти к апелляции и вынесению вердикта">АПЕЛЛЯЦИЯ</button>
                                    <button type="button" class="secondary btn-sm" onclick="window.openPlayerAltsModal('${esc(b.targetName)}')">АЛЬТЫ</button>
                                    ${
                                      isActive && canUnban
                                        ? `
                                        <button type="button" class="danger btn-sm" onclick="window.confirmUnban(${b.id}, '${esc(b.targetName)}')">РАЗБАНИТЬ</button>
                                    `
                                        : ""
                                    }
                                </div>
                            </td>
                        </tr>`;
        })
        .join("");
    };
    document
      .getElementById("bans-search-input")
      ?.addEventListener("input", renderBansTableRows);
    document
      .getElementById("bans-filter-reason")
      ?.addEventListener("change", renderBansTableRows);
    document
      .getElementById("bans-filter-status")
      ?.addEventListener("change", renderBansTableRows);
    await fetchBans();
  }

  // ----------------------------------------------------------------------
  // 3. SUBTAB: APPEALS (АПЕЛЛЯЦИИ DISCORD HUB & ВЕРДИКТЫ)
  // ----------------------------------------------------------------------
  async function loadAppealsSubtab(targetBanId = null) {
    const container = document.getElementById("punishments-subtab-container");
    if (!container) return;
    container.innerHTML = `
                <!-- Discord Appeals Banner & Workflow Info -->
                <div class="appeals-hub-banner" style="margin-bottom:20px;">
                    <div style="display:flex; justify-content:space-between; align-items:flex-start; gap:16px; flex-wrap:wrap;">
                        <div>
                            <div style="display:flex; align-items:center; gap:8px;">
                                <span>${renderSvgIcon("message", "violet", 20)}</span>
                                <h3 style="margin:0; font-size:16px; color:var(--text-main);">ИНТЕГРАЦИЯ С DISCORD: ТИКЕТЫ АПЕЛЛЯЦИЙ</h3>
                                <span class="badge green" style="font-size:10.5px; display:inline-flex; align-items:center; gap:4px;">${renderSvgIcon("check", "green", 11)} РАБОЧИЙ ШЛЮЗ</span>
                            </div>
                            <p style="font-size:13px; color:var(--text-secondary); margin:6px 0 0 0; line-height:1.5;">
                                На сервере приём апелляций осуществляется через Discord-бота в канале <b>#тикеты-апелляций</b>.
                                Ниже представлена очередь апелляций и тикетов с поддержкой двусторонней переписки и принятия решений.
                            </p>
                        </div>
                        <div>
                            <button type="button" class="secondary btn-sm" id="btn-toggle-discord-docs" style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon("fileText", "gray", 12)} СПРАВКА И МЕТОДЫ БОТА</button>
                        </div>
                    </div>
                </div>

                <!-- Collapsible Discord Bot API & Methods Doc -->
                <div id="discord-bot-docs-box" style="display:none; background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:16px; margin-bottom:20px;">
                    <h4 style="color:var(--accent-light); margin:0 0 10px 0; font-size:14px; display:flex; align-items:center; gap:6px;">${renderSvgIcon("server", "violet", 14)} МЕТОДЫ И ИНСТРУКЦИИ ДЛЯ DISCORD-БОТА</h4>
                    <div style="font-size:12.5px; color:var(--text-muted); line-height:1.6; margin-bottom:12px;">
                        Для автоматизации тикетов Discord-бот может обращаться к REST API веб-панели через авторизационный токен с правами <code>MANAGE_BANS</code>:
                    </div>
                    <pre class="discord-code-box"><code>// 1. Проверка активного бана по нику или ID:
GET /api/bans/{id}
Headers: { "Authorization": "Bearer &lt;TOKEN&gt;" }

// 2. Снятие бана (разбан игрока через бота):
POST /api/bans/{id}/unban
Body: { "reason": "Апелляция одобрена в Discord тикете #123" }

// 3. Формат готового Discord Embed сообщения с вердиктом:
{
  "embeds": [{
    "title": "Вердикт по апелляции на блокировку",
    "color": 3066993, // Зеленый при разбане, 15158332 при отказе
    "fields": [
      { "name": "Игрок", "value": "{targetName}", "inline": true },
      { "name": "Решение", "value": "Блокировка снята", "inline": true }
    ]
  }]
}</code></pre>
                </div>

                <!-- Live Ban Appeals & Discord Tickets Thread Section -->
                <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:18px; margin-bottom:20px;">
                    <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:12px; margin-bottom:14px;">
                        <div>
                            <h3 style="margin:0; font-size:15px; color:var(--text-main); display:flex; align-items:center; gap:8px;">
                                <span>${renderSvgIcon("scales", "violet", 15)}</span>
                                <span>ОЧЕРЕДЬ АПЕЛЛЯЦИЙ И ТИКЕТОВ DISCORD</span>
                                <span class="badge yellow" id="live-appeals-count-badge" style="font-size:11px;">0</span>
                            </h3>
                            <div style="font-size:12px; color:var(--text-muted); margin-top:2px;">
                                Все апелляции автоматически создают приватные тикеты Discord через системный мост и поддерживают двусторонний чат
                            </div>
                        </div>
                        <div style="display:flex; gap:8px;">
                            <select id="appeals-status-filter" style="font-size:12px; padding:5px 10px;">
                                <option value="ALL">Все статусы</option>
                                <option value="PENDING" selected>Ожидают решения (PENDING)</option>
                                <option value="APPROVED">Одобренные</option>
                                <option value="REJECTED">Отклонённые</option><option value="REDUCED">Сокращён срок</option>
                            </select>
                        </div>
                    </div>

                    <div id="live-appeals-list-wrap">
                        <div style="text-align:center; padding:20px; color:var(--text-muted);">Загрузка апелляций...</div>
                    </div>
                </div>
            `;

    // Toggle docs box
    document
      .getElementById("btn-toggle-discord-docs")
      ?.addEventListener("click", () => {
        const box = document.getElementById("discord-bot-docs-box");
        if (box)
          box.style.display = box.style.display === "none" ? "block" : "none";
      });
    async function renderLiveAppealsList() {
      const listWrap = document.getElementById("live-appeals-list-wrap");
      const badge = document.getElementById("live-appeals-count-badge");
      const filterEl = document.getElementById("appeals-status-filter");
      if (!listWrap) return;
      const statusFilter = filterEl ? filterEl.value : "PENDING";
      try {
        const res = await api("GET", `/api/appeals?status=${statusFilter}`);
        const appeals = res.appeals || [];
        if (badge) badge.textContent = appeals.length;
        if (!appeals.length) {
          listWrap.innerHTML = `
                            <div style="text-align:center; padding:24px; color:var(--text-muted); font-size:13px;">
                                В выбранном статусе (${esc(statusFilter)}) нет апелляций
                            </div>
                        `;
          return;
        }
        listWrap.innerHTML = `
                        <div style="display:flex; flex-direction:column; gap:10px;">
                            ${appeals
                              .map((a) => {
                                const stBadge =
                                  a.status === "APPROVED"
                                    ? '<span class="badge green">ОДОБРЕНО</span>'
                                    : a.status === "REJECTED"
                                      ? '<span class="badge red">ОТКЛОНЕНО</span>'
                                      : a.status === "REDUCED"
                                        ? '<span class="badge green">СРОК СОКРАЩЁН</span>'
                                        : a.status === "DECIDING"
                                          ? '<span class="badge yellow">ТРЕБУЕТ ПРОВЕРКИ</span>'
                                          : '<span class="badge yellow">НА РАССМОТРЕНИИ</span>';
                                return `
                                    <div style="background:rgba(255, 255, 255, 0.02); border:1px solid var(--border); border-radius:var(--radius-sm); padding:12px 14px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:12px;">
                                        <div style="display:flex; align-items:center; gap:12px;">
                                            <img src="https://mc-heads.net/avatar/${esc(a.playerName)}/36" style="width:36px; height:36px; border-radius:4px;" alt="">
                                            <div>
                                                <div style="display:flex; align-items:center; gap:8px;">
                                                    <span style="font-size:14px; font-weight:800; color:var(--text-main);">${esc(a.playerName)}</span>
                                                    ${stBadge}
                                                    ${a.discordChannelId ? `<span class="badge purple" style="font-size:10px; display:inline-flex; align-items:center; gap:4px;">${renderSvgIcon("message", "violet", 11)} Discord: ticket-бан-${esc(a.playerName)}</span>` : ""}
                                                </div>
                                                <div style="font-size:12px; color:var(--text-muted); margin-top:2px;">
                                                    Бан #${a.banId}: <b>${esc(a.banReason || "—")}</b> • Подана: ${fmtTime(a.createdAt)}
                                                </div>
                                                <div style="font-size:12.5px; color:var(--text-dim); margin-top:4px; max-width:600px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">
                                                    «${esc(a.reason)}»
                                                </div>
                                            </div>
                                        </div>
                                        <div style="display:flex; gap:8px;">
                                            <button type="button" class="primary btn-sm" data-open-appeal-thread="${a.id}" style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon("message", "white", 12)} ПЕРЕПИСКА</button>
                                        </div>
                                    </div>
                                `;
                              })
                              .join("")}
                        </div>
                    `;
        listWrap
          .querySelectorAll("[data-open-appeal-thread]")
          .forEach((btn) => {
            btn.addEventListener("click", () =>
              openAppealThreadModal(parseInt(btn.dataset.openAppealThread, 10)),
            );
          });
      } catch (e) {
        listWrap.innerHTML = `<div style="text-align:center; padding:16px; color:var(--red);">Ошибка загрузки: ${esc(e.message)}</div>`;
      }
    }
    async function openAppealThreadModal(appealId) {
      try {
        const appeal = await api("GET", `/api/appeals/${appealId}`);
        const statusBadge =
          appeal.status === "APPROVED"
            ? '<span class="badge green">ОДОБРЕНО</span>'
            : appeal.status === "REJECTED"
              ? '<span class="badge red">ОТКЛОНЕНО</span>'
              : appeal.status === "REDUCED"
                ? '<span class="badge green">СРОК СОКРАЩЁН</span>'
                : appeal.status === "DECIDING"
                  ? '<span class="badge yellow">ТРЕБУЕТ ПРОВЕРКИ</span>'
                  : '<span class="badge yellow">НА РАССМОТРЕНИИ</span>';
        openModal(
          `
                        <div class="modal-header">
                            <div style="display:flex; align-items:center; gap:10px;">
                                <img src="https://mc-heads.net/avatar/${esc(appeal.playerName)}/32" style="width:32px; height:32px; border-radius:4px;" alt="">
                                <div>
                                    <h3 style="margin:0; font-size:16px;">АПЕЛЛЯЦИЯ #${appeal.id}: ${esc(appeal.playerName)}</h3><a href="/case.html?kind=appeal&id=${appeal.id}">Ответственный, заметки и решение</a>
                                    <div style="font-size:11.5px; color:var(--text-muted);">Бан #${appeal.banId} • Причина: ${esc(appeal.banReason || "—")} • ${statusBadge}</div>
                                </div>
                            </div>
                            <button type="button" class="close-btn" onclick="window.closeCurrentModal()">${renderSvgIcon("close", "gray", 16)}</button>
                        </div>
                        <div class="modal-body" style="max-height:65vh; overflow-y:auto;">
                            <div style="background:var(--card-inner-bg); border:1px solid var(--border); border-radius:var(--radius-sm); padding:12px; margin-bottom:14px; font-size:12.5px;">
                                <div style="color:var(--text-dim); font-size:11px; font-weight:700; text-transform:uppercase;">Причина / аргумент апелляции:</div>
                                <div style="color:var(--text-main); margin-top:4px; line-height:1.5;">${esc(appeal.reason)}</div>
                                ${appeal.discordChannelId ? `<div style="margin-top:8px; color:var(--accent-light); font-size:11.5px; display:flex; align-items:center; gap:4px;">${renderSvgIcon("message", "violet", 12)} Discord Ticket ID: <code>${esc(appeal.discordChannelId)}</code></div>` : ""}
                            </div>

                            <div style="font-size:12px; font-weight:700; color:var(--text-dim); text-transform:uppercase; margin-bottom:8px;">
                                История сообщений тикета (Синхронизация с Discord):
                            </div>
                            <div id="appeal-messages-thread" style="display:flex; flex-direction:column; gap:10px; margin-bottom:16px;">
                                ${(appeal.messages || [])
                                  .map(
                                    (m) => `
                                    <div style="background:${m.isStaff ? "rgba(124, 58, 237, 0.12)" : "var(--card-inner-bg)"}; border:1px solid ${m.isStaff ? "var(--accent)" : "var(--border)"}; border-radius:6px; padding:10px 12px;">
                                        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:4px; font-size:11.5px;">
                                            <div style="display:flex; align-items:center; gap:6px;">
                                                <b>${esc(m.authorName)}</b>
                                                ${m.isStaff ? '<span class="badge purple" style="font-size:9.5px;">ПЕРСОНАЛ</span>' : '<span class="badge blue" style="font-size:9.5px;">ИГРОК</span>'}
                                            </div>
                                            <span style="color:var(--text-dim); font-size:10.5px;">${fmtTime(m.createdAt)}</span>
                                        </div>
                                        <div style="color:var(--text-main); font-size:13px; line-height:1.5; white-space:pre-wrap;">${esc(m.message)}</div>
                                    </div>
                                `,
                                  )
                                  .join("")}
                            </div>

                            ${
                              appeal.status === "PENDING"
                                ? `
                                <div style="border-top:1px solid var(--border); padding-top:12px;">
                                    <div class="form-group" style="margin-bottom:8px;">
                                        <label style="font-size:11.5px;">Ответить в тред и Discord канал тикета:</label>
                                        <textarea id="appeal-reply-text" rows="3" placeholder="Введите сообщение игроку..." style="width:100%;"></textarea>
                                    </div>
                                    <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px;">
                                        <button type="button" class="primary btn-sm" id="btn-send-appeal-reply" style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon("message", "white", 12)} Отправить ответ в Discord</button>
                                        <div style="display:flex; gap:8px;">
                                            <button type="button" class="danger btn-sm" id="btn-reject-appeal-modal" style="display:inline-flex; align-items:center; gap:4px;">${renderSvgIcon("ban", "white", 12)} Отклонить</button>
                                            <button type="button" class="success btn-sm" id="btn-approve-appeal-modal" style="display:inline-flex; align-items:center; gap:4px;">${renderSvgIcon("check", "white", 12)} Одобрить и разбанить</button>
                                        </div>
                                    </div>
                                </div>
                            `
                                : ""
                            }
                        </div>
                        <div class="modal-footer">
                            <button type="button" class="secondary" onclick="window.closeCurrentModal()">ЗАКРЫТЬ</button>
                        </div>
                    `,
          () => {
            document
              .getElementById("btn-send-appeal-reply")
              ?.addEventListener("click", async () => {
                const replyMsg = document
                  .getElementById("appeal-reply-text")
                  ?.value.trim();
                if (!replyMsg) return;
                try {
                  await api("POST", `/api/appeals/${appealId}/reply`, {
                    message: replyMsg,
                  });
                  showToast(
                    "Отправлено",
                    "Ответ доставлен в тикет Discord",
                    "success",
                  );
                  openAppealThreadModal(appealId);
                } catch (e) {
                  showToast("Ошибка", e.message, "error");
                }
              });
            for (const [action, label] of [
              ["approve", "Одобрить апелляцию"],
              ["reject", "Отклонить апелляцию"],
            ]) {
              const button = document.getElementById(
                `btn-${action}-appeal-modal`,
              );
              button?.addEventListener("click", async () => {
                button.disabled = true;
                try {
                  const reason = await explanationDialog(
                    label,
                    "Объяснение будет доступно игроку. Перед применением проверьте последствия решения.",
                  );
                  if (
                    !reason ||
                    !(await confirmCaseDecision(
                      api,
                      "appeal",
                      appealId,
                      action,
                      reason,
                    ))
                  )
                    return;
                  showToast(
                    "Решение сохранено",
                    "Обращение закрыто, доставка уведомления поставлена в очередь",
                    "success",
                  );
                  closeModal();
                  renderLiveAppealsList();
                } catch (e) {
                  showToast("Ошибка", e.message, "error");
                } finally {
                  button.disabled = false;
                }
              });
            }
          },
        );
      } catch (e) {
        showToast("Ошибка загрузки апелляции", e.message, "error");
      }
    }
    document
      .getElementById("btn-refresh-appeals-list")
      ?.addEventListener("click", renderLiveAppealsList);
    document
      .getElementById("appeals-status-filter")
      ?.addEventListener("change", renderLiveAppealsList);
    renderLiveAppealsList();
    updateVerdictView();
  }

  // Initialize default subtab
  switchSubtab(state.currentPunishmentsSubtab, initialBanId);
  updateReportsBadge();
}
export function exposeRenderPunishmentsView() {
  window.renderPunishmentsView = renderPunishmentsView;
}
export function exposeRenderBansView() {
  window.renderBansView = () => renderPunishmentsView("bans");
}
export function exposeRenderReportsView() {
  window.renderReportsView = () => renderPunishmentsView("reports");
}
export function exposeRefreshPunishmentsView() {
  window.refreshPunishmentsView = () =>
    renderPunishmentsView(state.currentPunishmentsSubtab);

  // --------------------------------------------------------------------------
  // Quick Permanent Ban Modal with Glowing Toggle & Visual Report Picker
  // --------------------------------------------------------------------------
}
export function exposeOpenQuickBanModal() {
  window.openQuickBanModal = async function (
    defaultPlayer = "",
    defaultReason = "",
    defaultComment = "",
    defaultReportId = null,
  ) {
    const reasons = await getBanReasons();
    const isManagement =
      state.me &&
      (state.me.isOwner ||
        ["администратор", "управляющий", "руководитель", "куратор"].includes(
          (state.me.role || "").toLowerCase(),
        ));
    let attachedScreenshot = "";
    let attachedReportId = defaultReportId
      ? parseInt(defaultReportId, 10)
      : null;
    let searchTimer = null;
    openModal(
      `
            <div class="modal-header">
                <h3>ВЫДАТЬ БАН</h3>
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon("close", "gray", 16)}</button>
            </div>
            <div class="modal-body">
                <div style="font-size:12px; color:var(--text-muted); margin-bottom:16px;">
                    Проверьте контекст игрока, выберите срок и причину из правил сервера.
                </div>

                <!-- Attached Report Box -->
                <div id="ban-player-context" role="status" class="message">Выберите игрока: перед блокировкой будет показан его контекст.</div><div id="ban-attached-report-box" style="display:none; margin-bottom:14px;"></div>

                <!-- Suggested Reports Hint Box -->
                <div id="ban-suggested-reports-box" style="display:none; margin-bottom:14px;"></div>

                <div class="form-group">
                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:4px;">
                        <label style="margin-bottom:0;">Никнейм игрока-нарушителя</label>
                        <button type="button" class="btn-tag-link" id="btn-browse-reports-picker" style="font-size:11px; padding:2px 8px; display:inline-flex; align-items:center; gap:5px;">${renderSvgIcon("fileText", "violet", 12)} Выбрать жалобу</button>
                    </div>
                    <input type="text" id="ban-target-name" placeholder="Введите ник игрока..." value="${esc(defaultPlayer)}" required autofocus autocomplete="off">
                </div>

                <div class="form-group">
                    <label>Причина бана (пункт правил)</label>
                    <select id="ban-reason-select" required>
                        <option value="">-- Выберите причину из списка --</option>
                        ${(() => {
                          const grouped = {};
                          reasons.forEach((r) => {
                            const cat = r.category || "Прочее";
                            if (!grouped[cat]) grouped[cat] = [];
                            grouped[cat].push(r);
                          });
                          return Object.keys(grouped)
                            .map(
                              (cat) => `
                                <optgroup label="${esc(cat)}">
                                    ${grouped[cat]
                                      .map((r) => {
                                        const isSel =
                                          defaultReason &&
                                          (r.name.toLowerCase() ===
                                            defaultReason.toLowerCase() ||
                                            r.name
                                              .toLowerCase()
                                              .startsWith(
                                                defaultReason.toLowerCase(),
                                              ) ||
                                            defaultReason
                                              .toLowerCase()
                                              .startsWith(
                                                r.name.toLowerCase(),
                                              ));
                                        return `
                                            <option value="${esc(r.name)}" data-comment="${r.require_comment ? "true" : "false"}" ${isSel ? "selected" : ""}>
                                                ${esc(r.name)}
                                            </option>
                                        `;
                                      })
                                      .join("")}
                                </optgroup>
                            `,
                            )
                            .join("");
                        })()}
                    </select>
                </div>

                <div class="form-group">
                    <label style="display:flex; justify-content:space-between; align-items:center;">
                        <span>Описание нарушения / Доказательства</span>
                        ${!isManagement ? '<span style="color:var(--yellow); font-size:11px; font-weight:700;">* ОБЯЗАТЕЛЬНО ДЛЯ МОДЕРАТОРОВ</span>' : '<span style="color:var(--text-dim); font-size:11px;">(Опционально)</span>'}
                    </label>
                    <textarea id="ban-comment-text" rows="3" placeholder="${!isManagement ? "Обязательно укажите подробности нарушения, таймкоды или контекст ситуации..." : "Укажите комментарий к блокировке (опционально)..."}">${esc(defaultComment || "")}</textarea>
                </div>

                <div class="form-group">
                    <label>Скриншот нарушения / Доказательство</label>
                    <input type="text" id="ban-screenshot-url" placeholder="Вставьте URL скриншота (Imgur, Yapx, Discord...) или загрузите файл ниже">

                    <div class="screenshot-dropzone" id="ban-screenshot-dropzone">
                        <span style="color:var(--accent-light); font-weight:600; display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon("download", "violet", 14)} Загрузить файл скриншота</span>
                        <span style="font-size:11.5px; color:var(--text-muted); display:block; margin-top:3px;">Кликните для выбора, перетащите изображение или нажмите Ctrl+V</span>
                        <input type="file" id="ban-screenshot-file" accept="image/png,image/jpeg,image/webp" style="display:none;">
                    </div>
                    <div id="ban-screenshot-preview-wrap" style="display:none;">
                        <div class="screenshot-preview-box">
                            <img id="ban-screenshot-img" class="screenshot-preview-img" alt="Скриншот">
                            <button type="button" class="btn-remove-screenshot" id="btn-clear-screenshot" title="Удалить скриншот">${renderSvgIcon("close", "gray", 14)}</button>
                        </div>
                    </div>
                </div>

                <div class="form-group"><label>Срок блокировки<select id="ban-duration"><option value="-1">Бессрочно</option><option value="3600">1 час</option><option value="86400">1 день</option><option value="604800">7 дней</option><option value="2592000">30 дней</option></select></label></div>
                <!-- Modern Glowing Toggle Switch for IP-Ban (Requirement 5) -->
                <div class="form-group">
                    <label class="toggle-switch-wrap">
                        <input type="checkbox" id="ban-ip-toggle" class="toggle-switch-input">
                        <span class="toggle-switch"></span>
                        <span class="toggle-label">
                            <span class="toggle-title">Заблокировать также IP-адрес игрока (IP-Ban)</span>
                            <span class="toggle-desc">Блокирует текущий сетевой адрес для предотвращения входа с твинков</span>
                        </span>
                    </label>
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                <button type="button" class="danger" id="ban-submit-btn">ЗАБЛОКИРОВАТЬ НАВСЕГДА</button>
            </div>
        `,
      () => {
        const select = document.getElementById("ban-reason-select");
        const dropzone = document.getElementById("ban-screenshot-dropzone");
        const fileInput = document.getElementById("ban-screenshot-file");
        const urlInput = document.getElementById("ban-screenshot-url");
        const previewWrap = document.getElementById(
          "ban-screenshot-preview-wrap",
        );
        const previewImg = document.getElementById("ban-screenshot-img");
        const clearBtn = document.getElementById("btn-clear-screenshot");
        const targetInput = document.getElementById("ban-target-name");
        const commentInput = document.getElementById("ban-comment-text");
        let contextGeneration = 0;
        async function loadBanContext() {
          const generation = ++contextGeneration,
            name = targetInput.value.trim(),
            panel = document.getElementById("ban-player-context");
          if (!name) {
            panel.textContent = "Выберите игрока для просмотра контекста";
            return;
          }
          panel.textContent = "Загрузка контекста…";
          try {
            const [bans, profile, reports] = await Promise.all([
              api("GET", "/api/bans/all"),
              hasPerm("VIEW_PLAYERS")
                ? api("GET", "/api/players/" + encodeURIComponent(name))
                : Promise.resolve(null),
              hasPerm("VIEW_REPORTS")
                ? api(
                    "GET",
                    "/api/reports?status=PENDING&target=" +
                      encodeURIComponent(name),
                  )
                : Promise.resolve(null),
            ]);
            if (
              generation !== contextGeneration ||
              targetInput.value.trim() !== name ||
              !panel.isConnected
            )
              return;
            const relevant = (bans || []).filter(
              (b) => b.targetName?.toLowerCase() === name.toLowerCase(),
            );
            panel.textContent =
              name +
              " · " +
              (profile
                ? profile.isOnline || profile.online
                  ? "В сети"
                  : "Не в сети"
                : "Профиль недоступен по правам") +
              " · Активных блокировок: " +
              relevant.filter((b) => b.status === "ACTIVE").length +
              " · Всего: " +
              relevant.length +
              (reports
                ? " · Открытых жалоб: " + (reports.reports?.length || 0)
                : "");
          } catch (e) {
            if (generation === contextGeneration && panel.isConnected)
              panel.textContent = "Контекст не загружен: " + e.message;
          }
        }
        targetInput.addEventListener("change", loadBanContext);
        targetInput.addEventListener("blur", loadBanContext);
        if (targetInput.value) loadBanContext();

        // Autocomplete for player names
        if (targetInput) {
          attachPlayerAutocomplete(targetInput, (chosenName) => {
            checkPendingReports();
          });
        }
        const setScreenshot = (src) => {
          attachedScreenshot = src;
          if (src) {
            previewImg.src = src;
            previewWrap.style.display = "block";
            dropzone.style.display = "none";
          } else {
            previewImg.src = "";
            previewWrap.style.display = "none";
            dropzone.style.display = "block";
            if (urlInput) urlInput.value = "";
            if (fileInput) fileInput.value = "";
          }
        };
        dropzone?.addEventListener("click", () => fileInput?.click());
        fileInput?.addEventListener("change", (e) => {
          const file = e.target.files?.[0];
          if (!file) return;
          if (
            file.size > 700000 ||
            !["image/png", "image/jpeg", "image/webp"].includes(file.type)
          ) {
            noticeDialog("Скриншот", "Выберите PNG, JPEG или WebP до 700 КБ");
            return;
          }
          const reader = new FileReader();
          reader.onload = (ev) => setScreenshot(ev.target.result);
          reader.readAsDataURL(file);
        });
        urlInput?.addEventListener("input", (e) => {
          const val = e.target.value.trim();
          if (val.startsWith("http://") || val.startsWith("https://")) {
            setScreenshot(val);
          }
        });
        clearBtn?.addEventListener("click", () => setScreenshot(""));

        // Paste image support
        const handlePaste = (e) => {
          const items = (e.clipboardData || e.originalEvent?.clipboardData)
            ?.items;
          if (!items) return;
          for (const item of items) {
            if (item.type.indexOf("image") !== -1) {
              const blob = item.getAsFile();
              if (!blob || blob.size > 700000) {
                noticeDialog(
                  "Скриншот",
                  "Изображение должно быть не больше 700 КБ",
                );
                return;
              }
              const reader = new FileReader();
              reader.onload = (ev) => setScreenshot(ev.target.result);
              reader.readAsDataURL(blob);
              break;
            }
          }
        };
        window.addEventListener("paste", handlePaste);
        const cleanup = () => {
          window.removeEventListener("paste", handlePaste);
          if (searchTimer) clearTimeout(searchTimer);
        };
        const renderAttachedReport = async (repId) => {
          attachedReportId = repId ? parseInt(repId, 10) : null;
          const box = document.getElementById("ban-attached-report-box");
          const suggBox = document.getElementById("ban-suggested-reports-box");
          if (!box) return;
          if (!attachedReportId) {
            box.style.display = "none";
            box.innerHTML = "";
            return;
          }
          try {
            const data = await api("GET", `/api/reports/${attachedReportId}`);
            const r = data.report;
            if (suggBox) suggBox.style.display = "none";
            box.style.display = "block";
            box.innerHTML = `
                        <div class="attached-report-card">
                            <div style="display:flex; justify-content:space-between; align-items:flex-start; gap:12px;">
                                <div>
                                    <div style="display:flex; align-items:center; gap:8px;">
                                        <span class="badge purple">ПРИКРЕПЛЕНА ЖАЛОБА #${r.id}</span>
                                        <span style="font-weight:700; color:var(--text-heading);">от ${esc(r.reporterName)}</span>
                                        ${r.isRecent ? '<span class="badge green" style="font-size:10px;">⏱ &lt; 5 мин</span>' : ""}
                                    </div>
                                    <div style="font-size:12px; color:var(--text-secondary); margin-top:4px;">
                                        Причины: <b>${(r.reasons || []).join(", ") || "не указаны"}</b>
                                    </div>
                                    ${
                                      r.description &&
                                      r.description !== "не указано"
                                        ? `
                                        <div style="font-size:12px; color:#e2e8f0; margin-top:4px;">"${esc(r.description)}"</div>
                                    `
                                        : ""
                                    }
                                </div>
                                <button type="button" class="btn-remove-screenshot" id="btn-detach-report" title="Открепить жалобу">${renderSvgIcon("close", "gray", 14)}</button>
                            </div>
                        </div>
                    `;
            document
              .getElementById("btn-detach-report")
              ?.addEventListener("click", () => {
                renderAttachedReport(null);
                checkPendingReports();
              });
            if (targetInput && !targetInput.value.trim()) {
              targetInput.value = r.targetName;
            }
            if (commentInput && !commentInput.value.trim()) {
              const desc =
                r.description &&
                r.description !== "не указано" &&
                r.description.trim()
                  ? r.description.trim()
                  : "";
              commentInput.value = desc
                ? `[Жалоба #${r.id}]: ${desc}`
                : `[Жалоба #${r.id}]`;
            }
            if (select && (!select.value || select.value === "")) {
              const reasonsText = (r.reasons || []).join(" ").toLowerCase();
              for (let i = 0; i < select.options.length; i++) {
                const optVal = select.options[i].value.toLowerCase();
                if (
                  optVal &&
                  (reasonsText.includes(optVal) ||
                    (optVal.includes("чит") && reasonsText.includes("чит")))
                ) {
                  select.selectedIndex = i;
                  break;
                }
              }
            }
          } catch (err) {
            console.warn("Не удалось загрузить данные жалобы #" + repId, err);
          }
        };
        const checkPendingReports = async () => {
          if (attachedReportId) return;
          const suggBox = document.getElementById("ban-suggested-reports-box");
          if (!suggBox || !targetInput) return;
          const name = targetInput.value.trim();
          if (!name || name.length < 2) {
            suggBox.style.display = "none";
            suggBox.innerHTML = "";
            return;
          }
          try {
            const res = await api(
              "GET",
              `/api/reports?status=PENDING&target=${encodeURIComponent(name)}`,
            );
            const list = res.reports || [];
            if (list.length > 0 && !attachedReportId) {
              suggBox.style.display = "block";
              suggBox.innerHTML = `
                            <div class="report-picker-hint">
                                <div>
                                    <span style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon("alert", "yellow", 13)} Найдено активных жалоб на игрока: <b>${list.length}</b></span>
                                </div>
                                <div style="display:flex; gap:6px; flex-wrap:wrap; align-items:center;">
                                    ${list
                                      .slice(0, 3)
                                      .map(
                                        (pr) => `
                                        <button type="button" class="btn-tag-link" style="color:#fbbf24; border-color:rgba(245,158,11,0.5);" data-attach-id="${pr.id}">
                                            Прикрепить #${pr.id} (${(pr.reasons || []).slice(0, 2).join(", ") || "жалоба"})
                                        </button>
                                    `,
                                      )
                                      .join("")}
                                </div>
                            </div>
                        `;
              suggBox.querySelectorAll("[data-attach-id]").forEach((btn) => {
                btn.addEventListener("click", () => {
                  const id = parseInt(btn.dataset.attachId, 10);
                  if (id) renderAttachedReport(id);
                });
              });
            } else {
              suggBox.style.display = "none";
              suggBox.innerHTML = "";
            }
          } catch (e) {
            suggBox.style.display = "none";
          }
        };
        targetInput?.addEventListener("input", () => {
          clearTimeout(searchTimer);
          searchTimer = setTimeout(checkPendingReports, 400);
        });

        // Open visual Report Picker Modal instead of prompt by ID!
        document
          .getElementById("btn-browse-reports-picker")
          ?.addEventListener("click", () => {
            openReportPickerModal(targetInput?.value || "", (rep) => {
              if (rep && rep.id) renderAttachedReport(rep.id);
            });
          });
        if (attachedReportId) {
          renderAttachedReport(attachedReportId);
        } else if (defaultPlayer && defaultPlayer.trim()) {
          checkPendingReports();
        }
        document
          .getElementById("ban-submit-btn")
          ?.addEventListener("click", async () => {
            const targetName = targetInput.value.trim();
            const reason = select.value;
            const comment = commentInput.value.trim();
            const isIpBan =
              document.getElementById("ban-ip-toggle")?.checked || false;
            const durationSeconds = Number(
              document.getElementById("ban-duration").value,
            );
            const durationLabel =
              document.getElementById("ban-duration").selectedOptions[0]
                .textContent;
            if (!targetName) {
              await noticeDialog(
                "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
                "Укажите никнейм игрока",
              );
              return;
            }
            if (!reason) {
              await noticeDialog(
                "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
                "Выберите причину бана",
              );
              return;
            }
            if (!isManagement && !comment) {
              await noticeDialog(
                "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
                "Для вашей роли описание доказательств бана является обязательным!",
              );
              commentInput?.focus();
              return;
            }
            const opt = select.options[select.selectedIndex];
            if (
              opt &&
              (opt.dataset.comment === "true" ||
                opt.value.toLowerCase().includes("другое")) &&
              !comment
            ) {
              await noticeDialog(
                "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
                "При выборе данной причины обязателен поясняющий комментарий!",
              );
              commentInput?.focus();
              return;
            }
            const confirmMsg = attachedReportId
              ? `Выдать бан (${durationLabel}) игроку ${targetName} по причине «${reason}»?\nПрикрепленная жалоба #${attachedReportId} будет закрыта со статусом «Наказание принято».`
              : `Выдать бан (${durationLabel}) игроку ${targetName} по причине: «${reason}»?`;
            confirmAction(
              "ПОДТВЕРЖДЕНИЕ БАНА",
              confirmMsg,
              async () => {
                try {
                  cleanup();
                  await api("POST", "/api/bans", {
                    targetName,
                    durationSeconds,
                    ruleReason: reason,
                    description: comment,
                    isIpBan,
                    proofUrls: attachedScreenshot ? [attachedScreenshot] : [],
                    screenshot: attachedScreenshot,
                    linkedReportId: attachedReportId,
                  });
                  const actionLog =
                    `Забанил ${targetName} (${reason})` +
                    (attachedReportId
                      ? ` [по жалобе #${attachedReportId}]`
                      : "");
                  showToast(
                    "Игрок забанен",
                    `Бан выдан игроку ${targetName}: ${durationLabel}`,
                    "danger",
                  );
                  closeModal();
                  if (window.refreshPunishmentsView)
                    window.refreshPunishmentsView();
                } catch (e) {
                  await noticeDialog(
                    "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
                    "Ошибка выдачи бана: " + e.message,
                  );
                }
              },
              durationSeconds < 0 ? "ЗАБЛОКИРОВАТЬ НАВСЕГДА" : "ЗАБЛОКИРОВАТЬ",
              true,
            );
          });
        return cleanup;
      },
    );
  };

  // Modal: Ban Details
}
export function exposeViewBanDetails() {
  window.viewBanDetails = async function (banId) {
    openModal(
      `
            <div class="modal-header">
                <h3>ДЕТАЛИ БЛОКИРОВКИ #${banId}</h3>
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon("close", "gray", 16)}</button>
            </div>
            <div class="modal-body" id="ban-details-body">
                <div style="text-align:center; padding:24px; color:var(--text-dim);">Загрузка деталей...</div>
            </div>
            <div class="modal-footer" id="ban-details-footer">
                <button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button>
            </div>
        `,
      async () => {
        const body = document.getElementById("ban-details-body");
        const footer = document.getElementById("ban-details-footer");
        if (!body) return;
        try {
          const b = await api("GET", `/api/bans/${banId}`);
          const isActive = b.status === "ACTIVE";
          const isMod = isModeratorRole();
          const canUnban = !isMod || state.me.isOwner;
          const proofUrl =
            b.screenshotUrl ||
            (b.proofUrls && b.proofUrls.length ? b.proofUrls[0] : null);
          body.innerHTML = `
                    <div style="display:flex; align-items:center; gap:16px; margin-bottom:20px; padding-bottom:16px; border-bottom:1px solid var(--border);">
                        <img src="https://mc-heads.net/avatar/${encodeURIComponent(b.targetName)}/56" style="width:56px; height:56px; border-radius:8px;" alt="">
                        <div>
                            <div style="display:flex; align-items:center; gap:8px;">
                                <h3 style="margin:0; font-size:20px; color:var(--text-heading);">${esc(b.targetName)}</h3>
                                <span class="badge ${isActive ? "red" : "green"}">${isActive ? "АКТИВЕН" : "СНЯТ"}</span>
                                ${b.isIpBan ? '<span class="badge yellow">IP-БАН</span>' : ""}
                            </div>
                            <div style="font-size:12.5px; color:var(--text-muted); margin-top:4px;">
                                ID блокировки: <b>#${b.id}</b> • Создан: <b>${fmtTime(b.createdAt)}</b>
                            </div>
                        </div>
                    </div>

                    <div style="display:grid; grid-template-columns:140px 1fr; gap:10px; font-size:13px; margin-bottom:16px;">
                        <div style="color:var(--text-dim); font-weight:700;">ПРИЧИНА (ПУНКТ):</div>
                        <div><span class="badge red">${esc(b.ruleReason)}</span></div>

                        <div style="color:var(--text-dim); font-weight:700;">АДМИНИСТРАТОР:</div>
                        <div style="color:var(--accent-light); font-weight:600;">${esc(b.sourceDisplay || b.creatorName)}</div>

                        <div style="color:var(--text-dim); font-weight:700;">СРОК ДЕЙСТВИЯ:</div>
                        <div><span class="badge purple">БЕССРОЧНО (PERMANENT)</span></div>

                        ${
                          b.linkedReportId
                            ? `
                            <div style="color:var(--text-dim); font-weight:700;">СВЯЗАННАЯ ЖАЛОБА:</div>
                            <div>
                                <button type="button" class="btn-tag-link" onclick="window.closeCurrentModal(); window.openReportDetailModal(${b.linkedReportId})" style="display:inline-flex; align-items:center; gap:5px;">
                                    ${renderSvgIcon("fileText", "violet", 12)} ЖАЛОБА #${b.linkedReportId}
                                </button>
                            </div>
                        `
                            : `
                            <div style="color:var(--text-dim); font-weight:700;">СВЯЗАННАЯ ЖАЛОБА:</div>
                            <div>
                                <button type="button" class="secondary btn-sm" id="btn-attach-report-to-ban" style="font-size:11px; padding:2px 8px;">
                                    + ПРИКРЕПИТЬ ЖАЛОБУ
                                </button>
                            </div>
                        `
                        }

                        <div style="color:var(--text-dim); font-weight:700;">ПОЯСНЕНИЕ:</div>
                        <div style="color:var(--text-main); line-height:1.5;">${esc(b.description || "Комментарий отсутствует")}</div>
                    </div>

                    ${
                      proofUrl
                        ? `
                        <div style="margin-top:16px;">
                            <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase; margin-bottom:6px;">Скриншот-доказательство:</div>
                            <img src="${esc(proofUrl)}" class="screenshot-preview-img" style="max-height:240px; cursor:pointer;" onclick="window.openScreenshotLightbox('${esc(proofUrl)}')" alt="Доказательство">
                        </div>
                    `
                        : ""
                    }
                `;
          document
            .getElementById("btn-attach-report-to-ban")
            ?.addEventListener("click", async () => {
              const input = prompt(
                "Введите номер жалобы (ID) для прикрепления к бану #" +
                  banId +
                  ":",
              );
              if (!input || !input.trim()) return;
              const reportId = parseInt(input.trim());
              if (isNaN(reportId) || reportId <= 0) {
                showToast(
                  "Ошибка",
                  "Укажите корректный числовой ID жалобы",
                  "warning",
                );
                return;
              }
              try {
                await api("POST", `/api/bans/${banId}/attach-report`, {
                  reportId,
                });
                showToast(
                  "Жалоба привязана",
                  `Жалоба #${reportId} успешно привязана к бану #${banId}`,
                  "success",
                );
                closeModal();
                window.viewBanDetails(banId);
              } catch (err) {
                showToast(
                  "Ошибка",
                  err.message || "Не удалось привязать жалобу",
                  "danger",
                );
              }
            });
          if (footer) {
            footer.innerHTML = `
                        <button type="button" class="secondary" onclick="window.closeCurrentModal(); window.renderPunishmentsView('appeals', ${b.id});" style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon("scales", "violet", 13)} АПЕЛЛЯЦИЯ (ТИКЕТЫ)</button>
                        <button type="button" class="secondary" onclick="window.openPlayerAltsModal('${esc(b.targetName)}')">ПРОВЕРИТЬ АЛЬТЫ</button>
                        ${
                          isActive && canUnban
                            ? `
                            <a class="secondary" href="/ban.html?id=${currentBan.id}">Исправить причину или срок · История</a><button type="button" class="danger" id="btn-modal-unban">РАЗБАНИТЬ ИГРОКА</button>
                        `
                            : ""
                        }
                        <button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button>
                    `;
            document
              .getElementById("btn-modal-unban")
              ?.addEventListener("click", () => {
                window.confirmUnban(b.id, b.targetName);
              });
          }
        } catch (e) {
          body.innerHTML = `<div style="color:var(--red); padding:24px; text-align:center;">Ошибка загрузки деталей бана: ${esc(e.message)}</div>`;
        }
      },
    );
  };

  // Modal: Screenshot Lightbox
}
export function exposeOpenScreenshotLightbox() {
  window.openScreenshotLightbox = function (imgSrc) {
    if (!imgSrc) return;
    const overlay = document.createElement("div");
    overlay.className = "lightbox-overlay";
    overlay.innerHTML = `
            <div style="position:relative; max-width:92vw; max-height:92vh; display:flex; flex-direction:column; align-items:center;">
                <button type="button" class="btn-remove-screenshot" style="top:-14px; right:-14px; width:32px; height:32px;" onclick="this.closest('.lightbox-overlay').remove()">${renderSvgIcon("close", "white", 16)}</button>
                <img src="${esc(imgSrc)}" class="lightbox-img" alt="Доказательство бана">
                <div style="margin-top:10px; display:flex; gap:10px;">
                    <a href="${esc(imgSrc)}" target="_blank" class="secondary btn-sm" style="text-decoration:none; padding:6px 14px; font-size:12px; border-radius:4px; border:1px solid var(--border); background:rgba(0,0,0,0.7); color:#fff;">ОТКРЫТЬ В НОВОЙ ВКЛАДКЕ ↗</a>
                </div>
            </div>
        `;
    overlay.addEventListener("click", (e) => {
      if (e.target === overlay) overlay.remove();
    });
    document.body.appendChild(overlay);
  };

  // Confirm Unban
}
export function exposeConfirmUnban() {
  window.confirmUnban = function (banId, playerName) {
    if (isModeratorRole() && !state.me.isOwner) {
      showToast(
        "Недостаточно прав",
        "Разбан игроков разрешён только Администраторам сервера!",
        "warning",
      );
      return;
    }
    confirmAction(
      "СНЯТИЕ БЛОКИРОВКИ",
      `Вы действительно хотите разбанить игрока ${playerName}? Блокировка #${banId} будет снята, игрок сможет зайти на сервер.`,
      async () => {
        try {
          await api("POST", `/api/bans/${banId}/unban`);
          showToast(
            "Разбан",
            `Игрок ${playerName} успешно разбанен`,
            "success",
          );
          closeModal();
          if (window.refreshPunishmentsView) window.refreshPunishmentsView();
          if (
            state.activeNavSection === "punishments" &&
            window.renderPunishmentsView
          )
            window.renderPunishmentsView("bans");
        } catch (e) {
          showToast("Ошибка разбана", e.message, "error");
        }
      },
      "РАЗБАНИТЬ",
      false,
    );
  };

  // Modal: Player Alts
}

const pendingReportDecisions = new Set();
async function decideReport(id, targetName, action) {
  if (pendingReportDecisions.has(id)) return;
  pendingReportDecisions.add(id);
  try {
    const reason = await explanationDialog(
      action === "approve" ? "Подтвердить жалобу" : "Отклонить жалобу",
      `Игрок: ${targetName}. Объясните вывод по представленным доказательствам.`,
    );
    if (
      !reason ||
      !(await confirmCaseDecision(api, "report", id, action, reason))
    )
      return;
    showToast(
      "Решение сохранено",
      "Обращение закрыто, доставка уведомления поставлена в очередь",
      "success",
    );
    if (window.refreshPunishmentsView) window.refreshPunishmentsView();
    updateReportsBadge();
  } catch (e) {
    showToast("Ошибка", e.message, "error");
  } finally {
    pendingReportDecisions.delete(id);
  }
}
export function exposeHandleAcceptReport() {
  window.handleAcceptReport = (id, targetName) =>
    decideReport(id, targetName, "approve");
}
export function exposeHandleRejectReport() {
  window.handleRejectReport = (id, targetName) =>
    decideReport(id, targetName, "reject");
}
export function exposeHandleDeleteReport() {
  window.handleDeleteReport = (id) => {
    confirmAction(
      "УДАЛЕНИЕ ЖАЛОБЫ #" + id,
      "Вы действительно хотите удалить запись этой жалобы из базы данных?",
      async () => {
        try {
          await api("DELETE", `/api/reports/${id}`);
          showToast("Удалено", `Жалоба #${id} успешно удалена`, "info");
          if (window.refreshPunishmentsView) window.refreshPunishmentsView();
          updateReportsBadge();
        } catch (e) {
          showToast("Ошибка", e.message, "error");
        }
      },
      "УДАЛИТЬ",
    );
  };
}
export function exposeHandleBanFromReportRow() {
  window.handleBanFromReportRow = (id) => {
    const r = state.lastLoadedReports.find((x) => x.id === id);
    if (!r) return;
    const mainReason =
      r.reasons && r.reasons.length ? r.reasons[0] : "Нарушение правил";
    const comment =
      r.description && r.description !== "не указано" ? r.description : "";
    openQuickBanModal(r.targetName, mainReason, comment, r.id);
  };

  // Modal: Report Detail & Chat Logs
}
export function exposeOpenReportDetailModal() {
  window.openReportDetailModal = async function (reportId) {
    openModal(
      `
            <div class="modal-header">
                <h3>ДЕТАЛИЗАЦИЯ ЖАЛОБЫ #${reportId}</h3>
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon("close", "gray", 16)}</button>
            </div>
            <div class="modal-body" id="report-modal-body">
                <div style="text-align:center; padding:32px; color:var(--text-dim);">Загрузка данных жалобы...</div>
            </div>
            <div class="modal-footer" id="report-modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button>
            </div>
        `,
      async () => {
        const body = document.getElementById("report-modal-body");
        const footer = document.getElementById("report-modal-footer");
        if (!body) return;
        try {
          const data = await api("GET", `/api/reports/${reportId}`);
          const r = data.report;
          const logs = data.chatLogs || [];
          let statusBadge;
          switch (r.status) {
            case "ACCEPTED":
              statusBadge = '<span class="badge green">ПРИНЯТ (НАКАЗАН)</span>';
              break;
            case "REJECTED":
              statusBadge = '<span class="badge gray">ЛОЖНЫЙ (ОТКЛОНЕН)</span>';
              break;
            case "MERGED":
              statusBadge = '<span class="badge gray">ОБЪЕДИНЕНА</span>';
              break;
            case "EXPIRED":
              statusBadge = '<span class="badge gray">ИСТЁК</span>';
              break;
            default:
              statusBadge =
                '<span class="badge yellow">ОЖИДАЕТ РАССМОТРЕНИЯ</span>';
              break;
          }
          body.innerHTML = `
                    <div style="display:grid; grid-template-columns:1fr 1fr; gap:14px; margin-bottom:18px;">
                        <div style="background:rgba(255,255,255,0.02); border:1px solid var(--border); border-radius:6px; padding:12px;">
                            <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase;">Нарушитель (Обвиняемый):</div>
                            <div style="display:flex; align-items:center; gap:10px; margin-top:8px;">
                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(r.targetName)}/40" style="width:40px; height:40px; border-radius:4px;" alt="">
                                <div>
                                    <div style="font-size:15px; font-weight:700; color:var(--text-main);">${esc(r.targetName)}</div>
                                    <div style="font-size:11.5px; color:var(--text-muted);">${r.targetReputation != null ? "Репутация: " + r.targetReputation : ""}</div>
                                </div>
                            </div>
                        </div>

                        <div style="background:rgba(255,255,255,0.02); border:1px solid var(--border); border-radius:6px; padding:12px;">
                            <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase;">Заявитель (Отправитель):</div>
                            <div style="display:flex; align-items:center; gap:10px; margin-top:8px;">
                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(r.reporterName)}/40" style="width:40px; height:40px; border-radius:4px;" alt="">
                                <div>
                                    <div style="font-size:15px; font-weight:700; color:var(--text-main);">${esc(r.reporterName)}</div>
                                    <div style="font-size:11.5px; color:var(--text-muted);">${r.reporterReputation != null ? "Репутация: " + r.reporterReputation : ""}</div>
                                </div>
                            </div>
                        </div>
                    </div>

                    <div style="display:grid; grid-template-columns:140px 1fr; gap:8px; font-size:13px; margin-bottom:16px;">
                        <div style="color:var(--text-dim); font-weight:700;">СТАТУС:</div>
                        <div>${statusBadge}</div>

                        <div style="color:var(--text-dim); font-weight:700;">ПУНКТЫ ПРАВИЛ:</div>
                        <div>${(r.reasons || []).map((re) => `<span class="badge purple" style="margin-right:4px;">${esc(re)}</span>`).join("")}</div>

                        <div style="color:var(--text-dim); font-weight:700;">ОПИСАНИЕ:</div>
                        <div style="color:var(--text-main);">${esc(r.description || "Не указано")}</div>

                        <div style="color:var(--text-dim); font-weight:700;">ВРЕМЯ ПОДАЧИ:</div>
                        <div>${fmtTime(r.createdAt)} (${r.isRecent ? "менее 5 минут назад" : "более 5 минут назад"})</div>
                    </div>

                    <div style="margin-top:16px;">
                        <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase; margin-bottom:6px;">Снимок чата на момент жалобы:</div>
                        <div style="background:var(--card-inner-bg); border:1px solid var(--border); border-radius:6px; padding:10px; max-height:160px; overflow-y:auto; font-family:monospace; font-size:12px;">
                            ${
                              logs.length
                                ? logs
                                    .map(
                                      (l) => `
                                <div style="padding:2px 0; color:${l.isTarget ? "var(--yellow)" : l.isReporter ? "var(--cyan)" : "var(--text-muted)"};">
                                    [${fmtTime(l.timestamp)}] <b>&lt;${esc(l.sender)}&gt;</b> ${esc(l.message)}
                                </div>
                            `,
                                    )
                                    .join("")
                                : '<div style="color:var(--text-dim);">Сохранённых сообщений чата нет</div>'
                            }
                        </div>
                    </div>
                `;
          if (footer) {
            if (r.status === "PENDING") {
              footer.innerHTML = `
                            <button type="button" class="secondary" id="rep-modal-alts-btn">ПРОВЕРИТЬ АЛЬТЫ</button>
                            <button type="button" class="danger" id="rep-modal-ban-btn">ВЫДАТЬ БАН</button>
                            <button type="button" class="secondary" id="rep-modal-reject-btn">ЛОЖНАЯ</button>
                            <button type="button" class="primary" id="rep-modal-accept-btn">ПРИНЯТЬ ЖАЛОБУ</button>
                        `;
              document
                .getElementById("rep-modal-alts-btn")
                ?.addEventListener("click", () => {
                  window.openPlayerAltsModal(r.targetName);
                });
              document
                .getElementById("rep-modal-ban-btn")
                ?.addEventListener("click", () => {
                  closeModal();
                  window.handleBanFromReportRow(r.id);
                });
              document
                .getElementById("rep-modal-reject-btn")
                ?.addEventListener("click", () => {
                  closeModal();
                  window.handleRejectReport(r.id, r.targetName);
                });
              document
                .getElementById("rep-modal-accept-btn")
                ?.addEventListener("click", () => {
                  closeModal();
                  window.handleAcceptReport(r.id, r.targetName);
                });
            } else if (r.linkedBanId && r.linkedBanId > 0) {
              footer.innerHTML = `
                            <button type="button" class="primary" onclick="window.closeCurrentModal(); window.viewBanDetails(${r.linkedBanId});" style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon("ban", "white", 13)} ПРОСМОТРЕТЬ БАН #${r.linkedBanId}</button>
                            <button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button>
                        `;
            } else {
              footer.innerHTML = `
                            <button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button>
                        `;
            }
          }
        } catch (e) {
          body.innerHTML = `<div style="color:var(--red); padding:24px; text-align:center;">Ошибка загрузки данных: ${esc(e.message)}</div>`;
        }
      },
      "modal-lg",
    );
  };

  // Modal: Appeal Templates & Verdicts Generator
}
export function exposeOpenAppealTemplateModal() {
  window.openAppealTemplateModal = async function (targetBanId = null) {
    openModal(
      `
            <div class="modal-header">
                <div style="display:flex; align-items:center; gap:10px;">
                    <span style="display:inline-flex; align-items:center;">${renderSvgIcon("fileText", "violet", 20)}</span>
                    <div>
                        <h3 style="margin:0;">ШАБЛОНЫ АПЕЛЛЯЦИЙ И ВЕРДИКТОВ</h3>
                        <div style="font-size:12px; color:var(--text-dim);" id="appeal-modal-subtitle">
                            Генератор формы для игрока и заготовки официальных решений администрации
                        </div>
                    </div>
                </div>
                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">${renderSvgIcon("close", "gray", 16)}</button>
            </div>
            <div class="modal-body" id="modal-appeal-body" style="padding:16px 20px;">
                <div style="text-align:center; padding:32px; color:var(--text-dim);">Загрузка шаблонов апелляций...</div>
            </div>
            <div class="modal-footer" id="modal-appeal-footer">
                <button type="button" class="secondary" onclick="window.closeCurrentModal()">ЗАКРЫТЬ</button>
            </div>
        `,
      async () => {
        const body = document.getElementById("modal-appeal-body");
        if (!body) return;
        try {
          if (!state.cachedAppealTemplates) {
            state.cachedAppealTemplates = await api(
              "GET",
              "/api/bans/appeal-templates",
            );
          }
          const tmpl = state.cachedAppealTemplates;
          let selectedBan = null;
          let bansList = [];
          try {
            bansList = await api("GET", "/api/bans/all");
          } catch (e) {
            bansList = [];
          }
          if (targetBanId) {
            selectedBan = bansList.find((b) => b.id === targetBanId);
            if (!selectedBan) {
              try {
                selectedBan = await api("GET", `/api/bans/${targetBanId}`);
              } catch (err) {
                selectedBan = null;
              }
            }
          }
          let currentBan =
            selectedBan || (bansList.length ? bansList[0] : null);
          let currentTab = "form"; // 'form' or 'verdicts'
          let formatMode = "plain"; // 'plain', 'discord', 'bbcode'
          let selectedVerdictId =
            tmpl.verdicts && tmpl.verdicts.length ? tmpl.verdicts[0].id : "";
          const renderModalContent = () => {
            const subtitle = document.getElementById("appeal-modal-subtitle");
            if (subtitle) {
              subtitle.innerHTML = currentBan
                ? `Блокировка <b>#${currentBan.id}</b> игрока <b style="color:var(--text-heading);">${esc(currentBan.targetName)}</b> • Причина: <b>${esc(currentBan.ruleReason)}</b>`
                : `Общий режим (без привязки к конкретному бану)`;
            }

            // Helper to replace placeholders
            const formatText = (rawStr) => {
              if (!rawStr) return "";
              const targetName = currentBan
                ? currentBan.targetName
                : "{targetName}";
              const banId = currentBan ? currentBan.id : "{id}";
              const creatorName = currentBan
                ? currentBan.creatorName
                : "{creatorName}";
              const ruleReason = currentBan
                ? currentBan.ruleReason
                : "{ruleReason}";
              const createdAt = currentBan
                ? fmtTime(currentBan.createdAt)
                : "{createdAt}";
              const proofUrl = currentBan
                ? currentBan.screenshotUrl ||
                  (currentBan.proofUrls && currentBan.proofUrls.length
                    ? currentBan.proofUrls[0]
                    : "Доказательства прикреплены в системе администрирования")
                : "{screenshotUrl}";
              return rawStr
                .replace(/{targetName}/g, targetName)
                .replace(/#{id}/g, "#" + banId)
                .replace(/{id}/g, banId)
                .replace(/{creatorName}/g, creatorName)
                .replace(/{ruleReason}/g, ruleReason)
                .replace(/{createdAt}/g, createdAt)
                .replace(/{screenshotUrl}/g, proofUrl);
            };

            // Build Tab 1 (Form) content
            let formContent = "";
            if (tmpl.form) {
              const header = formatText(tmpl.form.header || "");
              const fields = (tmpl.form.fields || [])
                .map((f) => formatText(f))
                .join("\n");
              const footerText = formatText(tmpl.form.footer || "");
              let combined =
                (header ? header + "\n\n" : "") +
                fields +
                (footerText ? "\n\n" + footerText : "");
              if (formatMode === "discord") {
                combined = "```markdown\n" + combined + "\n```";
              } else if (formatMode === "bbcode") {
                combined =
                  "[b]" +
                  (header || "ФОРМА АПЕЛЛЯЦИИ") +
                  "[/b]\n\n[code]\n" +
                  fields +
                  "\n[/code]\n\n[i]" +
                  footerText +
                  "[/i]";
              }
              formContent = combined;
            }

            // Build Tab 2 (Verdicts) content
            const curVerdict =
              (tmpl.verdicts || []).find((v) => v.id === selectedVerdictId) ||
              (tmpl.verdicts ? tmpl.verdicts[0] : null);
            let verdictContent = curVerdict
              ? formatText(curVerdict.template)
              : "";
            body.innerHTML = `
                        <!-- Ban Selector -->
                        <div style="background:rgba(255, 255, 255, 0.03); border:1px solid var(--border); border-radius:var(--radius-md); padding:10px 14px; margin-bottom:14px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px;">
                            <div style="display:flex; align-items:center; gap:10px;">
                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(currentBan ? currentBan.targetName : "Steve")}/32" style="width:32px; height:32px; border-radius:4px;" alt="">
                                <div>
                                    <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase;">Выбранный бан для авто-подстановки:</div>
                                    <div style="font-size:13.5px; font-weight:800; color:var(--text-heading);">
                                        ${currentBan ? `${esc(currentBan.targetName)} (Бан #${currentBan.id})` : "Общий шаблон без привязки"}
                                    </div>
                                </div>
                            </div>
                            <div style="min-width:240px; flex:1; max-width:360px;">
                                <select id="appeal-ban-picker" style="font-size:12px; padding:6px 10px; width:100%;">
                                    ${bansList
                                      .map(
                                        (b) => `
                                        <option value="${b.id}" ${currentBan && currentBan.id === b.id ? "selected" : ""}>
                                            #${b.id} — ${esc(b.targetName)} (${esc(b.ruleReason)})
                                        </option>
                                    `,
                                      )
                                      .join("")}
                                    <option value="0" ${!currentBan ? "selected" : ""}>[Без привязки / Переменные {targetName}]</option>
                                </select>
                            </div>
                        </div>

                        <!-- Tabs Header -->
                        <div class="appeal-tabs-bar">
                            <button type="button" class="appeal-tab-btn ${currentTab === "form" ? "active" : ""}" id="tab-btn-form">
                                <span style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon("fileText", "violet", 14)} ФОРМА ДЛЯ ИГРОКА</span>
                            </button>
                            <button type="button" class="appeal-tab-btn ${currentTab === "verdicts" ? "active" : ""}" id="tab-btn-verdicts">
                                <span style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon("scales", "violet", 14)} ВЕРДИКТЫ АДМИНИСТРАЦИИ (${(tmpl.verdicts || []).length})</span>
                            </button>
                        </div>

                        ${
                          currentTab === "form"
                            ? `
                            <!-- Tab 1: Form View -->
                            <div class="appeal-toolbar">
                                <div style="display:flex; gap:6px;">
                                    <button type="button" class="secondary btn-sm ${formatMode === "plain" ? "primary" : ""}" id="fmt-plain-btn">Plain Text</button>
                                    <button type="button" class="secondary btn-sm ${formatMode === "discord" ? "primary" : ""}" id="fmt-discord-btn">Discord (Markdown)</button>
                                    <button type="button" class="secondary btn-sm ${formatMode === "bbcode" ? "primary" : ""}" id="fmt-bbcode-btn">Форум (BBCode)</button>
                                </div>
                                <button type="button" class="primary btn-sm" id="btn-copy-form-text" style="display:inline-flex; align-items:center; gap:6px;">
                                    ${renderSvgIcon("copy", "white", 13)} СКОПИРОВАТЬ ФОРМУ
                                </button>
                            </div>
                            <textarea class="appeal-code-editor" id="appeal-form-textarea" spellcheck="false">${esc(formContent)}</textarea>
                            <div style="font-size:11.5px; color:var(--text-dim); margin-top:8px; display:flex; justify-content:space-between; flex-wrap:wrap; gap:8px;">
                                <span>Текст можно отредактировать перед копированием прямо в этом окне.</span>
                                <span>Формат: <b>${formatMode.toUpperCase()}</b></span>
                            </div>
                        `
                            : `
                            <!-- Tab 2: Verdicts View -->
                            <div style="font-size:11.5px; font-weight:700; color:var(--text-dim); text-transform:uppercase; margin-bottom:8px;">
                                Выберите заготовку решения по апелляции:
                            </div>
                            <div class="appeal-verdict-chips">
                                ${(tmpl.verdicts || [])
                                  .map((v) => {
                                    const isActive = v.id === selectedVerdictId;
                                    const typeClass = v.type
                                      ? v.type.toLowerCase()
                                      : "info";
                                    let icon = renderSvgIcon(
                                      "scales",
                                      "violet",
                                      13,
                                    );
                                    if (v.type === "APPROVE")
                                      icon = renderSvgIcon(
                                        "check",
                                        "green",
                                        13,
                                      );
                                    else if (v.type === "REJECT")
                                      icon = renderSvgIcon("ban", "red", 13);
                                    else if (v.type === "INFO")
                                      icon = renderSvgIcon(
                                        "fileText",
                                        "gray",
                                        13,
                                      );
                                    return `
                                        <button type="button" class="verdict-chip ${typeClass} ${isActive ? "active " + typeClass : ""}" data-verdict-id="${esc(v.id)}">
                                            <span>${icon}</span>
                                            <span>${esc(v.name)}</span>
                                        </button>
                                    `;
                                  })
                                  .join("")}
                            </div>

                            <div class="appeal-toolbar">
                                <div style="display:flex; align-items:center; gap:8px;">
                                    <span class="badge ${curVerdict && curVerdict.type === "APPROVE" ? "green" : curVerdict && curVerdict.type === "REJECT" ? "red" : "purple"}">
                                        ${curVerdict ? esc(curVerdict.name) : "ВЕРДИКТ"}
                                    </span>
                                </div>
                                <div style="display:flex; gap:8px; flex-wrap:wrap;">
                                    ${
                                      curVerdict &&
                                      curVerdict.type === "APPROVE" &&
                                      currentBan &&
                                      currentBan.status === "ACTIVE" &&
                                      (!isModeratorRole() || state.me.isOwner)
                                        ? `
                                        <button type="button" class="danger btn-sm" id="btn-unban-and-copy" title="Разбанить игрока на сервере и скопировать готовый вердикт" style="display:inline-flex; align-items:center; gap:6px;">
                                            ${renderSvgIcon("shield", "white", 13)} СНЯТЬ БАН И СКОПИРОВАТЬ
                                        </button>
                                    `
                                        : ""
                                    }
                                    <button type="button" class="primary btn-sm" id="btn-copy-verdict-text" style="display:inline-flex; align-items:center; gap:6px;">
                                        ${renderSvgIcon("copy", "white", 13)} СКОПИРОВАТЬ ВЕРДИКТ
                                    </button>
                                </div>
                            </div>
                            <textarea class="appeal-code-editor" id="appeal-verdict-textarea" spellcheck="false">${esc(verdictContent)}</textarea>
                            <div style="font-size:11.5px; color:var(--text-dim); margin-top:8px;">
                                Официальный ответ администратора на апелляцию с подставленными данными нарушителя.
                            </div>
                        `
                        }
                    `;

            // Wire up listeners
            document
              .getElementById("appeal-ban-picker")
              ?.addEventListener("change", (e) => {
                const val = parseInt(e.target.value);
                currentBan = bansList.find((b) => b.id === val) || null;
                renderModalContent();
              });
            document
              .getElementById("tab-btn-form")
              ?.addEventListener("click", () => {
                currentTab = "form";
                renderModalContent();
              });
            document
              .getElementById("tab-btn-verdicts")
              ?.addEventListener("click", () => {
                currentTab = "verdicts";
                renderModalContent();
              });
            document
              .getElementById("fmt-plain-btn")
              ?.addEventListener("click", () => {
                formatMode = "plain";
                renderModalContent();
              });
            document
              .getElementById("fmt-discord-btn")
              ?.addEventListener("click", () => {
                formatMode = "discord";
                renderModalContent();
              });
            document
              .getElementById("fmt-bbcode-btn")
              ?.addEventListener("click", () => {
                formatMode = "bbcode";
                renderModalContent();
              });
            document.querySelectorAll(".verdict-chip").forEach((btn) => {
              btn.addEventListener("click", () => {
                selectedVerdictId = btn.getAttribute("data-verdict-id");
                renderModalContent();
              });
            });
            const copyToClipboard = (text, successMsg) => {
              if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard
                  .writeText(text)
                  .then(() => {
                    showToast("Скопировано", successMsg, "success");
                  })
                  .catch(() => {
                    fallbackCopy(text, successMsg);
                  });
              } else {
                fallbackCopy(text, successMsg);
              }
            };
            const fallbackCopy = (text, successMsg) => {
              const el = document.createElement("textarea");
              el.value = text;
              document.body.appendChild(el);
              el.select();
              document.execCommand("copy");
              document.body.removeChild(el);
              showToast("Скопировано", successMsg, "success");
            };
            document
              .getElementById("btn-copy-form-text")
              ?.addEventListener("click", () => {
                const txt =
                  document.getElementById("appeal-form-textarea")?.value || "";
                copyToClipboard(
                  txt,
                  "Форма апелляции скопирована в буфер обмена",
                );
              });
            document
              .getElementById("btn-copy-verdict-text")
              ?.addEventListener("click", () => {
                const txt =
                  document.getElementById("appeal-verdict-textarea")?.value ||
                  "";
                copyToClipboard(
                  txt,
                  "Текст вердикта скопирован в буфер обмена",
                );
              });
            document
              .getElementById("btn-unban-and-copy")
              ?.addEventListener("click", () => {
                if (!currentBan) return;
                confirmAction(
                  "СНЯТИЕ БАНА ПО АПЕЛЛЯЦИИ #" + currentBan.id,
                  `Вы действительно хотите разбанить игрока ${currentBan.targetName} по этой апелляции?\nБлокировка будет снята на игровом сервере, а текст вердикта скопирован в буфер обмена.`,
                  async () => {
                    try {
                      await api("POST", `/api/bans/${currentBan.id}/unban`);
                      const txt =
                        document.getElementById("appeal-verdict-textarea")
                          ?.value || "";
                      copyToClipboard(
                        txt,
                        `Игрок ${currentBan.targetName} разбанен. Вердикт скопирован!`,
                      );
                      currentBan.status = "UNBANNED";
                      if (state.activeNavSection === "bans") renderBansView();
                      renderModalContent();
                    } catch (err) {
                      showToast("Ошибка разбана", err.message, "error");
                    }
                  },
                  "РАЗБАНИТЬ И СКОПИРОВАТЬ",
                  false,
                );
              });
          };
          renderModalContent();
        } catch (e) {
          body.innerHTML = `<div style="color:var(--red); padding:24px; text-align:center;">Ошибка загрузки шаблонов апелляций: ${esc(e.message)}</div>`;
        }
      },
      "modal-lg",
    );
  };

  // Modal: Linked Accounts / Alts (IP Graph)
}
export function exposeOpenPlayerAltsModal() {
  window.openPlayerAltsModal = async function (playerName) {
    openModal(
      `
            <div class="modal-header">
                <h3>СВЯЗАННЫЕ АККАУНТЫ (АЛЬТЫ)</h3>
                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">${renderSvgIcon("close", "gray", 16)}</button>
            </div>
            <div class="modal-body" id="alts-modal-body">
                <div style="text-align:center; padding:20px; color:var(--text-muted);">Поиск связей по IP-графу для игрока <b>${esc(playerName)}</b>...</div>
            </div>
        `,
      async () => {
        const body = document.getElementById("alts-modal-body");
        try {
          const data = await api(
            "GET",
            `/api/players/${encodeURIComponent(playerName)}/alts`,
          );
          const direct = data.directAlts || [];
          const subnet = data.subnetAlts || [];
          const ip = data.primaryIp || "—";
          body.innerHTML = `
                    <div style="background:var(--card-inner-bg); padding:12px 16px; border-radius:var(--radius-sm); border:1px solid var(--border); margin-bottom:16px;">
                        <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase;">Основной IP адрес игрока:</div>
                        <div class="font-mono" style="font-size:14px; font-weight:700; color:var(--accent-light); margin-top:2px;">${esc(ip)}</div>
                    </div>

                    <h4 style="font-size:13px; margin-bottom:8px; color:var(--text-main);">Точные совпадения по IP (${direct.length}):</h4>
                    <div style="margin-bottom:16px;">
                        ${
                          direct.length
                            ? direct
                                .map(
                                  (name) => `
                            <div style="display:flex; align-items:center; justify-content:space-between; padding:6px 0; border-bottom:1px solid var(--border);">
                                <div style="display:flex; align-items:center; gap:8px;">
                                    <img src="https://mc-heads.net/avatar/${encodeURIComponent(name)}/20" class="player-avatar-sm" alt="">
                                    <b style="color:var(--text-main);">${esc(name)}</b>
                                </div>
                                <div style="display:flex; gap:6px;">
                                    <button type="button" class="secondary btn-sm" onclick="window.viewPlayerProfile('${esc(name)}')">ДОСЬЕ</button>
                                    <button type="button" class="danger btn-sm" onclick="window.openQuickBanModal('${esc(name)}', 'Мультиаккаунт')">БАН</button>
                                </div>
                            </div>
                        `,
                                )
                                .join("")
                            : '<div style="color:var(--text-dim); font-size:12px;">Твинков с одинаковым IP не обнаружено</div>'
                        }
                    </div>

                    ${
                      subnet.length
                        ? `
                        <h4 style="font-size:13px; margin-bottom:8px; color:var(--yellow);">Совпадения по подсети (${subnet.length}):</h4>
                        <div>
                            ${subnet
                              .slice(0, 8)
                              .map(
                                (name) => `
                                <span class="badge yellow" style="cursor:pointer; margin:3px;" onclick="window.viewPlayerProfile('${esc(name)}')">${esc(name)}</span>
                            `,
                              )
                              .join("")}
                        </div>
                    `
                        : ""
                    }
                `;
        } catch (e) {
          body.innerHTML = `<div style="color:var(--red); padding:16px; text-align:center;">Ошибка поиска альтов: ${esc(e.message)}</div>`;
        }
      },
    );
  };

  // ==========================================================================
  // 5. АНТИЧИТ (ИНТЕГРАЦИЯ С VESUVIO, ЖИВОЙ ПОТОК, НАБЛЮДЕНИЕ)
  // ==========================================================================
}
