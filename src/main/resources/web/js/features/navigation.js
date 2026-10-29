import { captureView, restoreView } from "../core/view-state.js";
import { state } from "../state.js";
import {
  api,
  clearPollers,
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
                <div class="sidebar-mobile-backdrop" id="sidebar-mobile-backdrop"></div>
                <!-- Left Sidebar -->
                <aside class="app-sidebar ${state.sidebarCollapsed ? "collapsed" : ""}" id="app-sidebar">
                    <div class="sidebar-brand">
                        <div class="sidebar-logo-cube">
                            <svg viewBox="0 0 32 32" width="28" height="28" fill="none">
                                <polygon points="16,2 30,10 16,18 2,10" fill="#9B5CFF"/>
                                <polygon points="16,2 23,6 16,10 9,6" fill="#B788FF" opacity="0.6"/>
                                <polygon points="2,10 16,18 16,30 2,22" fill="#4C1D95"/>
                                <polygon points="16,18 30,10 30,22 16,30" fill="#6D28D9"/>
                                <polyline points="2,10 16,18 30,10" stroke="#1F2440" stroke-width="0.8"/>
                                <line x1="16" y1="18" x2="16" y2="30" stroke="#1F2440" stroke-width="0.8"/>
                            </svg>
                        </div>
                        <div class="sidebar-brand-info">
                            <span class="sidebar-brand-name">MINECRAFT</span>
                            <span class="sidebar-brand-sub">WebAdmin</span>
                        </div>
                    </div>

                    <!-- 8 Main Navigation Items -->
                    <nav class="sidebar-nav" id="sidebar-nav">
                        <div class="nav-section-title">Разделы панели</div>
                        ${state.SECTIONS.map((s) => {
                          if (!isSectionVisible(s)) return "";
                          const isActive = s.id === state.activeNavSection;
                          const keyShortcut = s.num === 10 ? "0" : s.num;
                          return `
                                <div class="nav-item ${isActive ? "active" : ""}" data-nav="${s.id}" data-tooltip="${esc(s.label)} (Клавиша: ${keyShortcut})">
                                    <span class="nav-icon">${s.icon}</span>
                                    <span class="nav-label">${esc(s.label)}</span>
                                    ${s.id === "punishments" || s.id === "reports" ? '<span class="nav-counter-badge" id="reports-pending-badge" style="display:none;">0</span>' : ""}
                                    <span class="nav-key-badge">${keyShortcut}</span>
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
                            <button type="button" class="mobile-menu-btn" id="mobile-menu-toggle" title="Открыть меню">${renderSvgIcon("menu", "gray", 16)}</button>
                            <h1 class="topbar-page-title" id="topbar-page-title">Дашборд</h1>
                        </div>

                        <div class="topbar-center">
                            <div class="global-search-trigger" id="topbar-search-trigger" data-tooltip="Глобальный поиск по никам, банам и логам (Ctrl+K)">
                                <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
                                <span>Поиск игрока, бана или события...</span>
                                <span class="search-shortcut-badge">Ctrl + K</span>
                            </div>
                        </div>

                        <div class="topbar-right" style="display:flex; align-items:center; gap:8px;">
                            <!-- Notifications Bell -->
                            <button type="button" class="topbar-icon-btn" id="topbar-notif-btn" data-tooltip="Уведомления персонала">
                                <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>
                                <span class="notif-badge" id="topbar-notif-badge" style="display:none;">0</span>
                            </button>

                            <!-- Theme Toggle Button -->
                            <button type="button" class="topbar-icon-btn" id="topbar-theme-btn" data-tooltip="Сменить тему (Тёмная / Светлая)">
                                <span id="theme-btn-icon">${renderSvgIcon("moon", "violet", 15)}</span>
                            </button>

                            <!-- Admin Settings Button -->
                            <button type="button" class="topbar-icon-btn" id="topbar-settings-btn" data-tooltip="Настройки администратора панели">
                                <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
                            </button>

                            <div class="status-pill" id="topbar-status-pill" data-tooltip="Статус ядра Minecraft сервера">
                                <span class="status-dot" id="server-status-dot"></span>
                                <span id="server-status-text">Онлайн: ...</span>
                            </div>

                            <button type="button" class="topbar-btn logout-btn" id="topbar-logout-btn" data-tooltip="Выйти из системы">
                                <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
                                ВЫХОД
                            </button>
                        </div>
                    </header>

                    <!-- Content Body -->
                    <div class="content-area" id="content-area">
                        <!-- Active view renders here -->
                    </div>
                </main>
            </div>
        `;

  // Apply theme from preferences
  const savedTheme =
    userPrefs.theme || state.browserStorage.getItem("wa_theme") || "dark";
  applyUserTheme(savedTheme);

  // Mobile drawer handlers
  const sidebar = document.getElementById("app-sidebar");
  const mobileBackdrop = document.getElementById("sidebar-mobile-backdrop");
  document
    .getElementById("mobile-menu-toggle")
    ?.addEventListener("click", () => {
      sidebar?.classList.toggle("mobile-open");
      mobileBackdrop?.classList.toggle("open");
    });
  mobileBackdrop?.addEventListener("click", () => {
    sidebar?.classList.remove("mobile-open");
    mobileBackdrop?.classList.remove("open");
  });

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
    ?.addEventListener("click", () => openAdminSettingsModal("general"));

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
  const section = route.get("section");
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
export function installHistoryNavigation() {
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
  document.querySelectorAll("#sidebar-nav .nav-item").forEach((el) => {
    const isMatch =
      el.dataset.nav === sectionId ||
      (el.dataset.nav === "punishments" &&
        (sectionId === "bans" || sectionId === "reports")) ||
      (el.dataset.nav === "staff" && sectionId === "admins");
    el.classList.toggle("active", isMatch);
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
      await renderJournalView();
      break;
    case "punishments":
      await renderPunishmentsView("reports");
      break;
    case "bans":
      await renderPunishmentsView("bans");
      break;
    case "reports":
      await renderPunishmentsView("reports");
      break;
    case "anticheat":
      await renderAnticheatView();
      break;
    case "server":
      await renderServerView();
      break;
    case "auth":
      await renderAuthView();
      break;
    case "staff":
    case "admins":
      await renderStaffView();
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
