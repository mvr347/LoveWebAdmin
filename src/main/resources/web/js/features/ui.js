import {esc,hasPerm} from "../core/access.js";
export {esc,hasPerm};
import { showReportPicker } from "../core/report-picker.js";
import { openModalLayer, closeModalLayer, closeAllModalLayers, installModalKeys } from "../core/modal-stack.js";
import { installCaseDialogs } from "../core/case-dialog.js";
import { installInterfaceControls } from "../core/interface-controls.js";
import { requestFetch, clearOperationIntents } from "../core/requests.js";
import { state } from "../state.js";
import { renderMaintenanceScreen } from "./notifications.js";
import { navigateTo } from "./navigation.js";
import { openAdminSettingsModal } from "./settings.js";
import { boot } from "./auth.js";
// ---------- Navigation Sections ----------
export function renderSvgIcon(name, colorType = "gray", size = 16) {
  let color = "currentColor";
  if (colorType === "violet" || colorType === "purple")
    color = "var(--accent, #ef4d5d)";
  else if (colorType === "white") color = "#ffffff";
  else if (colorType === "gray") color = "var(--text-muted, #94a3b8)";
  else if (colorType === "green") color = "var(--green, #22c55e)";
  else if (colorType === "red") color = "var(--red, #ef4444)";
  else if (colorType === "yellow") color = "var(--yellow, #f59e0b)";
  else if (colorType && colorType.startsWith("#")) color = colorType;
  const sw = 1.8;
  const iconPaths = {
    gear: `<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/>`,
    shield: `<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>`,
    bell: `<path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/>`,
    palette: `<circle cx="13.5" cy="6.5" r=".5" fill="currentColor"/><circle cx="17.5" cy="10.5" r=".5" fill="currentColor"/><circle cx="8.5" cy="7.5" r=".5" fill="currentColor"/><circle cx="6.5" cy="12.5" r=".5" fill="currentColor"/><path d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.926 0 1.648-.746 1.648-1.688 0-.437-.18-.835-.437-1.125-.29-.289-.438-.652-.438-1.125a1.64 1.64 0 0 1 1.668-1.668h1.996c3.051 0 5.555-2.503 5.555-5.554C21.965 6.012 17.461 2 12 2z"/>`,
    plug: `<path d="M12 22v-5"/><path d="M9 8V2"/><path d="M15 8V2"/><path d="M18 8v5a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4V8z"/>`,
    wrench: `<path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/>`,
    alert: `<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>`,
    globe: `<circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>`,
    server: `<rect x="2" y="2" width="20" height="8"/><rect x="2" y="14" width="20" height="8"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/>`,
    terminal: `<polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/>`,
    search: `<circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>`,
    users: `<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>`,
    user: `<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>`,
    scales: `<path d="m16 16 3-8 3 8c-.87.65-1.92 1-3 1s-2.13-.35-3-1Z"/><path d="m2 16 3-8 3 8c-.87.65-1.92 1-3 1s-2.13-.35-3-1Z"/><path d="M7 21h10"/><path d="M12 3v18"/><path d="M3 7h2c2 0 5-1 7-2 2 1 5 2 7 2h2"/>`,
    zap: `<polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>`,
    moon: `<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/>`,
    sun: `<circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m6.34 17.66-1.41 1.41"/><path d="m19.07 4.93-1.41 1.41"/>`,
    save: `<path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/>`,
    copy: `<rect width="14" height="14" x="8" y="8"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>`,
    check: `<polyline points="20 6 9 17 4 12"/>`,
    key: `<path d="m21 2-2 2m-1.5 1.5L16 7l-1.5-1.5M16 7l-3 3M8 14a5 5 0 1 0-6 6 5 5 0 0 0 6-6Z"/>`,
    lock: `<rect x="3" y="11" width="18" height="11"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>`,
    refresh: `<path d="M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/><path d="M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16"/><path d="M16 16h5v5"/>`,
    message: `<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>`,
    phone: `<rect width="14" height="20" x="5" y="2" rx="2" ry="2"/><line x1="12" y1="18" x2="12.01" y2="18"/>`,
    ban: `<circle cx="12" cy="12" r="10"/><line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/>`,
    close: `<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>`,
    menu: `<line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="18" x2="21" y2="18"/>`,
    dot: `<circle cx="12" cy="12" r="5" fill="${color}"/>`,
    fileText: `<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/>`,
    download: `<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>`,
    trash: `<polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>`,
    plus: `<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>`,
    volumeX: `<polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><line x1="23" y1="9" x2="17" y2="15"/><line x1="17" y1="9" x2="23" y2="15"/>`,
    userMinus: `<path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="8.5" cy="7" r="4"/><line x1="23" y1="11" x2="17" y2="11"/>`,
    clock: `<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>`,
    tag: `<path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"/><line x1="7" y1="7" x2="7.01" y2="7"/>`,
    shieldAlert: `<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>`,
  };
  const path = iconPaths[name] || iconPaths["gear"];
  return `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="${color}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round" style="display:inline-block; vertical-align:middle; flex-shrink:0;">${path}</svg>`;
}

