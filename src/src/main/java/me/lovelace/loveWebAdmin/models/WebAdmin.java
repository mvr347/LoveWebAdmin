package me.lovelace.loveWebAdmin.models;

public record WebAdmin(
    int id,
    String username,
    String passwordHash,
    int roleId,
    long createdAt,
    long lastLoginAt,
    String totpSecret,
    boolean totpEnabled,
    long last2faAt,
    String last2faIp,
    String status,
    String uiPreferences,
    String backupCodes
) {
    public WebAdmin(int id, String username, String passwordHash, int roleId, long createdAt, long lastLoginAt) {
        this(id, username, passwordHash, roleId, createdAt, lastLoginAt, null, false, 0, null, "ACTIVE", "{}", "[]");
    }

    public WebAdmin(int id, String username, String passwordHash, int roleId, long createdAt, long lastLoginAt,
                    String totpSecret, boolean totpEnabled, long last2faAt, String last2faIp, String status, String uiPreferences) {
        this(id, username, passwordHash, roleId, createdAt, lastLoginAt, totpSecret, totpEnabled, last2faAt, last2faIp, status, uiPreferences, "[]");
    }
}
