package me.lovelace.loveWebAdmin.integration;

import java.time.*;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/** Quiet hours postpone staff DMs only; a banned player's notification is never delayed. */
public record DiscordNotificationPolicy(
    boolean onlyOnShift,
    boolean quietEnabled,
    int startHour,
    int endHour,
    ZoneId zone,
    int escalationMinutes,
    Set<String> escalationRoles) {
  public DiscordNotificationPolicy {
    if (startHour < 0 || startHour > 23 || endHour < 0 || endHour > 23 || escalationMinutes < 0)
      throw new IllegalArgumentException("Проверьте часы уведомлений Discord");
    escalationRoles = Set.copyOf(escalationRoles);
  }

  public static DiscordNotificationPolicy from(ConfigurationSection config) {
    String p = "discord.notifications.";
    return new DiscordNotificationPolicy(
        config.getBoolean(p + "only-on-shift", true),
        config.getBoolean(p + "quiet-hours.enabled", false),
        config.getInt(p + "quiet-hours.start-hour", 23),
        config.getInt(p + "quiet-hours.end-hour", 8),
        ZoneId.of(config.getString(p + "quiet-hours.timezone", "Europe/Moscow")),
        config.getInt(p + "escalation.after-minutes", 15),
        Set.copyOf(config.getStringList(p + "escalation.roles")));
  }

  public Instant nextDelivery(Instant instant) {
    if (!quietEnabled || startHour == endHour) return instant;
    var local = instant.atZone(zone);
    int hour = local.getHour();
    boolean quiet =
        startHour < endHour
            ? hour >= startHour && hour < endHour
            : hour >= startHour || hour < endHour;
    if (!quiet) return instant;
    var end = local.toLocalDate().atTime(endHour, 0).atZone(zone);
    if (!end.toInstant().isAfter(instant)) end = end.plusDays(1);
    return end.toInstant();
  }
}
