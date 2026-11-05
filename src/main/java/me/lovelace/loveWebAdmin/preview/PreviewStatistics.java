package me.lovelace.loveWebAdmin.preview;

import java.util.*;
import me.lovelace.loveWebAdmin.database.WorkSummaryStore;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.workflow.CaseWaitPolicy;

/** Both previews keep statistics and queues inside their own stores. Never touches real plugins. */
final class PreviewStatistics {
  static Object general(PreviewRuntime r) {
    r.require("VIEW_ANALYTICS");
    var sections = new ArrayList<Map<String, Object>>();
    section(
        sections,
        "activity",
        "Аккаунты и активность",
        "LoveWebAdmin",
        Map.of("Аккаунтов в базе", r.list("players").size()),
        r.now());
    for (var entry :
        Map.of(
                "clans",
                "Кланы",
                "duels",
                "Дуэли",
                "hunt",
                "Охота",
                "adaptation",
                "Адаптации онлайн",
                "behavior",
                "Поведение онлайн",
                "contracts",
                "Контракты")
            .entrySet())
      section(
          sections,
          entry.getKey(),
          entry.getValue(),
          "Тестовые данные",
          Map.of("Тестовый показатель", 2),
          r.now());
    for (var entry :
        Map.of(
                "VIEW_ECONOMY",
                "economy",
                "MANAGE_LOVEAUTH",
                "security",
                "VIEW_BANS",
                "moderation",
                "VIEW_REPORTS",
                "reports",
                "VIEW_APPEALS",
                "appeals",
                "VIEW_VESUVIO",
                "anticheat")
            .entrySet())
      if (r.has(entry.getKey()))
        section(
            sections,
            entry.getValue(),
            switch (entry.getValue()) {
              case "economy" -> "Экономика";
              case "security" -> "Безопасность аккаунтов";
              case "moderation" -> "Наказания";
              case "reports" -> "Жалобы";
              case "appeals" -> "Апелляции";
              default -> "Античит";
            },
            "Тестовые данные",
            Map.of("Тестовый показатель", 1),
            r.now());
    return Map.of("scope", "server", "sections", sections, "observedAt", r.now());
  }

  private static void section(
      List<Map<String, Object>> result,
      String id,
      String title,
      String source,
      Map<String, Object> fields,
      long now) {
    result.add(
        Map.of(
            "id",
            id,
            "title",
            title,
            "source",
            source,
            "fields",
            fields,
            "observedAt",
            now,
            "available",
            true,
            "stale",
            false,
            "reason",
            ""));
  }

  static Object work(PreviewRuntime r, Map<String, String> query) throws Exception {
    r.require("VIEW_STATS");
    var rights = EnumSet.noneOf(Permission.class);
    for (var right : Permission.values()) if (r.has(right.name())) rights.add(right);
    boolean mine = !query.getOrDefault("scope", "mine").equals("all");
    String actor = r.viewer().get("username").toString();
    try (var store = new WorkSummaryStore(r.folder())) {
      var result =
          new LinkedHashMap<>(
              store.summary(
                  actor,
                  mine,
                  rights,
                  r.now(),
                  CaseWaitPolicy.defaults(),
                  query.getOrDefault("bucket", ""),
                  Math.clamp(Integer.parseInt(query.getOrDefault("offset", "0")), 0, 1000000)));
      var cards = new ArrayList<>((List<Map<String, Object>>) result.get("cards"));
      if (r.has("VIEW_BANS") && r.has("MANAGE_BANS")) {
        WorkSummaryStore.pendingCard(
                r.restrictions().store.pending(),
                actor,
                mine,
                r.now(),
                query.getOrDefault("bucket", ""),
                Integer.parseInt(query.getOrDefault("offset", "0")))
            .ifPresent(cards::addFirst);
      }
      result.put("cards", cards);
      return result;
    }
  }
}
