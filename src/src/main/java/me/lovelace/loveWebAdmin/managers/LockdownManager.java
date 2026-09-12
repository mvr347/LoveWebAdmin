package me.lovelace.loveWebAdmin.managers;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.LockdownStateRecord;
import me.lovelace.loveWebAdmin.utils.JsonUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import io.papermc.paper.event.player.AsyncChatEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Управляет режимом экстренной изоляции сервера («Красная кнопка» / Режим ЧС).
 * Мгновенно активирует защиту от бот-атак, рейдов или экстренных инцидентов.
 */
public class LockdownManager implements Listener {

    private final LoveWebAdmin plugin;

    private volatile boolean active = false;
    private volatile long activatedAt = 0;
    private volatile String activatedBy = "";
    private volatile String reason = "";

    // Настройки режима изоляции
    private volatile boolean kickNewbies = true;
    private volatile boolean muteChat = true;
    private volatile boolean blockCommands = false;
    private volatile boolean whitelistOnly = true;

    public LockdownManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
        loadState();
    }

    private void loadState() {
        LockdownStateRecord state = plugin.getDatabaseManager().loadLockdownState();
        this.active = state.active();
        this.activatedAt = state.activatedAt();
        this.activatedBy = state.activatedBy() != null ? state.activatedBy() : "";
        this.reason = state.reason() != null ? state.reason() : "";

        if (state.settingsJson() != null && !state.settingsJson().isBlank()) {
            Map<String, Object> map = JsonUtils.parseObject(state.settingsJson());
            if (map != null) {
                if (map.containsKey("kickNewbies")) this.kickNewbies = Boolean.TRUE.equals(map.get("kickNewbies"));
                if (map.containsKey("muteChat")) this.muteChat = Boolean.TRUE.equals(map.get("muteChat"));
                if (map.containsKey("blockCommands")) this.blockCommands = Boolean.TRUE.equals(map.get("blockCommands"));
                if (map.containsKey("whitelistOnly")) this.whitelistOnly = Boolean.TRUE.equals(map.get("whitelistOnly"));
            }
        }
    }

    public synchronized void activate(String admin, String reason, boolean kickNewbies, boolean muteChat, boolean blockCommands, boolean whitelistOnly) {
        this.active = true;
        this.activatedAt = System.currentTimeMillis() / 1000L;
        this.activatedBy = admin != null ? admin : "WebAdmin";
        this.reason = reason != null && !reason.isBlank() ? reason : "Экстренная изоляция сервера";
        this.kickNewbies = kickNewbies;
        this.muteChat = muteChat;
        this.blockCommands = blockCommands;
        this.whitelistOnly = whitelistOnly;

        Map<String, Object> settings = new HashMap<>();
        settings.put("kickNewbies", kickNewbies);
        settings.put("muteChat", muteChat);
        settings.put("blockCommands", blockCommands);
        settings.put("whitelistOnly", whitelistOnly);
        String settingsJson = JsonUtils.toJson(settings);

        plugin.getDatabaseManager().saveLockdownState(true, activatedAt, activatedBy, this.reason, settingsJson);
        plugin.getLogManager().logWebAction(activatedBy, "АКТИВИРОВАН РЕЖИМ ЧС («КРАСНАЯ КНОПКА»): " + this.reason);

        // Кик подозрительных игроков, если включено
        if (kickNewbies) {
            kickSuspectsOnServer();
        }
    }

    public synchronized void deactivate(String admin) {
        this.active = false;
        String deactivatedBy = admin != null ? admin : "WebAdmin";

        Map<String, Object> settings = new HashMap<>();
        settings.put("kickNewbies", kickNewbies);
        settings.put("muteChat", muteChat);
        settings.put("blockCommands", blockCommands);
        settings.put("whitelistOnly", whitelistOnly);
        String settingsJson = JsonUtils.toJson(settings);

        plugin.getDatabaseManager().saveLockdownState(false, activatedAt, activatedBy, this.reason, settingsJson);
        plugin.getLogManager().logWebAction(deactivatedBy, "РЕЖИМ ЧС УСПЕШНО ДЕАКТИВИРОВАН («СЕРВЕР В НОРМЕ»)");
    }

    private void kickSuspectsOnServer() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOp() || player.hasPermission("lovewebadmin.admin")) continue;

            // Если включен строгий вайтлист и игрок не в вайтлисте
            if (whitelistOnly && !player.isWhitelisted()) {
                player.getScheduler().run(plugin, task -> {
                    player.kick(Component.text("§cСервер изолирован в режиме экстренной защиты (ЧС).\n§7Попробуйте войти позже."));
                }, null);
            }
        }
    }

    public boolean isActive() {
        return active;
    }

    public Map<String, Object> getStatusMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("active", active);
        map.put("activatedAt", activatedAt);
        map.put("activatedBy", activatedBy);
        map.put("reason", reason);
        Map<String, Object> settings = new HashMap<>();
        settings.put("kickNewbies", kickNewbies);
        settings.put("muteChat", muteChat);
        settings.put("blockCommands", blockCommands);
        settings.put("whitelistOnly", whitelistOnly);
        map.put("settings", settings);
        return map;
    }

    // ---------- Event Listeners ----------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!active) return;

        if (whitelistOnly) {
            boolean isWhitelisted = Bukkit.getWhitelistedPlayers().stream()
                .anyMatch(op -> op.getUniqueId().equals(event.getUniqueId()));
            if (!isWhitelisted) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
                    Component.text("§c[РЕЖИМ ЧС АКТИВЕН]\n§fСервер временно изолирован администрацией.\n§7Вход для неавторизованных игроков ограничен."));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!active || !muteChat) return;
        Player player = event.getPlayer();
        if (player.isOp() || player.hasPermission("lovewebadmin.admin")) return;

        event.setCancelled(true);
        player.sendMessage(Component.text("§c[РЕЖИМ ЧС] Глобальный чат временно заблокирован в целях безопасности."));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!active || !blockCommands) return;
        Player player = event.getPlayer();
        if (player.isOp() || player.hasPermission("lovewebadmin.admin")) return;

        String cmd = event.getMessage().toLowerCase();
        // Разрешаем только команды авторизации
        if (cmd.startsWith("/l ") || cmd.startsWith("/login ") || cmd.startsWith("/reg ") || cmd.startsWith("/register ")) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(Component.text("§c[РЕЖИМ ЧС] Выполнение команд временно заблокировано в целях безопасности."));
    }
}
