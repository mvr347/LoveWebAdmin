package me.lovelace.loveWebAdmin.models;

public enum Permission {
    // Просмотр
    VIEW_SERVER_LOGS,
    VIEW_WEB_LOGS,
    VIEW_STATS,

    // Действия
    EXECUTE_COMMANDS,

    // Управление (только Управляющий или кастомные роли с этим правом)
    MANAGE_ADMINS,
    MANAGE_ROLES,
    MANAGE_PASSWORDS,

    // Баны и апелляции
    VIEW_BANS,
    MANAGE_BANS,
    VIEW_APPEALS,
    MANAGE_APPEALS,

    // Профили игроков и аналитика
    VIEW_PLAYERS,
    MANAGE_PLAYERS,
    VIEW_PLAYER_IP,
    VIEW_PLAYER_INVENTORY,
    CONFISCATE_ITEMS,
    RESTORE_ITEMS,
    FREEZE_PLAYERS,
    TELEPORT_PLAYERS,
    CHANGE_GAMEMODE,
    KICK_PLAYERS,
    KILL_PLAYERS,
    VIEW_ANALYTICS,

    // LoveAuth
    MANAGE_LOVEAUTH,

    // Vesuvio AntiCheat (Античит): базовый обзор — только подозреваемые и лог наказаний.
    VIEW_VESUVIO,
    // Vesuvio AntiCheat (Античит): расширенный обзор — вся телеметрия, движок, датасет самообучения,
    // подробный профиль игрока (CPS/StdDev/ML/self-learn и т.д.).
    VIEW_VESUVIO_ADVANCED,
    // Vesuvio AntiCheat (Античит): управляющие действия — сброс VL, пометка/снятие подозрения.
    MANAGE_VESUVIO,

    // Режим ЧС («Красная кнопка» / Emergency Lockdown)
    MANAGE_LOCKDOWN,

    // Аудит действий персонала и KPI
    VIEW_STAFF_AUDIT,

    // Экономика LoveCore (LoveEconomy)
    VIEW_ECONOMY,
    MANAGE_ECONOMY,

    // Репорты и жалобы игроков
    VIEW_REPORTS,
    MANAGE_REPORTS,

    // API и Вебхуки
    MANAGE_API,
  VIEW_SERVER_SETTINGS,
  MANAGE_PANEL_SETTINGS,
  MANAGE_SERVER_SETTINGS,
  MANAGE_SECURITY_SETTINGS,
  MANAGE_INTEGRATIONS,
  MANAGE_MAINTENANCE,
  MANAGE_WHITELIST,
  MANAGE_OPERATORS,
  MANAGE_PREVIEW,
  REVIEW_VESUVIO_SAMPLES,
  MANAGE_VESUVIO_MODELS,

    // Просмотр технических параметров сервера (СУБД, хеши, брутфорс-фильтр, системные сервисы)
    VIEW_SERVER_INTERNALS,

    // Обход режима технических работ
    BYPASS_MAINTENANCE
}
