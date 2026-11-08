import {renderPunishments} from "./punishments.js";
import { renderSeparatedCases, renderSeparatedPenalties } from "./separated-workspace.js";
import { captureView, restoreView } from "../core/view-state.js";
import { state } from "../state.js";
import {
  api,
  clearPollers,
  closeAllModals,
  confirmAction,
  esc,
  hasPerm,
  isModeratorRole,
  isSectionVisible,
  openGlobalSearchModal,
  openUserProfileModal,
  renderSvgIcon,
  setToken,
} from "./ui.js";
import {
  applyUserTheme,
  openNotificationsModal,
  updateNotificationsBadge,
} from "./notifications.js";
import { openAdminSettingsModal } from "./settings.js";
import { renderPunishmentsView, updateReportsBadge } from "./moderation.js";
import { renderDashboardView } from "./dashboard.js";
import { renderJournalView } from "./journal.js";
import { renderAnticheatView } from "./vesuvio.js";
import { renderServerView } from "./server.js";
import { renderAuthView } from "./loveauth.js";
import { renderStaffView } from "./staff.js";
import { renderRestrictions } from "./restrictions.js";
import { renderSettingsHome } from "./settings-home.js";
import { renderDatabaseView } from "./database.js";
// ---------- Main Application Layout (Sidebar + Viewport) ----------

