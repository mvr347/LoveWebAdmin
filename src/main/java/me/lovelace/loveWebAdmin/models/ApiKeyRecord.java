package me.lovelace.loveWebAdmin.models;

import java.util.List;

/**
 * Запись API ключа для внешних интеграций (боты, сайты, скрипты).
 */
public record ApiKeyRecord(
    int id,
    String name,
    String keyHash,
    String prefix,
    List<String> permissions,
    String creator,
    long createdAt,
    Long lastUsedAt,
    boolean isActive
) {
    public boolean hasPermission(String permission) {
        if (permissions == null || permissions.isEmpty()) return false;
        if (permissions.contains("*") || permissions.contains("ALL")) return true;
        return permissions.contains(permission);
    }
}
