import { api, esc, fmtTime, hasPerm, showToast } from "./ui.js";

// ==========================================================================
// ФИЛЬТР ЧАТА (LoveChatFilter): слова, проверка текста, настройки, статистика
// ==========================================================================

const KINDS = [
  ["bad", "Запрещённые слова", "Мат и оскорбления: ловятся всеми слоями обхода (латиница, цифры, разбивка пробелами, опечатки)."],
  ["safe", "Безопасные слова", "Невинные слова, которые никогда не считаются матом, даже если содержат запрещённый кусок. «*» в конце - любое окончание."],
  ["ad", "Реклама", "Домен, адрес или название сервера, которые нельзя писать в чате."],
];
const TYPES = [
  ["profanity", "Мат"],
  ["spam", "Спам"],
  ["caps", "Капс"],
  ["ads", "Реклама"],
];

export async function renderChatFilterView() {
  const area = document.getElementById("content-area");
  area.innerHTML = `
    <div class="view-header">
      <div class="view-title-block">
        <h2>ФИЛЬТР ЧАТА</h2>
        <p>Слова, настройки и статистика LoveChatFilter</p>
      </div>
    </div>
    <div class="sub-tabs-bar" id="cf-tabs">
      <button type="button" class="sub-tab-btn active" data-tab="words">СЛОВА</button>
      <button type="button" class="sub-tab-btn" data-tab="check">ПРОВЕРКА ТЕКСТА</button>
      <button type="button" class="sub-tab-btn" data-tab="settings">НАСТРОЙКИ</button>
      <button type="button" class="sub-tab-btn" data-tab="stats">СТАТИСТИКА</button>
    </div>
    <div id="cf-content"></div>`;
  const content = area.querySelector("#cf-content");
  const canManage = hasPerm("MANAGE_CHATFILTER");
  let status;
  try {
    status = await api("GET", "/api/chatfilter/status");
  } catch (error) {
    content.innerHTML = `<p class="error">${esc(error.message)}</p>`;
    return;
  }
  if (!status.available) {
    area.querySelector("#cf-tabs").hidden = true;
    content.innerHTML = `<div class="stat-card"><div class="stat-label">LoveChatFilter недоступен</div><p>Плагин не установлен или ещё не загрузился. Установите LoveChatFilter и откройте раздел снова.</p></div>`;
    return;
  }
  const tabs = { words: wordsTab, check: checkTab, settings: settingsTab, stats: statsTab };
  const open = async (name) => {
    area.querySelectorAll("#cf-tabs .sub-tab-btn").forEach((b) => b.classList.toggle("active", b.dataset.tab === name));
    content.innerHTML = "";
    try {
      await tabs[name](content, canManage);
    } catch (error) {
      content.innerHTML = `<p class="error">${esc(error.message)}</p>`;
    }
  };
  area.querySelectorAll("#cf-tabs .sub-tab-btn").forEach((b) => b.addEventListener("click", () => open(b.dataset.tab)));
  await open("words");
}

// ---------------------------------------------------------------- слова

