package me.lovelace.loveWebAdmin.models;

public record EconomySnapshotRecord(
    int id,
    long timestamp,
    long totalCoins,
    int trackedPlayers,
    String topBalancesJson
) {}