export function renderAppLayout() {
  const isMod = isModeratorRole();
  const roleName = state.me.isOwner
    ? "Управляющий"
    : state.me.role || (isMod ? "Модератор" : "Администратор");
  const roleClass = isMod && !state.me.isOwner ? "moderator" : "admin";
  let userPrefs = {};
  try {
    userPrefs =
      typeof state.me.uiPreferences === "string"
        ? JSON.parse(state.me.uiPreferences)
        : state.me.uiPreferences || {};
  } catch (_) {
    userPrefs = {};
  }
  state.app.innerHTML = `
            <div class="app-layout">
                <!-- Left Sidebar -->
                <aside class="app-sidebar ${state.sidebarCollapsed ? "collapsed" : ""}" id="app-sidebar">
                    <div class="sidebar-brand">
                        <div class="sidebar-logo-cube">
                            <img src="${location.pathname.startsWith('/preview/')?'/preview':''}/webadmin.svg" width="28" height="28" alt="">
                        </div>
                        <div class="sidebar-brand-info">
                            <span class="sidebar-brand-name">MINECRAFT</span>
                            <span class="sidebar-brand-sub">WebAdmin</span>
                        </div>
                    </div>

                    <!-- 8 Main Navigation Items -->
                    <nav class="sidebar-nav" id="sidebar-nav">

                        ${state.SECTIONS.map((s, i, sections) => {
                          if (!isSectionVisible(s)) return "";
                          const isActive = s.id === state.activeNavSection;
                          const keyShortcut = s.num === 10 ? "0" : s.num;
                          const previous = sections
                            .slice(0, i)
                            .filter(isSectionVisible)
                            .at(-1);
                          return `${previous?.group !== s.group ? `<div class="nav-section-title">${esc(s.group)}</div>` : ""}
                                <div class="nav-item ${isActive ? "active" : ""}" data-nav="${s.id}" data-tooltip="${esc(s.label)}${keyShortcut!==undefined?` (Клавиша: ${keyShortcut})`:""}">
                                    <span class="nav-icon">${s.icon}</span>
                                    <span class="nav-label">${esc(s.label)}</span>
                                    ${s.id === "punishments" || s.id === "reports" ? '<span class="nav-counter-badge" id="reports-pending-badge" style="display:none;">0</span>' : ""}
                                    ${keyShortcut!==undefined?`<span class="nav-key-badge">${keyShortcut}</span>`:""}
                                </div>
                            `;
                        }).join("")}
                    </nav>

                    <!-- Sidebar Footer -->
                    <div class="sidebar-footer">
                        <div class="sidebar-user-block" id="sidebar-user-btn" data-tooltip="Настройки администратора и профиль">
                            <div class="sidebar-user-avatar">${esc((state.me.username || "A").substring(0, 2).toUpperCase())}</div>
                            <div class="sidebar-user-meta">
                                <span class="sidebar-user-name">${esc(state.me.username)}</span>
                                <span class="sidebar-user-role">${esc(roleName)}</span>
                            </div>
                        </div>
                        <button type="button" class="sidebar-collapse-btn" id="sidebar-toggle-btn" data-tooltip="Свернуть / развернуть меню">
                            ${state.sidebarCollapsed ? "▶" : "◀"}
                        </button>
                    </div>
                </aside>

                <!-- Main Viewport -->
                <main class="main-viewport">
                    <!-- Topbar -->
                    <header class="topbar">
                        <div class="topbar-left" style="display:flex; align-items:center; gap:10px;">
                            <h1 class="topbar-page-title" id="topbar-page-title">Дашборд</h1>
                        </div>

                        <div class="topbar-center">
                            <button type="button" class="global-search-trigger" aria-label="Быстрый поиск" id="topbar-search-trigger" data-tooltip="Игроки, наказания и обращения (Ctrl+K)">
                                <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
                                <span>Поиск игроков, наказаний и обращений…</span>
                                <span class="search-shortcut-badge">Ctrl + K</span>
                            </button>
                        </div>

                        <div class="topbar-right" style="display:flex; align-items:center; gap:8px;">
                            <!-- Notifications Bell -->
                            <button type="button" class="topbar-icon-btn" id="topbar-notif-btn" aria-label="Оповещения" data-tooltip="Уведомления персонала">
                                <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>
                                <span class="notif-badge" id="topbar-notif-badge" style="display:none;">0</span>
                            </button>

                            <!-- Theme Toggle Button -->
                            <button type="button" class="topbar-icon-btn" id="topbar-theme-btn" aria-label="Сменить тему" data-tooltip="Сменить тему (Тёмная / Светлая)">
                                <span id="theme-btn-icon">${renderSvgIcon("moon", "violet", 15)}</span>
                            </button>

                            <!-- Admin Settings Button -->
                            <button type="button" class="topbar-icon-btn" id="topbar-settings-btn" aria-label="Настройки панели" data-tooltip="Настройки администратора панели">
                                <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
                            </button>

                            <div class="status-pill" id="topbar-status-pill">
                                <span class="status-dot" id="server-status-dot"></span>
                                <span id="server-status-text">Онлайн: ...</span>
                            </div>

                            <button type="button" class="topbar-btn logout-btn" id="topbar-logout-btn" data-tooltip="Выйти из системы">
                                <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
                                ВЫХОД
                            </button>
                        </div>
                    </header>
                    <nav class="compact-section-nav" aria-label="Разделы панели">
                      ${state.SECTIONS.filter(isSectionVisible)
                        .map(
                          (section) =>
                            `<button type="button" data-compact-nav="${section.id}" class="compact-section-link">${esc(section.label)}</button>`,
                        )
                        .join("")}
                    </nav>

                    <!-- Content Body -->
                    <div class="content-area" id="content-area">
                        <!-- Active view renders here -->
                    </div>
                </main>
            </div>
        `;

  document.body.classList.toggle("compact-rows",!!userPrefs.compactRows);
  document.body.classList.toggle("reduce-motion",!!userPrefs.reduceMotion);
  // Apply theme from preferences
  const savedTheme =
    state.browserStorage.getItem("wa_theme") || userPrefs.theme || document.documentElement.dataset.theme || "dark";
  applyUserTheme(savedTheme);

  // Theme button click
  document
    .getElementById("topbar-theme-btn")
    ?.addEventListener("click", async () => {
      const isLightNow = document.body.classList.contains("theme-light");
      const newTheme = isLightNow ? "dark" : "light";
      applyUserTheme(newTheme);
      userPrefs.theme = newTheme;
      try {
        await api("PUT", "/api/me/preferences", {
          uiPreferences: userPrefs,
        });
        state.me.uiPreferences = JSON.stringify(userPrefs);
      } catch (_) {}
    });

  // Shift button handlers
  document
    .getElementById("topbar-shift-btn")
    ?.addEventListener("click", toggleMyShift);
  document
    .getElementById("topbar-shift-list-btn")
    ?.addEventListener("click", openStaffOnShiftModal);
  // Notifications button handler
  document
    .getElementById("topbar-notif-btn")
    ?.addEventListener("click", openNotificationsModal);
  updateNotificationsBadge();
  state.globalPollers.notifications &&
    clearInterval(state.globalPollers.notifications);
  state.globalPollers.notifications = setInterval(() => {
    if (!document.hidden) updateNotificationsBadge();
  }, 6000);

  // Admin settings modal button
  document
    .getElementById("topbar-settings-btn")
    ?.addEventListener("click", () =>
      isSectionVisible(state.SECTIONS.find((s) => s.id === "settings"))
        ? navigateTo("settings")
        : openAdminSettingsModal("general"),
    );

  document
    .querySelector(".compact-section-nav")
    ?.addEventListener("click", (event) => {
      const item = event.target.closest("[data-compact-nav]");
      if (item) navigateTo(item.dataset.compactNav);
    });
  // Sidebar navigation clicks
  document.getElementById("sidebar-nav")?.addEventListener("click", (e) => {
    const item = e.target.closest(".nav-item");
    if (!item) return;
    const navId = item.dataset.nav;
    if (navId) navigateTo(navId);
  });

  // Sidebar collapse toggle
  document
    .getElementById("sidebar-toggle-btn")
    ?.addEventListener("click", () => {
      state.sidebarCollapsed = !state.sidebarCollapsed;
      state.browserStorage.setItem(
        "wa_sidebar_collapsed",
        state.sidebarCollapsed ? "true" : "false",
      );
      const sidebar = document.getElementById("app-sidebar");
      const btn = document.getElementById("sidebar-toggle-btn");
      if (sidebar)
        sidebar.classList.toggle("collapsed", state.sidebarCollapsed);
      if (btn) btn.textContent = state.sidebarCollapsed ? "▶" : "◀";
    });

  // Search trigger
  document
    .getElementById("topbar-search-trigger")
    ?.addEventListener("click", openGlobalSearchModal);

  // Logout
  document
    .getElementById("topbar-logout-btn")
    ?.addEventListener("click", () => {
      confirmAction(
        "ВЫХОД ИЗ ПАНЕЛИ",
        "Вы действительно хотите завершить текущую сессию в веб-панели?",
        async () => {
          try {
            await api("POST", "/api/auth/logout");
          } catch (e) {}
          setToken(null);
          clearPollers();
          location.reload();
        },
        "ВЫЙТИ",
      );
    });

  // User profile modal
  document
    .getElementById("sidebar-user-btn")
    ?.addEventListener("click", openUserProfileModal);

  // Start periodic server status monitor for topbar
  startTopbarStatusPoller();

  // Navigate to initially selected section
  const route = new URLSearchParams(location.hash.slice(1));
  const section = route.get("section") || userPrefs.startPage;
  const visible = state.SECTIONS.filter(isSectionVisible);
  if (section && visible.some((s) => s.id === section))
    state.activeNavSection = section;
  else if (!visible.some((s) => s.id === state.activeNavSection))
    state.activeNavSection = visible[0]?.id || "dashboard";
  navigateTo(state.activeNavSection, false);
  const player = route.get("player");
  if (player && /^[A-Za-z0-9_]{1,16}$/.test(player) && hasPerm("VIEW_PLAYERS"))
    window.viewPlayerProfile(player);
}
let historyInstalled = false;
export function installHistoryNavigation() {
  if (historyInstalled) return;
  historyInstalled = true;
  window.addEventListener("popstate", () => {
    if (!state.me) return;
    const route = new URLSearchParams(location.hash.slice(1));
    const section = route.get("section") || "dashboard";
    if (state.SECTIONS.some((s) => s.id === section && isSectionVisible(s)))
      navigateTo(section, false);
    const player = route.get("player");
    if (
      player &&
      /^[A-Za-z0-9_]{1,16}$/.test(player) &&
      hasPerm("VIEW_PLAYERS")
    )
      window.viewPlayerProfile(player);
  });
}
export function startTopbarStatusPoller() {
  const updateStatus = async () => {
    try {
      const s = await api("GET", "/api/stats");
      const dot = document.getElementById("server-status-dot");
      const text = document.getElementById("server-status-text");
      if (text && s) {
        const tps = s.tps && s.tps.length ? s.tps[0].toFixed(1) : "20.0";
        text.textContent = `Онлайн: ${s.onlinePlayers}/${s.maxPlayers} • TPS ${tps}`;
        if (dot) {
          dot.className = "status-dot";
          if (parseFloat(tps) < 16) dot.classList.add("danger");
          else if (parseFloat(tps) < 18.5) dot.classList.add("warning");
        }
      }
    } catch (e) {
      // Ignore topbar polling errors
    }
    updateReportsBadge();
  };
  updateStatus();
  state.globalPollers.status && clearInterval(state.globalPollers.status);
  state.globalPollers.status = setInterval(() => {
    if (!document.hidden) updateStatus();
  }, 5000);
}
export async function navigateTo(sectionId, recordHistory = true) {
  if(sectionId === "punishments") sectionId="restrictions";
  if(sectionId === "bans"){state.punishmentWorkspace={...(state.punishmentWorkspace||{}),kind:"ban",stage:"active",page:0,query:"",actor:"",from:"",to:""};sectionId="restrictions";}
  const target = state.SECTIONS.find((s) => s.id === sectionId);
  if(!target)return;
  if (target && !isSectionVisible(target)) return;
  closeAllModals();
  captureView();
  if (recordHistory)
    history.pushState(
      {
        section: sectionId,
      },
      "",
      "#section=" + encodeURIComponent(sectionId),
    );
  clearPollers();
  state.activeNavSection = sectionId;

  // Update active class in sidebar
  document
    .querySelectorAll(
      "#sidebar-nav .nav-item,.compact-section-nav [data-compact-nav]",
    )
    .forEach((el) => {
      const isMatch =
        (el.dataset.nav || el.dataset.compactNav) === sectionId ||
        (el.dataset.nav === "punishments" &&
          (sectionId === "bans" || sectionId === "reports")) ||
        (el.dataset.nav === "staff" && sectionId === "admins");
      el.classList.toggle("active", isMatch);
      if (isMatch) el.setAttribute("aria-current", "page");
      else el.removeAttribute("aria-current");
    });

  // Update topbar page title on the left
  const topbarTitle = document.getElementById("topbar-page-title");
  if (topbarTitle) {
    const sec = state.SECTIONS.find((s) => s.id === sectionId);
    if (sec) {
      topbarTitle.textContent = sec.label;
    } else if (sectionId === "bans") {
      topbarTitle.textContent = "Список блокировок";
    } else if (sectionId === "reports") {
      topbarTitle.textContent = "Жалобы игроков";
    } else {
      topbarTitle.textContent = "Панель управления";
    }
  }
  const area = document.getElementById("content-area");
  if (!area) return;
  area.classList.remove("page-enter");
  void area.offsetWidth;
  area.classList.add("page-enter");
  switch (sectionId) {
    case "dashboard":
      await renderDashboardView();
      break;
    case "journal":
      await renderJournalView("server");
      break;
    case "punishments":
      await renderSeparatedCases(area,"report");
      break;
    case "bans":
      await renderPunishmentsView("bans");
      break;
    case "reports":
      await renderSeparatedCases(area,"report");
      break;
    case "appeals":
      await renderSeparatedCases(area,"appeal");
      break;
    case "support":
      await renderSeparatedCases(area,"support");
      break;
    case "penalty-history":
      await renderPunishments(area,true);
      break;
    case "anticheat":
      await renderAnticheatView();
      break;
    case "server":
      await renderServerView();
      break;
    case "web-journal":
      await renderJournalView("web");break;
    case "game-auth":
      await renderAuthView("game");break;
    case "auth":
      await renderAuthView();
      break;
    case "staff":
    case "admins":
      await renderStaffView();
      break;
    case "restrictions":
      await renderPunishments(area);
      break;
    case "settings":
      renderSettingsHome();
      break;
    case "database":
      await renderDatabaseView();
      break;
    default:
      await renderDashboardView();
      break;
  }
  await restoreView(sectionId);
}
export function exposeNavigateTo() {
  window.navigateTo = navigateTo;

  // ==========================================================================
  // 1. ДАШБОРД (НАСТРАИВАЕМЫЙ С ПРЕСЕТАМИ)
  // ==========================================================================
}
