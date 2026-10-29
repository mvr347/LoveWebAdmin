import { state } from "../state.js";
import {
  api,
  clearPollers,
  closeModal,
  esc,
  fmtDuration,
  fmtTime,
  openModal,
  renderSvgIcon,
  showToast,
} from "./ui.js";
// ==========================================================================
// THEME & APPEARANCE
// ==========================================================================

export function applyUserTheme(theme) {
  const isLight = theme === "light";
  document.body.classList.toggle("theme-light", isLight);
  const icon = document.getElementById("theme-btn-icon");
  if (icon)
    icon.innerHTML = renderSvgIcon(
      isLight ? "sun" : "moon",
      isLight ? "yellow" : "violet",
      15,
    );
  state.browserStorage.setItem("wa_theme", theme);
}

// ==========================================================================
// SHIFT STATUS MANAGEMENT («Я НА СМЕНЕ»)
// ==========================================================================

// ==========================================================================
// NOTIFICATIONS SYSTEM
// ==========================================================================
export async function updateNotificationsBadge() {
  try {
    const res = await api("GET", "/api/notifications/unread-count");
    const badge = document.getElementById("topbar-notif-badge");
    if (badge) {
      const count = res.unreadCount || 0;
      badge.textContent = count > 99 ? "99+" : count;
      badge.style.display = count > 0 ? "inline-flex" : "none";
    }
  } catch (_) {}
}
export async function openNotificationsModal() {
  openModal(
    `
            <div class="modal-header">
                <h3 style="display:flex; align-items:center; gap:8px;">${renderSvgIcon("bell", "violet", 18)} УВЕДОМЛЕНИЯ ПЕРСОНАЛА</h3>
                <div style="display:flex; gap:8px; align-items:center;">
                    <button type="button" class="secondary btn-sm" id="btn-create-notif">+ ОБЪЯВЛЕНИЕ</button>
                    <button type="button" class="secondary btn-sm" id="btn-read-all-notifs">ПРОЧИТАТЬ ВСЕ</button>
                    <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon("close", "gray", 16)}</button>
                </div>
            </div>
            <div class="modal-body" id="notifs-modal-body" style="max-height:65vh; overflow-y:auto;">
                <div style="text-align:center; padding:24px; color:var(--text-muted);">Загрузка уведомлений...</div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button>
            </div>
        `,
    async () => {
      const body = document.getElementById("notifs-modal-body");
      const loadList = async () => {
        try {
          const data = await api("GET", "/api/notifications?limit=50");
          const list = data.notifications || [];
          if (!list.length) {
            body.innerHTML = `
                            <div style="text-align:center; padding:32px; color:var(--text-dim);">
                                <div style="margin-bottom:8px;">${renderSvgIcon("bell", "gray", 32)}</div>
                                <div>Новых уведомлений для персонала нет</div>
                            </div>
                        `;
            return;
          }
          body.innerHTML = list
            .map((n) => {
              let badgeClass = "blue";
              let typeLabel = "ИНФО";
              if (n.type === "WARNING") {
                badgeClass = "yellow";
                typeLabel = "ПРЕДУПРЕЖДЕНИЕ";
              } else if (n.type === "IMPORTANT") {
                badgeClass = "purple";
                typeLabel = "ВАЖНО";
              } else if (n.type === "CRITICAL") {
                badgeClass = "red";
                typeLabel = "КРИТИЧНО";
              }
              return `
                            <div class="notif-item ${n.read ? "read" : "unread"}" style="background:var(--card-inner-bg); border:1px solid var(--border); border-radius:var(--radius-sm); padding:12px; margin-bottom:8px;">
                                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:4px;">
                                    <div style="display:flex; align-items:center; gap:8px;">
                                        <span class="badge ${badgeClass}">${typeLabel}</span>
                                        <b style="color:var(--text-main); font-size:13.5px;">${esc(n.title)}</b>
                                    </div>
                                    <span class="font-mono" style="font-size:11px; color:var(--text-dim);">${fmtTime(n.createdAt)}</span>
                                </div>
                                <div style="color:var(--text-secondary); font-size:12.5px; line-height:1.4; margin-top:4px;">
                                    ${esc(n.message)}
                                </div>
                                <div style="display:flex; justify-content:space-between; align-items:center; margin-top:8px; font-size:11px; color:var(--text-dim);">
                                    <span>От: <b>${esc(n.sender || "Система")}</b></span>
                                    ${
                                      !n.read
                                        ? `
                                        <button type="button" class="secondary btn-sm" onclick="window.markNotifRead(${n.id})" style="font-size:10px; padding:2px 6px; display:inline-flex; align-items:center; gap:4px;">
                                            ${renderSvgIcon("check", "gray", 11)} Прочитано
                                        </button>
                                    `
                                        : '<span style="color:var(--text-dim);">Прочитано</span>'
                                    }
                                </div>
                            </div>
                        `;
            })
            .join("");
        } catch (e) {
          body.innerHTML = `<div style="color:var(--red); padding:16px;">Ошибка загрузки уведомлений: ${esc(e.message)}</div>`;
        }
      };
      window.markNotifRead = async (id) => {
        try {
          await api("POST", `/api/notifications/${id}/read`);
          updateNotificationsBadge();
          loadList();
        } catch (_) {}
      };
      document
        .getElementById("btn-read-all-notifs")
        ?.addEventListener("click", async () => {
          try {
            await api("POST", "/api/notifications/read-all");
            updateNotificationsBadge();
            loadList();
            showToast(
              "Уведомления",
              "Все уведомления помечены как прочитанные",
              "info",
            );
          } catch (_) {}
        });
      document
        .getElementById("btn-create-notif")
        ?.addEventListener("click", openNewAnnouncementModal);
      await loadList();
    },
  );
}
export function openNewAnnouncementModal() {
  openModal(
    `
            <div class="modal-header">
                <h3>СОЗДАТЬ ОБЪЯВЛЕНИЕ ДЛЯ ПЕРСОНАЛА</h3>
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon("close", "gray", 16)}</button>
            </div>
            <div class="modal-body">
                <div class="form-group">
                    <label>Категория важности</label>
                    <select id="ann-type-select">
                        <option value="INFO">Инфо (Стандартное оповещение)</option>
                        <option value="WARNING">Предупреждение (Обратите внимание)</option>
                        <option value="IMPORTANT">Важно (Инструкция / Правила)</option>
                        <option value="CRITICAL">Критично (Срочные действия)</option>
                    </select>
                </div>
                <div class="form-group">
                    <label>Заголовок объявления</label>
                    <input type="text" id="ann-title" placeholder="Например: Собрание состава в Discord в 19:00" required>
                </div>
                <div class="form-group">
                    <label>Текст сообщения</label>
                    <textarea id="ann-message" rows="4" placeholder="Подробный текст сообщения для всех членов персонала..." style="width:100%;"></textarea>
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                <button type="button" class="primary" id="btn-submit-announcement">ОТПРАВИТЬ ОБЪЯВЛЕНИЕ</button>
            </div>
        `,
    () => {
      document
        .getElementById("btn-submit-announcement")
        ?.addEventListener("click", async () => {
          const type = document.getElementById("ann-type-select").value;
          const title = document.getElementById("ann-title").value.trim();
          const message = document.getElementById("ann-message").value.trim();
          if (!title || !message) {
            showToast(
              "Ошибка",
              "Заполните заголовок и текст сообщения",
              "warning",
            );
            return;
          }
          try {
            await api("POST", "/api/notifications", {
              type,
              title,
              message,
            });
            showToast(
              "Объявление отправлено",
              "Сообщение доставлено всем сотрудникам",
              "success",
            );
            closeModal();
            updateNotificationsBadge();
          } catch (e) {
            showToast("Ошибка отправки", e.message, "error");
          }
        });
    },
  );
}

