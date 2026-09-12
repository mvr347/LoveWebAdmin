package me.lovelace.loveWebAdmin.models;

/**
 * Сообщение в треде апелляции бана (синхронизируется с Discord тикетом).
 */
public record AppealMessage(
    int id,
    int appealId,
    String authorName,
    boolean isStaff,
    String message,
    long createdAt
) {}
