package me.lovelace.loveWebAdmin.managers;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

/**
 * Менеджер заморозки игроков для проверки на читы / модерации (Freeze / SS).
 */
public class FreezeManager {

    private final LoveWebAdmin plugin;
    private final Set<UUID> frozenPlayers = ConcurrentHashMap.newKeySet();

    public FreezeManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    public boolean isFrozen(UUID uuid) {
        return plugin.getModerationService() != null ? plugin.getModerationService().isFrozen(uuid) : frozenPlayers.contains(uuid);
    }

    public boolean isFrozen(Player player) {
        return player != null && isFrozen(player.getUniqueId());
    }

    public void setFrozen(Player player, boolean freeze, String moderator) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();

        if (freeze) {
      if (plugin.getModerationService() != null)
        plugin
            .getModerationService()
            .freeze(
                moderator == null ? "Система" : moderator,
                player.getName(),
                "Проверка сотрудником");
            frozenPlayers.add(uuid);
            plugin.getLogManager().logWebAction(moderator != null ? moderator : "WebAdmin",
                "Заморозил игрока " + player.getName() + " для проверки");

            Component titleComp = Component.text("ВЫ ЗАМОРОЖЕНЫ", NamedTextColor.RED);
      Component subComp =
          Component.text("Оставайтесь на месте. Не выходите из игры!", NamedTextColor.GOLD);
            Title title = Title.title(titleComp, subComp, Title.Times.times(
                Duration.ofMillis(300), Duration.ofSeconds(10), Duration.ofMillis(500)
            ));
            player.showTitle(title);
      if (plugin.getModerationService() == null)
        player.sendMessage(
            Component.text(
                "Проверка сотрудником · перемещение ограничено. Ожидайте инструкций.",
                NamedTextColor.GOLD));
        } else {
      if (plugin.getModerationService() != null)
        plugin
            .getModerationService()
            .remove(
                "FREEZE",
                moderator == null ? "Система" : moderator,
                player.getName(),
                "Проверка завершена");
            frozenPlayers.remove(uuid);
            plugin.getLogManager().logWebAction(moderator != null ? moderator : "WebAdmin",
                "Разморозил игрока " + player.getName());

            player.clearTitle();
      if (plugin.getModerationService() == null)
        player.sendMessage(
            Component.text("Проверка завершена. Вы снова можете двигаться.", NamedTextColor.GREEN));
        }
    }

    public Set<UUID> getFrozenPlayers() {
    if (plugin.getModerationService() != null)
      return plugin.getModerationService().restrictions().stream()
          .filter(r -> r.kind().equals("FREEZE") && r.active(System.currentTimeMillis() / 1000))
          .map(r -> UUID.fromString(r.uuid()))
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return Collections.unmodifiableSet(frozenPlayers);
    }

    public void clearAll() {
        frozenPlayers.clear();
    }
}
