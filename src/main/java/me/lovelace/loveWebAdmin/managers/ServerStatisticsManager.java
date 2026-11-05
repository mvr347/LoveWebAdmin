package me.lovelace.loveWebAdmin.managers;

import java.util.*;
import java.util.concurrent.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.integration.ServerStatisticsBridge;
import me.lovelace.loveWebAdmin.models.Permission;
import org.bukkit.Bukkit;

/** Server-wide counters with explicit source, coverage and last observation. No player dossiers. */
public final class ServerStatisticsManager {
  private final LoveWebAdmin plugin;

  public ServerStatisticsManager(LoveWebAdmin plugin) {
    this.plugin = plugin;
  }

  public Map<String, Object> statistics(Set<Permission> rights) {
    var definitions = new LinkedHashMap<String, String>();
    definitions.put("activity", "Аккаунты и активность");
    definitions.put("clans", "Кланы");
    definitions.put("duels", "Дуэли");
    definitions.put("hunt", "Охота");
    definitions.put("adaptation", "Адаптации онлайн");
    definitions.put("behavior", "Поведение онлайн");
    definitions.put("contracts", "Контракты");
    if (rights.contains(Permission.VIEW_ECONOMY)) {
      definitions.put("shops", "Торговля");
      definitions.put("economy", "Экономика");
    }
    if (rights.contains(Permission.MANAGE_LOVEAUTH))
      definitions.put("security", "Безопасность аккаунтов");
    if (rights.contains(Permission.VIEW_BANS)) definitions.put("moderation", "Наказания");
    if (rights.contains(Permission.VIEW_APPEALS)) definitions.put("appeals", "Апелляции");
    if (rights.contains(Permission.VIEW_REPORTS)) definitions.put("reports", "Жалобы");
    if (rights.contains(Permission.FREEZE_PLAYERS) || rights.contains(Permission.MANAGE_MUTES))
      definitions.put("restrictions", "Проверки и ограничения");
    if (rights.contains(Permission.VIEW_VESUVIO)) definitions.put("anticheat", "Античит");
    var fresh = plugin.main().call(() -> new ServerStatisticsBridge().collect()).join();
    var db = plugin.getDatabaseManager();
    long now = System.currentTimeMillis() / 1000;
    fresh.put("activity", db.generalServerCounts());
    if (rights.contains(Permission.VIEW_BANS))
      fresh.put("moderation", db.generalModerationCounts());
    if (rights.contains(Permission.VIEW_APPEALS)) fresh.put("appeals", db.generalAppealCounts());
    if (rights.contains(Permission.VIEW_REPORTS)) {
      var reports = db.getReportsStats();
      fresh.put(
          "reports",
          Map.of(
              "Всего жалоб",
              reports.getOrDefault("total", 0),
              "Открытых жалоб",
              reports.getOrDefault("pending", 0),
              "Подтверждено",
              reports.getOrDefault("accepted", 0),
              "Отклонено",
              reports.getOrDefault("rejected", 0)));
    }
    if (definitions.containsKey("restrictions")) {
      var values = new LinkedHashMap<String, Object>();
      var active =
          plugin.getModerationService().restrictions().stream().filter(r -> r.active(now)).toList();
      if (rights.contains(Permission.FREEZE_PLAYERS))
        values.put(
            "Активных проверок", active.stream().filter(r -> r.kind().equals("FREEZE")).count());
      if (rights.contains(Permission.MANAGE_MUTES))
        values.put("Активных мутов", active.stream().filter(r -> r.kind().equals("MUTE")).count());
      fresh.put("restrictions", values);
    }
    if (definitions.containsKey("economy")) {
      var samples = db.getRecentEconomySnapshots(1);
      boolean available =
          plugin.main().call(() -> plugin.getLoveEconomyBridge().isAvailable()).join();
      if (available && !samples.isEmpty()) {
        var sample = samples.getFirst();
        fresh.put(
            "economy",
            Map.of(
                "Монет у отслеживаемых игроков онлайн",
                sample.totalCoins(),
                "Охват: игроков онлайн",
                sample.trackedPlayers(),
                "Время измерения",
                sample.timestamp()));
      }
    }
    if (definitions.containsKey("security")) {
      try {
        CompletableFuture<?> future =
            plugin
                .main()
                .call(
                    () -> {
                      var source = Bukkit.getPluginManager().getPlugin("LoveAuth");
                      if (source == null || !source.isEnabled()) return null;
                      try {
                        Object manager =
                            source.getClass().getMethod("getDatabaseManager").invoke(source);
                        return (CompletableFuture<?>)
                            manager.getClass().getMethod("getStats").invoke(manager);
                      } catch (ReflectiveOperationException error) {
                        return null;
                      }
                    })
                .join();
        if (future != null) {
          Object stats = future.get(3, TimeUnit.SECONDS);
          fresh.put(
              "security",
              Map.of(
                  "Зарегистрированных аккаунтов",
                  stats.getClass().getMethod("registered").invoke(stats),
                  "Заблокированных аккаунтов",
                  stats.getClass().getMethod("locked").invoke(stats),
                  "Сохранённых сессий",
                  stats.getClass().getMethod("sessions").invoke(stats)));
        }
      } catch (Exception error) {
        if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      }
    }
    if (definitions.containsKey("anticheat"))
      try {
        Object wave =
            plugin.main().call(() -> plugin.getVesuvioBridge().call("getModerationWave")).join();
        if (wave instanceof List<?> list) {
          var counts = new LinkedHashMap<String, Object>();
          for (var entry :
              Map.of("В очереди", "QUEUED", "Применено", "APPLIED", "Требует проверки", "REVIEW")
                  .entrySet())
            counts.put(
                entry.getKey(),
                list.stream()
                    .filter(
                        item ->
                            item instanceof Map<?, ?> row
                                && entry.getValue().equals(row.get("state")))
                    .count());
          fresh.put("anticheat", counts);
        }
      } catch (RuntimeException ignored) {
      }
    var stored = db.serverMetrics();
    var sections = new ArrayList<Map<String, Object>>();
    for (var definition : definitions.entrySet()) {
      String id = definition.getKey(), source = source(id);
      var fields = fresh.get(id);
      boolean measured = fields != null && !fields.isEmpty();
      if (measured) db.saveServerMetrics(id, source, fields, now);
      var old = stored.get(id);
      var section = new LinkedHashMap<String, Object>();
      section.put("id", id);
      section.put("title", definition.getValue());
      section.put("source", source);
      section.put("available", measured || old != null);
      section.put("stale", !measured && old != null);
      section.put("observedAt", measured ? now : old != null ? old.get("observedAt") : 0);
      section.put("fields", measured ? fields : old != null ? old.get("fields") : Map.of());
      section.put(
          "reason",
          measured
              ? ""
              : old != null
                  ? "Сохранённый снимок: актуальный источник недоступен"
                  : "Нет измерений или версия интеграции не предоставляет сводку");
      sections.add(section);
    }
    return Map.of("sections", sections, "observedAt", now, "scope", "server");
  }

  private static String source(String id) {
    return switch (id) {
      case "activity", "moderation", "reports", "appeals", "restrictions" -> "LoveWebAdmin";
      case "security" -> "LoveAuth";
      case "economy" -> "LoveCore";
      case "anticheat" -> "Vesuvio";
      case "clans" -> "LoveClans";
      case "duels" -> "LoveDuels";
      case "hunt" -> "LoveHunt";
      case "adaptation" -> "LoveAdaptation";
      case "behavior" -> "LoveBehavior";
      case "contracts" -> "LoveContracts";
      case "shops" -> "LoveShops";
      default -> "LoveWebAdmin";
    };
  }
}
