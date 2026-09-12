package me.lovelace.loveWebAdmin.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Логирует сообщения чата игроков в базу данных для истории в профиле игрока.
 * Поддерживает как Paper AsyncChatEvent, так и обычный AsyncPlayerChatEvent.
 */
public class ChatLogListener implements Listener {

    private final LoveWebAdmin plugin;

    public ChatLogListener(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPaperChat(AsyncChatEvent event) {
        String msg = PlainTextComponentSerializer.plainText().serialize(event.message());
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            plugin.getDatabaseManager().saveChatLog(
                event.getPlayer().getName(),
                event.getPlayer().getUniqueId().toString(),
                msg
            );
        });
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        String msg = event.getMessage();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            plugin.getDatabaseManager().saveChatLog(
                event.getPlayer().getName(),
                event.getPlayer().getUniqueId().toString(),
                msg
            );
        });
    }
}
