package me.lovelace.loveWebAdmin.models;

public record AdminInviteRecord(
    int id,
    String code,
    String username,
    int roleId,
    int probationDays,
    long roleExpiresAt,
    String createdBy,
    long createdAt,
    long expiresAt,
    String status
) {
}
