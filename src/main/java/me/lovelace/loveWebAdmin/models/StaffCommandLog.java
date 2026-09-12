package me.lovelace.loveWebAdmin.models;

public record StaffCommandLog(
    int id,
    String adminUsername,
    String command,
    boolean suspicious,
    String riskLevel,
    long timestamp
) {}
