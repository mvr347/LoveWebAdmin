import { noticeDialog } from "../core/dialogs.js";
import { confirmDialog } from "../core/dialogs.js";
import { state } from "../state.js";
import { api, hasPerm, esc, fmtTime, renderSvgIcon, showToast } from "./ui.js";
// ==========================================================================
// 5. АНТИЧИТ (ИНТЕГРАЦИЯ С VESUVIO, ЖИВОЙ ПОТОК, НАБЛЮДЕНИЕ)
// ==========================================================================

export async function renderAnticheatView() {
  const area = document.getElementById("content-area");
  let currentCheckFilter = "";
  let isStreamPaused = false;
  let activeTab = hasPerm("VIEW_VESUVIO_ADVANCED") ? "stream" : "suspects"; // stream, suspects, models

  area.innerHTML = `
            <div class="view-header">
                <div class="view-title-block">
                    <h2>АНТИЧИТ</h2>
                    <p>Прямая телеметрия, детекция запрещённых модификаций, поведенческие эвристики и ML-модели</p>
                </div>
            </div>

            <!-- Live Beacon Status Bar with embedded Pause Button inside the stream -->
            <div class="radar-stream-header">
                <div class="live-beacon">
                    <span class="live-beacon-dot" id="radar-beacon-dot"></span>
                    <span id="radar-beacon-text">ЖИВОЙ ПОТОК СРАБАТЫВАНИЙ (LIVE 3S)</span>
                </div>
                <div style="display:flex; align-items:center; gap:10px;">
                    <button type="button" class="secondary btn-sm" id="vesuvio-pause-btn">⏸ ПАУЗА ПОТОКА</button>
                    <div style="font-size:12px; color:var(--text-muted);">
                        Эвристики: <span class="badge green">АКТИВНЫ</span>
                    </div>
                </div>
            </div>

            <!-- Sub-Tabs: Stream / Suspects / Models -->
            <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:12px 16px; margin-bottom:16px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:12px;">
                <div class="filter-tags" id="anticheat-tabs">
                    <button type="button" class="filter-tag-btn active" data-tab="stream">Поток нарушений</button>
                    <button type="button" class="filter-tag-btn" data-tab="suspects">Разбивка по игрокам / Наблюдение</button>
                    <button type="button" class="filter-tag-btn" data-tab="punishments">Журнал наказаний</button><button type="button" class="filter-tag-btn" data-tab="models">Модели и типы проверок</button>
                </div>

                <div style="width:220px;">
                    <select id="anticheat-filter-category">
                        <option value="">Все типы проверок</option>
                        <option value="Movement">Movement (Fly, Speed, NoFall)</option>
                        <option value="Combat">Combat (Killaura, Reach, HitBox)</option>
                        <option value="World">World (Scaffold, FastBreak)</option>
                        <option value="Timer">Timer / Tick</option>
                    </select>
                </div>
            </div>

            <div id="anticheat-content-body">
                <!-- Stream / Suspects / Models rendered here -->
            </div>
        `;
  const pauseBtn = document.getElementById("vesuvio-pause-btn");
  pauseBtn?.addEventListener("click", () => {
    isStreamPaused = !isStreamPaused;
    pauseBtn.textContent = isStreamPaused
      ? "▶ ВОЗОБНОВИТЬ ПОТОК"
      : "⏸ ПАУЗА ПОТОКА";
    const dot = document.getElementById("radar-beacon-dot");
    const txt = document.getElementById("radar-beacon-text");
    if (dot)
      dot.style.animationPlayState = isStreamPaused ? "paused" : "running";
    if (txt)
      txt.textContent = isStreamPaused
        ? "ПОТОК ПРИОСТАНОВЛЕН (ПАУЗА)"
        : "ЖИВОЙ ПОТОК СРАБАТЫВАНИЙ (LIVE 3S)";
  });

  // Tab switches
  document.getElementById("anticheat-tabs")?.addEventListener("click", (e) => {
    const btn = e.target.closest(".filter-tag-btn");
    if (!btn) return;
    document
      .querySelectorAll("#anticheat-tabs .filter-tag-btn")
      .forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    activeTab = btn.dataset.tab;
    renderAnticheatTab();
  });
  document
    .getElementById("anticheat-filter-category")
    ?.addEventListener("change", (e) => {
      currentCheckFilter = e.target.value;
      renderAnticheatTab();
    });
  const renderAnticheatTab = async () => {
    const body = document.getElementById("anticheat-content-body");
    if (!body) return;
    if (activeTab === "stream") {
      await renderStream(body);
    } else if (activeTab === "suspects") {
      await renderSuspects(body);
    } else if (activeTab === "punishments") {
      try {
        const entries = await api("GET", "/api/vesuvio/punishments?limit=100");
        body.replaceChildren();
        for (const entry of entries) {
          const card = document.createElement("article");
          card.className = "message";
          card.textContent =
            entry.name +
            " · " +
            entry.action +
            " · " +
            entry.reason +
            " · " +
            fmtTime(entry.timestamp);
          body.append(card);
        }
        if (!entries.length) body.textContent = "Записей о наказаниях пока нет";
      } catch (e) {
        body.textContent = e.message;
      }
    } else if (activeTab === "models") {
      await renderCheckModels(body);
    }
  };
  const renderStream = async (container) => {
    try {
      const res = await api(
        "GET",
        `/api/vesuvio/violations?player=${encodeURIComponent(playerName)}&limit=50`,
      ).catch(() => api("GET", "/api/vesuvio/violations?limit=50"));
      const list = Array.isArray(res) ? res : res.violations || [];
      let filtered = list;
      if (currentCheckFilter) {
        filtered = filtered.filter((v) =>
          (v.checkName || v.check || v.type || "")
            .toLowerCase()
            .includes(currentCheckFilter.toLowerCase()),
        );
      }
      if (!filtered.length) {
        container.innerHTML = `<div style="text-align:center; padding:36px; color:var(--text-dim); background:var(--card-bg); border-radius:var(--radius-md); border:1px solid var(--border);">Новых нарушений пока нет. Сервер под защитой античита.</div>`;
        return;
      }
      container.innerHTML = filtered
        .map((v) => {
          const name = v.playerName || v.name || "Игрок";
          const check = v.checkName || v.check || v.type || "Movement";
          const vl = v.vl ?? v.vlScore ?? 0;
          return `
                        <div class="violation-card">
                            <div class="violation-info">
                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(name)}/32" class="player-avatar-sm" style="width:32px; height:32px;" alt="">
                                <div class="violation-meta">
                                    <h4>${esc(name)} <span class="badge red">${esc(check)}</span></h4>
                                    <div class="violation-details">
                                        Нарушение: <b>${esc(check)}</b> • Уверенность VL: <b style="color:var(--yellow);">${vl}</b> • ${fmtTime(v.timestamp || Date.now() / 1000)}
                                    </div>
                                </div>
                            </div>
                            <div style="display:flex; gap:8px;">
                                <button type="button" class="secondary btn-sm" onclick="window.openObservationView('${esc(name)}', '${esc(v.playerUuid || v.uuid || "")}')">НАБЛЮДЕНИЕ</button>
                                <button type="button" class="danger btn-sm" onclick="window.openQuickBanModal('${esc(name)}', 'Читы')">ЗАБАНИТЬ</button>
                                <button type="button" class="secondary btn-sm" onclick="window.viewPlayerProfile('${esc(name)}')">ДОСЬЕ</button>
                            </div>
                        </div>`;
        })
        .join("");
    } catch (e) {
      console.warn("[Anticheat] live stream error:", e);
      const msg = e && e.message ? String(e.message) : "";
      const isMissing =
        /404|503|not found|unavailable|vesuvio/i.test(msg) || !msg;
      container.innerHTML = `
                    <div style="color:var(--text-muted); padding:28px; text-align:center; background:var(--card-bg); border-radius:var(--radius-md); border:1px solid var(--border);">
                        <div style="font-size:15px; font-weight:600; color:var(--text-heading); margin-bottom:8px;">
                            ${isMissing ? "Интеграция с Vesuvio не активна" : "Модуль античита временно недоступен"}
                        </div>
                        <div style="font-size:12.5px; line-height:1.5; max-width:420px; margin:0 auto;">
                            ${isMissing ? "Убедитесь, что плагин <b>Vesuvio</b> установлен и запущен. LoveWebAdmin использует soft-depend — API появится автоматически после загрузки античита." : "Ошибка запроса к API. Проверьте логи сервера и права <code>VIEW_VESUVIO</code>."}
                        </div>
                        ${msg ? `<div style="margin-top:10px; font-size:11px; color:var(--text-dim); font-family:monospace;">${esc(msg).slice(0, 180)}</div>` : ""}
                    </div>`;
    }
  };
  const renderSuspects = async (container) => {
    container.innerHTML = `<div style="text-align:center; padding:20px; color:var(--text-muted);">Загрузка аналитики нарушителей...</div>`;
    try {
      const [suspectsList, rawViolations] = await Promise.all([
        api("GET", "/api/vesuvio/suspects").catch(() => []),
        hasPerm("VIEW_VESUVIO_ADVANCED")
          ? api("GET", "/api/vesuvio/violations?limit=100").catch(() => [])
          : Promise.resolve([]),
      ]);
      const violations = Array.isArray(rawViolations)
        ? rawViolations
        : rawViolations.violations || [];

      // Aggregate per-player violation stats
      const playerStats = {};
      violations.forEach((v) => {
        const p = v.playerName || v.name || "Неизвестный";
        if (!playerStats[p]) {
          playerStats[p] = {
            count: 0,
            maxVl: 0,
            checks: new Set(),
            lastTime: 0,
          };
        }
        playerStats[p].count++;
        playerStats[p].maxVl = Math.max(playerStats[p].maxVl, v.vl ?? 0);
        playerStats[p].checks.add(
          v.checkName || v.check || v.type || "General",
        );
        playerStats[p].lastTime = Math.max(
          playerStats[p].lastTime,
          v.timestamp || 0,
        );
      });
      const mergedList = [...suspectsList];
      Object.keys(playerStats).forEach((pName) => {
        if (
          !mergedList.find(
            (s) => (s.name || "").toLowerCase() === pName.toLowerCase(),
          )
        ) {
          mergedList.push({
            name: pName,
            vl: playerStats[pName].maxVl,
            isAutomated: true,
            checks: Array.from(playerStats[pName].checks),
          });
        }
      });
      if (!mergedList.length) {
        container.innerHTML = `
                        <div style="text-align:center; padding:36px; color:var(--text-dim); background:var(--card-bg); border-radius:var(--radius-md); border:1px solid var(--border);">
                            <div style="margin-bottom:8px; display:flex; justify-content:center;">${renderSvgIcon("check", "green", 28)}</div>
                            <h3>Подозреваемых игроков нет</h3>
                            <p style="font-size:12.5px; color:var(--text-muted); margin-top:4px;">Добавляйте игроков в ручное наблюдение из потока нарушений или досье</p>
                        </div>`;
        return;
      }
      container.innerHTML = `
                    <div class="table-wrap">
                        <table>
                            <thead>
                                <tr>
                                    <th>ИГРОК</th>
                                    <th>СТАТУС ПРОВЕРКИ</th>
                                    <th>ФЛАГИ & ТИПЫ НАРУШЕНИЙ</th>
                                    <th>СКОР (MAX VL)</th>
                                    <th style="text-align:right;">ДЕЙСТВИЯ</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${mergedList
                                  .map((s) => {
                                    const st = playerStats[s.name] || {
                                      count: 0,
                                      checks: new Set(),
                                    };
                                    const checksArr =
                                      s.checks || Array.from(st.checks);
                                    return `
                                    <tr>
                                        <td>
                                            <div style="display:flex; align-items:center; gap:8px;">
                                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(s.name || "")}/24" class="player-avatar-sm" alt="">
                                                <b style="color:var(--text-heading);">${esc(s.name)}</b>
                                            </div>
                                        </td>
                                        <td>
                                            <span class="badge ${s.isAutomated ? "red" : "yellow"}">
                                                ${s.isAutomated ? "ТЕЛЕМЕТРИЯ FLAGGED" : "РУЧНОЕ НАБЛЮДЕНИЕ"}
                                            </span>
                                        </td>
                                        <td>
                                            ${
                                              checksArr.length
                                                ? checksArr
                                                    .slice(0, 3)
                                                    .map(
                                                      (c) =>
                                                        `<span class="badge gray" style="margin-right:4px;">${esc(c)}</span>`,
                                                    )
                                                    .join("")
                                                : '<span style="color:var(--text-dim);">—</span>'
                                            }
                                        </td>
                                        <td class="font-mono"><b style="color:var(--yellow);">${s.vl || st.maxVl || 1}</b></td>
                                        <td style="text-align:right;">
                                            <div style="display:flex; justify-content:flex-end; gap:6px;">
                                                <button type="button" class="secondary btn-sm" onclick="window.viewPlayerProfile('${esc(s.name)}')">ДОСЬЕ</button>
                                                <button type="button" class="danger btn-sm" onclick="window.openQuickBanModal('${esc(s.name)}', 'Читы')">БАН</button>
                                                <button type="button" class="secondary btn-sm" onclick="window.openObservationView('${esc(s.name)}', '${esc(s.uuid || "")}')">
                                                    НАБЛЮДЕНИЕ
                                                </button>
                                            </div>
                                        </td>
                                    </tr>
                                `;
                                  })
                                  .join("")}
                            </tbody>
                        </table>
                    </div>`;
    } catch (e) {
      container.innerHTML = `<div style="color:var(--red); padding:20px;">Ошибка загрузки списка нарушителей</div>`;
    }
  };
  const renderCheckModels = async (container) => {
    try {
      const engine = await api("GET", "/api/vesuvio/engine");
      container.replaceChildren();
      const heading = document.createElement("h3");
      heading.textContent = "Модели и обучение";
      container.append(heading);
      const facts = document.createElement("div");
      facts.className = "workspace-grid";
      for (const [label, value] of [
        [
          "Модель кликов",
          engine.clickModelLoaded ? "Загружена" : "Не загружена",
        ],
        [
          "Модель прицеливания",
          engine.aimModelLoaded ? "Загружена" : "Не загружена",
        ],
        ["Образцов в датасете", engine.datasetSize],
        ["Обучающих образцов классификатора", engine.classifierTrainedSamples],
        ["Вычислений ONNX", engine.onnxTotalInferences],
      ]) {
        const card = document.createElement("section");
        const name = document.createElement("h4");
        name.textContent = label;
        const field = document.createElement("p");
        field.textContent = String(value ?? "Нет данных");
        card.append(name, field);
        facts.append(card);
      }
      container.append(facts);
      if (hasPerm("MANAGE_VESUVIO_MODELS")) {
        const reload = document.createElement("button");
        reload.className = "primary";
        reload.textContent = "Перезагрузить модели";
        reload.onclick = async () => {
          if (
            !(await confirmDialog(
              "Перезагрузка моделей",
              "Загрузить файлы моделей с сервера? Повторная перезагрузка доступна через минуту.",
            ))
          )
            return;
          try {
            await api("POST", "/api/vesuvio/models/reload", {});
            await renderCheckModels(container);
          } catch (error) {
            showToast("Модели", error.message, "error");
          }
        };
        container.append(reload);
      }
      const title = document.createElement("h3");
      title.textContent = "Образцы для разбора";
      container.append(title);
      let samples;
      try {
        samples = await api("GET", "/api/vesuvio/reviews?limit=50");
      } catch (error) {
        const note = document.createElement("p");
        note.textContent = error.message;
        container.append(note);
        return;
      }
      if (!samples.length) {
        const empty = document.createElement("p");
        empty.textContent = "Образцов для разбора сейчас нет";
        container.append(empty);
      }
      for (const sample of samples) {
        const card = document.createElement("section");
        const label = document.createElement("h4");
        label.textContent =
          sample.name +
          " · " +
          (sample.domain === "aim" ? "Прицеливание" : "Клики");
        const description = document.createElement("p");
        description.textContent =
          "Вероятность: " +
          (sample.probability * 100).toFixed(1) +
          "% · Образец " +
          sample.id;
        const vector = document.createElement("details");
        const summary = document.createElement("summary");
        summary.textContent = "Признаки образца";
        const values = document.createElement("pre");
        values.textContent = JSON.stringify(sample.features);
        vector.append(summary, values);
        card.append(label, description, vector);
        for (const [verdict, text] of [
          ["legit", "Чистая игра"],
          ["cheat", "Использование читов"],
        ]) {
          const button = document.createElement("button");
          button.textContent = text;
          button.className = verdict === "cheat" ? "danger" : "secondary";
          button.disabled = !hasPerm("REVIEW_VESUVIO_SAMPLES");
          if (button.disabled)
            button.title = "Нужно право разбора обучающих образцов";
          button.onclick = async () => {
            if (
              !(await confirmDialog(
                "Разбор образца",
                sample.name + ": " + text + "? Вердикт влияет на обучение.",
              ))
            )
              return;
            try {
              await api(
                "POST",
                "/api/vesuvio/review/" + encodeURIComponent(sample.id),
                {
                  verdict,
                },
              );
              await renderCheckModels(container);
            } catch (error) {
              showToast("Образец", error.message, "error");
            }
          };
          card.append(button);
        }
        container.append(card);
      }
    } catch (error) {
      container.textContent =
        "Не удалось получить состояние моделей: " + error.message;
    }
  };
  await renderAnticheatTab();

  // Stream poller: auto updates without manual refresh
  state.pollers["vesuvio_live_stream"] = setInterval(() => {
    if (!isStreamPaused && activeTab === "stream") {
      const body = document.getElementById("anticheat-content-body");
      if (body) renderStream(body);
    }
  }, 3000);
}

