// Small independent forms for global configuration and personal delivery preferences.
const scopes = {
  server: {
    title: "Веб-панель",
    intro:
      "Адрес и порт определяют, где открывается сайт. Внешний адрес нужен для ссылок из Discord. Смена адреса, порта и внешнего URL применяется после перезапуска.",
    permission: "MANAGE_PANEL_SETTINGS",
    groups: [
      [
        "Доступ к сайту",
        [
          [
            "webEnabled",
            "Включить веб-сайт",
            "checkbox",
            "Отключение вступит в силу после перезапуска; текущая страница останется доступна до него.",
            true,
          ],
          [
            "webHost",
            "Адрес прослушивания",
            "text",
            "0.0.0.0 — все интерфейсы; 127.0.0.1 — только эта машина или локальный обратный прокси.",
            "0.0.0.0",
          ],
          [
            "webPort",
            "Порт сайта",
            "number",
            "Свободный TCP-порт. При внешнем доступе откройте его в настройках хостинга.",
            8080,
            1,
            65535,
          ],
          [
            "webPublicUrl",
            "Внешний адрес сайта",
            "url",
            "Полный адрес, например https://panel.example.com. Пусто — ссылки из Discord не добавляются.",
            "",
          ],
        ],
      ],
      [
        "Сессии и журнал",
        [
          [
            "sessionLifetimeMinutes",
            "Срок веб-сессии, минут",
            "number",
            "Используется для новых сессий. После истечения потребуется обычный вход с 2FA.",
            60,
            5,
            43200,
          ],
          [
            "keepLogCount",
            "Записей серверного журнала в SQLite",
            "number",
            "Ограничивает хранимую историю серверного журнала; не является лимитом истории дел.",
            5000,
            500,
            50000,
          ],
        ],
      ],
    ],
  },
  discord: {
    title: "Бот и обращения Discord",
    intro:
      "Бот и его токен настраиваются в ядре сервера. Здесь задаются каналы и правила обращений. Вебхук безопасности находится в отдельном разделе и не создаёт кнопки или тикеты.",
    permission: "MANAGE_INTEGRATIONS",
    groups: [
      [
        "Панель обращений и персонал",
        [
          [
            "discordPanelChannelId",
            "Текстовый канал с кнопками обращений",
            "text",
            "ПКМ по текстовому каналу Discord → «Скопировать ID». Нужен режим разработчика Discord. Пусто — постоянная панель выключена.",
            "",
          ],
          [
            "discordQueueChannelId",
            "Закрытый канал очереди персонала",
            "text",
            "Другой текстовый канал: краткие карточки и назначение ответственного. Пусто — очередь не публикуется.",
            "",
          ],
          [
            "discordSupervisorRoleIds",
            "Discord ID ролей руководства",
            "list",
            "По одному ID роли на строку, максимум 20. Это доступ к каналам; права сотрудника в веб-панели проверяются отдельно.",
            [],
          ],
        ],
      ],
      [
        "Сроки ожидания",
        [
          [
            "discordStaffReminderMinutes",
            "Напомнить сотруднику через, минут",
            "number",
            "0 отключает напоминание. Таймер никогда не принимает решение и не закрывает дело.",
            20,
            0,
            43200,
          ],
          [
            "discordStaffEscalationMinutes",
            "Напомнить руководству через, минут",
            "number",
            "Не раньше напоминания сотруднику. 0 отключает этот порог.",
            60,
            0,
            43200,
          ],
          [
            "discordPlayerReminderMinutes",
            "Напомнить игроку через, минут",
            "number",
            "2880 минут — 48 часов. 0 отключает напоминание; отсутствие ответа не выносит вердикт.",
            2880,
            0,
            43200,
          ],
        ],
      ],
      [
        "Уведомления о делах",
        [
          [
            "discordReportsTransport",
            "Способ отправки",
            "select",
            "Относится к уведомлениям. Панель с кнопками всегда требует бота.",
            "auto",
            [
              ["auto", "Автоматически: бот, иначе вебхук"],
              ["bot", "Только бот"],
              ["webhook", "Только вебхук"],
              ["both", "Бот и вебхук"],
            ],
          ],
          [
            "discordReportsChannelId",
            "Дополнительный канал жалоб",
            "text",
            "ID текстового канала уведомлений; не категория тикетов. Можно оставить пустым.",
            "",
          ],
          [
            "discordAppealsChannelId",
            "Дополнительный канал апелляций",
            "text",
            "ID текстового канала уведомлений. Можно оставить пустым.",
            "",
          ],
        ],
      ],
      [
        "Тихие часы персонала",
        [
          [
            "discordQuietEnabled",
            "Откладывать служебные личные сообщения ночью",
            "checkbox",
            "Уведомления игроку о наказании не задерживаются. Личные предпочтения сотрудника также учитываются.",
            false,
          ],
          [
            "discordQuietStartHour",
            "Начало, час",
            "number",
            "От 0 до 23. Например, 23 означает 23:00.",
            23,
            0,
            23,
          ],
          [
            "discordQuietEndHour",
            "Окончание, час",
            "number",
            "Можно указать время следующего дня. Одинаковые часы не создают период тишины.",
            8,
            0,
            23,
          ],
          [
            "discordQuietTimezone",
            "Часовой пояс",
            "text",
            "Например Europe/Moscow, Europe/Kyiv или UTC. Используйте название часового пояса, а не текущий час.",
            "Europe/Moscow",
          ],
        ],
      ],
    ],
  },
  preview: {
    title: "Preview и тестовый Discord",
    intro:
      "Локальный Preview моделирует интерфейс. Серверный Preview использует отдельную SQLite; настоящие сообщения допускаются только в выделенной тестовой категории. Вход и 2FA рабочего сайта не отключаются.",
    permission: "MANAGE_PREVIEW",
    groups: [
      [
        "Песочница",
        [
          [
            "previewEnabled",
            "Включить Preview",
            "checkbox",
            "Открывает /preview/. Доступ к серверным песочницам требует отдельного права сотрудника.",
            false,
          ],
        ],
      ],
      [
        "Подключение тестового Discord",
        [
          [
            "previewCategoryId",
            "1. ID отдельной тестовой категории",
            "text",
            "Создайте категорию Discord. Она должна отличаться от рабочих категорий тикетов.",
            "",
          ],
          [
            "previewChannelId",
            "2. ID текстового канала внутри категории",
            "text",
            "Создайте текстовый канал внутри категории выше. У канала ДРУГОЙ ID; вставлять сюда ID категории нельзя.",
            "",
          ],
          [
            "previewAllowedUserIds",
            "3. Discord ID тестировщиков",
            "list",
            "По одному ID пользователя на строку, максимум 10. Нужен хотя бы один для настоящих отправок. Это не роли и не Minecraft UUID.",
            [],
          ],
        ],
      ],
    ],
  },
  webhooks: {
    title: "Уведомления безопасности Discord",
    intro:
      "Отдельный вебхук для событий входа и безопасности. Он не управляет ботом, личными сообщениями или панелью жалоб. Старый секрет не показывается и не подставляется в форму.",
    permission: "MANAGE_INTEGRATIONS",
    groups: [
      [
        "Канал безопасности",
        [
          [
            "webhooksEnabled",
            "Отправлять уведомления безопасности",
            "checkbox",
            "Включайте после сохранения корректного webhook URL. Только Discord.",
            false,
          ],
          [
            "discordWebhookUrl",
            "Новый адрес Discord webhook",
            "password",
            "Discord → настройки закрытого канала → интеграции → вебхуки. Пустое поле сохраняет действующий адрес.",
            "",
          ],
          [
            "clearDiscordWebhookUrl",
            "Удалить сохранённый адрес",
            "checkbox",
            "Применится только после сохранения. Также выключит отправку; оставлять поле адреса пустым для удаления недостаточно.",
            false,
          ],
        ],
      ],
      [
        "Какие события отправлять",
        [
          [
            "webhookNotifyNewIp",
            "Вход сотрудника с нового IP",
            "checkbox",
            "Помогает обнаружить вход с неизвестного устройства или сети.",
            true,
          ],
          [
            "webhookNotifyAllLogins",
            "Каждый успешный вход",
            "checkbox",
            "Увеличивает количество сообщений. По умолчанию достаточно уведомлять о новом IP.",
            false,
          ],
          [
            "webhookNotifyBruteForce",
            "Блокировка перебора паролей",
            "checkbox",
            "Срабатывание ограничения неудачных попыток входа.",
            true,
          ],
          [
            "webhookNotifyRegistration",
            "Новая заявка сотрудника",
            "checkbox",
            "Уведомление о регистрации, ожидающей рассмотрения.",
            true,
          ],
          [
            "webhookNotifyCommands",
            "Команды из веб-консоли",
            "checkbox",
            "Сообщение содержит сотрудника и выполненную команду. Канал должен быть закрытым.",
            true,
          ],
          [
            "webhookNotifyDestructive",
            "Опасные административные действия",
            "checkbox",
            "Например, удаление или массовый сброс данных.",
            true,
          ],
        ],
      ],
    ],
  },
};
export function renderSettingsSection(
  host,
  kind,
  { api, config, hasPerm, onSaved, drafts = {}, unavailable },
) {
  const schema = scopes[kind];
  host.replaceChildren();
  host.classList.add("settings-section");
  const heading = document.createElement("h4");
  heading.textContent = schema.title;
  const intro = document.createElement("p");
  intro.className = "settings-help";
  intro.textContent = schema.intro;
  host.append(heading, intro);
  const form = document.createElement("form"),
    status = document.createElement("p");
  status.setAttribute("role", "status");
  status.className = "settings-save-status";
  const fields = new Map(),
    original = {};
  for (const [title, items] of schema.groups) {
    const card = document.createElement("fieldset"),
      legend = document.createElement("legend");
    legend.textContent = title;
    card.append(legend);
    const grid = document.createElement("div");
    grid.className = "settings-fields";
    for (const [key, label, type, help, fallback, min, max] of items) {
      const wrap = document.createElement("div");
      wrap.className = "settings-field";
      const caption = document.createElement("label");
      caption.htmlFor = "setting-" + key;
      caption.textContent = label;
      const input = document.createElement(
        type === "list" ? "textarea" : type === "select" ? "select" : "input",
      );
      input.id = "setting-" + key;
      const value =
        key === "discordWebhookUrl"
          ? ""
          : (drafts[kind]?.[key] ?? config[key] ?? fallback);
      original[key] =
        key === "discordWebhookUrl" ? "" : (config[key] ?? fallback);
      if (type === "select")
        for (const [v, text] of min) {
          const option = document.createElement("option");
          option.value = v;
          option.textContent = text;
          input.append(option);
        }
      else if (type !== "list") input.type = type;
      if (type === "checkbox") input.checked = !!value;
      else
        input.value =
          type === "list"
            ? Array.isArray(value)
              ? value.join("\n")
              : ""
            : String(value);
      if (type === "number") {
        input.min = min;
        input.max = max;
        input.step = "1";
        input.required = true;
      }
      if (type === "list") input.rows = 3;
      if (type === "password") {
        input.autocomplete = "new-password";
        input.placeholder = config.discordWebhookConfigured
          ? "Адрес сохранён. Для замены введите новый"
          : "Адрес ещё не задан";
      }
      const note = document.createElement("p");
      note.className = "settings-help";
      note.id = input.id + "-help";
      note.textContent = help;
      input.setAttribute("aria-describedby", note.id);
      if (type === "checkbox") {
        wrap.classList.add("settings-toggle");
        caption.prepend(input);
        wrap.append(caption, note);
      } else wrap.append(caption, input, note);
      input.disabled = !!unavailable || !hasPerm(schema.permission);
      fields.set(key, { input, type, label });
      grid.append(wrap);
      input.addEventListener("input", () => {
        drafts[kind] = read();
        status.textContent = "Есть несохранённые изменения.";
      });
    }
    card.append(grid);
    form.append(card);
  }
  function read() {
    const data = {};
    for (const [key, { input, type }] of fields)
      data[key] =
        type === "checkbox"
          ? input.checked
          : type === "number"
            ? Number(input.value)
            : type === "list"
              ? input.value
                  .split(/[\n,]/)
                  .map((s) => s.trim())
                  .filter(Boolean)
              : input.value.trim();
    return data;
  }
  if (kind === "webhooks") {
    const clear = fields.get("clearDiscordWebhookUrl").input,
      url = fields.get("discordWebhookUrl").input;
    clear.addEventListener("change", () => {
      url.disabled = clear.checked;
      if (clear.checked) url.value = "";
      drafts[kind] = read();
    });
    if (clear.checked) {
      url.disabled = true;
      url.value = "";
    }
  }
  const save = document.createElement("button");
  save.type = "submit";
  save.className = "primary";
  save.textContent = "Сохранить настройки";
  save.disabled = !!unavailable || !hasPerm(schema.permission);
  form.append(save, status);
  host.append(form);
  if (drafts[kind]) status.textContent = "Есть несохранённые изменения.";
  if (unavailable)
    status.textContent =
      "Настройки не загружены: " +
      unavailable +
      ". Сохранение недоступно, чтобы не заменить значения шаблоном.";
  if (kind === "discord") {
    const link = document.createElement("a");
    link.href = "/deliveries.html";
    link.textContent = "Открыть диагностику и публикацию панели обращений";
    host.append(link);
  }
  if (kind === "preview") {
    const link = document.createElement("a");
    link.href = "/preview/";
    link.textContent = "Открыть Preview и проверки Discord";
    host.append(link);
  }
  let saving = false;
  form.onsubmit = async (event) => {
    event.preventDefault();
    if (saving) return;
    const data = read(),
      patch = {};
    for (const [key, value] of Object.entries(data)) {
      if (
        (key === "discordWebhookUrl" && !value) ||
        (key === "clearDiscordWebhookUrl" && !value)
      )
        continue;
      if (JSON.stringify(value) !== JSON.stringify(original[key]))
        patch[key] = value;
    }
    if (!Object.keys(patch).length) {
      status.textContent = "Изменений нет.";
      return;
    }
    saving = true;
    save.disabled = true;
    for (const field of fields.values()) field.input.disabled = true;
    status.textContent = "Сохранение…";
    try {
      const response = await api("POST", "/api/server/config", patch);
      Object.assign(config, response);
      onSaved(response);
      delete drafts[kind];
      for (const [key, field] of fields) {
        original[key] = response[key] ?? data[key];
        if (key !== "discordWebhookUrl" && key !== "clearDiscordWebhookUrl") {
          if (field.type === "checkbox") field.input.checked = !!original[key];
          else
            field.input.value =
              field.type === "list"
                ? original[key].join("\n")
                : String(original[key]);
        }
        if (key === "discordWebhookUrl") {
          field.input.value = "";
          original[key] = "";
          field.input.placeholder = response.discordWebhookConfigured
            ? "Адрес сохранён. Для замены введите новый"
            : "Адрес не задан";
        }
        if (key === "clearDiscordWebhookUrl") {
          field.input.checked = false;
          original[key] = false;
        }
      }
      if (kind === "webhooks")
        fields.get("discordWebhookUrl").input.disabled = false;
      status.textContent = response.restartRequiredFields?.length
        ? "Сохранено в config.yml. Для применения изменённых каналов, адреса сайта или правил требуется перезапуск плагина/сервера."
        : "Настройки сохранены.";
    } catch (error) {
      status.textContent = error.message;
    } finally {
      saving = false;
      save.disabled = false;
      for (const [key, field] of fields)
        field.input.disabled =
          !!unavailable ||
          !hasPerm(schema.permission) ||
          (key === "discordWebhookUrl" &&
            fields.get("clearDiscordWebhookUrl")?.input.checked);
    }
  };
}

