package me.lovelace.loveWebAdmin.models;

/**
 * Запись об апелляции бана (тикет обжалования).
 */
public record WebBanAppeal(
    int id,
    int banId,
    String playerUuid,
    String playerName,
    String reason,
    String status,
    String discordChannelId,
    long createdAt,
    long updatedAt
) {
    public WebBanAppeal(int id, int banId, String playerUuid, String playerName, String reason, String status, String discordChannelId, long createdAt) {
        this(id, banId, playerUuid, playerName, reason, status, discordChannelId, createdAt, createdAt);
    }
}
