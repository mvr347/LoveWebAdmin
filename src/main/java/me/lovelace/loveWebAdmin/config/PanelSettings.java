package me.lovelace.loveWebAdmin.config;

import java.net.URI;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Predicate;
import org.bukkit.configuration.ConfigurationSection;
import me.lovelace.loveWebAdmin.models.Permission;

/** One allowlist and validation contract for live settings and the server sandbox. */
public final class PanelSettings {
  enum Type {
    BOOL,
    INT,
    TEXT,
    ID,
    IDS,
    LINES,
    PUBLIC_URL,
    WEBHOOK,
    TRANSPORT,
    ZONE
  }

  record Field(
      String path,
      Permission permission,
      Type type,
      Object fallback,
      int min,
      int max,
      boolean restart) {}

  private static final Map<String, Field> FIELDS = new LinkedHashMap<>();

  static {
    Permission p = Permission.MANAGE_PANEL_SETTINGS,
        i = Permission.MANAGE_INTEGRATIONS,
        v = Permission.MANAGE_PREVIEW,
        s = Permission.MANAGE_SECURITY_SETTINGS;
    add("webEnabled", "web.enabled", p, Type.BOOL, true, 0, 0, true);
    add("webHost", "web.host", p, Type.TEXT, "0.0.0.0", 1, 253, true);
    add("webPort", "web.port", p, Type.INT, 8080, 1, 65535, true);
    add("webPublicUrl", "web.public-url", p, Type.PUBLIC_URL, "", 0, 500, true);
    add("sessionLifetimeMinutes", "web.session-lifetime-minutes", p, Type.INT, 60, 5, 43200, false);
    add("keepLogCount", "logs.keep-count", p, Type.INT, 5000, 500, 50000, false);
    add("strictIp", "security.strict-ip", s, Type.BOOL, false, 0, 0, false);
    add("minPasswordLength", "security.password.min-length", s, Type.INT, 12, 12, 32, false);
    add("loginMaxAttempts", "security.login.max-attempts", s, Type.INT, 5, 1, 20, true);
    add("loginLockoutMinutes", "security.login.lockout-minutes", s, Type.INT, 15, 1, 1440, true);
    add("commandBlacklist", "security.command-blacklist", s, Type.LINES, List.of(), 0, 200, false);
    add(
        "corsAllowedOrigins",
        "security.cors.allowed-origins",
        s,
        Type.LINES,
        List.of(),
        0,
        30,
        true);
    add("previewEnabled", "preview.enabled", v, Type.BOOL, false, 0, 0, false);
    add("previewCategoryId", "preview.discord.category-id", v, Type.ID, "", 0, 0, true);
    add("previewChannelId", "preview.discord.channel-id", v, Type.ID, "", 0, 0, true);
    add(
        "previewAllowedUserIds",
        "preview.discord.allowed-user-ids",
        v,
        Type.IDS,
        List.of(),
        0,
        10,
        true);
    add("discordPanelChannelId", "discord.tickets.panel-channel-id", i, Type.ID, "", 0, 0, true);
    add("discordQueueChannelId", "discord.tickets.queue-channel-id", i, Type.ID, "", 0, 0, true);
    add(
        "discordSupervisorRoleIds",
        "discord.tickets.supervisor-role-ids",
        i,
        Type.IDS,
        List.of(),
        0,
        20,
        true);
    add(
        "discordStaffReminderMinutes",
        "discord.tickets.waiting.staff-reminder-minutes",
        i,
        Type.INT,
        20,
        0,
        43200,
        true);
    add(
        "discordStaffEscalationMinutes",
        "discord.tickets.waiting.staff-escalation-minutes",
        i,
        Type.INT,
        60,
        0,
        43200,
        true);
    add(
        "discordPlayerReminderMinutes",
        "discord.tickets.waiting.player-reminder-minutes",
        i,
        Type.INT,
        2880,
        0,
        43200,
        true);
    add(
        "discordReportsTransport",
        "discord.reports-transport",
        i,
        Type.TRANSPORT,
        "auto",
        0,
        0,
        false);
    add("discordReportsChannelId", "discord.reports-channel-id", i, Type.ID, "", 0, 0, false);
    add("discordAppealsChannelId", "discord.appeals-channel-id", i, Type.ID, "", 0, 0, false);
    add(
        "discordQuietEnabled",
        "discord.notifications.quiet-hours.enabled",
        i,
        Type.BOOL,
        false,
        0,
        0,
        true);
    add(
        "discordQuietStartHour",
        "discord.notifications.quiet-hours.start-hour",
        i,
        Type.INT,
        23,
        0,
        23,
        true);
    add(
        "discordQuietEndHour",
        "discord.notifications.quiet-hours.end-hour",
        i,
        Type.INT,
        8,
        0,
        23,
        true);
    add(
        "discordQuietTimezone",
        "discord.notifications.quiet-hours.timezone",
        i,
        Type.ZONE,
        "Europe/Moscow",
        0,
        100,
        true);
    add("webhooksEnabled", "security.webhooks.enabled", i, Type.BOOL, false, 0, 0, false);
    add(
        "discordWebhookUrl",
        "security.webhooks.discord.webhook-url",
        i,
        Type.WEBHOOK,
        "",
        0,
        500,
        false);
    for (var entry :
        Map.of(
                "webhookNotifyAllLogins",
                "all-logins",
                "webhookNotifyNewIp",
                "new-ip",
                "webhookNotifyCommands",
                "commands",
                "webhookNotifyBruteForce",
                "brute-force",
                "webhookNotifyRegistration",
                "registration",
                "webhookNotifyDestructive",
                "destructive-actions")
            .entrySet())
      add(
          entry.getKey(),
          "security.webhooks.notify-on." + entry.getValue(),
          i,
          Type.BOOL,
          !entry.getValue().equals("all-logins"),
          0,
          0,
          false);
    add(
        "maintenanceEnabled",
        "security.maintenance.enabled",
        Permission.MANAGE_MAINTENANCE,
        Type.BOOL,
        false,
        0,
        0,
        false);
    add(
        "maintenanceMessage",
        "security.maintenance.message",
        Permission.MANAGE_MAINTENANCE,
        Type.TEXT,
        "Ведутся технические работы",
        1,
        1000,
        false);
  }

