package me.lovelace.loveWebAdmin.listeners;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.WebBan;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Отслеживает входы/выходы игроков, сессии, обновляет SteamDB аналитику
 * и блокирует вход игрокам с активными банами в WebAdmin.
 */
public class PlayerConnectionListener implements Listener {

    private final LoveWebAdmin plugin;
    private final Map<UUID, Long> joinTimes = new ConcurrentHashMap<>();

    public PlayerConnectionListener(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        long now = System.currentTimeMillis() / 1000L;
        joinTimes.put(uuid, now);

        String ip = null;
        InetSocketAddress address = player.getAddress();
        if (address != null && address.getAddress() != null) {
            ip = address.getAddress().getHostAddress();
        }

        final String finalIp = ip;
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            // Проверка активного бана в WebAdmin
            List<WebBan> activeBans = plugin.getDatabaseManager().getActiveBansForPlayer(name, uuid.toString(), finalIp);
            if (!activeBans.isEmpty()) {
                WebBan ban = activeBans.get(0);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.kickPlayer("§c§lВЫ ЗАБЛОКИРОВАНЫ НА СЕРВЕРЕ!\n"
                            + "§7Причина: §f" + ban.ruleReason() + "\n"
                            + (ban.description() != null && !ban.description().isBlank() ? "§7Детали: §f" + ban.description() + "\n" : "")
                            + "§7Заблокировал: §e" + ban.creatorName());
                });
                return;
            }

            // Запись в базу статистики
            plugin.getDatabaseManager().recordPlayerLogin(uuid.toString(), name, finalIp);

            // Дневная аналитика
            String today = LocalDate.now().toString();
            int currentOnline = Bukkit.getOnlinePlayers().size();
            boolean isNew = !player.hasPlayedBefore();
            plugin.getDatabaseManager().recordDailyStats(today, 1, isNew ? 1 : 0, currentOnline, 0);

            // Доставка отложенных уведомлений о рассмотренных жалобах
            List<String> pendingNotifications = plugin.getDatabaseManager().getAndClearPendingReportNotifications(uuid.toString());
            if (!pendingNotifications.isEmpty()) {
                plugin.getServer().getRegionScheduler().runDelayed(plugin, player.getLocation(), t -> {
                    if (player.isOnline()) {
                        for (String msg : pendingNotifications) {
                            player.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(msg));
                        }
                    }
                }, 40L); // 2 секунды после захода
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Long joinTime = joinTimes.remove(uuid);
        if (joinTime != null) {
            long duration = (System.currentTimeMillis() / 1000L) - joinTime;
            if (duration > 0) {
                plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                    plugin.getDatabaseManager().recordPlayerQuit(uuid.toString(), duration);
                    String today = LocalDate.now().toString();
                    plugin.getDatabaseManager().recordDailyStats(today, 0, 0, 0, (int) (duration / 60));
                });
            }
        }
    }
}