// ---------- Helpers ----------
export function fmtTime(ts) {
  if (!ts || ts <= 0) return "Никогда";
  return new Date(ts * 1000).toLocaleString("ru-RU");
}
export function fmtDuration(sec) {
  if (sec < 0) return "Бессрочно";
  if (sec === 0) return "0 сек";
  const d = Math.floor(sec / 86400);
  const h = Math.floor((sec % 86400) / 3600);
  const m = Math.floor((sec % 3600) / 60);
  if (d > 0) return `${d} д ${h} ч`;
  if (h > 0) return `${h} ч ${m} м`;
  return `${m} мин`;
}
export function formatUptime(seconds) {
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (d > 0) return `${d}д ${h}ч ${m}м`;
  return `${h}ч ${m}м`;
}
export function parseDeviceString(ua) {
  if (!ua) return "Неизвестное устройство";
  let browser = "Браузер";
  if (ua.includes("Edg/")) browser = "Microsoft Edge";
  else if (ua.includes("Chrome/")) browser = "Google Chrome";
  else if (ua.includes("Firefox/")) browser = "Mozilla Firefox";
  else if (ua.includes("Safari/")) browser = "Apple Safari";
  else if (ua.includes("Opera/") || ua.includes("OPR/")) browser = "Opera";
  let os = "ОС";
  if (ua.includes("Windows NT 10")) os = "Windows 10/11";
  else if (ua.includes("Windows")) os = "Windows";
  else if (ua.includes("Mac OS X")) os = "macOS";
  else if (ua.includes("Android")) os = "Android";
  else if (ua.includes("iPhone") || ua.includes("iPad")) os = "iOS";
  else if (ua.includes("Linux")) os = "Linux";
  return `${browser} (${os})`;
}
export function isModeratorRole() {
  if (!state.me) return false;
  if (state.me.isOwner) return false;
  const role = (state.me.role || "").toLowerCase();
  return role.includes("модератор") || role.includes("mod");
}
export function isSectionVisible(section) {
  if (!state.me) return false;
  if (state.me.isOwner) return true;
  return section.anyPerms ? section.anyPerms.some(hasPerm) : hasPerm(section.perm);
}
export function clearPollers() {
  state.requestGeneration++;
  for (const controller of state.readRequests) controller.abort();
  state.readRequests.clear();
  Object.values(state.pollers).forEach(clearInterval);
  for (const k in state.pollers) delete state.pollers[k];
}
export function setToken(newToken) {
  if (state.token !== newToken) {
    closeAllModals();
    delete state.punishmentWorkspace;
  }
  state.token = newToken;
  if (state.token) {
    state.browserStorage.setItem("wa_token", state.token);
    state.browserStorage.setItem("lwa_token", state.token);
  } else {
    state.browserStorage.removeItem("wa_token");
    state.browserStorage.removeItem("lwa_token");
  }
}

