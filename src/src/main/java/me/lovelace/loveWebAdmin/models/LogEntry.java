package me.lovelace.loveWebAdmin.models;

public record LogEntry(
    int id,
    String type,
    String actor,
    String action,
    long timestamp
) {}
