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

    // Vesuvio AntiCheat: базовый обзор — только подозреваемые и лог наказаний.
    VIEW_VESUVIO,
    // Vesuvio AntiCheat: расширенный обзор — вся телеметрия, движок, датасет самообучения,
    // подробный профиль игрока (CPS/StdDev/ML/self-learn и т.д.).
    VIEW_VESUVIO_ADVANCED,
    // Vesuvio AntiCheat: управляющие действия — сброс VL, пометка/снятие подозрения.
    MANAGE_VESUVIO,

    // Тикеты (апелляции/поддержка/жалобы): просмотр списка и переписки.
    VIEW_TICKETS,
    // Тикеты: ответить в переписке, закрыть/переоткрыть.
    MANAGE_TICKETS
}
