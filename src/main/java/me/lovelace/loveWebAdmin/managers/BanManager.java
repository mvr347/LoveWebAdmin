package me.lovelace.loveWebAdmin.managers;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.WebBan;
import me.lovelace.loveWebAdmin.utils.JsonUtils;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Управление банами через плагин и веб-панель.
 * Поддерживает черновики, прикрепление доказательств, бан по IP,
 * выполнение бана на сервере и авто-очистку записей старше 2 месяцев (60 дней).
 */
public class BanManager {

    private static final long SIXTY_DAYS_SECONDS = 60L * 24L * 3600L;

    private final LoveWebAdmin plugin;
    private ScheduledTask cleanupTask;

    public BanManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    public void startCleanupTask() {
        // Очистка каждые 24 часа
        cleanupTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> {
            long threshold = (System.currentTimeMillis() / 1000L) - SIXTY_DAYS_SECONDS;
            plugin.getDatabaseManager().purgeOldBans(threshold);
            plugin.getDatabaseManager().purgeOldChatLogs(threshold);
        }, 1, 24 * 60, TimeUnit.MINUTES);
    }

    public void stopCleanupTask() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    /**
     * Создает черновик бана из игры (/бан <игрок>) или веб-панели.
     */
    public WebBan createDraft(String creatorName, String targetName) {
        String cleanTarget = targetName.trim();
        String targetUuid = null;
        String targetIp = null;

        Player online = Bukkit.getPlayerExact(cleanTarget);
        if (online != null) {
            targetUuid = online.getUniqueId().toString();
            InetSocketAddress address = online.getAddress();
            if (address != null && address.getAddress() != null) {
                targetIp = address.getAddress().getHostAddress();
            }
        } else {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(cleanTarget);
            if (offline.hasPlayedBefore() || offline.getName() != null) {
                targetUuid = offline.getUniqueId().toString();
            }
            // Пытаемся взять последний известный IP из статистики
            var stats = plugin.getDatabaseManager().getPlayerStats(cleanTarget);
            if (stats.isPresent() && stats.get().get("lastIp") != null) {
                targetIp = String.valueOf(stats.get().get("lastIp"));
            }
        }

        long now = System.currentTimeMillis() / 1000L;
        WebBan draft = new WebBan(
            0,
            cleanTarget,
            targetUuid,
            targetIp,
            creatorName,
            "Черновик (правило не выбрано)",
            "",
            "[]",
            false,
            "DRAFT",
            -1,
            now,
            -1
        );

        WebBan saved = plugin.getDatabaseManager().saveBan(draft);
        plugin.getLogManager().logWebAction(creatorName, "Создал черновик бана для игрока " + cleanTarget);
        return saved;
    }

    /**
     * Оформляет и применяет бан на сервере Bukkit с опциональной привязкой к жалобе.
     */
    public boolean finalizeBan(
            int banId,
            String actorUsername,
            String targetName,
            String ruleReason,
            String description,
            List<String> proofUrls,
            boolean isIpBan,
            long durationSeconds,
            Integer linkedReportId) {

        String cleanTarget = targetName.trim();
        String targetUuid = null;
        String targetIp = null;

        Player online = Bukkit.getPlayerExact(cleanTarget);
        if (online != null) {
            targetUuid = online.getUniqueId().toString();
            InetSocketAddress address = online.getAddress();
            if (address != null && address.getAddress() != null) {
                targetIp = address.getAddress().getHostAddress();
            }
        } else {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(cleanTarget);
            if (offline.hasPlayedBefore() || offline.getName() != null) {
                targetUuid = offline.getUniqueId().toString();
            }
            var stats = plugin.getDatabaseManager().getPlayerStats(cleanTarget);
            if (stats.isPresent() && stats.get().get("lastIp") != null) {
                targetIp = String.valueOf(stats.get().get("lastIp"));
            }
        }

        long now = System.currentTimeMillis() / 1000L;
        long expiresAt = (durationSeconds > 0) ? (now + durationSeconds) : -1L;
        String proofJson = JsonUtils.toJson(proofUrls != null ? proofUrls : List.of());

        WebBan ban = new WebBan(
            banId,
            cleanTarget,
            targetUuid,
            targetIp,
            actorUsername,
            ruleReason,
            description != null ? description : "",
            proofJson,
            isIpBan,
            "ACTIVE",
            durationSeconds,
            now,
            expiresAt,
            linkedReportId
        );

        WebBan savedBan = plugin.getDatabaseManager().saveBan(ban);

        // Если бан привязан к жалобе — автоматически принимаем жалобу и награждаем заявителя
        if (linkedReportId != null && linkedReportId > 0) {
            int effectiveBanId = (savedBan != null && savedBan.id() > 0) ? savedBan.id() : banId;
            plugin.getReportManager().acceptReport(linkedReportId, actorUsername, effectiveBanId);
        }

        // Применяем бан в Minecraft (в главном потоке)
        final String finalTargetUuid = targetUuid;
        final String finalTargetIp = targetIp;
        Bukkit.getScheduler().runTask(plugin, () -> {
            applyBanInBukkit(cleanTarget, finalTargetUuid, finalTargetIp, ruleReason, description, expiresAt, isIpBan, actorUsername);
        });

        String reportNote = (linkedReportId != null && linkedReportId > 0) ? " (по жалобе #" + linkedReportId + ")" : "";
        plugin.getLogManager().logWebAction(actorUsername,
            "Забанил игрока " + cleanTarget + " по причине [" + ruleReason + "]" + reportNote + (isIpBan ? " (+бан по IP)" : ""));

        // Вызываем Bukkit Event для разработчиков сторонних плагинов
        try {
            var banEvent = new me.lovelace.loveWebAdmin.api.events.WebAdminBanEvent(cleanTarget, ruleReason, actorUsername, isIpBan, linkedReportId);
            banEvent.setCreatedBan(savedBan);
            Bukkit.getPluginManager().callEvent(banEvent);
        } catch (Exception e) {
            plugin.getLogger().warning("[BanManager] Ошибка вызова WebAdminBanEvent: " + e.getMessage());
        }

        // Отправляем исходящий вебхук (Discord / HTTP)
        if (plugin.getWebhookManager() != null) {
            Map<String, Object> hookData = new java.util.LinkedHashMap<>();
            hookData.put("targetName", cleanTarget);
            hookData.put("creatorName", actorUsername);
            hookData.put("ruleReason", ruleReason);
            hookData.put("description", description != null ? description : "");
            hookData.put("isIpBan", isIpBan);
            hookData.put("linkedReportId", linkedReportId);
            plugin.getWebhookManager().dispatch("BAN", hookData);
        }

        return true;
    }

    public boolean finalizeBan(
            int banId,
            String actorUsername,
            String targetName,
            String ruleReason,
            String description,
            List<String> proofUrls,
            boolean isIpBan,
            long durationSeconds) {
        return finalizeBan(banId, actorUsername, targetName, ruleReason, description, proofUrls, isIpBan, durationSeconds, null);
    }

    public WebBan finalizeBan(
            int banId,
            String targetName,
            String ruleReason,
            String actorUsername,
            String description,
            List<String> proofUrls,
            boolean isIpBan,
            Integer linkedReportId) {
        finalizeBan(banId, actorUsername, targetName, ruleReason, description, proofUrls, isIpBan, -1L, linkedReportId);
        return plugin.getDatabaseManager().getActiveBan(targetName).orElse(null);
    }

    private void applyBanInBukkit(
            String targetName,
            String targetUuid,
            String targetIp,
            String ruleReason,
            String description,
            long expiresAt,
            boolean isIpBan,
            String banner) {

        Date expiryDate = (expiresAt > 0) ? Date.from(Instant.ofEpochSecond(expiresAt)) : null;
        String cleanDesc = (description != null) ? description.trim() : "";
        if (cleanDesc.endsWith(":")) {
            cleanDesc = cleanDesc.substring(0, cleanDesc.length() - 1).trim();
        }
        String banMessage = "§c§lВЫ ЗАБЛОКИРОВАНЫ НА СЕРВЕРЕ!\n"
                + "§7Причина: §f" + ruleReason + "\n"
                + (!cleanDesc.isBlank() ? "§7Детали: §f" + cleanDesc + "\n" : "")
                + "§7Заблокировал: §e" + banner + "\n"
                + "§7Срок: §f" + (expiryDate != null ? expiryDate.toString() : "Навсегда") + "\n"
                + "§8Доказательства зафиксированы в WebAdmin.";

        try {
            // Бан по нику
            var nameBanList = Bukkit.getBanList(BanList.Type.NAME);
            nameBanList.addBan(targetName, banMessage, expiryDate, banner);

            // Бан по IP если запрошено
            if (isIpBan && targetIp != null && !targetIp.isBlank()) {
                var ipBanList = Bukkit.getBanList(BanList.Type.IP);
                ipBanList.addBan(targetIp, banMessage, expiryDate, banner);
            }

            // Если игрок онлайн — немедленно кикаем с экраном бана
            Player online = Bukkit.getPlayerExact(targetName);
            if (online != null && online.isOnline()) {
                online.kickPlayer(banMessage);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка применения бана через Bukkit API: " + e.getMessage());
        }
    }

    /**
     * Разбанивает игрока.
     */
    public boolean unban(int banId, String actorUsername) {
        Optional<WebBan> banOpt = plugin.getDatabaseManager().getBanById(banId);
        if (banOpt.isEmpty()) return false;

        WebBan ban = banOpt.get();

        // Проверяем Bukkit Event (Cancellable)
        try {
            var unbanEvent = new me.lovelace.loveWebAdmin.api.events.WebAdminUnbanEvent(banId, ban.targetName(), actorUsername);
            Bukkit.getPluginManager().callEvent(unbanEvent);
            if (unbanEvent.isCancelled()) {
                return false;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[BanManager] Ошибка вызова WebAdminUnbanEvent: " + e.getMessage());
        }

        boolean ok = plugin.getDatabaseManager().unban(banId);
        if (ok) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    Bukkit.getBanList(BanList.Type.NAME).pardon(ban.targetName());
                    if (ban.isIpBan() && ban.targetIp() != null) {
                        Bukkit.getBanList(BanList.Type.IP).pardon(ban.targetIp());
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Ошибка разбана в Bukkit API: " + e.getMessage());
                }
            });
            plugin.getLogManager().logWebAction(actorUsername, "Разбанил игрока " + ban.targetName());

            // Отправляем исходящий вебхук (Discord / HTTP)
            if (plugin.getWebhookManager() != null) {
                Map<String, Object> hookData = new java.util.LinkedHashMap<>();
                hookData.put("banId", banId);
                hookData.put("targetName", ban.targetName());
                hookData.put("actorUsername", actorUsername);
                plugin.getWebhookManager().dispatch("UNBAN", hookData);
            }
        }
        return ok;
    }

    /**
     * Разбанивает игрока по нику.
     * Сначала проверяет активный бан в базе данных LoveWebAdmin, затем снимает ограничения в Bukkit BanList.
     */
    public boolean unban(String targetName, String actorUsername) {
        if (targetName == null || targetName.isBlank()) return false;
        String cleanTarget = targetName.trim();

        Optional<WebBan> banOpt = plugin.getDatabaseManager().getActiveBan(cleanTarget);
        if (banOpt.isPresent()) {
            return unban(banOpt.get().id(), actorUsername);
        }

        // Проверяем Bukkit BanList напрямую (если бан был выдан вне WebAdmin)
        boolean wasBannedInBukkit = false;
        try {
            var nameBanList = Bukkit.getBanList(BanList.Type.NAME);
            if (nameBanList.isBanned(cleanTarget)) {
                nameBanList.pardon(cleanTarget);
                wasBannedInBukkit = true;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка разбана в Bukkit API: " + e.getMessage());
        }

        if (wasBannedInBukkit) {
            plugin.getLogManager().logWebAction(actorUsername, "Разбанил игрока (Bukkit) " + cleanTarget);
            if (plugin.getWebhookManager() != null) {
                Map<String, Object> hookData = new java.util.LinkedHashMap<>();
                hookData.put("targetName", cleanTarget);
                hookData.put("actorUsername", actorUsername);
                plugin.getWebhookManager().dispatch("UNBAN", hookData);
            }
            return true;
        }

        return false;
    }
}
