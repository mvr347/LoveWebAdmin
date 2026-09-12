package me.lovelace.loveWebAdmin.models;

public record StaffKpiRecord(
    String username,
    String roleName,
    long playtimeMinutes,
    int bansCount,
    int totalCommands,
    int suspiciousCommands,
    long lastSeen
) {}
