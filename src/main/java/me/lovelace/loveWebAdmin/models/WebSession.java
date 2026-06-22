package me.lovelace.loveWebAdmin.models;

public record WebSession(
    String token,
    int adminId,
    String adminUsername,
    int roleId,
    long expiresAt
) {}
