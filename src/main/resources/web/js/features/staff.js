import { noticeDialog } from "../core/dialogs.js";
import { state } from "../state.js";
import {
  api,
  attachPlayerAutocomplete,
  closeModal,
  confirmAction,
  esc,
  fmtTime,
  openModal,
  renderSvgIcon,
  showToast,
} from "./ui.js";
// ==========================================================================
// 7. ПЕРСОНАЛ И РОЛИ (СОТРУДНИКИ, ПРИГЛАШЕНИЯ, ИСПЫТАТЕЛЬНЫЙ СРОК, РОЛИ)
// ==========================================================================

export async function renderStaffView() {
  const area = document.getElementById("content-area");
  let sub = "personnel"; // personnel | roles
  let currentFilter = "active"; // active | invites | review

  area.innerHTML = `
            <div class="view-header">
                <div class="view-title-block">
                    <h2>ПЕРСОНАЛ</h2>
                    <p>Сотрудники, приглашения и роли веб-панели</p>
                </div>
                <div class="view-actions" id="staff-header-actions">
                    <button type="button" class="primary" id="btn-add-staff">+ ДОБАВИТЬ СОТРУДНИКА</button>
                </div>
            </div>
            <div class="filter-tags" id="staff-sub-tabs" style="margin-bottom:18px;">
                <button type="button" class="filter-tag-btn active" data-sub="personnel">Персонал</button>
                <button type="button" class="filter-tag-btn" data-sub="roles">Роли</button>
            </div>
            <div id="staff-tab-body"></div>
        `;
  let rolesCache = [];
  let adminsCache = [];
  let invitesCache = [];
  const mount = () => {
    const headerActions = document.getElementById("staff-header-actions");
    if (headerActions) {
      if (sub === "personnel") {
        headerActions.innerHTML = `<button type="button" class="primary" id="btn-add-staff">+ ДОБАВИТЬ СОТРУДНИКА</button>`;
        document
          .getElementById("btn-add-staff")
          ?.addEventListener("click", openInviteWizardModal);
      } else {
        headerActions.innerHTML = `<button type="button" class="primary" id="btn-create-role">+ СОЗДАТЬ РОЛЬ</button>`;
        document
          .getElementById("btn-create-role")
          ?.addEventListener("click", openCreateRoleModal);
      }
    }
    if (sub === "personnel") {
      renderPersonnelTab();
    } else {
      renderRolesTab();
    }
  };
  document.getElementById("staff-sub-tabs")?.addEventListener("click", (e) => {
    const btn = e.target.closest("[data-sub]");
    if (!btn) return;
    sub = btn.dataset.sub;
    document
      .querySelectorAll("#staff-sub-tabs .filter-tag-btn")
      .forEach((b) => b.classList.toggle("active", b.dataset.sub === sub));
    mount();
  });

  // ---------------- Personnel Tab ----------------
  async function renderPersonnelTab() {
    const container = document.getElementById("staff-tab-body");
    if (!container) return;
    container.innerHTML = `
                <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:12px; margin-bottom:16px;">
                    <div class="filter-tags" id="personnel-filter-tags">
                        <button type="button" class="filter-tag-btn ${currentFilter === "active" ? "active" : ""}" data-filter="active">
                            Активные сотрудники (<span id="count-active">0</span>)
                        </button>
                        <button type="button" class="filter-tag-btn ${currentFilter === "invites" ? "active" : ""}" data-filter="invites">
                            Приглашения (<span id="count-invites">0</span>)
                        </button>
                        <button type="button" class="filter-tag-btn ${currentFilter === "review" ? "active" : ""}" data-filter="review">
                            Требуют решения (<span id="count-review">0</span>)
                        </button>
                    </div>
                    <button type="button" class="secondary btn-sm" id="btn-refresh-staff">
                        ${renderSvgIcon("refresh", "gray", 13)} Обновить
                    </button>
                </div>
                <div id="personnel-table-container">
                    <div style="text-align:center; padding:30px; color:var(--text-muted);">Загрузка данных...</div>
                </div>
            `;
    document
      .getElementById("personnel-filter-tags")
      ?.addEventListener("click", (e) => {
        const btn = e.target.closest("[data-filter]");
        if (!btn) return;
        currentFilter = btn.dataset.filter;
        document
          .querySelectorAll("#personnel-filter-tags .filter-tag-btn")
          .forEach((b) =>
            b.classList.toggle("active", b.dataset.filter === currentFilter),
          );
        renderPersonnelList();
      });
    document
      .getElementById("btn-refresh-staff")
      ?.addEventListener("click", loadPersonnelData);
    await loadPersonnelData();
  }
  async function loadPersonnelData() {
    try {
      const [admins, roles, invites] = await Promise.all([
        api("GET", "/api/admins"),
        api("GET", "/api/roles"),
        api("GET", "/api/admins/invites").catch(() => []),
      ]);
      adminsCache = admins;
      rolesCache = roles;
      invitesCache = invites;
      const activeCount = admins.filter((a) => a.status === "ACTIVE").length;
      const invitesCount = invites.length;
      const reviewCount = admins.filter((a) => {
        const expiry = a.roleExpiresAt || 0;
        const expired = expiry > 0 && expiry <= Math.floor(Date.now() / 1000);
        return a.pendingRoleReview || expired;
      }).length;
      const cAct = document.getElementById("count-active");
      const cInv = document.getElementById("count-invites");
      const cRev = document.getElementById("count-review");
      if (cAct) cAct.textContent = activeCount;
      if (cInv) cInv.textContent = invitesCount;
      if (cRev) cRev.textContent = reviewCount;
      renderPersonnelList();
    } catch (e) {
      const tc = document.getElementById("personnel-table-container");
      if (tc)
        tc.innerHTML = `<div style="text-align:center; color:var(--red); padding:20px;">Ошибка загрузки: ${esc(e.message)}</div>`;
    }
  }
  function renderPersonnelList() {
    const container = document.getElementById("personnel-table-container");
    if (!container) return;
    if (currentFilter === "invites") {
      if (!invitesCache.length) {
        container.innerHTML = `
                        <div style="text-align:center; padding:40px 20px; background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); color:var(--text-muted);">
                            <div style="margin-bottom:8px;">${renderSvgIcon("mail", "gray", 28)}</div>
                            <div style="font-size:14px; font-weight:600; color:var(--text-main);">Нет активных приглашений</div>
                            <div style="font-size:12px; margin-top:4px;">Нажмите «+ Добавить сотрудника», чтобы создать новый код приглашения</div>
                        </div>
                    `;
        return;
      }
      container.innerHTML = `
                    <div class="table-wrap" style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md);">
                        <table>
                            <thead>
                                <tr>
                                    <th>СОТРУДНИК</th>
                                    <th>РОЛЬ</th>
                                    <th>КОД ПРИГЛАШЕНИЯ</th>
                                    <th>КЕМ СОЗДАН</th>
                                    <th>ДЕЙСТВИТЕЛЕН ДО</th>
                                    <th style="text-align:right;">ДЕЙСТВИЯ</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${invitesCache
                                  .map((inv) => {
                                    const code = inv.code || "";
                                    const masked =
                                      code.length > 4
                                        ? code.slice(0, 4) +
                                          "••••" +
                                          code.slice(-4)
                                        : code;
                                    const roleObj = rolesCache.find(
                                      (r) => r.id === inv.roleId,
                                    );
                                    const roleName =
                                      inv.roleName ||
                                      (roleObj
                                        ? roleObj.name
                                        : `Роль #${inv.roleId}`);
                                    const roleColor =
                                      (roleObj && roleObj.color) || "#6366f1";
                                    const expiry = inv.expiresAt
                                      ? fmtTime(inv.expiresAt)
                                      : "7 дней";
                                    return `
                                        <tr>
                                            <td>
                                                <div style="display:flex; align-items:center; gap:8px;">
                                                    <img src="https://mc-heads.net/avatar/${encodeURIComponent(inv.username || "")}/24" class="player-avatar-sm" alt="" style="width:24px; height:24px; border-radius:4px;">
                                                    <b style="color:var(--text-main);">${esc(inv.username)}</b>
                                                </div>
                                            </td>
                                            <td>
                                                <span class="badge" style="background:${roleColor}22; color:${roleColor}; border:1px solid ${roleColor}44;">
                                                    ${esc(roleName)}
                                                </span>
                                            </td>
                                            <td>
                                                <span class="font-mono invite-code-preview" data-full="${esc(code)}" data-masked="${esc(masked)}" title="Нажмите, чтобы показать/скрыть" style="cursor:pointer; font-weight:700; color:var(--accent);">
                                                    ${esc(masked)}
                                                </span>
                                            </td>
                                            <td style="font-size:12px; color:var(--text-muted);">${esc(inv.createdBy || "—")}</td>
                                            <td style="font-size:12px; color:var(--text-dim);">${expiry}</td>
                                            <td style="text-align:right;">
                                                <button type="button" class="danger btn-sm" onclick="window.cancelStaffInvite(${inv.id}, '${esc(inv.username)}')">
                                                    ОТМЕНИТЬ
                                                </button>
                                            </td>
                                        </tr>
                                    `;
                                  })
                                  .join("")}
                            </tbody>
                        </table>
                    </div>
                `;
      container.querySelectorAll(".invite-code-preview").forEach((el) => {
        let revealed = false;
        el.addEventListener("click", () => {
          revealed = !revealed;
          el.textContent = revealed ? el.dataset.full : el.dataset.masked;
        });
      });
      return;
    }

    // Filter admins: active or review
    let filteredAdmins = adminsCache.filter((a) => a.status === "ACTIVE");
    if (currentFilter === "review") {
      filteredAdmins = filteredAdmins.filter((a) => {
        const expiry = a.roleExpiresAt || 0;
        const expired = expiry > 0 && expiry <= Math.floor(Date.now() / 1000);
        return a.pendingRoleReview || expired;
      });
    }
    if (!filteredAdmins.length) {
      container.innerHTML = `
                    <div style="text-align:center; padding:40px 20px; background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); color:var(--text-muted);">
                        <div style="font-size:14px; font-weight:600; color:var(--text-main);">
                            ${currentFilter === "review" ? "Нет сотрудников, требующих решения по испытательному сроку" : "Нет активных сотрудников"}
                        </div>
                    </div>
                `;
      return;
    }
    container.innerHTML = `
                <div class="table-wrap" style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md);">
                    <table>
                        <thead>
                            <tr>
                                <th>СОТРУДНИК</th>
                                <th>ТЕКУЩАЯ РОЛЬ</th>
                                <th>2FA ЗАЩИТА</th>
                                <th>СРОК РОЛИ</th>
                                <th>ПОСЛЕДНИЙ ВХОД</th>
                                <th style="text-align:right;">ДЕЙСТВИЯ</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${filteredAdmins
                              .map((a) => {
                                const roleObj = rolesCache.find(
                                  (r) => r.id === a.roleId,
                                ) || {
                                  name: "Неизвестно",
                                  color: "#6366f1",
                                };
                                const roleColor =
                                  roleObj.color ||
                                  (roleObj.isOwner ? "#a855f7" : "#6366f1");
                                const hasTotp = a.totpEnabled;
                                const isSelf =
                                  state.me &&
                                  (state.me.username === a.username ||
                                    state.me.id === a.id);
                                const hideChangeRole =
                                  isSelf &&
                                  (Boolean(state.me?.isOwner) ||
                                    Boolean(roleObj.isOwner));
                                const expirySec = a.roleExpiresAt || 0;
                                const isExpired =
                                  expirySec > 0 &&
                                  expirySec <= Math.floor(Date.now() / 1000);
                                const isPendingReview =
                                  a.pendingRoleReview || isExpired;
                                let expiryHtml = "Бессрочно";
                                if (isPendingReview) {
                                  expiryHtml = `<span class="badge badge-review">Срок истёк</span>`;
                                } else if (expirySec > 0) {
                                  expiryHtml = `<span style="color:var(--text-muted); font-size:12px;">до ${fmtTime(expirySec)}</span>`;
                                }
                                return `
                                    <tr>
                                        <td>
                                            <div style="display:flex; align-items:center; gap:8px;">
                                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(a.username || "")}/26" class="player-avatar-sm" alt="" style="width:26px; height:26px; border-radius:4px;">
                                                <div>
                                                    <b style="color:var(--text-main);">${esc(a.username)}</b>
                                                    ${isSelf ? '<span class="badge gray" style="font-size:10px; margin-left:4px;">ВЫ</span>' : ""}
                                                </div>
                                            </div>
                                        </td>
                                        <td>
                                            <span class="badge" style="background:${roleColor}22; color:${roleColor}; border:1px solid ${roleColor}44;">
                                                ${esc(roleObj.name)}
                                            </span>
                                        </td>
                                        <td>
                                            <span class="badge ${hasTotp ? "green" : "yellow"}" style="display:inline-flex; align-items:center; gap:4px;">
                                                ${hasTotp ? renderSvgIcon("check", "green", 11) + " 2FA ВКЛ" : "НЕТ 2FA"}
                                            </span>
                                        </td>
                                        <td>${expiryHtml}</td>
                                        <td class="font-mono" style="font-size:12px; color:var(--text-dim);">${fmtTime(a.lastLoginAt)}</td>
                                        <td style="text-align:right;">
                                            <div style="display:flex; justify-content:flex-end; gap:6px; flex-wrap:wrap;">
                                                ${
                                                  isPendingReview
                                                    ? `
                                                    <button type="button" class="primary btn-sm" onclick="window.promoteToModerator(${a.id}, '${esc(a.username)}')">СДЕЛАТЬ МОДЕРАТОРОМ</button>
                                                    <button type="button" class="secondary btn-sm" onclick="window.keepCurrentRole(${a.id}, '${esc(a.username)}')">ОСТАВИТЬ</button>
                                                    <button type="button" class="danger btn-sm" onclick="window.revokeStaffAccess(${a.id}, '${esc(a.username)}')">СНЯТЬ ДОСТУП</button>
                                                `
                                                    : `
                                                    ${
                                                      !hideChangeRole
                                                        ? `
                                                        <button type="button" class="secondary btn-sm" onclick="window.openEditAdminRoleModal(${a.id}, '${esc(a.username)}', ${a.roleId})">РОЛЬ</button>
                                                    `
                                                        : ""
                                                    }
                                                    <button type="button" class="secondary btn-sm" onclick="window.resetStaffPassword(${a.id}, '${esc(a.username)}')">СБРОС ПАРОЛЯ</button>
                                                    <button type="button" class="danger btn-sm" onclick="window.terminateStaffSessions(${a.id}, '${esc(a.username)}')">СБРОС СЕССИЙ</button>
                                                `
                                                }
                                            </div>
                                        </td>
                                    </tr>
                                `;
                              })
                              .join("")}
                        </tbody>
                    </table>
                </div>
            `;
  }

  // ---------------- Roles Tab ----------------
  async function renderRolesTab() {
    const container = document.getElementById("staff-tab-body");
    if (!container) return;
    container.innerHTML = `<div style="text-align:center; padding:30px; color:var(--text-muted);">Загрузка ролей...</div>`;
    try {
      const [roles, admins] = await Promise.all([
        api("GET", "/api/roles"),
        api("GET", "/api/admins"),
      ]);
      rolesCache = roles;
      adminsCache = admins;

      // Category order: OWNER -> ADMIN -> MOD -> PROBATION -> CUSTOM
      const catOrder = ["OWNER", "ADMIN", "MOD", "PROBATION", "CUSTOM"];
      const catLabels = {
        OWNER: "Управляющие (OWNER)",
        ADMIN: "Администраторы (ADMIN)",
        MOD: "Модераторы (MOD)",
        PROBATION: "Испытательный срок (PROBATION)",
        CUSTOM: "Пользовательские роли (CUSTOM)",
      };
      const grouped = {};
      catOrder.forEach((c) => {
        grouped[c] = [];
      });
      roles.forEach((r) => {
        let c = (r.category || "").toUpperCase();
        if (!catOrder.includes(c)) c = "CUSTOM";
        grouped[c].push(r);
      });
      container.innerHTML = `
                    <div style="display:flex; flex-direction:column; gap:24px;">
                        ${catOrder
                          .map((cat) => {
                            const list = grouped[cat];
                            if (!list || !list.length) return "";
                            return `
                                <div>
                                    <div style="display:flex; align-items:center; gap:8px; margin-bottom:12px;">
                                        <h3 style="font-size:14px; font-weight:700; color:var(--text-heading); margin:0;">${esc(catLabels[cat] || cat)}</h3>
                                        <span class="badge gray" style="font-size:11px;">${list.length}</span>
                                    </div>
                                    <div style="display:grid; grid-template-columns:repeat(auto-fill, minmax(320px, 1fr)); gap:14px;">
                                        ${list
                                          .map((r) => {
                                            const rCol =
                                              r.color ||
                                              (r.isOwner
                                                ? "#a855f7"
                                                : "#8b5cf6");
                                            const assignedAdmins =
                                              admins.filter(
                                                (a) => a.roleId === r.id,
                                              );
                                            const hasAdmins =
                                              assignedAdmins.length > 0;
                                            const canDelete =
                                              !r.isOwner && !hasAdmins;

                                            // Description rule: if empty or equals name, don't show duplicate; if empty show "Без описания"
                                            let descText = (
                                              r.description || ""
                                            ).trim();
                                            let isBlank = false;
                                            if (
                                              !descText ||
                                              descText.toLowerCase() ===
                                                r.name.toLowerCase()
                                            ) {
                                              descText = "Без описания";
                                              isBlank = true;
                                            }
                                            const permsCount = (
                                              r.permissions || []
                                            ).length;
                                            const lpText = r.lpGroup
                                              ? ` · LP: ${esc(r.lpGroup)}`
                                              : "";
                                            return `
                                                <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:16px; display:flex; flex-direction:column; justify-content:space-between; gap:12px;">
                                                    <div>
                                                        <div style="display:flex; justify-content:space-between; align-items:flex-start; gap:8px; margin-bottom:6px;">
                                                            <div style="display:flex; align-items:center; gap:8px;">
                                                                <span style="display:inline-block; width:12px; height:12px; border-radius:50%; background:${esc(rCol)}; box-shadow:0 0 6px ${esc(rCol)};"></span>
                                                                <h4 style="margin:0; font-size:15px; color:var(--text-heading);">${esc(r.name)}</h4>
                                                            </div>
                                                            <span class="badge" style="background:${rCol}22; color:${rCol}; border:1px solid ${rCol}44; font-size:10.5px;">
                                                                ${r.isOwner ? "ВЛАДЕЛЕЦ" : esc(r.category || "РОЛЬ")}
                                                            </span>
                                                        </div>
                                                        <p style="font-size:12px; color:${isBlank ? "var(--text-dim)" : "var(--text-muted)"}; margin:4px 0 10px 0; line-height:1.4;">
                                                            ${esc(descText)}
                                                        </p>
                                                        <div style="font-size:11.5px; color:var(--text-muted); display:flex; align-items:center; gap:6px;">
                                                            <span><b>${permsCount}</b> прав</span>${lpText}
                                                            <span style="color:var(--text-dim);">•</span>
                                                            <span>${assignedAdmins.length} сотр.</span>
                                                        </div>
                                                    </div>
                                                    <div style="display:flex; justify-content:flex-end; gap:8px; border-top:1px solid var(--border); padding-top:10px;">
                                                        ${
                                                          !r.isOwner
                                                            ? `
                                                            <button type="button" class="secondary btn-sm" onclick="window.openEditRoleModal(${r.id})">РЕДАКТИРОВАТЬ</button>
                                                            <button type="button" class="danger btn-sm" ${!canDelete ? 'disabled title="Нельзя удалить: к роли привязаны сотрудники"' : ""} onclick="window.deleteRole(${r.id}, '${esc(r.name)}')">
                                                                УДАЛИТЬ
                                                            </button>
                                                        `
                                                            : `
                                                            <span style="font-size:11.5px; color:var(--text-dim); padding:4px 0;">Полный доступ (системная роль)</span>
                                                        `
                                                        }
                                                    </div>
                                                </div>
                                            `;
                                          })
                                          .join("")}
                                    </div>
                                </div>
                            `;
                          })
                          .join("")}
                    </div>
                `;
    } catch (e) {
      container.innerHTML = `<div style="text-align:center; color:var(--red); padding:20px;">Ошибка загрузки ролей: ${esc(e.message)}</div>`;
    }
  }

  // ---------------- Invite Creation Wizard ----------------
  function openInviteWizardModal() {
    const availableRoles = rolesCache
      .filter((r) => !r.isOwner)
      .sort((a, b) => (a.sortOrder || 99) - (b.sortOrder || 99));
    openModal(
      `
                <div class="modal-header">
                    <h3>ПРИГЛАШЕНИЕ СОТРУДНИКА</h3>
                    <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon("close", "gray", 16)}</button>
                </div>
                <div class="modal-body" id="invite-wizard-body">
                    <!-- Step 1 -->
                    <div id="invite-step-1">
                        <div class="form-group">
                            <label>Никнейм сотрудника в Minecraft *</label>
                            <input id="invite-username" placeholder="Например: Steve" autocomplete="username" autofocus required>
                        </div>
                        <div class="form-group">
                            <label>Назначаемая роль *</label>
                            <select id="invite-role">
                                ${availableRoles
                                  .map(
                                    (r) => `
                                    <option value="${r.id}">${esc(r.name)} (${esc(r.category || "Роль")})</option>
                                `,
                                  )
                                  .join("")}
                            </select>
                        </div>
                        <div class="form-group">
                            <label>Испытательный срок</label>
                            <select id="invite-probation">
                                <option value="0">Без срока (постоянный доступ)</option>
                                <option value="7">7 дней (испытательный срок)</option>
                                <option value="14">14 дней</option>
                                <option value="30">30 дней</option>
                            </select>
                        </div>
                        <div id="invite-err" class="error" style="display:none; margin-bottom:12px;"></div>
                    </div>
                    <!-- Step 2 -->
                    <div id="invite-step-2" style="display:none;"></div>
                </div>
                <div class="modal-footer" id="invite-wizard-footer">
                    <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                    <button type="button" class="primary" id="invite-next">ДАЛЕЕ</button>
                </div>
            `,
      () => {
        const uInput = document.getElementById("invite-username");
        if (uInput) attachPlayerAutocomplete(uInput);
        document
          .getElementById("invite-next")
          ?.addEventListener("click", async () => {
            const username = document
              .getElementById("invite-username")
              .value.trim();
            const roleId = parseInt(
              document.getElementById("invite-role").value,
              10,
            );
            const probationDays =
              parseInt(document.getElementById("invite-probation").value, 10) ||
              0;
            const errEl = document.getElementById("invite-err");
            const nextBtn = document.getElementById("invite-next");
            if (!username) {
              if (errEl) {
                errEl.textContent = "Укажите никнейм сотрудника";
                errEl.style.display = "block";
              }
              return;
            }
            if (errEl) errEl.style.display = "none";
            nextBtn.disabled = true;
            nextBtn.textContent = "СОЗДАНИЕ...";
            try {
              const res = await api("POST", "/api/admins/invites", {
                username,
                roleId,
                probationDays,
              });
              const code = res.code || "";
              const inviteId = res.id;

              // Switch to Step 2
              document.getElementById("invite-step-1").style.display = "none";
              const step2 = document.getElementById("invite-step-2");
              step2.style.display = "block";
              step2.innerHTML = `
                            <div class="invite-code-box">
                                <div class="code-label">Код для сотрудника ${esc(username)}</div>
                                <div class="code-value" id="invite-code-display">${esc(code)}</div>
                                <button type="button" class="primary" id="copy-invite-code" style="margin-bottom:14px; display:inline-flex; align-items:center; gap:6px;">
                                    ${renderSvgIcon("copy", "white", 14)} Скопировать код
                                </button>
                                <p style="font-size:12px; color:var(--text-muted); line-height:1.4; margin-bottom:16px;">
                                    Передайте этот одноразовый код сотруднику лично. Сотрудник вводит его на экране «Регистрация по приглашению» для создания пароля и привязки 2FA.
                                </p>
                                <button type="button" id="cancel-invite-btn" class="danger btn-sm">
                                    Отменить приглашение
                                </button>
                            </div>
                        `;
              document.getElementById("invite-wizard-footer").innerHTML = `
                            <button type="button" class="primary" data-modal-close="true">ГОТОВО</button>
                        `;
              document
                .getElementById("copy-invite-code")
                ?.addEventListener("click", async () => {
                  try {
                    if (navigator.clipboard && navigator.clipboard.writeText) {
                      await navigator.clipboard.writeText(code);
                    } else {
                      const ta = document.createElement("textarea");
                      ta.value = code;
                      document.body.appendChild(ta);
                      ta.select();
                      document.execCommand("copy");
                      document.body.removeChild(ta);
                    }
                    const btn = document.getElementById("copy-invite-code");
                    if (btn) {
                      btn.innerHTML = `${renderSvgIcon("check", "green", 14)} Код скопирован!`;
                      setTimeout(() => {
                        if (btn)
                          btn.innerHTML = `${renderSvgIcon("copy", "white", 14)} Скопировать код`;
                      }, 2000);
                    }
                  } catch (_) {}
                });
              document
                .getElementById("cancel-invite-btn")
                ?.addEventListener("click", async () => {
                  try {
                    await api("DELETE", `/api/admins/invites/${inviteId}`);
                    showToast(
                      "Приглашение отменено",
                      `Код для ${username} отозван`,
                      "info",
                    );
                    closeModal();
                    loadPersonnelData();
                  } catch (e) {
                    await noticeDialog(
                      "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
                      "Ошибка отмены: " + e.message,
                    );
                  }
                });
              loadPersonnelData();
            } catch (e) {
              if (errEl) {
                errEl.textContent = e.message;
                errEl.style.display = "block";
              }
              nextBtn.disabled = false;
              nextBtn.textContent = "ДАЛЕЕ";
            }
          });
      },
    );
  }

  // ---------------- Staff Actions ----------------
  window.cancelStaffInvite = (inviteId, username) => {
    confirmAction(
      "ОТМЕНА ПРИГЛАШЕНИЯ",
      `Отозвать приглашение для сотрудника ${username}? Код станет недействительным.`,
      async () => {
        try {
          await api("DELETE", `/api/admins/invites/${inviteId}`);
          showToast(
            "Приглашение отменено",
            `Приглашение для ${username} успешно отменено`,
            "info",
          );
          loadPersonnelData();
        } catch (e) {
          await noticeDialog(
            "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
            "Ошибка отмены: " + e.message,
          );
        }
      },
      "ОТОЗВАТЬ",
    );
  };
  window.promoteToModerator = async (adminId, username) => {
    // Find Moderator role (MOD category or name 'Модератор')
    const modRole = rolesCache.find(
      (r) =>
        (r.category || "").toUpperCase() === "MOD" ||
        r.name.toLowerCase().includes("модератор"),
    );
    if (!modRole) {
      await noticeDialog(
        "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
        "Роль Модератора не найдена в системе",
      );
      return;
    }
    try {
      await api("PUT", `/api/admins/${adminId}/role`, {
        roleId: modRole.id,
        expiresAt: 0,
        clearPendingRoleReview: true,
      });
      showToast(
        "Сотрудник переведён",
        `${username} успешно назначен на должность «${modRole.name}»`,
        "success",
      );
      loadPersonnelData();
    } catch (e) {
      await noticeDialog(
        "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
        "Ошибка назначения: " + e.message,
      );
    }
  };
  window.keepCurrentRole = async (adminId, username) => {
    const admin = adminsCache.find((a) => a.id === adminId);
    if (!admin) return;
    try {
      await api("PUT", `/api/admins/${adminId}/role`, {
        roleId: admin.roleId,
        expiresAt: 0,
        clearPendingRoleReview: true,
      });
      showToast(
        "Статус подтвержден",
        `Сотрудник ${username} оставлен в текущей должности на постоянной основе`,
        "info",
      );
      loadPersonnelData();
    } catch (e) {
      await noticeDialog(
        "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
        "Ошибка: " + e.message,
      );
    }
  };
  window.revokeStaffAccess = (adminId, username) => {
    confirmAction(
      "СНЯТИЕ ДОСТУПА",
      `Снять доступ к панели для сотрудника ${username}? Учетная запись будет деактивирована.`,
      async () => {
        try {
          await api("DELETE", `/api/admins/${adminId}`);
          showToast(
            "Доступ снят",
            `Сотрудник ${username} удален из панели`,
            "info",
          );
          loadPersonnelData();
        } catch (e) {
          await noticeDialog(
            "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
            "Ошибка: " + e.message,
          );
        }
      },
      "СНЯТЬ ДОСТУП",
    );
  };
  window.openEditAdminRoleModal = async (adminId, username, currentRoleId) => {
    const admin = adminsCache.find((a) => a.id === adminId);
    const currentRole = rolesCache.find((r) => r.id === currentRoleId);
    if (
      currentRole &&
      currentRole.isOwner &&
      state.me &&
      state.me.isOwner &&
      (state.me.username === username || state.me.id === adminId)
    ) {
      await noticeDialog(
        "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
        "Управляющий не может изменить собственную роль",
      );
      return;
    }
    openModal(
      `
                <div class="modal-header">
                    <h3>НАЗНАЧЕНИЕ РОЛИ: ${esc(username)}</h3>
                    <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon("close", "gray", 16)}</button>
                </div>
                <div class="modal-body">
                    <div class="form-group">
                        <label>Выберите роль</label>
                        <select id="edit-admin-role-select">
                            ${rolesCache
                              .filter((r) => !r.isOwner)
                              .map(
                                (r) => `
                                <option value="${r.id}" ${r.id === currentRoleId ? "selected" : ""}>${esc(r.name)} (${esc(r.category || "Роль")})</option>
                            `,
                              )
                              .join("")}
                        </select>
                    </div>

                    <div class="form-group">
                        <label>Временная роль (испытательный срок)?</label>
                        <select id="edit-admin-temp-select">
                            <option value="0">Бессрочно (постоянный доступ)</option>
                            <option value="7">Испытательный срок 7 дней</option>
                            <option value="14">Испытательный срок 14 дней</option>
                            <option value="30">Испытательный срок 30 дней</option>
                        </select>
                    </div>
                </div>
                <div class="modal-footer">
                    <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                    <button type="button" class="primary" id="btn-save-admin-role">СОХРАНИТЬ</button>
                </div>
            `,
      () => {
        document
          .getElementById("btn-save-admin-role")
          ?.addEventListener("click", async () => {
            const roleId = parseInt(
              document.getElementById("edit-admin-role-select").value,
              10,
            );
            const days =
              parseInt(
                document.getElementById("edit-admin-temp-select").value,
                10,
              ) || 0;
            const expiresAt =
              days > 0 ? Math.floor(Date.now() / 1000) + days * 86400 : 0;
            try {
              await api("PUT", `/api/admins/${adminId}/role`, {
                roleId,
                expiresAt,
                clearPendingRoleReview: true,
              });
              showToast(
                "Роль обновлена",
                `Роль сотрудника ${username} успешно изменена`,
                "success",
              );
              closeModal();
              loadPersonnelData();
            } catch (e) {
              await noticeDialog(
                "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
                "Ошибка: " + e.message,
              );
            }
          });
      },
    );
  };
  window.resetStaffPassword = (adminId, username) => {
    confirmAction(
      "СБРОС ПАРОЛЯ",
      `Сбросить пароль для сотрудника ${username}? При следующем входе система потребует задать новый пароль.`,
      async () => {
        try {
          await api("DELETE", `/api/admins/${adminId}/password`);
          showToast("Пароль сброшен", `Пароль для ${username} сброшен`, "info");
        } catch (e) {
          await noticeDialog(
            "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
            "Ошибка сброса: " + e.message,
          );
        }
      },
      "СБРОСИТЬ",
    );
  };
  window.terminateStaffSessions = (adminId, username) => {
    confirmAction(
      "СБРОС СЕССИЙ СОТРУДНИКА",
      `Принудительно завершить все сессии сотрудника ${username}? Пользователь будет немедленно разлогинен на всех устройствах.`,
      async () => {
        try {
          await api("POST", `/api/admins/${adminId}/terminate-sessions`);
          showToast(
            "Сессии завершены",
            `Все сессии ${username} успешно аннулированы`,
            "info",
          );
          loadPersonnelData();
        } catch (e) {
          showToast("Ошибка", e.message, "error");
        }
      },
      "СБРОСИТЬ ВСЕ",
    );
  };

  // Helper to render permissions checkboxes
  const renderPermCheckboxes = (activePerms = []) => {
    return state.ALL_PERMISSIONS.map((p) => {
      const has = activePerms.includes(p);
      const desc = state.PERMISSION_LABELS[p] || p;
      const displayName = state.PERMISSION_NAMES[p] || p;
      return `
                    <label style="display:flex; align-items:flex-start; gap:10px; padding:10px 12px; background:var(--card-bg); border:1px solid var(--border); border-radius:5px; cursor:pointer;" title="${esc(desc)}">
                        <input type="checkbox" class="role-perm-cb" value="${p}" ${has ? "checked" : ""} style="width:16px; height:16px; accent-color:var(--accent); margin-top:2px;">
                        <div>
                            <div style="font-size:12.5px; font-weight:700; color:var(--text-heading);">${esc(displayName)}</div>
                            <div style="font-size:11.5px; color:var(--text-muted); line-height:1.35; margin-top:3px;">${esc(desc)}</div>
                        </div>
                    </label>
                `;
    }).join("");
  };

  // Modal: Create Role
  function openCreateRoleModal() {
    openModal(
      `
                <div class="modal-header">
                    <h3>СОЗДАНИЕ НОВОЙ РОЛИ</h3>
                    <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon("close", "gray", 16)}</button>
                </div>
                <div class="modal-body" style="max-height:75vh; overflow-y:auto;">
                    <div class="form-group">
                        <label>Название роли *</label>
                        <input type="text" id="create-role-name" placeholder="Например: Старший Модератор" required autofocus>
                    </div>
                    <div class="form-group">
                        <label>Описание роли (кратко о правах и назначении)</label>
                        <input type="text" id="create-role-desc" placeholder="Например: Проверка жалоб и базовый надзор">
                    </div>
                    <div class="form-group">
                        <label>Категория роли</label>
                        <select id="create-role-category">
                            <option value="CUSTOM">Пользовательская (CUSTOM)</option>
                            <option value="ADMIN">Администраторы (ADMIN)</option>
                            <option value="MOD">Модераторы (MOD)</option>
                            <option value="PROBATION">Испытательный срок (PROBATION)</option>
                        </select>
                    </div>
                    <div class="form-group">
                        <label>Цвет роли (для бейджей, списков и журналов)</label>
                        <div style="display:flex; align-items:center; gap:10px;">
                            <input type="color" id="create-role-color" value="#8b5cf6" style="width:42px; height:34px; padding:0; cursor:pointer; background:none; border:none;">
                            <span style="font-size:12px; color:var(--text-muted);">Акцентный цвет оформления роли</span>
                        </div>
                    </div>

                    <div style="margin-top:16px; margin-bottom:8px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px;">
                        <label style="margin-bottom:0; font-weight:700; color:var(--text-heading);">Права доступа роли в панели:</label>
                        <div style="display:flex; gap:6px;">
                            <button type="button" class="secondary btn-sm" id="btn-perm-all">Выбрать все</button>
                            <button type="button" class="secondary btn-sm" id="btn-perm-none">Снять все</button>
                        </div>
                    </div>

                    <div style="display:grid; grid-template-columns:1fr 1fr; gap:8px;" id="perms-matrix-container">
                        ${renderPermCheckboxes(["VIEW_STATS", "VIEW_PLAYERS", "VIEW_BANS", "MANAGE_BANS", "VIEW_REPORTS", "MANAGE_REPORTS", "VIEW_SERVER_LOGS"])}
                    </div>
                </div>
                <div class="modal-footer">
                    <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                    <button type="button" class="primary" id="btn-submit-create-role">СОЗДАТЬ РОЛЬ</button>
                </div>
            `,
      () => {
        document
          .getElementById("btn-perm-all")
          ?.addEventListener("click", () => {
            document
              .querySelectorAll(".role-perm-cb")
              .forEach((cb) => (cb.checked = true));
          });
        document
          .getElementById("btn-perm-none")
          ?.addEventListener("click", () => {
            document
              .querySelectorAll(".role-perm-cb")
              .forEach((cb) => (cb.checked = false));
          });
        document
          .getElementById("btn-submit-create-role")
          ?.addEventListener("click", async () => {
            const name = document
              .getElementById("create-role-name")
              .value.trim();
            const description =
              document.getElementById("create-role-desc")?.value.trim() || "";
            const category =
              document.getElementById("create-role-category")?.value ||
              "CUSTOM";
            const color =
              document.getElementById("create-role-color")?.value || "#8b5cf6";
            const permissions = Array.from(
              document.querySelectorAll(".role-perm-cb:checked"),
            ).map((cb) => cb.value);
            if (!name) {
              await noticeDialog(
                "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
                "Укажите название роли",
              );
              return;
            }
            try {
              await api("POST", "/api/roles", {
                name,
                description,
                category,
                lpGroup: "",
                color,
                permissions,
              });
              showToast(
                "Роль создана",
                `Роль LoveWebAdmin успешно добавлена`,
                "success",
              );
              closeModal();
              renderRolesTab();
            } catch (e) {
              await noticeDialog(
                "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
                "Ошибка: " + e.message,
              );
            }
          });
      },
      "modal-lg",
    );
  }

  // Modal: Edit Role
  window.openEditRoleModal = (roleId) => {
    const role = rolesCache.find((r) => r.id === roleId);
    if (!role) return;
    openModal(
      `
                <div class="modal-header">
                    <h3>РЕДАКТИРОВАНИЕ РОЛИ: ${esc(role.name)}</h3>
                    <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon("close", "gray", 16)}</button>
                </div>
                <div class="modal-body" style="max-height:75vh; overflow-y:auto;">
                    <div class="form-group">
                        <label>Название роли *</label>
                        <input type="text" id="edit-role-name" value="${esc(role.name)}" required autofocus>
                    </div>
                    <div class="form-group">
                        <label>Описание роли</label>
                        <input type="text" id="edit-role-desc" value="${esc(role.description || "")}" placeholder="Краткое описание обязанностей">
                    </div>
                    <div class="form-group">
                        <label>Категория роли</label>
                        <select id="edit-role-category">
                            <option value="CUSTOM" ${role.category === "CUSTOM" ? "selected" : ""}>Пользовательская (CUSTOM)</option>
                            <option value="ADMIN" ${role.category === "ADMIN" ? "selected" : ""}>Администраторы (ADMIN)</option>
                            <option value="MOD" ${role.category === "MOD" ? "selected" : ""}>Модераторы (MOD)</option>
                            <option value="PROBATION" ${role.category === "PROBATION" ? "selected" : ""}>Испытательный срок (PROBATION)</option>
                        </select>
                    </div>
                    <div class="form-group">
                        <label>Цвет роли (для бейджей, списков и журналов)</label>
                        <div style="display:flex; align-items:center; gap:10px;">
                            <input type="color" id="edit-role-color" value="${esc(role.color || "#8b5cf6")}" style="width:42px; height:34px; padding:0; cursor:pointer; background:none; border:none;">
                            <span style="font-size:12px; color:var(--text-muted);">Акцентный цвет оформления роли</span>
                        </div>
                    </div>

                    <div style="margin-top:16px; margin-bottom:8px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px;">
                        <label style="margin-bottom:0; font-weight:700; color:var(--text-heading);">Права доступа роли в панели:</label>
                        <div style="display:flex; gap:6px;">
                            <button type="button" class="secondary btn-sm" id="btn-edit-perm-all">Выбрать все</button>
                            <button type="button" class="secondary btn-sm" id="btn-edit-perm-none">Снять все</button>
                        </div>
                    </div>

                    <div style="display:grid; grid-template-columns:1fr 1fr; gap:8px;">
                        ${renderPermCheckboxes(role.permissions || [])}
                    </div>
                </div>
                <div class="modal-footer">
                    <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                    <button type="button" class="primary" id="btn-submit-edit-role">СОХРАНИТЬ ИЗМЕНЕНИЯ</button>
                </div>
            `,
      () => {
        document
          .getElementById("btn-edit-perm-all")
          ?.addEventListener("click", () => {
            document
              .querySelectorAll(".role-perm-cb")
              .forEach((cb) => (cb.checked = true));
          });
        document
          .getElementById("btn-edit-perm-none")
          ?.addEventListener("click", () => {
            document
              .querySelectorAll(".role-perm-cb")
              .forEach((cb) => (cb.checked = false));
          });
        document
          .getElementById("btn-submit-edit-role")
          ?.addEventListener("click", async () => {
            const name = document.getElementById("edit-role-name").value.trim();
            const description =
              document.getElementById("edit-role-desc")?.value.trim() || "";
            const category =
              document.getElementById("edit-role-category")?.value || "CUSTOM";
            const color =
              document.getElementById("edit-role-color")?.value || "#8b5cf6";
            const permissions = Array.from(
              document.querySelectorAll(".role-perm-cb:checked"),
            ).map((cb) => cb.value);
            if (!name) {
              await noticeDialog(
                "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
                "Укажите название роли",
              );
              return;
            }
            try {
              await api("PUT", `/api/roles/${roleId}`, {
                name,
                description,
                category,
                lpGroup: "",
                color,
                permissions,
              });
              showToast(
                "Роль обновлена",
                `Параметры и права для роли LoveWebAdmin сохранены`,
                "success",
              );
              closeModal();
              renderRolesTab();
            } catch (e) {
              await noticeDialog(
                "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
                "Ошибка сохранения: " + e.message,
              );
            }
          });
      },
      "modal-lg",
    );
  };

  // Delete Role Handler
  window.deleteRole = (roleId, roleName) => {
    confirmAction(
      "УДАЛЕНИЕ РОЛИ",
      `Вы действительно хотите безвозвратно удалить роль «${roleName}»? Если к этой роли привязаны сотрудники, система заблокирует удаление до переназначения их ролей.`,
      async () => {
        try {
          await api("DELETE", `/api/roles/${roleId}`);
          showToast(
            "Роль удалена",
            `Роль «${roleName}» успешно удалена`,
            "info",
          );
          renderRolesTab();
        } catch (e) {
          await noticeDialog(
            "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
            "Не удалось удалить роль: " + e.message,
          );
        }
      },
      "УДАЛИТЬ РОЛЬ",
      true,
    );
  };
  mount();
}
export function exposeRenderStaffView() {
  window.renderStaffView = renderStaffView;
}
export function exposeRenderAdminsView() {
  window.renderAdminsView = renderStaffView;

  // 8. БАЗА ДАННЫХ (СВОДНАЯ СТАТИСТИКА, ГРАФИКИ, ТОП ИГРОКОВ)
  // ==========================================================================
}