// ---------- API Client ----------
export async function api(method, path, body) {
  const token = state.token;
  const assertCurrentAccount = () => {
    if (state.token !== token) throw new DOMException("Учётная запись изменилась", "AbortError");
  };
  const opts = {
    isCurrent: () => state.token === token,
    method,
    headers: {},
  };
  if (state.token) opts.headers["Authorization"] = "Bearer " + state.token;
  if (body !== undefined) {
    opts.headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(body);
  }
  const generation = state.requestGeneration;
  const controller = new AbortController();
  if (method === "GET") {
    opts.signal = controller.signal;
    state.readRequests.add(controller);
  }
  let res;
  try {
    res = await requestFetch(path, opts);
  } catch (error) {
    assertCurrentAccount();
    throw error;
  } finally {
    state.readRequests.delete(controller);
  }
  assertCurrentAccount();
  if (method === "GET" && generation !== state.requestGeneration)
    throw new Error("Открыт другой раздел");
  let json;
  try {
    json = await res.json();
  } catch (e) {
    throw new Error("Некорректный ответ сервера");
  }
  assertCurrentAccount();
  if (method === "GET" && generation !== state.requestGeneration)
    throw new Error("Открыт другой раздел");
  if (!res.ok || !json.success) {
    if (res.status === 401 && state.token && !path.includes("/login")) {
      setToken(null);
      clearPollers();
      const errMsg = json.error || "Сессия аннулирована";
      showToast("Сессия аннулирована", errMsg, "error");
      setTimeout(() => boot(), 400);
      throw new Error(errMsg);
    }
    if (
      res.status === 503 &&
      (json.maintenance || String(json.error).startsWith("MAINTENANCE:"))
    ) {
      renderMaintenanceScreen(json.error);
      throw new Error(json.error || "Ведутся технические работы");
    }
    throw new Error(json.error || "Ошибка запроса");
  }
  return json.data;
}

// ---------- Toast Notification Engine ----------
export function showToast(title, msg, type = "info") {
  let container = document.getElementById("toast-container");
  if (!container) {
    container = document.createElement("div");
    container.id = "toast-container";
    container.className = "toast-container";
    document.body.appendChild(container);
  }
  const toast = document.createElement("div");
  toast.className = `toast-item ${type}`;
  toast.innerHTML = `
            <div class="toast-content">
                <div class="toast-title">${esc(title)}</div>
                <div class="toast-msg">${esc(msg)}</div>
            </div>
        `;
  container.appendChild(toast);
  setTimeout(() => {
    toast.style.opacity = "0";
    toast.style.transform = "translateX(40px)";
    toast.style.transition = "all 0.25s ease";
    setTimeout(() => toast.remove(), 250);
  }, 4000);
}
export function exposeShowToast() {
  window.showToast = showToast;

  // ---------- Modal System ----------
}
export function openModal(html, onMount, customClass = "") {
  return openModalLayer(html, onMount, customClass);
}
export function openChildModal(html, onMount, customClass = "") {
  return openModalLayer(html, onMount, customClass, true);
}
export function closeModal(expected) {
  return closeModalLayer(expected);
}
export function closeAllModals() {
  closeAllModalLayers();
}
export function exposeCloseCurrentModal() {
  window.closeCurrentModal = closeModal;
}
export function exposeCloseModal() {
  window.closeModal = closeModal;

  // Глобальное закрытие модальных окон по Escape
}
export function installModalKeyboardShortcuts() {
  installModalKeys();

  // Confirmation Modal for Risky Operations
}
export function confirmAction(
  title,
  message,
  onConfirm,
  confirmBtnText = "ПОДТВЕРДИТЬ",
  danger = true,
) {
  const confirmation = openChildModal(
    `
            <div class="modal-header">
                <h3>${esc(title)}</h3>
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon("close", "gray", 16)}</button>
            </div>
            <div class="modal-body">
                <p style="font-size:14px; line-height:1.6; color:var(--text-secondary);">${esc(message)}</p>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                <button type="button" class="${danger ? "danger" : "primary"}" id="modal-confirm-btn">${esc(confirmBtnText)}</button>
            </div>
        `,
    () => {
      document
        .getElementById("modal-confirm-btn")
        ?.addEventListener("click", () => {
          if (!closeModal(confirmation)) return;
          if (onConfirm) onConfirm();
        });
    },
  );
}

