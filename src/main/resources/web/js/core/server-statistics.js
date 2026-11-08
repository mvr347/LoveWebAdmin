import { api, esc, fmtTime } from "../features/ui.js";

export async function renderServerStatistics(host) {
  const request = {};
  host.statisticsRequest = request;
  host.textContent = "Загружаем общую статистику сервера…";
  try {
    const data = await api("GET", "/api/analytics/general");
    if (!host.isConnected || host.statisticsRequest !== request) return;
    host.innerHTML = `<p class="muted">Текущее состояние и накопленные данные сервера. Показатели с пометкой «онлайн» относятся только к указанной группе игроков. Период уникальных посещений сохраняется в соседнем режиме.</p><div class="server-statistics-grid">${(
      data.sections || []
    )
      .map(
        (section) =>
          `<section class="server-statistics-section" data-stat-section="${esc(section.id)}"><h3>${esc(section.title)}</h3><p class="stat-source">Обновлено${section.observedAt ? " · " + esc(fmtTime(section.observedAt)) : ""}${section.stale ? " · Сохранённый снимок" : ""}</p>${section.reason ? `<p class="muted">Данные пока недоступны</p>` : ""}<dl>${Object.entries(
            section.fields || {},
          )
            .map(
              ([label, value]) =>
                `<div><dt>${esc(label)}</dt><dd>${esc(label === "Время измерения" ? fmtTime(value) : format(value))}</dd></div>`,
            )
            .join(
              "",
            )}</dl>${!section.available ? '<p class="muted">Нет данных</p>' : ""}</section>`,
      )
      .join("")}</div>`;
  } catch (error) {
    if (host.isConnected && host.statisticsRequest === request)
      host.textContent =
        "Не удалось загрузить общую статистику: " + error.message;
  }
}
function format(value) {
  if (value === null || value === undefined) return "Нет данных";
  if (typeof value === "boolean") return value ? "Да" : "Нет";
  if (typeof value === "number")
    return Number.isFinite(value)
      ? value.toLocaleString("ru-RU")
      : "Нет данных";
  return typeof value === "string" ? value : "Нет данных";
}
