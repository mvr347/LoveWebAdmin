package me.lovelace.loveWebAdmin.listeners;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.WebAdmin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Перехватывает команды веб-админов в игре и пишет их в web_logs.
 * Список ников веб-админов кэшируется в памяти и обновляется при изменении состава админов.
 */
public class CommandLogListener implements Listener {

    private final LoveWebAdmin plugin;
    private final Set<String> adminUsernamesCache = ConcurrentHashMap.newKeySet();

    public CommandLogListener(LoveWebAdmin plugin) {
        this.plugin = plugin;
        refreshCache();
    }

    public void refreshCache() {
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            Set<String> usernames = plugin.getDatabaseManager().getAllAdmins().stream()
                .map(WebAdmin::username)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
            adminUsernamesCache.clear();
            adminUsernamesCache.addAll(usernames);
        });
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String playerName = event.getPlayer().getName();
        if (!adminUsernamesCache.contains(playerName.toLowerCase())) return;

        String command = event.getMessage().substring(1);
        plugin.getLogManager().logWebAction(playerName, "Выполнил команду в игре: /" + command);
    }
}