// ---------- Player Autocomplete Engine ----------
export function attachPlayerAutocomplete(inputEl, onSelect) {
  if (!inputEl) return;
  let parent = inputEl.parentElement;
  if (!parent) return;
  parent.classList.add("autocomplete-wrapper");
  let dropdown = parent.querySelector(".autocomplete-dropdown");
  if (!dropdown) {
    dropdown = document.createElement("div");
    dropdown.className = "autocomplete-dropdown";
    parent.appendChild(dropdown);
  }
  const lifetime = new AbortController(), signal = lifetime.signal;
  let generation = 0;
  const cleanup = () => { ++generation; clearTimeout(timer); lifetime.abort(); closeDropdown(); };
  inputEl.closest(".modal-overlay")?.addEventListener("lwa:dispose", cleanup, { once: true });
  let timer = null;
  let currentResults = [];
  let selectedIdx = -1;
  const closeDropdown = () => {
    dropdown.classList.remove("open");
    dropdown.innerHTML = "";
    selectedIdx = -1;
  };
  const renderItems = (players) => {
    currentResults = players;
    selectedIdx = -1;
    if (!players.length) {
      closeDropdown();
      return;
    }
    dropdown.innerHTML = players
      .map(
        (p, idx) => `
                <div class="autocomplete-item" data-idx="${idx}">
                    <img src="https://mc-heads.net/avatar/${encodeURIComponent(p.name)}/20" class="player-avatar-sm" alt="" style="width:20px; height:20px; border-radius:3px;">
                    <span class="autocomplete-item-name">${esc(p.name)}</span>
                    <span class="autocomplete-item-sub">${p.playtimeSeconds ? fmtDuration(p.playtimeSeconds) : "Игрок"}</span>
                </div>
            `,
      )
      .join("");
    dropdown.classList.add("open");
    dropdown.querySelectorAll(".autocomplete-item").forEach((item) => {
      item.addEventListener("click", (e) => {
        e.stopPropagation();
        const idx = parseInt(item.dataset.idx, 10);
        if (currentResults[idx]) {
          inputEl.value = currentResults[idx].name;
          closeDropdown();
          inputEl.dispatchEvent(new Event("change", { bubbles: true }));
          if (onSelect) onSelect(currentResults[idx].name);
        }
      });
    });
  };
  inputEl.addEventListener("input", () => {
    clearTimeout(timer);
    const request = ++generation;
    const val = inputEl.value.trim();
    if (val.length < 2) {
      closeDropdown();
      return;
    }
    timer = setTimeout(async () => {
      try {
        const players = await api(
          "GET",
          `/api/players?q=${encodeURIComponent(val)}&limit=8`,
        );
        if (!signal.aborted && request === generation && inputEl.isConnected && inputEl.value.trim() === val) renderItems(players || []);
      } catch (e) {
        if (request === generation) closeDropdown();
      }
    }, 200);
  });
  inputEl.addEventListener("keydown", (e) => {
    if (!dropdown.classList.contains("open")) return;
    const items = dropdown.querySelectorAll(".autocomplete-item");
    if (e.key === "ArrowDown") {
      e.preventDefault();
      selectedIdx = Math.min(selectedIdx + 1, items.length - 1);
      items.forEach((it, i) =>
        it.classList.toggle("selected", i === selectedIdx),
      );
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      selectedIdx = Math.max(selectedIdx - 1, 0);
      items.forEach((it, i) =>
        it.classList.toggle("selected", i === selectedIdx),
      );
    } else if (e.key === "Enter") {
      if (selectedIdx >= 0 && currentResults[selectedIdx]) {
        e.preventDefault();
        const chosen = currentResults[selectedIdx].name;
        inputEl.value = chosen;
        closeDropdown();
        inputEl.dispatchEvent(new Event("change", { bubbles: true }));
        if (onSelect) onSelect(chosen);
      }
    } else if (e.key === "Escape") {
      closeDropdown();
    }
  });
  document.addEventListener("click", (e) => {
    if (!inputEl.contains(e.target) && !dropdown.contains(e.target)) {
      closeDropdown();
    }
  }, { signal });
  return cleanup;
}
export function exposeAttachPlayerAutocomplete() {
  window.attachPlayerAutocomplete = attachPlayerAutocomplete;

  // ---------- Visual Report Picker Modal ----------
}
export function openReportPickerModal(targetPlayer = "", onSelected) {
  return showReportPicker({ targetPlayer, onSelected, api, esc, fmtTime, openChildModal, closeModal });
}
export function exposeOpenReportPickerModal() {
  window.openReportPickerModal = openReportPickerModal;

  // ---------- Universal Tooltip Engine ----------
}
export function initTooltipEngine() {
  let tipEl = document.getElementById("wa-global-tooltip");
  if (!tipEl) {
    tipEl = document.createElement("div");
    tipEl.id = "wa-global-tooltip";
    tipEl.className = "wa-tooltip";
    document.body.appendChild(tipEl);
  }
  let currentTarget = null;
  document.addEventListener("mouseover", (e) => {
    const target = e.target.closest("[data-tooltip], [title]");
    if (!target) return;
    if (target.hasAttribute("title")) {
      const titleText = target.getAttribute("title");
      if (titleText && titleText.trim())
        target.setAttribute("data-tooltip", titleText);
      target.removeAttribute("title");
    }
    const text = target.getAttribute("data-tooltip");
    if (!text || !text.trim()) return;
    currentTarget = target;
    tipEl.textContent = text;
    tipEl.classList.add("visible");
    positionTooltip(target, tipEl);
  });
  document.addEventListener("mouseout", (e) => {
    if (currentTarget && !currentTarget.contains(e.relatedTarget)) {
      tipEl.classList.remove("visible");
      currentTarget = null;
    }
  });
  function positionTooltip(target, tip) {
    const rect = target.getBoundingClientRect();
    const tipRect = tip.getBoundingClientRect();
    let left = rect.left + rect.width / 2 - tipRect.width / 2;
    let top = rect.top - tipRect.height - 8;
    if (left < 10) left = 10;
    if (left + tipRect.width > window.innerWidth - 10)
      left = window.innerWidth - tipRect.width - 10;
    if (top < 10) top = rect.bottom + 8;
    tip.style.left = `${Math.round(left)}px`;
    tip.style.top = `${Math.round(top)}px`;
  }
}