export function renderPersonalNotifications(
  host,
  { api, me, preferences, onSaved },
) {
  host.replaceChildren();
  host.classList.add("settings-section");
  host.innerHTML =
    '<h4>Мои уведомления Discord</h4><p class="settings-help">Эти переключатели влияют только на ваши личные сообщения от бота. Они не изменяют серверные каналы, роли или уведомления других сотрудников.</p>';
  const account = document.createElement("p");
  account.textContent = me.discordVerified
    ? "Подтверждённый аккаунт: " + me.discordId
    : "Discord не привязан. Сначала подтвердите привязку аккаунта через сервер.";
  host.append(account);
  const form = document.createElement("form");
  for (const [key, text] of [
    ["discordNotifyReports", "Получать сообщения о новых жалобах"],
    ["discordNotifyTickets", "Получать сообщения об апелляциях"],
  ]) {
    const label = document.createElement("label");
    label.className = "settings-personal-toggle";
    const input = document.createElement("input");
    input.type = "checkbox";
    input.name = key;
    input.checked = preferences[key] === true;
    input.disabled = !me.discordVerified;
    label.append(input, document.createTextNode(text));
    form.append(label);
  }
  const save = document.createElement("button");
  save.type = "submit";
  save.className = "primary";
  save.textContent = "Сохранить мои уведомления";
  save.disabled = !me.discordVerified;
  const status = document.createElement("p");
  status.setAttribute("role", "status");
  form.append(save, status);
  host.append(form);
  let saving = false;
  form.onsubmit = async (e) => {
    e.preventDefault();
    if (saving) return;
    saving = true;
    save.disabled = true;
    for (const input of form.querySelectorAll("input")) input.disabled = true;
    try {
      const next = { ...preferences };
      delete next.discordId;
      for (const input of form.querySelectorAll("input"))
        next[input.name] = input.checked;
      await api("PUT", "/api/me/preferences", { uiPreferences: next });
      Object.assign(preferences, next);
      onSaved(next);
      status.textContent = "Личные уведомления сохранены.";
    } catch (error) {
      status.textContent = error.message;
    } finally {
      saving = false;
      save.disabled = false;
      for (const input of form.querySelectorAll("input"))
        input.disabled = !me.discordVerified;
    }
  };
}