// Observation Mode (Карточка прямого наблюдения за подозреваемым)
export function exposeOpenObservationView() {
  window.openObservationView = async function (playerName, uuid = "") {
    const area = document.getElementById("content-area");
    if (!area) return;

    // Clear live stream poller if running
    if (state.pollers["vesuvio_live_stream"]) {
      clearInterval(state.pollers["vesuvio_live_stream"]);
      delete state.pollers["vesuvio_live_stream"];
    }
    if (state.pollers["observation"]) {
      clearInterval(state.pollers["observation"]);
      delete state.pollers["observation"];
    }
    let playerUuid = uuid;
    let isSuspect = true;
    let playerDossier = null;
    area.innerHTML = `
            <div class="observation-view-layout">
                <div style="margin-bottom:8px;">
                    <button type="button" class="secondary btn-sm" id="btn-close-observation" style="display:inline-flex; align-items:center; gap:6px;">
                        ${renderSvgIcon("arrowLeft", "gray", 14)} ← Выйти из наблюдения
                    </button>
                </div>

                <!-- Top Header Card -->
                <div class="observation-header-card">
                    <div style="display:flex; align-items:center; gap:14px;">
                        <img src="https://mc-heads.net/avatar/${encodeURIComponent(playerName)}/48" class="player-avatar-sm" style="width:48px; height:48px; border-radius:6px;" alt="">
                        <div>
                            <div style="display:flex; align-items:center; gap:10px; flex-wrap:wrap;">
                                <h3 style="font-size:18px; margin:0; color:var(--text-heading);">${esc(playerName)}</h3>
                                <span class="badge red" id="obs-badge-suspect">ПОДОЗРЕНИЕ: ДА</span>
                                <span class="badge yellow" id="obs-badge-vl">MAX VL: ...</span>
                            </div>
                            <div class="font-mono" style="font-size:11.5px; color:var(--text-dim); margin-top:4px;" id="obs-uuid-display">
                                UUID: ${esc(playerUuid || "Поиск...")}
                            </div>
                        </div>
                    </div>

                    <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
                        <button type="button" class="danger" id="btn-obs-ban-draft" style="display:inline-flex; align-items:center; gap:6px;">
                            ${renderSvgIcon("shieldAlert", "white", 14)} Черновик бана
                        </button>
                        <button type="button" class="secondary" id="btn-obs-reset">Сбросить VL и снизить риск</button><button type="button" class="secondary" id="btn-obs-toggle-suspect" style="display:inline-flex; align-items:center; gap:6px;">
                            Снять наблюдение
                        </button>
                    </div>
                </div>

                <!-- 2 Columns Grid -->
                <div class="observation-grid">
                    <!-- Left: Stream of Flags -->
                    <div class="observation-stream-panel">
                        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:14px; border-bottom:1px solid var(--border); padding-bottom:10px;">
                            <div style="display:flex; align-items:center; gap:8px;">
                                <span class="live-beacon-dot" style="width:8px; height:8px; background:var(--red); border-radius:50%; box-shadow:0 0 8px var(--red);"></span>
                                <h4 style="margin:0; font-size:13.5px; color:var(--text-heading);">ПОТОК СРАБАТЫВАНИЙ ИГРОКА (LIVE 3S)</h4>
                            </div>
                            <span id="obs-flags-count" class="badge gray">0 флагов</span>
                        </div>
                        <div id="obs-violations-stream" style="display:flex; flex-direction:column; gap:8px; max-height:550px; overflow-y:auto; padding-right:4px;">
                            <div style="text-align:center; padding:30px; color:var(--text-muted);">Загрузка нарушений...</div>
                        </div>
                    </div>

                    <!-- Right: Dossier Info -->
                    <div class="observation-dossier-panel">
                        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:14px; border-bottom:1px solid var(--border); padding-bottom:10px;">
                            <h4 style="margin:0; font-size:13.5px; color:var(--text-heading);">ДОСЬЕ И АНАЛИТИКА</h4>
                            <button type="button" class="btn-tag-link" id="btn-obs-full-profile" style="font-size:11.5px;">Открыть полное досье →</button>
                        </div>
                        <div id="obs-dossier-body">
                            <div style="text-align:center; padding:30px; color:var(--text-muted);">Загрузка досье игрока...</div>
                        </div>
                    </div>
                </div>
            </div>
        `;
    document
      .getElementById("btn-close-observation")
      ?.addEventListener("click", closeObservationView);
    document
      .getElementById("btn-obs-ban-draft")
      ?.addEventListener("click", () => {
        window.openQuickBanModal(playerName, "Читы");
      });
    document
      .getElementById("btn-obs-full-profile")
      ?.addEventListener("click", () => {
        window.viewPlayerProfile(playerName);
      });
    const reset = document.getElementById("btn-obs-reset");
    reset.disabled = !hasPerm("MANAGE_VESUVIO");
    reset.title = reset.disabled ? "Нужно право управления Vesuvio" : "";
    reset.onclick = async () => {
      if (
        !playerUuid ||
        !(await confirmDialog(
          "Сброс показателей",
          "Сбросить VL и снизить риск на 30?",
        ))
      )
        return;
      try {
        await api(
          "POST",
          "/api/vesuvio/player/" + encodeURIComponent(playerUuid) + "/reset-vl",
          { reduceRisk: true },
        );
        showToast("Vesuvio", "Показатели сброшены", "success");
      } catch (e) {
        showToast("Vesuvio", e.message, "error");
      }
    };
    const toggleSuspectBtn = document.getElementById("btn-obs-toggle-suspect");
    toggleSuspectBtn.disabled = !hasPerm("MANAGE_VESUVIO");
    toggleSuspectBtn.title = toggleSuspectBtn.disabled
      ? "Нужно право управления Vesuvio"
      : "";
    toggleSuspectBtn?.addEventListener("click", async () => {
      if (!playerUuid) return;
      isSuspect = !isSuspect;
      try {
        await api(
          "POST",
          `/api/vesuvio/player/${encodeURIComponent(playerUuid)}/suspect`,
          {
            suspect: isSuspect,
          },
        );
        toggleSuspectBtn.textContent = isSuspect
          ? "Снять наблюдение"
          : "Поставить на наблюдение";
        const sBadge = document.getElementById("obs-badge-suspect");
        if (sBadge) {
          sBadge.className = isSuspect ? "badge red" : "badge gray";
          sBadge.textContent = isSuspect ? "ПОДОЗРЕНИЕ: ДА" : "ПОДОЗРЕНИЕ: НЕТ";
        }
        showToast(
          "Наблюдение",
          isSuspect
            ? "Игрок добавлен в список наблюдения"
            : "С игрока снято наблюдение",
          "info",
        );
      } catch (e) {
        await noticeDialog(
          "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
          "Ошибка: " + e.message,
        );
      }
    });

    // Ensure suspect state and fetch dossier
    try {
      const p = await api(
        "GET",
        `/api/players/${encodeURIComponent(playerName)}`,
      );
      playerDossier = p;
      if (p.uuid) {
        playerUuid = p.uuid;
        const uuidEl = document.getElementById("obs-uuid-display");
        if (uuidEl) uuidEl.textContent = "UUID: " + playerUuid;
        // Opening a view never changes the player's detection state.
        isSuspect = Boolean(p.vesuvio?.manualSuspect || p.vesuvio?.suspect);
      }
      renderObsDossier(playerDossier);
    } catch (e) {
      const db = document.getElementById("obs-dossier-body");
      if (db)
        db.innerHTML = `<div style="color:var(--text-dim); padding:16px;">Досье недоступно: ${esc(e.message)}</div>`;
    }

    // Render Dossier helper
    function renderObsDossier(data) {
      const db = document.getElementById("obs-dossier-body");
      if (!db) return;
      if (!data) {
        db.innerHTML = `<div style="color:var(--text-dim); padding:16px;">Данные отсутствуют</div>`;
        return;
      }
      const alts = Array.isArray(data.alts) ? data.alts : [];
      const kicks = data.kicksCount || 0;
      const bans = data.bansCount || 0;
      const notes = data.notes || data.staffNotes || "Заметок персонала нет";
      db.innerHTML = `
                <div style="display:flex; flex-direction:column; gap:12px; font-size:12.5px;">
                    <div style="background:var(--card-inner-bg, rgba(255,255,255,0.03)); padding:12px; border-radius:6px; border:1px solid var(--border);">
                        <div style="display:flex; justify-content:space-between; margin-bottom:6px;">
                            <span style="color:var(--text-muted);">IP адрес:</span>
                            <b class="font-mono" style="color:var(--accent-light);">${esc(data.ip || data.lastIp || "—")}</b>
                        </div>
                        <div style="display:flex; justify-content:space-between; margin-bottom:6px;">
                            <span style="color:var(--text-muted);">Клиент:</span>
                            <b>${esc(data.clientBrand || data.client || "Vanilla")}</b>
                        </div>
                        <div style="display:flex; justify-content:space-between; margin-bottom:6px;">
                            <span style="color:var(--text-muted);">Первый вход:</span>
                            <span style="color:var(--text-dim);">${fmtTime(data.firstJoin || data.registeredAt || 0)}</span>
                        </div>
                        <div style="display:flex; justify-content:space-between;">
                            <span style="color:var(--text-muted);">Последний вход:</span>
                            <span style="color:var(--text-dim);">${fmtTime(data.lastJoin || data.lastSeen || 0)}</span>
                        </div>
                    </div>

                    <div style="background:var(--card-inner-bg, rgba(255,255,255,0.03)); padding:12px; border-radius:6px; border:1px solid var(--border);">
                        <div style="color:var(--text-muted); font-size:11.5px; text-transform:uppercase; font-weight:700; margin-bottom:6px;">Связанные твинки (альты)</div>
                        <div style="display:flex; gap:6px; flex-wrap:wrap;">
                            ${
                              alts.length
                                ? alts
                                    .map(
                                      (a) => `
                                <span class="badge gray" style="font-size:11px; cursor:pointer;" onclick="window.openObservationView('${esc(a)}')">${esc(a)}</span>
                            `,
                                    )
                                    .join("")
                                : '<span style="color:var(--text-dim);">Других аккаунтов по IP не зафиксировано</span>'
                            }
                        </div>
                    </div>

                    <div style="display:grid; grid-template-columns:1fr 1fr; gap:10px;">
                        <div style="background:var(--card-inner-bg, rgba(255,255,255,0.03)); padding:10px 12px; border-radius:6px; border:1px solid var(--border);">
                            <div style="font-size:11px; color:var(--text-muted);">КИКОВ СЕРВЕРА</div>
                            <div style="font-size:16px; font-weight:700; color:var(--yellow); margin-top:2px;">${kicks}</div>
                        </div>
                        <div style="background:var(--card-inner-bg, rgba(255,255,255,0.03)); padding:10px 12px; border-radius:6px; border:1px solid var(--border);">
                            <div style="font-size:11px; color:var(--text-muted);">БАНОВ СЕРВЕРА</div>
                            <div style="font-size:16px; font-weight:700; color:${bans > 0 ? "var(--red)" : "var(--green)"}; margin-top:2px;">${bans}</div>
                        </div>
                    </div>

                    <div style="background:var(--card-inner-bg, rgba(255,255,255,0.03)); padding:12px; border-radius:6px; border:1px solid var(--border);">
                        <div style="color:var(--text-muted); font-size:11.5px; text-transform:uppercase; font-weight:700; margin-bottom:4px;">Заметки модераторов</div>
                        <div style="font-size:12px; color:var(--text-dim); line-height:1.4;">${esc(notes)}</div>
                    </div>
                </div>
            `;
    }

    // Polling Violations filtered by player
    async function fetchPlayerViolations() {
      try {
        const res = await api("GET", "/api/vesuvio/violations?limit=50");
        const list = Array.isArray(res) ? res : res.violations || [];
        const playerViolations = list.filter((v) => {
          const vName = (v.playerName || v.name || "").toLowerCase();
          const vUuid = (v.playerUuid || v.uuid || "").toLowerCase();
          return (
            vName === playerName.toLowerCase() ||
            (playerUuid && vUuid === playerUuid.toLowerCase())
          );
        });
        const streamEl = document.getElementById("obs-violations-stream");
        const countEl = document.getElementById("obs-flags-count");
        const vlEl = document.getElementById("obs-badge-vl");
        if (!streamEl) return;
        if (countEl) countEl.textContent = playerViolations.length + " флагов";
        let maxVl = 0;
        playerViolations.forEach((v) => {
          const vl = v.vl ?? v.vlScore ?? 0;
          if (vl > maxVl) maxVl = vl;
        });
        if (vlEl) vlEl.textContent = "MAX VL: " + (maxVl || 0);
        if (!playerViolations.length) {
          streamEl.innerHTML = `
                        <div style="text-align:center; padding:30px; color:var(--text-dim); background:var(--card-inner-bg, rgba(255,255,255,0.02)); border-radius:6px; border:1px dashed var(--border);">
                            Нет активных флагов античита за последнее время
                        </div>
                    `;
          return;
        }
        streamEl.innerHTML = playerViolations
          .map((v) => {
            const check = v.checkName || v.check || v.type || "Movement";
            const vl = v.vl ?? v.vlScore ?? 0;
            return `
                        <div style="background:var(--card-inner-bg, rgba(255,255,255,0.03)); border:1px solid var(--border); border-radius:6px; padding:10px 12px; display:flex; justify-content:space-between; align-items:center;">
                            <div>
                                <div style="display:flex; align-items:center; gap:6px;">
                                    <span class="badge red" style="font-size:11px;">${esc(check)}</span>
                                    <span class="font-mono" style="font-size:11px; color:var(--text-dim);">${fmtTime(v.timestamp || Date.now() / 1000)}</span>
                                </div>
                                <div style="font-size:11.5px; color:var(--text-muted); margin-top:3px;">
                                    ${esc(v.details || v.description || "Эвристическое отклонение пакетов")}
                                </div>
                            </div>
                            <div style="text-align:right;">
                                <div style="font-size:11px; color:var(--text-dim);">VL</div>
                                <div class="font-mono" style="font-size:14px; font-weight:700; color:var(--yellow);">+${vl}</div>
                            </div>
                        </div>
                    `;
          })
          .join("");
      } catch (_) {}
    }
    await fetchPlayerViolations();
    state.pollers["observation"] = setInterval(fetchPlayerViolations, 3000);
  };
}
export function exposeCloseObservationView() {
  window.closeObservationView = function () {
    if (state.pollers["observation"]) {
      clearInterval(state.pollers["observation"]);
      delete state.pollers["observation"];
    }
    renderAnticheatView();
  };
}
export function exposeTogglePlayerSuspect() {
  window.togglePlayerSuspect = async function (playerName, forceVal = null) {
    try {
      const p = await api(
        "GET",
        `/api/players/${encodeURIComponent(playerName)}`,
      );
      const uuid = p.uuid;
      const current =
        forceVal !== null
          ? !forceVal
          : p.anticheat && p.anticheat.manualSuspect;
      const next = !current;
      await api(
        "POST",
        `/api/vesuvio/player/${encodeURIComponent(uuid)}/suspect`,
        {
          suspect: next,
        },
      );
      showToast(
        "Античит",
        next
          ? `Игрок ${playerName} добавлен в список наблюдения`
          : `С игрока ${playerName} снято наблюдение`,
        "info",
      );
      if (state.activeNavSection === "anticheat") renderAnticheatView();
    } catch (e) {
      await noticeDialog(
        "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435",
        "Ошибка переключения наблюдения: " + e.message,
      );
    }
  };

  // ==========================================================================
  // 5. СЕРВЕР (ИГРОКИ, КОНСОЛЬ, WHITELIST & OPs)
  // ==========================================================================
}
