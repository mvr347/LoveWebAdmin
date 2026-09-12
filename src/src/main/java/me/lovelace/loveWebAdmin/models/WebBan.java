package me.lovelace.loveWebAdmin.models;

public record WebBan(
    int id,
    String targetName,
    String targetUuid,
    String targetIp,
    String creatorName,
    String ruleReason,
    String description,
    String proofUrls,
    boolean isIpBan,
    String status,
    long durationSeconds,
    long createdAt,
    long expiresAt,
    Integer linkedReportId
) {
    public WebBan(
        int id,
        String targetName,
        String targetUuid,
        String targetIp,
        String creatorName,
        String ruleReason,
        String description,
        String proofUrls,
        boolean isIpBan,
        String status,
        long durationSeconds,
        long createdAt,
        long expiresAt
    ) {
        this(id, targetName, targetUuid, targetIp, creatorName, ruleReason, description, proofUrls, isIpBan, status, durationSeconds, createdAt, expiresAt, null);
    }
}
