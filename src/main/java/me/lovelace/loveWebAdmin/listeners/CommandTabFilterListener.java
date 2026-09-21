package me.lovelace.loveWebAdmin.listeners;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Фильтрует автодополнение команд для обычных игроков:
 * Скрывает англоязычные команды, оставляя видимыми только русскоязычные (содержащие кириллицу)
 * и явно разрешённые в конфигурации.
 */
public class CommandTabFilterListener implements Listener {

    private final LoveWebAdmin plugin;
    private boolean enabled;
    private boolean hideEnglish;
    private final Set<String> allowedCommands = new HashSet<>();

    public CommandTabFilterListener(LoveWebAdmin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("command-tab-filter.enabled", true);
        this.hideEnglish = plugin.getConfig().getBoolean("command-tab-filter.hide-english-commands", true);
        this.allowedCommands.clear();
        List<String> list = plugin.getConfig().getStringList("command-tab-filter.allowed-commands");
        if (list != null) {
            for (String s : list) {
                this.allowedCommands.add(s.toLowerCase().trim());
            }
        }
    }

    private boolean isCyrillic(String str) {
        if (str == null) return false;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if ((c >= 'а' && c <= 'я') || (c >= 'А' && c <= 'Я') || c == 'ё' || c == 'Ё') {
                return true;
            }
        }
        return false;
    }

    private boolean isCommandAllowed(String cmd) {
        if (cmd == null) return false;
        String lower = cmd.toLowerCase().trim();
        if (lower.contains(":")) {
            lower = lower.substring(lower.indexOf(':') + 1);
        }
        if (isCyrillic(lower)) {
            return true;
        }
        if (!hideEnglish) {
            return true;
        }
        return allowedCommands.contains(lower);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommandSend(PlayerCommandSendEvent event) {
        if (!enabled) return;
        Player player = event.getPlayer();
        if (player.isOp() || player.hasPermission("lovewebadmin.admin") || player.hasPermission("lovewebadmin.tabcomplete.bypass")) {
            return;
        }

        event.getCommands().removeIf(cmd -> !isCommandAllowed(cmd));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncTabComplete(AsyncTabCompleteEvent event) {
        if (!enabled || !hideEnglish) return;
        if (!(event.getSender() instanceof Player player)) return;
        if (player.isOp() || player.hasPermission("lovewebadmin.admin") || player.hasPermission("lovewebadmin.tabcomplete.bypass")) {
            return;
        }

        String buffer = event.getBuffer();
        if (buffer != null && buffer.startsWith("/")) {
            if (!buffer.substring(1).contains(" ")) {
                event.getCompletions().removeIf(completion -> {
                    String clean = completion.startsWith("/") ? completion.substring(1) : completion;
                    return !isCommandAllowed(clean);
                });
            }
        }
    }
}