// ---------- Global Search (Ctrl + K) ----------
export function initGlobalShortcuts() {
  document.addEventListener("keydown", (e) => {
    if (e.defaultPrevented || document.querySelector(".modal-overlay,dialog[open]")) return;
    // Global search: Ctrl + K or Cmd + K
    if (
      (e.ctrlKey || e.metaKey) &&
      (e.key === "k" || e.key === "K" || e.key === "л" || e.key === "Л")
    ) {
      e.preventDefault();
      openGlobalSearchModal();
      return;
    }

    // Quick Ban: B (outside inputs)
    if (e.key === "b" || e.key === "B" || e.key === "и" || e.key === "И") {
      const tag = document.activeElement
        ? document.activeElement.tagName.toLowerCase()
        : "";
      if (tag !== "input" && tag !== "textarea" && tag !== "select") {
        e.preventDefault();
        window.openQuickBanModal("");
        return;
      }
    }

    // Number keys 1-9 and 0 for section switching (outside inputs)
    if ((e.key >= "1" && e.key <= "9") || e.key === "0") {
      const tag = document.activeElement
        ? document.activeElement.tagName.toLowerCase()
        : "";
      if (tag !== "input" && tag !== "textarea" && tag !== "select") {
        const shortcut = e.key === "0" ? 10 : parseInt(e.key, 10);
        const targetSection = state.SECTIONS.find(section => section.num === shortcut);
        if (targetSection) {
          if (isSectionVisible(targetSection)) {
            e.preventDefault();
            navigateTo(targetSection.id);
          }
        }
      }
    }

  });
}
export function openGlobalSearchModal() {
  openModal(
    `
            <div class="search-modal">
                <div class="search-input-header">
                    <span style="display:inline-flex; align-items:center;">${renderSvgIcon("search", "violet", 18)}</span>
                    <input type="text" id="global-search-input" placeholder="Игроки, наказания и обращения… (Esc для выхода)" autofocus>
                    <span class="search-shortcut-badge">ESC</span>
                </div>
                <div class="search-results-list" id="global-search-results">
                    <div style="padding:16px; text-align:center; color:var(--text-dim); font-size:12.5px;">
                        Введите ник, причину наказания или текст обращения…
                    </div>
                </div>
            </div>
        `,
    () => {
      const input = document.getElementById("global-search-input");
      const results = document.getElementById("global-search-results");
      let debounceTimer = null,
        version = 0;
      input?.addEventListener("input", () => {
        clearTimeout(debounceTimer);
        const request = ++version,
          q = input.value.trim();
        if (!q) {
          results.textContent =
            "Ищите игроков, жалобы, апелляции, поддержку и наказания.";
          return;
        }
        debounceTimer = setTimeout(async () => {
          try {
            const response = await api(
              "GET",
              `/api/search?q=${encodeURIComponent(q)}`,
            );
            if (request !== version || !results.isConnected) return;
            results.replaceChildren();
            const labels = {
              player: "Игрок",
              ban: "Блокировка",
              report: "Жалоба",
              appeal: "Апелляция",
              support: "Поддержка",
            };
            for (const item of response.items || []) {
              const row = document.createElement("article");
              row.className = "search-result-card";
              const title = document.createElement("strong");
              title.textContent =
                labels[item.kind] +
                (item.id ? " №" + item.id : "") +
                " · " +
                item.name;
              const description = document.createElement("p");
              description.textContent = item.summary;
              row.append(title, description);
              if (["report", "appeal", "support"].includes(item.kind)) {
                const link = document.createElement("a");
                link.className = "secondary";
                link.href = `/case.html?kind=${item.kind}&id=${Number(item.id)}`;
                link.textContent = "Открыть дело";
                row.append(link);
              } else {
                const button = document.createElement("button");
                button.className = "secondary";
                button.type = "button";
                button.textContent =
                  item.kind === "ban" ? "Открыть наказание" : "Открыть досье";
                button.onclick = () =>
                  item.kind === "ban"
                    ? window.viewBanDetails(Number(item.id))
                    : window.viewPlayerProfile(item.name);
                row.append(button);
              }
              results.append(row);
            }
            if (!results.children.length)
              results.textContent =
                "Ничего не найдено. Попробуйте другой запрос.";
          } catch (error) {
            if (request === version && results.isConnected)
              results.textContent = error.message;
          }
        }, 220);
      });
    },
  );
}