async function wordsTab(content, canManage, kind = "bad") {
  const rows = await api("GET", "/api/chatfilter/words?kind=" + encodeURIComponent(kind));
  const info = KINDS.find((k) => k[0] === kind);
  content.innerHTML = `
    <div class="sub-tabs-bar" id="cf-kinds">
      ${KINDS.map((k) => `<button type="button" class="sub-tab-btn ${k[0] === kind ? "active" : ""}" data-kind="${k[0]}">${esc(k[1])}</button>`).join("")}
    </div>
    <p class="muted">${esc(info[2])}</p>
    ${canManage ? `<form class="row" id="cf-add"><input type="text" name="word" maxlength="100" placeholder="Новое слово" required><button class="primary" type="submit">Добавить</button></form>` : ""}
    <div class="row"><input type="search" id="cf-filter" maxlength="64" placeholder="Поиск по списку"></div>
    <div class="table-wrap"><table><thead><tr><th>Слово</th><th>Источник</th><th>Добавил</th><th></th></tr></thead><tbody id="cf-rows"></tbody></table></div>`;
  const body = content.querySelector("#cf-rows");
  const draw = () => {
    const needle = content.querySelector("#cf-filter").value.trim().toLowerCase();
    const shown = rows.filter((r) => !needle || r.word.toLowerCase().includes(needle));
    body.innerHTML = shown.length
      ? shown
          .map(
            (r, i) => `<tr>
              <td class="font-mono">${esc(r.word)}</td>
              <td><span class="badge ${r.source === "game" ? "purple" : "gray"}">${r.source === "game" ? "из игры и панели" : "config.yml"}</span></td>
              <td>${r.source === "game" ? `${esc(r.addedBy || "")} · ${fmtTime(r.addedAt)}` : "—"}</td>
              <td>${canManage && r.source === "game" ? `<button type="button" class="danger btn-sm" data-remove="${i}">Убрать</button>` : ""}</td>
            </tr>`,
          )
          .join("")
      : `<tr><td colspan="4" class="muted">Слов нет</td></tr>`;
    body.querySelectorAll("[data-remove]").forEach((button) =>
      button.addEventListener("click", async () => {
        const word = shown[Number(button.dataset.remove)].word;
        try {
          await api("POST", "/api/chatfilter/words/remove", { kind, word });
          showToast("Слово убрано", word, "success");
          await wordsTab(content, canManage, kind);
        } catch (error) {
          showToast("Не удалось убрать", error.message, "error");
        }
      }),
    );
  };
  draw();
  content.querySelector("#cf-filter").addEventListener("input", draw);
  content.querySelectorAll("#cf-kinds [data-kind]").forEach((b) => b.addEventListener("click", () => wordsTab(content, canManage, b.dataset.kind)));
  content.querySelector("#cf-add")?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const input = event.target.elements.word;
    try {
      const result = await api("POST", "/api/chatfilter/words", { kind, word: input.value });
      showToast("Слово добавлено", result.word + " - работает сразу", "success");
      await wordsTab(content, canManage, kind);
    } catch (error) {
      showToast("Не удалось добавить", error.message, "error");
    }
  });
}

// ---------------------------------------------------------------- проверка текста

async function checkTab(content, canManage) {
  content.innerHTML = `
    <p class="muted">Проверка ничего не отправляет в чат: показывается, что сделает фильтр с текстом.</p>
    <div class="form-group"><textarea id="cf-text" rows="4" maxlength="500" placeholder="Введите фразу для проверки"></textarea></div>
    <div class="row"><button class="primary" type="button" id="cf-run">Проверить</button></div>
    <div id="cf-result"></div>`;
  const text = content.querySelector("#cf-text");
  const result = content.querySelector("#cf-result");
  const run = async () => {
    if (!text.value.trim()) return;
    try {
      const r = await api("POST", "/api/chatfilter/check", { text: text.value });
      const found = [];
      if (r.profane) found.push("мат" + (r.layers && r.layers.length ? " (" + r.layers.join(", ") + ")" : ""));
      if (r.ads) found.push("реклама");
      if (r.caps) found.push("капс");
      result.innerHTML = `
        <div class="stat-card">
          <div class="stat-label">${r.clean ? `<span class="badge green">Чисто</span>` : `<span class="badge red">Найдено: ${esc(found.join(", "))}</span>`}</div>
          <p>Так увидят в чате: <span class="font-mono">${esc(r.text)}</span></p>
        </div>
        ${
          canManage
            ? `<form class="row" id="cf-quick">
                 <input type="text" name="word" maxlength="100" placeholder="Слово или кусок текста" required>
                 <select name="kind">${KINDS.map((k) => `<option value="${k[0]}">${esc(k[1])}</option>`).join("")}</select>
                 <button class="secondary" type="submit">Добавить в список</button>
               </form>
               <p class="muted">Выделите слово в тексте выше и нажмите «Добавить в список»: оно подставится само. Если мат не нашёлся, добавьте его в «Запрещённые», если слово зря закрасилось, добавьте в «Безопасные».</p>`
            : ""
        }`;
      const quick = result.querySelector("#cf-quick");
      quick?.addEventListener("submit", async (event) => {
        event.preventDefault();
        const form = event.target.elements;
        try {
          const added = await api("POST", "/api/chatfilter/words", { kind: form.kind.value, word: form.word.value });
          showToast("Слово добавлено", added.word + " - работает сразу", "success");
          await run();
        } catch (error) {
          showToast("Не удалось добавить", error.message, "error");
        }
      });
      // The selected part of the text becomes the word to add; the whole text is not taken, so a phrase is never added by accident.
      quick?.querySelector("button")?.addEventListener("mousedown", () => {
        const selected = text.value.slice(text.selectionStart, text.selectionEnd).trim();
        if (selected) quick.elements.word.value = selected;
      });
    } catch (error) {
      result.innerHTML = `<p class="error">${esc(error.message)}</p>`;
    }
  };
  content.querySelector("#cf-run").addEventListener("click", run);
}

