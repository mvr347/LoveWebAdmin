package me.lovelace.loveWebAdmin.models;

public record EconomyAnomalyRecord(
    int id,
    String playerName,
    long oldBalance,
    long newBalance,
    long delta,
    long timestamp,
    boolean reviewed
) {}
