import { installTableControls } from "./core/table-controls.js";
import { installViewState } from "./core/view-state.js";
import { state } from "./state.js";
import {
  exposeShowToast,
  exposeCloseCurrentModal,
  exposeCloseModal,
  installModalKeyboardShortcuts,
  exposeAttachPlayerAutocomplete,
  exposeOpenReportPickerModal,
  exposeOpenQuickKickModal,
  exposeOpenQuickTeleportModal,
  exposeOpenQuickVanishModal,
  exposeOpenQuickMuteModal,
  startInitialSession,
} from "./features/ui.js";
import {
  installHistoryNavigation,
  exposeNavigateTo,
} from "./features/navigation.js";
import {
  exposeOpenQuickActionsConfigModal,
  exposeOpenQuickPlayerSearchModal,
  exposeQuickClearChatAction,
} from "./features/dashboard.js";
import {
  exposeRenderPunishmentsView,
  exposeRenderBansView,
  exposeRenderReportsView,
  exposeRefreshPunishmentsView,
  exposeOpenQuickBanModal,
  exposeViewBanDetails,
  exposeOpenScreenshotLightbox,
  exposeConfirmUnban,
  exposeHandleAcceptReport,
  exposeHandleRejectReport,
  exposeHandleDeleteReport,
  exposeHandleBanFromReportRow,
  exposeOpenReportDetailModal,
  exposeOpenAppealTemplateModal,
  exposeOpenPlayerAltsModal,
} from "./features/moderation.js";
import {
  exposeOpenObservationView,
  exposeCloseObservationView,
  exposeTogglePlayerSuspect,
} from "./features/vesuvio.js";
import {
  exposeViewPlayerProfile,
  exposeDeleteStaffNote,
} from "./features/server.js";
import {
  exposeRenderStaffView,
  exposeRenderAdminsView,
} from "./features/staff.js";
export function startApplication() {
  installViewState();
  installTableControls();
  try {
    state.browserStorage = window.LovePreview?.storage || window.localStorage;
    state.browserStorage.getItem("wa_theme");
  } catch {
    const memory = new Map();
    state.browserStorage = {
      getItem: (key) => memory.get(key) ?? null,
      setItem: (key, value) => memory.set(key, String(value)),
      removeItem: (key) => memory.delete(key),
    };
  }
  state.app = document.getElementById("app");
  state.token =
    state.browserStorage.getItem("wa_token") ||
    state.browserStorage.getItem("lwa_token");
  state.me = null;
  state.pollers = {};
  state.activeNavSection = "dashboard";
  state.banReasonsCache = null;
  state.sidebarCollapsed =
    state.browserStorage.getItem("wa_sidebar_collapsed") === "true";
  state.serverStatus = null;
  state.SECTIONS = [
    {
      id: "dashboard",
      label: "Дашборд",
      icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/></svg>`,
      num: 1,
      perm: "VIEW_STATS",
      modAllowed: true,
    },
    {
      id: "journal",
      label: "Журнал",
      icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>`,
      num: 2,
      perm: "VIEW_SERVER_LOGS",
      modAllowed: true,
    },
    {
      id: "punishments",
      label: "Наказания",
      icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>`,
      num: 3,
      perm: "VIEW_BANS",
      modAllowed: true,
    },
    {
      id: "anticheat",
      label: "Античит",
      icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>`,
      num: 4,
      perm: "VIEW_VESUVIO",
      modAllowed: true,
    },
    {
      id: "server",
      label: "Сервер",
      icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="2" width="20" height="8"/><rect x="2" y="14" width="20" height="8"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/></svg>`,
      num: 5,
      perm: "VIEW_PLAYERS",
      modAllowed: true,
    },
    {
      id: "auth",
      label: "Авторизация",
      icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>`,
      num: 6,
      perm: "MANAGE_LOVEAUTH",
      modAllowed: false,
    },
    {
      id: "staff",
      label: "Персонал",
      icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>`,
      num: 7,
      perm: "MANAGE_ADMINS",
      modAllowed: false,
    },
    {
      id: "database",
      label: "База данных",
      icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg>`,
      num: 8,
      perm: "VIEW_ANALYTICS",
      modAllowed: true,
    },
  ];
  for (const section of state.SECTIONS) {
    section.group = ["dashboard", "journal"].includes(section.id)
      ? "Работа"
      : ["punishments", "anticheat"].includes(section.id)
        ? "Модерация"
        : section.id === "staff"
          ? "Управление"
          : "Сервер и игроки";
  }
  state.SECTIONS.find((s) => s.id === "server").anyPerms = [
    "VIEW_PLAYERS",
    "EXECUTE_COMMANDS",
    "MANAGE_WHITELIST",
    "MANAGE_OPERATORS",
  ];
  state.SECTIONS.push({
    id: "restrictions",
    label: "Ограничения",
    icon: state.SECTIONS.find((s) => s.id === "punishments").icon,
    num: 9,
    group: "Модерация",
    anyPerms: ["VIEW_BANS", "MANAGE_BANS", "MANAGE_MUTES", "FREEZE_PLAYERS"],
  });
  state.SECTIONS.push({
    id: "settings",
    label: "Настройки",
    icon: state.SECTIONS.find((s) => s.id === "server").icon,
    num: 10,
    group: "Управление",
    anyPerms: [
      "MANAGE_PANEL_SETTINGS",
      "MANAGE_SECURITY_SETTINGS",
      "MANAGE_INTEGRATIONS",
      "MANAGE_PREVIEW",
      "MANAGE_MAINTENANCE",
      "MANAGE_WHITELIST",
      "MANAGE_OPERATORS",
    ],
  });
  const originalSections = new Map(
    state.SECTIONS.map((section) => [section.id, section]),
  );
  const base = (id) => ({ ...originalSections.get(id) });
  const messageIcon = `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>`;
  state.SECTIONS = [
    { ...base("dashboard"), group: "Работа", num: 1 },
    {
      ...base("journal"),
      label: "Игровой журнал",
      perm: "VIEW_SERVER_LOGS",
      anyPerms: undefined,
      group: "Работа",
      num: 2,
    },
    {
      id: "reports",
      label: "Жалобы",
      icon: messageIcon,
      num: 3,
      group: "Обращения",
      perm: "VIEW_REPORTS",
    },
    {
      id: "appeals",
      label: "Апелляции",
      icon: base("journal").icon,
      num: 4,
      group: "Обращения",
      perm: "VIEW_APPEALS",
    },
    {
      id: "support",
      label: "Поддержка",
      icon: messageIcon,
      num: 5,
      group: "Обращения",
      perm: "VIEW_REPORTS",
    },
    {
      ...base("restrictions"),
      label: "Наказания",
      num: 6,
      group: "Модерация",
    },
    {
      id: "penalty-history",
      label: "История",
      icon: base("journal").icon,
      num: 7,
      group: "Модерация",
      anyPerms: ["VIEW_BANS", "MANAGE_MUTES", "FREEZE_PLAYERS"],
    },
    { ...base("anticheat"), num: 8, group: "Модерация" },
    {
      id: "chat-filter",
      label: "Фильтр чата",
      icon: messageIcon,
      group: "Модерация",
      perm: "VIEW_CHATFILTER",
    },
    { ...base("server"), num: 9, group: "Сервер" },
    {
      id: "game-auth",
      label: "Безопасность аккаунтов",
      icon: base("auth").icon,
      perm: "MANAGE_LOVEAUTH",
      group: "Сервер",
    },
    { ...base("database"), num: undefined, group: "Сервер" },
    {
      ...base("staff"),
      label: "Сотрудники и роли",
      num: undefined,
      group: "Вебадмин",
    },
    {
      ...base("auth"),
      label: "Авторизация панели",
      anyPerms: ["MANAGE_ADMINS"],
      perm: undefined,
      group: "Вебадмин",
    },
    {
      id: "web-journal",
      label: "Веб-журнал",
      icon: base("journal").icon,
      perm: "VIEW_WEB_LOGS",
      group: "Вебадмин",
    },
    {
      ...base("settings"),
      anyPerms:["MANAGE_PANEL_SETTINGS","MANAGE_SECURITY_SETTINGS","MANAGE_INTEGRATIONS","MANAGE_MAINTENANCE","MANAGE_PREVIEW","MANAGE_API"],
      label: "Настройки панели",
      num: 10,
      group: "Вебадмин",
    },
  ];

  state.ALL_PERMISSIONS = [
    "VIEW_STATS",
    "VIEW_ANALYTICS",
    "VIEW_PLAYERS",
    "MANAGE_PLAYERS",
    "VIEW_BANS",
    "MANAGE_BANS",
    "MANAGE_MUTES",
    "VIEW_PLAYER_IP",
    "VIEW_PLAYER_INVENTORY",
    "CONFISCATE_ITEMS",
    "RESTORE_ITEMS",
    "FREEZE_PLAYERS",
    "TELEPORT_PLAYERS",
    "CHANGE_GAMEMODE",
    "KICK_PLAYERS",
    "KILL_PLAYERS",
    "VIEW_APPEALS",
    "MANAGE_APPEALS",
    "VIEW_REPORTS",
    "MANAGE_REPORTS",
    "VIEW_VESUVIO",
    "VIEW_VESUVIO_ADVANCED",
    "MANAGE_VESUVIO",
    "VIEW_SERVER_LOGS",
    "VIEW_WEB_LOGS",
    "EXECUTE_COMMANDS",
    "MANAGE_LOVEAUTH",
    "MANAGE_ADMINS",
    "MANAGE_ROLES",
    "MANAGE_PASSWORDS",
    "MANAGE_LOCKDOWN",
    "VIEW_STAFF_AUDIT",
    "VIEW_ECONOMY",
    "MANAGE_ECONOMY",
    "VIEW_CHATFILTER",
    "MANAGE_CHATFILTER",
    "MANAGE_API",
    "VIEW_SERVER_INTERNALS",
    "BYPASS_MAINTENANCE",
  ];
  state.PERMISSION_LABELS = {
    VIEW_PLAYER_IP: "Просмотр IP и связанных аккаунтов",
    VIEW_PLAYER_INVENTORY: "Просмотр инвентаря",
    CONFISCATE_ITEMS: "Изъятие предметов",
    RESTORE_ITEMS: "Возврат изъятого",
    FREEZE_PLAYERS: "Заморозка игроков",
    TELEPORT_PLAYERS: "Телепортация игроков",
    CHANGE_GAMEMODE: "Изменение режима игры",
    KICK_PLAYERS: "Отключение игроков",
    KILL_PLAYERS: "Убийство игроков",
    VIEW_STATS: "Просмотр метрик сервера (TPS, MSPT, память, онлайн)",
    VIEW_ANALYTICS: "Просмотр аналитики базы данных и активности",
    VIEW_PLAYERS: "Просмотр списков и профилей игроков",
    MANAGE_PLAYERS:
      "Заметки и операции с досье. Бан, кик, телепорт и другие действия требуют отдельных прав.",
    VIEW_BANS: "Просмотр списка банов",
    MANAGE_MUTES: "Управление мутами",
    MANAGE_BANS: "Выдача и управление банами",
    VIEW_APPEALS: "Просмотр апелляций банов и тикетов Discord",
    MANAGE_APPEALS: "Управление апелляциями (одобрение, отклонение, переписка)",
    VIEW_REPORTS: "Просмотр жалоб игроков и чат-логов",
    MANAGE_REPORTS: "Обработка жалоб (принятие, отклонение, удаление)",
    VIEW_VESUVIO: "Античит: базовый обзор и живой поток флагов",
    VIEW_VESUVIO_ADVANCED: "Античит: детальная телеметрия и профили",
    MANAGE_VESUVIO: "Античит: режим наблюдения и сброс VL",
    VIEW_SERVER_LOGS: "Просмотр журнала событий сервера",
    VIEW_WEB_LOGS: "Просмотр аудита действий веб-панели",
    EXECUTE_COMMANDS: "Консоль: выполнение команд сервера",
    MANAGE_LOVEAUTH: "Управление игровыми аккаунтами авторизации",
    MANAGE_ADMINS: "Управление администраторами и сессиями",
    MANAGE_ROLES: "Управление ролями и матрицей прав",
    MANAGE_PASSWORDS: "Сброс паролей персонала",
    MANAGE_LOCKDOWN: "Экстренный режим ЧС (изоляция сервера)",
    VIEW_STAFF_AUDIT: "Аудит команд персонала и KPI метрики",
    VIEW_ECONOMY: "Просмотр оборота экономики",
    MANAGE_ECONOMY: "Управление балансом игроков",
    VIEW_CHATFILTER: "Просмотр слов, проверка текста и статистика фильтра чата",
    MANAGE_CHATFILTER: "Изменение слов и настроек фильтра чата",
    MANAGE_API: "Шаблоны Discord, диагностика доставки и служебные подключения",
    VIEW_SERVER_INTERNALS:
      "Позволяет видеть ошибки плагинов, технические детали сервера, расширенные логи и stacktrace",
    BYPASS_MAINTENANCE:
      "Позволяет входить в веб-панель во время технических работ",
  };
  state.PERMISSION_NAMES = {
    VIEW_STATS: "Просмотр метрик сервера",
    VIEW_ANALYTICS: "Аналитика активности",
    VIEW_PLAYERS: "Просмотр игроков",
    MANAGE_PLAYERS: "Администрирование досье",
    VIEW_BANS: "Просмотр банов",
    MANAGE_MUTES: "Управление мутами",
    MANAGE_BANS: "Выдача и снятие банов",
    VIEW_APPEALS: "Просмотр апелляций",
    MANAGE_APPEALS: "Управление апелляциями",
    VIEW_REPORTS: "Просмотр жалоб",
    MANAGE_REPORTS: "Обработка жалоб",
    VIEW_VESUVIO: "Античит: базовый мониторинг",
    VIEW_VESUVIO_ADVANCED: "Античит: детальная телеметрия",
    MANAGE_VESUVIO: "Античит: управление и сброс VL",
    VIEW_SERVER_LOGS: "Серверный журнал",
    VIEW_WEB_LOGS: "Веб журнал аудита",
    EXECUTE_COMMANDS: "Консоль и команды",
    MANAGE_LOVEAUTH: "Управление аккаунтами LoveAuth",
    MANAGE_ADMINS: "Управление сотрудниками",
    MANAGE_ROLES: "Управление ролями",
    MANAGE_PASSWORDS: "Сброс паролей персонала",
    MANAGE_LOCKDOWN: "Режим изоляции сервера (ЧС)",
    VIEW_STAFF_AUDIT: "Аудит действий персонала",
    VIEW_ECONOMY: "Просмотр экономики",
    MANAGE_ECONOMY: "Управление балансами",
    VIEW_CHATFILTER: "Просмотр фильтра чата",
    MANAGE_CHATFILTER: "Управление фильтром чата",
    MANAGE_API: "Служебные подключения",
    VIEW_SERVER_INTERNALS: "Техническая информация и ошибки",
    BYPASS_MAINTENANCE: "Обход тех. работ панели",
  };
  Object.assign(state.PERMISSION_LABELS, {
    VIEW_SERVER_SETTINGS: "Просмотр настроек сервера",
    MANAGE_PANEL_SETTINGS: "Настройки панели",
    MANAGE_SERVER_SETTINGS: "Настройки сервера",
    MANAGE_SECURITY_SETTINGS: "Системная безопасность",
    MANAGE_INTEGRATIONS: "Управление интеграциями",
    MANAGE_MAINTENANCE: "Технические работы",
    MANAGE_WHITELIST: "Управление whitelist",
    MANAGE_OPERATORS: "Управление операторами",
    MANAGE_PREVIEW: "Управление Preview",
    REVIEW_VESUVIO_SAMPLES: "Vesuvio: разбор обучающих образцов",
    MANAGE_VESUVIO_MODELS: "Vesuvio: перезагрузка моделей",
  });
  Object.assign(state.PERMISSION_NAMES, {
    VIEW_SERVER_SETTINGS: "Просмотр настроек сервера",
    MANAGE_PANEL_SETTINGS: "Настройки панели",
    MANAGE_SERVER_SETTINGS: "Настройки сервера",
    MANAGE_SECURITY_SETTINGS: "Системная безопасность",
    MANAGE_INTEGRATIONS: "Управление интеграциями",
    MANAGE_MAINTENANCE: "Технические работы",
    MANAGE_WHITELIST: "Управление whitelist",
    MANAGE_OPERATORS: "Управление операторами",
    MANAGE_PREVIEW: "Управление Preview",
    REVIEW_VESUVIO_SAMPLES: "Vesuvio: разбор обучающих образцов",
    MANAGE_VESUVIO_MODELS: "Vesuvio: перезагрузка моделей",
  });
  state.ALL_PERMISSIONS.push(
    ...[
      "VIEW_SERVER_SETTINGS",
      "MANAGE_PANEL_SETTINGS",
      "MANAGE_SERVER_SETTINGS",
      "MANAGE_SECURITY_SETTINGS",
      "MANAGE_INTEGRATIONS",
      "MANAGE_MAINTENANCE",
      "MANAGE_WHITELIST",
      "MANAGE_OPERATORS",
      "MANAGE_PREVIEW",
      "REVIEW_VESUVIO_SAMPLES",
      "MANAGE_VESUVIO_MODELS",
    ].filter((name) => !state.ALL_PERMISSIONS.includes(name)),
  );
  state.globalPollers = {};
  state.requestGeneration = 0;
  state.readRequests = new Set();
  exposeShowToast();
  exposeCloseCurrentModal();
  exposeCloseModal();
  installModalKeyboardShortcuts();
  exposeAttachPlayerAutocomplete();
  exposeOpenReportPickerModal();
  installHistoryNavigation();
  exposeNavigateTo();
  state.DEFAULT_DASHBOARD_TILES = [
    "server_stats",
    "quick_actions",
    "vesuvio_flags",
    "attention",
    "punishments_stats",
    "online_players",
    "admin_actions",
  ];
  state.ALL_QUICK_ACTIONS = {
    ban: {
      id: "ban",
      permission: "MANAGE_BANS",
      label: "ВЫДАТЬ БАН",
      sub: "Блокировка",
      icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>`,
      color: "var(--red)",
      action: "window.openQuickBanModal('')",
    },
    mute: {
      id: "mute",
      permission: "MANAGE_MUTES",
      label: "ВЫДАТЬ МУТ",
      sub: "Заглушить в чате",
      icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5L6 9H2v6h4l5 4V5z"/><line x1="23" y1="9" x2="17" y2="15"/><line x1="17" y1="9" x2="23" y2="15"/></svg>`,
      color: "var(--yellow)",
      action: "window.openQuickMuteModal()",
    },
    kick: {
      id: "kick",
      permission: "KICK_PLAYERS",
      label: "КИК ИГРОКА",
      sub: "Выгнать с сервера",
      icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><line x1="18" y1="8" x2="23" y2="13"/><line x1="23" y1="8" x2="18" y2="13"/></svg>`,
      color: "#F87171",
      action: "window.openQuickKickModal()",
    },
    vanish: {
      id: "vanish",
      permission: "MANAGE_PLAYERS",
      label: "VANISH",
      sub: "Режим скрытности",
      icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>`,
      color: "var(--accent)",
      action: "window.openQuickVanishModal()",
    },
    clear_chat: {
      id: "clear_chat",
      permission: "EXECUTE_COMMANDS",
      label: "ОЧИСТИТЬ ЧАТ",
      sub: "Сброс спама",
      icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>`,
      color: "var(--green)",
      action: "window.quickClearChatAction()",
    },
    dossier: {
      id: "dossier",
      permission: "VIEW_PLAYERS",
      label: "ДОСЬЕ ИГРОКА",
      sub: "Поиск по нику",
      icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><path d="M11 8v6M8 11h6"/></svg>`,
      color: "var(--text-secondary)",
      action: "window.openQuickPlayerSearchModal()",
    },
  };
  exposeOpenQuickActionsConfigModal();
  exposeOpenQuickPlayerSearchModal();
  exposeQuickClearChatAction();
  state.currentPunishmentsSubtab = "reports";
  state.lastLoadedReports = [];
  state.lastLoadedBans = [];
  state.appealTemplatesCache = null;
  exposeRenderPunishmentsView();
  exposeRenderBansView();
  exposeRenderReportsView();
  exposeRefreshPunishmentsView();
  exposeOpenQuickBanModal();
  exposeViewBanDetails();
  exposeOpenScreenshotLightbox();
  exposeConfirmUnban();
  exposeHandleAcceptReport();
  exposeHandleRejectReport();
  exposeHandleDeleteReport();
  exposeHandleBanFromReportRow();
  exposeOpenReportDetailModal();
  state.cachedAppealTemplates = null;
  exposeOpenAppealTemplateModal();
  exposeOpenPlayerAltsModal();
  exposeOpenObservationView();
  exposeCloseObservationView();
  exposeTogglePlayerSuspect();
  exposeViewPlayerProfile();
  exposeDeleteStaffNote();
  exposeRenderStaffView();
  exposeRenderAdminsView();
  exposeOpenQuickKickModal();
  exposeOpenQuickTeleportModal();
  exposeOpenQuickVanishModal();
  exposeOpenQuickMuteModal();
  startInitialSession();
}