// ---------------------------------------------------------------- настройки

async function settingsTab(content, canManage) {
  const rows = await api("GET", "/api/chatfilter/settings");
  const groups = [...new Set(rows.map((r) => r.group))];
  content.innerHTML = `
    <p class="muted">Значения, изменённые здесь, хранятся поверх config.yml (сам файл не переписывается). «Вернуть» возвращает значение из файла.</p>
    ${groups
      .map(
        (group) => `<h3>${esc(group)}</h3><div class="table-wrap"><table><tbody>
          ${rows
            .filter((r) => r.group === group)
            .map((r) => `<tr data-key="${esc(r.key)}"><td>${esc(r.label)}${r.overridden ? ` <span class="badge purple">из панели</span>` : ""}<br><span class="muted font-mono">${esc(r.key)}</span></td><td>${control(r, canManage)}</td></tr>`)
            .join("")}
        </tbody></table></div>`,
      )
      .join("")}`;
  content.querySelectorAll("tr[data-key]").forEach((row) => {
    const key = row.dataset.key;
    const input = row.querySelector("[data-value]");
    const save = async (value) => {
      try {
        await api("POST", "/api/chatfilter/settings", { key, value: String(value) });
        showToast("Настройка сохранена", key, "success");
        await settingsTab(content, canManage);
      } catch (error) {
        showToast("Не сохранено", error.message, "error");
      }
    };
    if (input?.tagName === "SELECT") input.addEventListener("change", () => save(input.value));
    row.querySelector("[data-save]")?.addEventListener("click", () => save(input.value));
    row.querySelector("[data-reset]")?.addEventListener("click", async () => {
      try {
        await api("POST", "/api/chatfilter/settings/reset", { key });
        showToast("Значение из config.yml", key, "success");
        await settingsTab(content, canManage);
      } catch (error) {
        showToast("Не сброшено", error.message, "error");
      }
    });
  });
}

function control(row, canManage) {
  const disabled = canManage ? "" : "disabled";
  const reset = canManage && row.overridden ? ` <button type="button" class="secondary btn-sm" data-reset>Вернуть</button>` : "";
  if (row.type === "bool")
    return `<select data-value ${disabled}><option value="true" ${row.value === true ? "selected" : ""}>Включено</option><option value="false" ${row.value === false ? "selected" : ""}>Выключено</option></select>${reset}`;
  const attrs = row.type === "int" ? `type="number" min="${Number(row.min)}" max="${Number(row.max)}"` : `type="text" maxlength="${Number(row.max)}"`;
  return `<input data-value ${attrs} value="${esc(String(row.value ?? ""))}" ${disabled}>${canManage ? ` <button type="button" class="primary btn-sm" data-save>Сохранить</button>` : ""}${reset}`;
}

// ---------------------------------------------------------------- статистика

async function statsTab(content, canManage, days = 30) {
  const data = await api("GET", "/api/chatfilter/stats?days=" + days);
  const peak = Math.max(1, ...data.perDay.map((d) => d.total));
  content.innerHTML = `
    <div class="row"><label>За период <select id="cf-days">${[7, 14, 30, 90].map((d) => `<option value="${d}" ${d === days ? "selected" : ""}>${d} дн.</option>`).join("")}</select></label></div>
    <div class="row">${TYPES.map(([key, label]) => `<div class="stat-card"><div class="stat-label">${esc(label)}</div><div class="stat-value">${Number(data.totals[key] || 0)}</div><div class="stat-sub">за ${Number(data.days)} дн.</div></div>`).join("")}</div>
    <div class="table-wrap"><table><thead><tr><th>День</th>${TYPES.map(([, label]) => `<th>${esc(label)}</th>`).join("")}<th>Всего</th><th></th></tr></thead><tbody>
      ${[...data.perDay]
        .reverse()
        .map(
          (d) => `<tr><td>${esc(d.day)}</td>${TYPES.map(([key]) => `<td>${Number(d[key])}</td>`).join("")}<td>${Number(d.total)}</td><td><div style="height:8px;border-radius:4px;background:var(--accent,#8b5cf6);width:${Math.round((100 * d.total) / peak)}%"></div></td></tr>`,
        )
        .join("")}
    </tbody></table></div>
    <p class="muted">Счётчики ведутся плагином и сохраняются между перезапусками; в статистику попадает каждое сработавшее нарушение.</p>`;
  content.querySelector("#cf-days").addEventListener("change", (event) => statsTab(content, canManage, Number(event.target.value)));
}
