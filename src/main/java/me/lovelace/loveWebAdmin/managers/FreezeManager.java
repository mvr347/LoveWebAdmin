package me.lovelace.loveWebAdmin.managers;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
        return frozenPlayers.contains(uuid);
    }

    public boolean isFrozen(Player player) {
        return player != null && frozenPlayers.contains(player.getUniqueId());
    }

    public void setFrozen(Player player, boolean freeze, String moderator) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();

        if (freeze) {
            frozenPlayers.add(uuid);
            plugin.getLogManager().logWebAction(moderator != null ? moderator : "WebAdmin",
                "Заморозил игрока " + player.getName() + " для проверки");

            Component titleComp = Component.text("ВЫ ЗАМОРОЖЕНЫ", NamedTextColor.RED);
            Component subComp = Component.text("Оставайтесь на месте. Не выходите из игры!", NamedTextColor.GOLD);
            Title title = Title.title(titleComp, subComp, Title.Times.times(
                Duration.ofMillis(300), Duration.ofSeconds(10), Duration.ofMillis(500)
            ));
            player.showTitle(title);
            player.sendMessage(Component.text("§c§l[WebAdmin] §eВы заморожены администратором для проверки! Не выходите из игры во избежание блокировки."));
        } else {
            frozenPlayers.remove(uuid);
            plugin.getLogManager().logWebAction(moderator != null ? moderator : "WebAdmin",
                "Разморозил игрока " + player.getName());

            player.clearTitle();
            player.sendMessage(Component.text("§a§l[WebAdmin] §aВы разморожены администратором. Приятной игры!"));
        }
    }

    public Set<UUID> getFrozenPlayers() {
        return Collections.unmodifiableSet(frozenPlayers);
    }

    public void clearAll() {
        frozenPlayers.clear();
    }
}
