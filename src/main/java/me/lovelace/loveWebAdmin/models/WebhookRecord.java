package me.lovelace.loveWebAdmin.models;

import java.util.List;

/**
 * Запись исходящего вебхука (Discord / HTTP POST) для событий сервера и WebAdmin.
 */
public record WebhookRecord(
    int id,
    String name,
    String url,
    List<String> events,
    boolean isActive,
    String secret,
    long createdAt,
    Long lastTriggerAt
) {
    public boolean subscribesTo(String event) {
        if (!isActive || events == null || events.isEmpty()) return false;
        if (events.contains("*") || events.contains("ALL")) return true;
        return events.contains(event.toUpperCase());
    }

    public boolean isDiscord() {
        return url != null && url.toLowerCase().contains("discord.com/api/webhooks");
    }
}