// ---------- Ban Reasons Loader ----------
export async function getBanReasons() {
  if (state.banReasonsCache) return state.banReasonsCache;
  try {
    state.banReasonsCache = await api("GET", "/api/bans/reasons");
  } catch (e) {
    state.banReasonsCache = [
      {
        id: "cheats",
        name: "Читы",
        require_comment: false,
      },
      {
        id: "bug_abuse",
        name: "Использование багов",
        require_comment: false,
      },
      {
        id: "national_insult",
        name: "Оскорбление на почве национальности",
        require_comment: false,
      },
      {
        id: "advertising",
        name: "Реклама",
        require_comment: false,
      },
      {
        id: "griefing",
        name: "Гриферство",
        require_comment: false,
      },
      {
        id: "modded_client",
        name: "Читерские модификации клиента",
        require_comment: false,
      },
      {
        id: "anticheat_bypass",
        name: "Обход античита",
        require_comment: false,
      },
      {
        id: "toxic_behavior",
        name: "Токсичное поведение",
        require_comment: false,
      },
      {
        id: "multi_account",
        name: "Мультиаккаунт",
        require_comment: false,
      },
      {
        id: "other",
        name: "Другое (с обязательным комментарием)",
        require_comment: true,
      },
    ];
  }
  return state.banReasonsCache;
}

// ---------- Boot & Authentication Gate ----------
// Backwards-compatible alias for user profile trigger