// ==========================================================================
// MAINTENANCE MODE SCREEN
// ==========================================================================
export function renderMaintenanceScreen(message) {
  clearPollers();
  state.app.innerHTML = `
            <div class="maintenance-screen">
                <div class="maintenance-card">
                    <div class="maintenance-icon" style="display:flex; justify-content:center; margin-bottom:16px;">${renderSvgIcon("wrench", "violet", 48)}</div>
                    <h2>ВЕДУТСЯ ТЕХНИЧЕСКИЕ РАБОТЫ</h2>
                    <p class="maintenance-msg">${esc(message || "Веб-панель временно закрыта на плановое обслуживание сервера. Доступ разрешён только Управляющему.")}</p>
                    <div style="font-size:12px; color:var(--text-dim); margin-bottom:18px;">
                        Если вы являетесь Управляющим или имеете право обхода тех. работ, войдите с подтверждением.
                    </div>
                    <div style="display:flex; gap:10px; justify-content:center;">
                        <button type="button" class="primary" onclick="location.reload()" style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon("refresh", "white", 14)} ПРОВЕРИТЬ СНОВА</button>
                        <button type="button" class="secondary" onclick="browserStorage.removeItem('wa_token'); location.reload();">ВЫЙТИ В ВХОД</button>
                    </div>
                </div>
            </div>
        `;
}

// ==========================================================================
// UNIFIED ADMIN SETTINGS MODAL (REAL SETTINGS & ICONS)
// ==========================================================================
