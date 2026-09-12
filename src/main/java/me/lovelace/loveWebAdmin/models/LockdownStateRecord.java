package me.lovelace.loveWebAdmin.models;

public record LockdownStateRecord(
    boolean active,
    long activatedAt,
    String activatedBy,
    String reason,
    String settingsJson
) {}
