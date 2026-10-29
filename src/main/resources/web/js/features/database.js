import { noticeDialog } from "../core/dialogs.js";
import { state } from "../state.js";
import { api, esc, fmtDuration, fmtTime } from "./ui.js";
// 8. БАЗА ДАННЫХ (СВОДНАЯ СТАТИСТИКА, ГРАФИКИ, ТОП ИГРОКОВ)
// ==========================================================================

export async function renderDatabaseView() {
  const area = document.getElementById("content-area");
  let selectedPeriod = "today";
  let customFrom = null;
  let customTo = null;
  area.innerHTML = `
            <div class="view-header">
                <div class="view-title-block">
                    <h2>БАЗА ДАННЫХ И АНАЛИТИКА СЕРВЕРА</h2>
                    <p>Комплексная статистика игроков, сравнительный анализ за периоды, динамика прироста и лидерборды</p>
                </div>
            </div>

            <!-- Period Filter Toolbar with Percentage Trend Badges -->
            <div class="period-filter-toolbar">
                <div style="display:flex; align-items:center; gap:12px; flex-wrap:wrap;">
                    <span style="font-size:12px; font-weight:700; color:var(--text-dim); text-transform:uppercase; letter-spacing:0.04em;">Период выборки:</span>
                    <div class="period-pills-group" id="db-period-pills">
                        <button type="button" class="period-pill-btn active" data-period="today">Сегодня</button>
                        <button type="button" class="period-pill-btn" data-period="yesterday">Вчера</button>
                        <button type="button" class="period-pill-btn" data-period="7d">7 дней</button>
                        <button type="button" class="period-pill-btn" data-period="30d">30 дней</button>
                        <button type="button" class="period-pill-btn" data-period="custom">Произвольный</button>
                    </div>
                </div>

                <div id="db-custom-range-box" style="display:none; align-items:center; gap:8px;">
                    <input type="date" id="db-date-from" style="padding:4px 8px; font-size:12px; width:135px; background:var(--input-bg); border:1px solid var(--border); color:var(--text-heading); border-radius:4px;">
                    <span style="color:var(--text-dim); font-size:12px;">—</span>
                    <input type="date" id="db-date-to" style="padding:4px 8px; font-size:12px; width:135px; background:var(--input-bg); border:1px solid var(--border); color:var(--text-heading); border-radius:4px;">
                    <button type="button" class="primary btn-sm" id="btn-apply-custom-period">ПРИМЕНИТЬ</button>
                </div>
            </div>

            <!-- Global Key Metrics Cards Grid -->
            <div class="cards-grid" style="margin-bottom:24px;" id="db-metrics-grid">
                <div class="stat-card"><div class="stat-label">ЗАГРУЗКА АНАЛИТИКИ...</div></div>
            </div>

            <!-- 24-Hour Activity Chart -->
            <div class="hourly-chart-wrap" style="margin-bottom:24px;">
                <div class="chart-header">
                    <div>
                        <h3 style="font-size:15px; color:var(--text-heading);" id="db-chart-title">АКТИВНОСТЬ ПО ЧАСАМ (24 ЧАСА)</h3>
                        <p style="font-size:12px; color:var(--text-muted);" id="db-chart-subtitle">Сравнение распределения действий с предыдущим аналогичным периодом</p>
                    </div>
                    <span class="badge purple" id="db-chart-badge">СЕГОДНЯ</span>
                </div>
                <div class="hourly-bars-grid" id="hourly-chart-bars">
                    <!-- 24 bars rendered dynamically -->
                </div>
            </div>

            <!-- Top Playtime Players & Dedicated Player Inspector -->
            <div style="display:grid; grid-template-columns: 2fr 1fr; gap:20px;">
                <!-- Top Playtime Table -->
                <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:18px;">
                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:14px;">
                        <h3 style="font-size:15px; color:var(--text-heading);">ТОП ИГРОКОВ ПО НАИГРАННОМУ ВРЕМЕНИ</h3>
                        <span style="font-size:12px; color:var(--text-dim);">Всего в базе данных</span>
                    </div>
                    <div class="table-wrap" style="max-height:440px; overflow-y:auto;">
                        <table>
                            <thead>
                                <tr>
                                    <th style="width:45px;">#</th>
                                    <th>ИГРОК</th>
                                    <th>ВРЕМЯ В ИГРЕ</th>
                                    <th>ПЕРВЫЙ ВХОД</th>
                                    <th style="text-align:right;">ДЕЙСТВИЕ</th>
                                </tr>
                            </thead>
                            <tbody id="top-playtime-tbody">
                                <tr><td colspan="5" style="text-align:center; padding:18px; color:var(--text-dim);">Загрузка лидерборда...</td></tr>
                            </tbody>
                        </table>
                    </div>
                </div>

                <!-- Dedicated Player Stat Inspector -->
                <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:18px;">
                    <h3 style="font-size:15px; margin-bottom:6px; color:var(--text-heading);">КАРТОЧКА СТАТИСТИКИ ИГРОКА</h3>
                    <p style="font-size:12px; color:var(--text-muted); margin-bottom:14px;">Персональное досье и параметры конкретного пользователя</p>

                    <div style="display:flex; gap:8px; margin-bottom:16px;">
                        <input type="text" id="inspect-player-name" placeholder="Введите ник игрока..." style="flex:1;">
                        <button type="button" class="primary btn-sm" id="btn-inspect-player">НАЙТИ</button>
                    </div>

                    <div id="inspector-card-result">
                        <div style="text-align:center; padding:30px 10px; color:var(--text-dim); font-size:12.5px;">
                            Введите никнейм для просмотра персональной статистики
                        </div>
                    </div>
                </div>
            </div>
        `;
  function formatDeltaBadge(delta, prevLabel) {
    if (delta === null || delta === undefined || isNaN(delta)) {
      return `<span class="trend-badge neutral">Нет сравнения</span>`;
    }
    const num = Math.round(delta * 10) / 10;
    const labelStr = prevLabel
      ? ` <span style="font-weight:400; opacity:0.75;">${esc(prevLabel)}</span>`
      : "";
    if (num > 0) {
      return `<span class="trend-badge up">▲ +${num}%${labelStr}</span>`;
    } else if (num < 0) {
      return `<span class="trend-badge down">▼ ${num}%${labelStr}</span>`;
    } else {
      return `<span class="trend-badge neutral">— 0%${labelStr}</span>`;
    }
  }
  const loadAnalytics = async () => {
    try {
      let url = `/api/analytics/summary?period=${encodeURIComponent(selectedPeriod)}`;
      if (selectedPeriod === "custom" && customFrom && customTo) {
        url += `&from=${customFrom}&to=${customTo}`;
      }
      const data = await api("GET", url);
      const topPlayers = await api(
        "GET",
        "/api/analytics/top-players?limit=20",
      ).catch(() => []);

      // Update Chart header
      const chartBadge = document.getElementById("db-chart-badge");
      if (chartBadge)
        chartBadge.textContent = (
          data.periodLabel || selectedPeriod
        ).toUpperCase();
      const chartSub = document.getElementById("db-chart-subtitle");
      if (chartSub)
        chartSub.textContent = `Распределение действий по часам суток (сравнение: ${data.prevPeriodLabel || "пред. период"})`;

      // Render metrics cards
      const mGrid = document.getElementById("db-metrics-grid");
      if (mGrid) {
        mGrid.innerHTML = `
                        <div class="stat-card">
                            <div class="stat-label">УНИКАЛЬНЫХ ИГРОКОВ</div>
                            <div class="stat-value" style="color:var(--green);">${(data.uniquePlayers || 0).toLocaleString()}</div>
                            <div>${formatDeltaBadge(data.uniquePlayersDelta, data.prevPeriodLabel)}</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-label">НОВЫХ ИГРОКОВ</div>
                            <div class="stat-value" style="color:var(--yellow);">${(data.newPlayers || 0).toLocaleString()}</div>
                            <div>${formatDeltaBadge(data.newPlayersDelta, data.prevPeriodLabel)}</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-label">ПИКОВЫЙ ОНЛАЙН</div>
                            <div class="stat-value" style="color:var(--accent-light);">${data.peakOnline ?? "Нет измерений"}</div>
                            <div>${formatDeltaBadge(data.peakOnlineDelta, data.prevPeriodLabel)}</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-label">СРЕДНИЙ ОНЛАЙН</div>
                            <div class="stat-value" style="color:#60a5fa;">${data.avgOnline ?? "Нет измерений"}</div>
                            <div>${formatDeltaBadge(data.avgOnlineDelta, data.prevPeriodLabel)}</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-label">НАКАЗАНИЙ ВЫДАНО</div>
                            <div class="stat-value" style="color:var(--red);">${(data.punishments || 0).toLocaleString()}</div>
                            <div>${formatDeltaBadge(data.punishmentsDelta, data.prevPeriodLabel)}</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-label">СООБЩЕНИЙ В ЧАТЕ</div>
                            <div class="stat-value" style="color:#c084fc;">${(data.chatMessages || 0).toLocaleString()}</div>
                            <div>${formatDeltaBadge(data.chatMessagesDelta, data.prevPeriodLabel)}</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-label">RETENTION (УДЕРЖАНИЕ)</div>
                            <div class="stat-value" style="color:#38bdf8;">${data.retentionRate || 0}%</div>
                            <div>${formatDeltaBadge(data.retentionDelta, data.prevPeriodLabel)}</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-label">СРЕДНЯЯ СЕССИЯ</div>
                            <div class="stat-value">${data.avgPlaytimeMinutes || 0} <span style="font-size:13px; color:var(--text-dim);">мин</span></div>
                            <div class="stat-sub">Длина одной сессии игрока</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-label">ВСЕГО АККАУНТОВ В БД</div>
                            <div class="stat-value">${(data.totalPlayers || 0).toLocaleString()}</div>
                            <div class="stat-sub">Наиграно: ${(data.totalPlaytimeHours || 0).toLocaleString()} ч</div>
                        </div>
                    `;
      }

      // Render hourly chart
      const chartBars = document.getElementById("hourly-chart-bars");
      if (chartBars) {
        const hourly = Array.isArray(data.hourlyActivity)
          ? data.hourlyActivity
          : Array.from(
              {
                length: 24,
              },
              () => 0,
            );
        const prevHourly = Array.isArray(data.prevHourlyActivity)
          ? data.prevHourlyActivity
          : Array.from(
              {
                length: 24,
              },
              () => 0,
            );
        const maxVal = Math.max(...hourly, ...prevHourly, 1);
        chartBars.innerHTML = hourly
          .map((cnt, hr) => {
            const pct = Math.max(6, Math.round((cnt / maxVal) * 100));
            const prevCnt = prevHourly[hr] || 0;
            const hrStr = String(hr).padStart(2, "0") + ":00";
            return `
                            <div class="hour-column" data-tooltip="${hrStr} — Сейчас: ${cnt} | ${esc(data.prevPeriodLabel || "Пред")}: ${prevCnt}">
                                <div class="hour-bar" style="height:${pct}%;"></div>
                                <span class="hour-label">${hr % 3 === 0 ? hrStr : ""}</span>
                            </div>`;
          })
          .join("");
      }

      // Render Top Players Table
      const topBody = document.getElementById("top-playtime-tbody");
      if (topBody) {
        if (!topPlayers.length) {
          topBody.innerHTML = `<tr><td colspan="5" style="text-align:center; padding:16px; color:var(--text-dim);">Нет данных по времени</td></tr>`;
        } else {
          topBody.innerHTML = topPlayers
            .map(
              (p, idx) => `
                            <tr>
                                <td class="font-mono" style="font-weight:700; color:var(--accent-light);">#${idx + 1}</td>
                                <td>
                                    <div style="display:flex; align-items:center; gap:8px; cursor:pointer;" onclick="window.viewPlayerProfile('${esc(p.name)}')">
                                        <img src="https://mc-heads.net/avatar/${encodeURIComponent(p.name)}/22" class="player-avatar-sm" alt="">
                                        <b style="color:var(--text-heading);">${esc(p.name)}</b>
                                    </div>
                                </td>
                                <td><b style="color:var(--green);">${fmtDuration(p.playtimeSeconds)}</b></td>
                                <td class="font-mono" style="font-size:12px; color:var(--text-muted);">${fmtTime(p.firstJoinedAt)}</td>
                                <td style="text-align:right;">
                                    <button type="button" class="secondary btn-sm" onclick="window.viewPlayerProfile('${esc(p.name)}')">ДОСЬЕ</button>
                                </td>
                            </tr>
                        `,
            )
            .join("");
        }
      }
    } catch (e) {
      console.error("Error loading analytics:", e);
    }
  };

  // Period filter buttons
  document
    .querySelectorAll("#db-period-pills .period-pill-btn")
    .forEach((btn) => {
      btn.addEventListener("click", () => {
        document
          .querySelectorAll("#db-period-pills .period-pill-btn")
          .forEach((b) => b.classList.remove("active"));
        btn.classList.add("active");
        selectedPeriod = btn.dataset.period;
        const customBox = document.getElementById("db-custom-range-box");
        if (selectedPeriod === "custom") {
          if (customBox) customBox.style.display = "flex";
        } else {
          if (customBox) customBox.style.display = "none";
          loadAnalytics();
        }
      });
    });

  // Apply custom period button
  document
    .getElementById("btn-apply-custom-period")
    ?.addEventListener("click", async () => {
      const fromVal = document.getElementById("db-date-from")?.value;
      const toVal = document.getElementById("db-date-to")?.value;
      if (!fromVal || !toVal) {
        await noticeDialog(
          "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
          "Выберите обе даты периода",
        );
        return;
      }
      customFrom = new Date(fromVal).getTime();
      customTo = new Date(toVal + "T23:59:59").getTime();
      loadAnalytics();
    });

  // Dedicated player inspector button
  const doInspectPlayer = async () => {
    const name = document.getElementById("inspect-player-name")?.value.trim();
    const resBox = document.getElementById("inspector-card-result");
    if (!name || !resBox) return;
    resBox.innerHTML = `<div style="text-align:center; padding:20px; color:var(--text-muted);">Поиск...</div>`;
    try {
      const p = await api("GET", `/api/players/${encodeURIComponent(name)}`);
      const hours = Math.round((p.playtimeSeconds || 0) / 3600);
      resBox.innerHTML = `
                    <div style="background:var(--card-inner-bg); padding:14px; border-radius:var(--radius-sm); border:1px solid var(--border-light);">
                        <div style="display:flex; align-items:center; gap:10px; margin-bottom:12px;">
                            <img src="https://mc-heads.net/avatar/${encodeURIComponent(p.name)}/36" class="player-avatar-sm" style="width:36px; height:36px;" alt="">
                            <div>
                                <h4 style="color:var(--text-main); font-size:15px; margin:0;">${esc(p.name)}</h4>
                                <span class="badge ${p.isOnline ? "green" : "gray"}" style="margin-top:2px;">${p.isOnline ? "ОНЛАЙН" : "ОФФЛАЙН"}</span>
                            </div>
                        </div>

                        <div style="display:flex; flex-direction:column; gap:8px; font-size:12.5px;">
                            <div style="display:flex; justify-content:space-between; border-bottom:1px solid var(--border); padding-bottom:4px;">
                                <span style="color:var(--text-dim);">Всего в игре:</span>
                                <b style="color:var(--accent-light);">${hours} ч (${fmtDuration(p.playtimeSeconds || 0)})</b>
                            </div>
                            <div style="display:flex; justify-content:space-between; border-bottom:1px solid var(--border); padding-bottom:4px;">
                                <span style="color:var(--text-dim);">Первый вход:</span>
                                <span class="font-mono">${fmtTime(p.firstJoinedAt)}</span>
                            </div>
                            <div style="display:flex; justify-content:space-between; border-bottom:1px solid var(--border); padding-bottom:4px;">
                                <span style="color:var(--text-dim);">Последний вход:</span>
                                <span class="font-mono">${fmtTime(p.lastSeenAt)}</span>
                            </div>
                            <div style="display:flex; justify-content:space-between;">
                                <span style="color:var(--text-dim);">Рейтинг доверия:</span>
                                <b style="color:${p.trustColor || "#10b981"};">${p.trustGrade || "A"} (${p.trustScore || 85}/100)</b>
                            </div>
                        </div>

                        <button type="button" class="primary btn-sm" style="width:100%; margin-top:14px;" onclick="window.viewPlayerProfile('${esc(p.name)}')">ОТКРЫТЬ ПОЛНОЕ ДОСЬЕ</button>
                    </div>
                `;
    } catch (e) {
      resBox.innerHTML = `<div style="color:var(--red); padding:16px; text-align:center;">Игрок «${esc(name)}» не найден</div>`;
    }
  };
  document
    .getElementById("btn-inspect-player")
    ?.addEventListener("click", doInspectPlayer);
  document
    .getElementById("inspect-player-name")
    ?.addEventListener("keydown", (e) => {
      if (e.key === "Enter") doInspectPlayer();
    });
  document
    .getElementById("db-refresh-btn")
    ?.addEventListener("click", loadAnalytics);
  await loadAnalytics();
}

// ==========================================================================
// THEME & APPEARANCE
// ==========================================================================
