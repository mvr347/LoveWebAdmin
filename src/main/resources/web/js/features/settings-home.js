import { hasPerm } from "./ui.js";
import { openAdminSettingsModal } from "./settings.js";
import { renderServerWhitelistTab } from "./server.js";

export function renderSettingsHome() {
  const area = document.getElementById("content-area");
  const groups = [
    [
      "Веб-панель",
      "Общие настройки сайта для всех сотрудников.",
      [
        [
          "server",
          "Сайт и сессии",
          "Адрес, порт, внешние ссылки, время сессии и хранение журнала.",
          "MANAGE_PANEL_SETTINGS",
        ],
        [
          "panel-security",
          "Правила входа",
          "Требования к паролям, блокировка подбора и привязка сессий к IP.",
          "MANAGE_SECURITY_SETTINGS",
        ],
        [
          "blacklist",
          "Команды веб-консоли",
          "Запрет отдельных команд при выполнении через панель.",
          "MANAGE_SECURITY_SETTINGS",
        ],
        [
          "maintenance",
          "Обслуживание панели",
          "Временно ограничить работу сотрудников с сайтом.",
          "MANAGE_MAINTENANCE",
        ],
      ],
    ],
    [
      "Игровой сервер",
      "Доступ игроков к Minecraft. Права сотрудников панели настраиваются в разделе «Персонал».",
      [
        [
          "game-access",
          "Вход и операторы",
          "Белый список игроков и права операторов Minecraft.",
          ["MANAGE_WHITELIST", "MANAGE_OPERATORS"],
        ],
      ],
    ],
    [
      "Discord и проверка",
      "Обращения, уведомления и безопасная проверка сценариев.",
      [
        [
          "discord",
          "Бот и обращения",
          "Каналы обращений, очередь персонала и сроки напоминаний.",
          "MANAGE_INTEGRATIONS",
        ],
        [
          "webhooks",
          "Уведомления безопасности",
          "Доставка служебных событий через вебхук.",
          "MANAGE_INTEGRATIONS",
        ],
        [
          "preview",
          "Preview и тесты",
          "Отдельная песочница и тестовые обращения Discord.",
          "MANAGE_PREVIEW",
        ],
      ],
    ],
  ];
  area.innerHTML = `<div class="view-header"><div class="view-title-block"><h2>Настройки</h2><p>Выберите, что хотите изменить. Каждый раздел сохраняется отдельно.</p></div><button type="button" class="secondary" data-personal>Мои настройки</button></div>${groups
    .map(([title, description, entries]) => {
      const visible = entries.filter((e) =>
        (Array.isArray(e[3]) ? e[3] : [e[3]]).some(hasPerm),
      );
      return visible.length
        ? `<section class="settings-home-group"><h3>${title}</h3><p>${description}</p><div class="settings-home-grid">${visible.map(([id, label, help]) => `<button type="button" class="settings-home-card" data-settings="${id}"><strong>${label}</strong><span>${help}</span><small>Открыть настройки →</small></button>`).join("")}</div></section>`
        : "";
    })
    .join("")}<section data-game-access hidden></section>`;
  area.querySelector("[data-personal]").onclick = () =>
    openAdminSettingsModal("general");
  area.querySelectorAll("[data-settings]").forEach((button) =>
    button.addEventListener("click", () => {
      if (button.dataset.settings === "game-access") {
        const section = area.querySelector("[data-game-access]");
        section.hidden = false;
        section.innerHTML =
          "<h3>Доступ к Minecraft-серверу</h3><p>Эти настройки не меняют роли или доступ к веб-панели.</p><div data-access-content></div>";
        renderServerWhitelistTab(
          section.querySelector("[data-access-content]"),
        );
        section.scrollIntoView({ behavior: "smooth", block: "start" });
      } else openAdminSettingsModal(button.dataset.settings);
    }),
  );
}
