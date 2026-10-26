package me.lovelace.loveWebAdmin.integration;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Re-evaluated on main immediately before mutations, never cached from HTTP request arrival. */
public final class GameplayPolicy {
  private GameplayPolicy() {}

  private static Boolean oracle(String type, String method, UUID player) {
    for (Class<?> service : Bukkit.getServicesManager().getKnownServices())
      if (service.getName().equals(type)) {
        var registration = Bukkit.getServicesManager().getRegistration(service);
        if (registration == null) return null;
        try {
          return (Boolean)
              service.getMethod(method, UUID.class).invoke(registration.getProvider(), player);
        } catch (ReflectiveOperationException e) {
          throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
              "Проверка игрового состояния временно недоступна");
        }
      }
    return null;
  }

  public static void check(Player player) {
    if (!Bukkit.isPrimaryThread())
      throw new IllegalStateException("Game state must be checked on main");
    if (!player.isOnline() || player.isDead())
      throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
          "Игрок вышел с сервера или погиб");
    UUID id = player.getUniqueId();
    if (Boolean.FALSE.equals(
        oracle("dev.lovelace.lovecore.api.auth.AuthOracle", "isAuthenticated", id)))
      throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
          "Игрок ещё не прошёл авторизацию");
    if (Boolean.TRUE.equals(oracle("dev.lovelace.lovecore.api.revive.DownedOracle", "downed", id)))
      throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
          "Игрок ранен. Используйте механику помощи LoveEssential");
    if (Boolean.TRUE.equals(oracle("dev.lovelace.lovecore.api.combat.CombatState", "inCombat", id)))
      throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException("Игрок находится в бою");
    if (player.getVehicle() instanceof Player
        || player.getPassengers().stream().anyMatch(p -> p instanceof Player))
      throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
          "Игрок переносит другого игрока или находится на руках");
    var duels = Bukkit.getPluginManager().getPlugin("LoveDuels");
    if (duels != null && duels.isEnabled())
      try {
        Object manager = duels.getClass().getMethod("getDuelManager").invoke(duels);
        Object matches = manager.getClass().getMethod("getMatchManager").invoke(manager);
        if (Boolean.TRUE.equals(
            matches.getClass().getMethod("isInMatch", UUID.class).invoke(matches, id)))
          throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
              "Игрок участвует в дуэли");
      } catch (ReflectiveOperationException e) {
        throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
            "Проверка состояния LoveDuels временно недоступна");
      }
  }
}
