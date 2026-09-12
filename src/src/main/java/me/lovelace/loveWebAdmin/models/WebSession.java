package me.lovelace.loveWebAdmin.models;

public record WebSession(
    String token,
    int adminId,
    String adminUsername,
    int roleId,
    long expiresAt,
    String ip,
    String userAgent,
    long createdAt,
    long lastUsedAt
) {
    public WebSession(String token, int adminId, String adminUsername, int roleId, long expiresAt) {
        this(token, adminId, adminUsername, roleId, expiresAt, null, null, System.currentTimeMillis() / 1000L, System.currentTimeMillis() / 1000L);
    }
}
