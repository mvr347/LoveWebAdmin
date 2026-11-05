package me.lovelace.loveWebAdmin.integration;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Read-only cached/public plugin interfaces. This collector is invoked on the main thread. */
public final class ServerStatisticsBridge {
  @FunctionalInterface
  private interface Reader {
    Map<String, Object> read(Plugin plugin) throws Exception;
  }

  public Map<String, Map<String, Object>> collect() {
    var result = new LinkedHashMap<String, Map<String, Object>>();
    collect(
        result,
        "clans",
        "LoveClans",
        plugin -> {
          var type =
              Class.forName(
                  "me.lovelace.loveclans.api.LoveClansAPI",
                  true,
                  plugin.getClass().getClassLoader());
          Object api = type.getMethod("getInstance").invoke(null);
          Collection<?> clans = (Collection<?>) call(api, "getAllClans");
          long members = 0, territories = 0;
          for (Object clan : clans) {
            members += ((Map<?, ?>) call(clan, "members")).size();
            territories += ((Collection<?>) call(clan, "territories")).size();
          }
          return Map.of(
              "Кланов",
              clans.size(),
              "Участников кланов",
              members,
              "Занятых территорий",
              territories);
        });
    collect(
        result,
        "duels",
        "LoveDuels",
        plugin -> {
          Object manager = call(plugin, "getDuelManager");
          return Map.of(
              "Активных дуэлей",
              ((Collection<?>) call(call(manager, "getMatchManager"), "getActiveMatches")).size());
        });
    collect(
        result,
        "hunt",
        "LoveHunt",
        plugin -> {
          Object api = service("me.lovelace.loveHunt.api.LoveHuntAPI");
          return Map.of(
              "Активных заказов",
              ((Collection<?>) call(api, "getAllActiveBounties")).size(),
              "Охотников с рейтингом",
              ((Map<?, ?>) call(api, "getHunterRatings")).size());
        });
    collect(
        result,
        "shops",
        "LoveShops",
        plugin -> {
          Object api = service("dev.lovelace.loveshops.api.LoveShopsAPI");
          return Map.of("Сервисных NPC торговли", ((Collection<?>) call(api, "getAllNpcs")).size());
        });
    collect(
        result,
        "contracts",
        "LoveContracts",
        plugin -> {
          Object manager = call(plugin, "getContractManager");
          int active = 0;
          for (var player : Bukkit.getOnlinePlayers())
            if (manager
                    .getClass()
                    .getMethod("peekActiveContract", UUID.class)
                    .invoke(manager, player.getUniqueId())
                != null) active++;
          return Map.of(
              "Доступных контрактов",
              ((Collection<?>) call(manager, "getActiveContracts")).size(),
              "Игроков онлайн с контрактом",
              active);
        });
    collect(
        result,
        "adaptation",
        "LoveAdaptation",
        plugin -> {
          var type =
              Class.forName(
                  "me.lovelace.LoveAdaptation.managers.PluginManager",
                  true,
                  plugin.getClass().getClassLoader());
          Object manager = call(type.getMethod("getInstance").invoke(null), "getAdaptationManager");
          var distribution = new LinkedHashMap<String, Object>();
          int measured = 0;
          for (var player : Bukkit.getOnlinePlayers()) {
            Object data =
                manager
                    .getClass()
                    .getMethod("getPlayerData", UUID.class)
                    .invoke(manager, player.getUniqueId());
            if (data == null) continue;
            Object adaptation = call(data, "getCurrentAdaptation");
            String name =
                String.valueOf(
                        manager
                            .getClass()
                            .getMethod("getAdaptationDisplayName", adaptation.getClass())
                            .invoke(manager, adaptation))
                    .replaceAll("<[^>]+>", "")
                    .replaceAll("§[0-9a-fk-orx]", "");
            distribution.put(name, ((Number) distribution.getOrDefault(name, 0)).intValue() + 1);
            measured++;
          }
          distribution.put("Охват: игроков онлайн", measured);
          return distribution;
        });
    collect(
        result,
        "behavior",
        "LoveBehavior",
        plugin -> {
          var bridge = new LoveBehaviorBridge();
          long politeness = 0, style = 0;
          int measured = 0;
          for (var player : Bukkit.getOnlinePlayers()) {
            var data = bridge.getPlayerBehavior(player.getUniqueId());
            if (!Boolean.TRUE.equals(data.get("available"))) continue;
            politeness += ((Number) data.get("politenessPoints")).longValue();
            style += ((Number) data.get("playstylePoints")).longValue();
            measured++;
          }
          if (measured == 0) return Map.of("Охват: игроков онлайн", 0);
          return Map.of(
              "Охват: игроков онлайн",
              measured,
              "Средняя вежливость онлайн",
              Math.round(politeness / (double) measured),
              "Средний стиль игры онлайн",
              Math.round(style / (double) measured));
        });
    return result;
  }

  private static Object service(String name) throws Exception {
    for (Class<?> type : Bukkit.getServicesManager().getKnownServices())
      if (type.getName().equals(name)) {
        var registration = Bukkit.getServicesManager().getRegistration(type);
        if (registration != null && registration.getPlugin().isEnabled())
          return registration.getProvider();
      }
    throw new IllegalStateException("Нет провайдера статистики");
  }

  private static Object call(Object target, String method) throws Exception {
    return target.getClass().getMethod(method).invoke(target);
  }

  private static void collect(
      Map<String, Map<String, Object>> result, String id, String name, Reader reader) {
    Plugin source = Bukkit.getPluginManager().getPlugin(name);
    if (source == null || !source.isEnabled()) return;
    try {
      result.put(id, reader.read(source));
    } catch (Exception ignored) {
      /* Unsupported API stays unavailable, never invented zeros. */
    }
  }
}
