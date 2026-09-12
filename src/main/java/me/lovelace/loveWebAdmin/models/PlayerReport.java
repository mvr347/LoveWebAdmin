package me.lovelace.loveWebAdmin.models;

import java.util.List;

/**
 * Модель жалобы (репорта) на игрока.
 */
public record PlayerReport(
    int id,
    String reporterUuid,
    String reporterName,
    String reporterIp,
    String targetUuid,
    String targetName,
    List<String> reasons,
    String description,
    boolean isRecent,
    long createdAt,
    String status, // PENDING, ACCEPTED, REJECTED, EXPIRED
    String resolvedBy,
    long resolvedAt,
    int reputationDeducted,
    Integer linkedBanId
) {
    public PlayerReport(
        int id,
        String reporterUuid,
        String reporterName,
        String reporterIp,
        String targetUuid,
        String targetName,
        List<String> reasons,
        String description,
        boolean isRecent,
        long createdAt,
        String status,
        String resolvedBy,
        long resolvedAt,
        int reputationDeducted
    ) {
        this(id, reporterUuid, reporterName, reporterIp, targetUuid, targetName, reasons, description, isRecent, createdAt, status, resolvedBy, resolvedAt, reputationDeducted, null);
    }
}
