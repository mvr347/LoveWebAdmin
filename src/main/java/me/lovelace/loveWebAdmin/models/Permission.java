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
    MANAGE_PASSWORDS
}