export function openUserProfileModal() {
  openAdminSettingsModal("general");
}

// Modal: Quick Kick Modal
export function exposeOpenQuickKickModal() {
  window.openQuickKickModal = function () {
    openModal(
      `
            <div class="modal-header">
                <h3>КИКНУТЬ ИГРОКА С СЕРВЕРА</h3>
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon("close", "gray", 16)}</button>
            </div>
            <div class="modal-body">
                <div class="form-group">
                    <label>Никнейм игрока онлайн</label>
                    <input type="text" id="quick-kick-name" placeholder="Введите ник игрока..." required autofocus autocomplete="off">
                </div>
                <div class="form-group">
                    <label>Причина кика</label>
                    <input type="text" id="quick-kick-reason" value="Кикнут администратором через веб-панель">
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                <button type="button" class="danger" id="btn-submit-quick-kick">КИКНУТЬ</button>
            </div>
        `,
      () => {
        const nameInput = document.getElementById("quick-kick-name");
        if (nameInput) attachPlayerAutocomplete(nameInput);
        document
          .getElementById("btn-submit-quick-kick")
          ?.addEventListener("click", async () => {
            const name = nameInput ? nameInput.value.trim() : "";
            const reason =
              document.getElementById("quick-kick-reason")?.value.trim() || "";
            if (!name) return;
            try {
              await api(
                "POST",
                `/api/players/${encodeURIComponent(name)}/action`,
                {
                  action: "kick",
                  reason,
                },
              );
              showToast(
                "Кик",
                `Игрок LoveWebAdmin кикнут с сервера`,
                "warning",
              );
              closeModal();
            } catch (e) {
              if (e.message && e.message.includes("не в сети")) {
                showToast(
                  "Игрок оффлайн",
                  `Игрок LoveWebAdmin не в сети или не найден`,
                  "warning",
                );
              } else {
                showToast(
                  "Ошибка",
                  e.message || "Не удалось кикнуть игрока",
                  "danger",
                );
              }
            }
          });
      },
    );
  };

  // Modal: Quick Teleport Spawn
}
export function exposeOpenQuickTeleportModal() {
  window.openQuickTeleportModal = function () {
    openModal(
      `
            <div class="modal-header">
                <h3>ТЕЛЕПОРТАЦИЯ НА СПАВН</h3>
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon("close", "gray", 16)}</button>
            </div>
            <div class="modal-body">
                <div class="form-group">
                    <label>Никнейм игрока онлайн</label>
                    <input type="text" id="quick-tp-name" placeholder="Введите ник игрока..." required autofocus autocomplete="off">
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                <button type="button" class="primary" id="btn-submit-quick-tp">ТЕЛЕПОРТИРОВАТЬ</button>
            </div>
        `,
      () => {
        const nameInput = document.getElementById("quick-tp-name");
        if (nameInput) attachPlayerAutocomplete(nameInput);
        document
          .getElementById("btn-submit-quick-tp")
          ?.addEventListener("click", async () => {
            const name = nameInput ? nameInput.value.trim() : "";
            if (!name) return;
            try {
              await api(
                "POST",
                `/api/players/${encodeURIComponent(name)}/action`,
                {
                  action: "teleport_spawn",
                },
              );
              showToast(
                "Телепортация",
                `Игрок LoveWebAdmin отправлен на спавн`,
                "info",
              );
              closeModal();
            } catch (e) {
              if (e.message && e.message.includes("не в сети")) {
                showToast(
                  "Игрок оффлайн",
                  `Игрок LoveWebAdmin не в сети или не найден`,
                  "warning",
                );
              } else {
                showToast(
                  "Ошибка",
                  e.message || "Не удалось телепортировать игрока",
                  "danger",
                );
              }
            }
          });
      },
    );
  };

  // Modal: Quick Vanish Modal
}
export function exposeOpenQuickVanishModal() {
  window.openQuickVanishModal = function () {
    openModal(
      `
            <div class="modal-header">
                <h3>РЕЖИМ НЕВИДИМОСТИ (VANISH)</h3>
                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">${renderSvgIcon("close", "gray", 16)}</button>
            </div>
            <div class="modal-body">
                <div class="form-group">
                    <label>Никнейм игрока онлайн</label>
                    <input type="text" id="quick-vanish-name" placeholder="Введите ник игрока..." required autofocus>
                </div>
                <div style="font-size:12px; color:var(--text-muted);">
                    Скрывает игрока от всех остальных пользователей на сервере (включая таб и радары).
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" onclick="window.closeCurrentModal()">ОТМЕНА</button>
                <button type="button" class="primary" id="btn-submit-quick-vanish">ПЕРЕКЛЮЧИТЬ VANISH</button>
            </div>
        `,
      () => {
        document
          .getElementById("btn-submit-quick-vanish")
          ?.addEventListener("click", async () => {
            const name = document
              .getElementById("quick-vanish-name")
              .value.trim();
            if (!name) return;
            try {
              await api(
                "POST",
                `/api/players/${encodeURIComponent(name)}/action`,
                {
                  action: "vanish",
                },
              );
              showToast(
                "Vanish",
                `Режим скрытности переключен для LoveWebAdmin`,
                "info",
              );
              closeModal();
            } catch (e) {
              if (e.message && e.message.includes("не в сети")) {
                showToast(
                  "Игрок оффлайн",
                  `Игрок LoveWebAdmin не в сети или не найден`,
                  "warning",
                );
              } else {
                showToast(
                  "Ошибка",
                  e.message || "Не удалось переключить Vanish",
                  "danger",
                );
              }
            }
          });
      },
    );
  };

  // Modal: Quick Mute Modal
}
export function exposeOpenQuickMuteModal() {
  window.openQuickMuteModal = function () {
    openModal(
      `
            <div class="modal-header">
                <h3>ЗАГЛУШИТЬ ИГРОКА (MUTE)</h3>
                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">${renderSvgIcon("close", "gray", 16)}</button>
            </div>
            <div class="modal-body">
                <div class="form-group">
                    <label>Никнейм игрока онлайн</label>
                    <input type="text" id="quick-mute-name" placeholder="Введите ник игрока..." required autofocus>
                </div>
                <div class="form-group">
                    <label>Длительность заглушения</label>
                    <select id="quick-mute-duration">
                        <option value="15m">15 минут</option>
                        <option value="30m">30 минут</option>
                        <option value="1h" selected>1 час</option>
                        <option value="2h">2 часа</option>
                        <option value="1d">1 день</option>
                        <option value="7d">7 дней</option>
                    </select>
                </div>
                <div class="form-group">
                    <label>Причина мута</label>
                    <input type="text" id="quick-mute-reason" value="Флуд / Спам / Нарушение правил чата">
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" onclick="window.closeCurrentModal()">ОТМЕНА</button>
                <button type="button" class="danger" id="btn-submit-quick-mute">ВЫДАТЬ МУТ</button>
            </div>
        `,
      () => {
        document
          .getElementById("btn-submit-quick-mute")
          ?.addEventListener("click", async () => {
            const name = document
              .getElementById("quick-mute-name")
              .value.trim();
            const duration = document.getElementById(
              "quick-mute-duration",
            ).value;
            const reason = document
              .getElementById("quick-mute-reason")
              .value.trim();
            if (!name) return;
            try {
              await api(
                "POST",
                `/api/players/${encodeURIComponent(name)}/action`,
                {
                  action: "mute",
                  duration,
                  reason,
                },
              );
              showToast(
                "Мут чата",
                `Игрок LoveWebAdmin заглушен на ${duration}`,
                "warning",
              );
              closeModal();
            } catch (e) {
              if (e.message && e.message.includes("не в сети")) {
                showToast(
                  "Игрок оффлайн",
                  `Игрок LoveWebAdmin не в сети или не найден`,
                  "warning",
                );
              } else {
                showToast(
                  "Ошибка",
                  e.message || "Не удалось выдать мут",
                  "danger",
                );
              }
            }
          });
      },
    );
  };

  // Start App
}
export function startInitialSession() {
  boot();
}

installInterfaceControls();

installCaseDialogs(openChildModal);
