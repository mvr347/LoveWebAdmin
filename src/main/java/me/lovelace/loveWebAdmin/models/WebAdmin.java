package me.lovelace.loveWebAdmin.models;

public record WebAdmin(
    int id,
    String username,
    String passwordHash,
    int roleId,
    long createdAt,
    long lastLoginAt
) {}