  private static void add(
      String name,
      String path,
      Permission permission,
      Type type,
      Object fallback,
      int min,
      int max,
      boolean restart) {
    FIELDS.put(name, new Field(path, permission, type, fallback, min, max, restart));
  }

  public static Permission permission(String name) {
    if (name.equals("clearDiscordWebhookUrl") || name.equals("discordWebhookConfigured"))
      return Permission.MANAGE_INTEGRATIONS;
    var field = FIELDS.get(name);
    return field == null ? Permission.MANAGE_SERVER_SETTINGS : field.permission();
  }

  public static boolean canRead(Predicate<Permission> can) {
    return can.test(Permission.VIEW_SERVER_SETTINGS)
        || FIELDS.values().stream().anyMatch(f -> can.test(f.permission()));
  }

  public static Map<String, Object> defaults() {
    var result = new LinkedHashMap<String, Object>();
    FIELDS.forEach((name, field) -> result.put(name, field.fallback()));
    return result;
  }

  public static void persist(java.nio.file.Path file, String yaml) throws java.io.IOException {
    var temporary = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
    try {
      java.nio.file.Files.writeString(
          temporary,
          yaml,
          java.nio.charset.StandardCharsets.UTF_8,
          java.nio.file.StandardOpenOption.CREATE_NEW);
      java.nio.file.Files.move(
          temporary,
          file,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } finally {
      java.nio.file.Files.deleteIfExists(temporary);
    }
  }

  public static Map<String, Object> snapshot(ConfigurationSection config) {
    var result = new LinkedHashMap<String, Object>();
    FIELDS.forEach(
        (name, f) ->
            result.put(
                name,
                switch (f.type()) {
                  case INT -> config.getInt(f.path(), ((Number) f.fallback()).intValue());
                  case BOOL -> config.getBoolean(f.path(), (Boolean) f.fallback());
                  case IDS, LINES -> config.getStringList(f.path());
                  default -> config.getString(f.path(), f.fallback().toString());
                }));
    result.put(
        "minPasswordLength", Math.max(12, ((Number) result.get("minPasswordLength")).intValue()));
    return result;
  }

  public static Map<String, Object> view(Map<String, Object> raw, Predicate<Permission> can) {
    var result = new LinkedHashMap<String, Object>();
    FIELDS.forEach(
        (name, f) -> {
          if (!name.equals("discordWebhookUrl")
              && (can.test(Permission.VIEW_SERVER_SETTINGS) || can.test(f.permission())))
            result.put(name, raw.getOrDefault(name, f.fallback()));
        });
    if (can.test(Permission.MANAGE_INTEGRATIONS))
      result.put(
          "discordWebhookConfigured",
          !Objects.toString(raw.get("discordWebhookUrl"), "").isBlank());
    return result;
  }

  public static Map<String, Object> normalize(
      Map<String, Object> body, Map<String, Object> current) {
    var patch = new LinkedHashMap<String, Object>();
    for (var e : body.entrySet()) {
      if (e.getKey().equals("clearDiscordWebhookUrl")) {
        if (!(e.getValue() instanceof Boolean))
          throw new IllegalArgumentException("Действие очистки должно быть переключателем");
        continue;
      }
      var f = FIELDS.get(e.getKey());
      if (f == null) throw new IllegalArgumentException("Неизвестная настройка: " + e.getKey());
      if (e.getKey().equals("discordWebhookUrl")
          && e.getValue() instanceof String text
          && text.isBlank()) continue;
      patch.put(e.getKey(), value(e.getKey(), f, e.getValue()));
    }
    if (Boolean.TRUE.equals(body.get("clearDiscordWebhookUrl"))) {
      if (patch.containsKey("discordWebhookUrl"))
        throw new IllegalArgumentException("Нельзя одновременно заменить и удалить адрес вебхука");
      patch.put("discordWebhookUrl", "");
      patch.put("webhooksEnabled", false);
    }
    var merged = new LinkedHashMap<String, Object>();
    FIELDS.forEach((k, f) -> merged.put(k, current.getOrDefault(k, f.fallback())));
    merged.putAll(patch);
    if (patch.keySet().stream().anyMatch(k -> k.startsWith("preview") || k.startsWith("discord"))) {
      distinct(
          merged,
          "discordPanelChannelId",
          "discordQueueChannelId",
          "Панель обращений и очередь персонала должны находиться в разных каналах");
      distinct(
          merged,
          "previewCategoryId",
          "previewChannelId",
          "Категория Preview и текстовый канал должны иметь разные ID");
      for (String key :
          List.of(
              "discordPanelChannelId",
              "discordQueueChannelId",
              "discordReportsChannelId",
              "discordAppealsChannelId"))
        distinct(
            merged,
            "previewChannelId",
            key,
            "Тестовый канал Preview не должен совпадать с рабочим каналом Discord");
      int first = ((Number) merged.get("discordStaffReminderMinutes")).intValue(),
          second = ((Number) merged.get("discordStaffEscalationMinutes")).intValue();
      if (first > 0 && second > 0 && second < first)
        throw new IllegalArgumentException(
            "Срок напоминания руководству должен быть не меньше срока напоминания сотруднику");
    }
    if (patch.containsKey("webhooksEnabled")
        && Boolean.TRUE.equals(patch.get("webhooksEnabled"))
        && !validWebhook(Objects.toString(merged.get("discordWebhookUrl"), "")))
      throw new IllegalArgumentException(
          "Сначала укажите корректный адрес Discord webhook или выключите отправку");
    return patch;
  }

  private static void distinct(Map<String, Object> values, String a, String b, String error) {
    String left = Objects.toString(values.get(a), "");
    if (!left.isBlank() && left.equals(Objects.toString(values.get(b), "")))
      throw new IllegalArgumentException(error);
  }

  private static Object value(String name, Field f, Object value) {
    switch (f.type()) {
      case BOOL:
        if (value instanceof Boolean) return value;
        break;
      case INT:
        if (value instanceof Number n
            && Double.isFinite(n.doubleValue())
            && n.doubleValue() == n.longValue()
            && n.longValue() >= f.min()
            && n.longValue() <= f.max()) return n.intValue();
        break;
      case IDS:
      case LINES:
        if (value instanceof List<?> list && list.size() <= f.max()) {
          var result = new LinkedHashSet<String>();
          for (Object item : list) {
            if (!(item instanceof String text))
              throw new IllegalArgumentException("Список «" + name + "» должен содержать строки");
            text = text.trim();
            if (text.isBlank()) continue;
            if (f.type() == Type.IDS && !text.matches("[0-9]{5,25}"))
              throw new IllegalArgumentException(
                  "В «" + name + "» нужны числовые Discord ID, по одному на строку");
            if (text.length() > 250)
              throw new IllegalArgumentException("Слишком длинная строка списка");
            result.add(text);
          }
          return List.copyOf(result);
        }
        break;
      default:
        if (!(value instanceof String text)) break;
        text = text.trim();
        switch (f.type()) {
          case ID:
            if (text.isBlank() || text.matches("[0-9]{5,25}")) return text;
            break;
          case TEXT:
            if (text.length() >= f.min()
                && text.length() <= f.max()
                && !text.contains("\n")
                && !text.contains("\r")) return text;
            break;
          case TRANSPORT:
            if (Set.of("auto", "bot", "webhook", "both").contains(text)) return text;
            break;
          case ZONE:
            try {
              ZoneId.of(text);
              return text;
            } catch (Exception ignored) {
            }
            break;
          case PUBLIC_URL:
            if (text.isEmpty()) return text;
            try {
              URI u = URI.create(text);
              if (Set.of("http", "https").contains(u.getScheme())
                  && u.getHost() != null
                  && u.getUserInfo() == null
                  && u.getQuery() == null
                  && u.getFragment() == null
                  && text.length() <= 500) return text.replaceAll("/+$", "");
            } catch (Exception ignored) {
            }
            break;
          case WEBHOOK:
            if (validWebhook(text)) return text;
            throw new IllegalArgumentException(
                "Нужен HTTPS-адрес Discord webhook. Пустое поле сохраняет текущий адрес");
          default:
            break;
        }
    }
    throw new IllegalArgumentException(
        "Некорректное значение «"
            + name
            + "»"
            + (f.type() == Type.INT ? ": допустимо от " + f.min() + " до " + f.max() : ""));
  }

  public static boolean validWebhook(String text) {
    try {
      URI u = URI.create(text);
      return "https".equals(u.getScheme())
          && Set.of("discord.com", "discordapp.com").contains(u.getHost())
          && u.getUserInfo() == null
          && u.getPort() == -1
          && u.getQuery() == null
          && u.getFragment() == null
          && u.getPath().matches("/api/webhooks/[0-9]{5,25}/[A-Za-z0-9._-]+")
          && text.length() <= 500;
    } catch (Exception ignored) {
      return false;
    }
  }

  public static List<String> restartFields(Map<String, Object> patch, Map<String, Object> before) {
    return patch.keySet().stream()
        .filter(k -> FIELDS.get(k).restart() && !Objects.equals(patch.get(k), before.get(k)))
        .toList();
  }

  public static void apply(ConfigurationSection config, Map<String, Object> patch) {
    patch.forEach((name, value) -> config.set(FIELDS.get(name).path(), value));
    if (patch.containsKey("discordWebhookUrl")) config.set("security.webhooks.discord-url", null);
  }
}
